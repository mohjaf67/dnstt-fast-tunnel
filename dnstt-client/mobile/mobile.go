// Package mobile provides a simplified API for dnstt-client suitable for mobile apps.
// Build with: gomobile bind -target=android -o dnstt.aar ./dnstt-client/mobile
package mobile

import (
	"context"
	"errors"
	"fmt"
	"io"
	"log"
	"net"
	"sort"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	utls "github.com/refraction-networking/utls"
	dnstt "www.bamsoftware.com/git/dnstt.git/dnstt-client/lib"
	"www.bamsoftware.com/git/dnstt.git/noise"
)

// State constants - exported as int for gomobile
const (
	StateStopped    = 0
	StateConnecting = 1
	StateConnected  = 2
	StateError      = 3
)

// StatusCallback is called when tunnel status changes.
// Implement this interface in Java/Kotlin.
type StatusCallback interface {
	OnStatusChange(state int64, message string)
	OnBytesTransferred(bytesIn, bytesOut int64)
}

// Config holds the tunnel configuration.
// Fields are unexported to avoid gomobile generating duplicate setters.
type Config struct {
	transportType   string
	transportAddr   string
	pubkeyHex       string
	domain          string
	listenAddr      string
	tunnels         int
	mtu             int
	utlsFingerprint string
	useZstd         bool // Enable zstd compression (server must also have -zstd flag)
	numParallel     int  // Number of parallel DNS query senders (1-16, higher = more throughput)
}

// NewConfig creates a default configuration.
func NewConfig() *Config {
	return &Config{
		transportType:   "doh",
		transportAddr:   "https://dns.google/dns-query",
		listenAddr:      "127.0.0.1:1080",
		tunnels:         8,
		mtu:             1232,
		utlsFingerprint: "none", // Use standard TLS - uTLS causes errors on Android
		useZstd:         true,   // Default to enabled (server has it on by default)
		numParallel:     8,      // Default parallel DNS senders for better throughput
	}
}

// Setter methods for gomobile compatibility
func (c *Config) SetTransportType(v string)   { c.transportType = v }
func (c *Config) SetTransportAddr(v string)   { c.transportAddr = v }
func (c *Config) SetPubkeyHex(v string)       { c.pubkeyHex = v }
func (c *Config) SetDomain(v string)          { c.domain = v }
func (c *Config) SetListenAddr(v string)      { c.listenAddr = v }
func (c *Config) SetTunnels(v int)            { c.tunnels = v; c.numParallel = v }
func (c *Config) SetMTU(v int)                { c.mtu = v }
func (c *Config) SetUTLSFingerprint(v string) { c.utlsFingerprint = v }
func (c *Config) SetUseZstd(v bool)           { c.useZstd = v }
func (c *Config) SetNumParallel(v int)        { c.numParallel = v }

// Client represents a dnstt tunnel client for mobile.
type Client struct {
	mu            sync.Mutex
	listener      net.Listener
	pool          *dnstt.TunnelPool
	state         int32
	cancel        context.CancelFunc
	callback      StatusCallback
	bytesIn       int64
	bytesOut      int64
	activeStreams int32
}

// NewClient creates a new tunnel client.
func NewClient() *Client {
	return &Client{
		state: StateStopped,
	}
}

// SetCallback sets the status callback.
func (c *Client) SetCallback(cb StatusCallback) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.callback = cb
}

// GetState returns the current tunnel state.
func (c *Client) GetState() int {
	return int(atomic.LoadInt32(&c.state))
}

// GetBytesIn returns total bytes received.
func (c *Client) GetBytesIn() int64 {
	return atomic.LoadInt64(&c.bytesIn)
}

// GetBytesOut returns total bytes sent.
func (c *Client) GetBytesOut() int64 {
	return atomic.LoadInt64(&c.bytesOut)
}

// GetActiveStreams returns the number of active connections.
func (c *Client) GetActiveStreams() int {
	return int(atomic.LoadInt32(&c.activeStreams))
}

func (c *Client) setState(state int32, message string) {
	atomic.StoreInt32(&c.state, state)

	c.mu.Lock()
	cb := c.callback
	c.mu.Unlock()

	if cb != nil {
		cb.OnStatusChange(int64(state), message)
	}
}

// Start starts the tunnel with the given configuration.
func (c *Client) Start(cfg *Config) error {
	if atomic.LoadInt32(&c.state) == StateConnecting || atomic.LoadInt32(&c.state) == StateConnected {
		return errors.New("tunnel already running")
	}

	c.setState(StateConnecting, "Connecting...")

	// Parse public key
	pubkey, err := noise.DecodeKey(cfg.pubkeyHex)
	if err != nil {
		c.setState(StateError, fmt.Sprintf("Invalid pubkey: %v", err))
		return fmt.Errorf("invalid pubkey: %w", err)
	}

	// Parse domain
	domain, err := dnstt.ParseDomain(cfg.domain)
	if err != nil {
		c.setState(StateError, fmt.Sprintf("Invalid domain: %v", err))
		return fmt.Errorf("invalid domain: %w", err)
	}

	// Parse uTLS fingerprint
	var utlsID *utls.ClientHelloID
	spec := cfg.utlsFingerprint
	if spec == "" || spec == "none" {
		// Default to standard TLS (no uTLS fingerprinting)
		// uTLS fingerprints cause "tls: unexpected message" errors on Android
		utlsID = nil
		log.Printf("using standard TLS (uTLS disabled)")
	} else {
		utlsID, err = dnstt.SampleUTLSDistribution(spec)
		if err != nil {
			c.setState(StateError, fmt.Sprintf("Invalid uTLS spec: %v", err))
			return fmt.Errorf("invalid utls spec: %w", err)
		}
		log.Printf("using uTLS fingerprint: %s", spec)
	}

	// Create tunnel pool
	pool := dnstt.NewTunnelPool()
	numTunnels := cfg.tunnels
	if numTunnels < 1 {
		numTunnels = 8
	}

	// Calculate the proper MTU based on domain name capacity
	// DNS encoding has strict limits - the MTU must fit in DNS query names
	// The formula subtracts: 8 (ClientID) + 1 (padding length) + 3 (numPadding) + 1 (packet length)
	mtu := dnstt.DNSNameCapacity(domain) - 8 - 1 - 3 - 1
	if mtu < 80 {
		c.setState(StateError, fmt.Sprintf("Domain too long, MTU only %d bytes", mtu))
		return fmt.Errorf("domain %s leaves only %d bytes for payload", cfg.domain, mtu)
	}
	log.Printf("calculated effective MTU: %d bytes", mtu)

	// Set compression flag before creating tunnels
	dnstt.UseCompression = cfg.useZstd
	if cfg.useZstd {
		log.Printf("zstd compression enabled")
	}

	// Set parallel DNS senders for better throughput
	if cfg.numParallel > 0 {
		if cfg.numParallel > 16 {
			cfg.numParallel = 16 // Cap at 16
		}
		dnstt.NumDNSSenders = cfg.numParallel
		log.Printf("parallel DNS senders: %d", cfg.numParallel)
	}

	// Create tunnels
	successCount := 0
	log.Printf("creating %d tunnels with transport=%s addr=%s domain=%s", numTunnels, cfg.transportType, cfg.transportAddr, cfg.domain)
	for i := 0; i < numTunnels; i++ {
		log.Printf("creating tunnel %d/%d...", i+1, numTunnels)
		tunnel, err := dnstt.CreateTunnelExported(
			utlsID,
			pubkey,
			domain,
			mtu,
			cfg.transportType,
			cfg.transportAddr,
		)
		if err != nil {
			log.Printf("failed to create tunnel %d: %v", i, err)
			continue
		}
		log.Printf("tunnel %d created successfully", i+1)
		pool.Add(tunnel)
		successCount++
	}

	if successCount == 0 {
		c.setState(StateError, "Failed to create any tunnels")
		return errors.New("failed to create any tunnels")
	}

	// Start SOCKS listener
	listenAddr := cfg.listenAddr
	if listenAddr == "" {
		listenAddr = "127.0.0.1:1080"
	}
	ln, err := net.Listen("tcp", listenAddr)
	if err != nil {
		pool.Close()
		c.setState(StateError, fmt.Sprintf("Failed to listen: %v", err))
		return fmt.Errorf("listening on %s: %w", listenAddr, err)
	}

	ctx, cancel := context.WithCancel(context.Background())

	c.mu.Lock()
	c.pool = pool
	c.listener = ln
	c.cancel = cancel
	atomic.StoreInt64(&c.bytesIn, 0)
	atomic.StoreInt64(&c.bytesOut, 0)
	c.mu.Unlock()

	c.setState(StateConnected, fmt.Sprintf("Connected with %d tunnels", successCount))

	// Accept SOCKS connections
	go c.acceptLoop(ctx, ln, pool)

	// Stats reporter
	go c.statsReporter(ctx)

	return nil
}

func (c *Client) acceptLoop(ctx context.Context, ln net.Listener, pool *dnstt.TunnelPool) {
	for {
		select {
		case <-ctx.Done():
			return
		default:
		}

		conn, err := ln.Accept()
		if err != nil {
			select {
			case <-ctx.Done():
				return
			default:
				continue
			}
		}

		go c.handleSOCKS(ctx, conn, pool)
	}
}

func (c *Client) handleSOCKS(ctx context.Context, conn net.Conn, pool *dnstt.TunnelPool) {
	defer conn.Close()

	atomic.AddInt32(&c.activeStreams, 1)
	defer atomic.AddInt32(&c.activeStreams, -1)

	tunnel := pool.Get()
	if tunnel == nil {
		return
	}

	stream, err := tunnel.OpenStream()
	if err != nil {
		return
	}
	defer stream.Close()

	// SOCKS5 handshake with local client (tun2socks)
	buf := make([]byte, 256)

	// Read version and auth methods from local client
	n, err := conn.Read(buf)
	if err != nil || n < 2 || buf[0] != 0x05 {
		return
	}

	// Respond to local client: no auth required
	conn.Write([]byte{0x05, 0x00})

	// Read connect request from local client
	n, err = conn.Read(buf)
	if err != nil || n < 7 || buf[0] != 0x05 || buf[1] != 0x01 {
		return
	}

	// Validate and determine request length
	var reqLen int
	switch buf[3] {
	case 0x01: // IPv4
		if n < 10 {
			return
		}
		reqLen = 10
	case 0x03: // Domain
		domainLen := int(buf[4])
		if n < 5+domainLen+2 {
			return
		}
		reqLen = 5 + domainLen + 2
	case 0x04: // IPv6
		if n < 22 {
			return
		}
		reqLen = 22
	default:
		conn.Write([]byte{0x05, 0x08, 0x00, 0x01, 0, 0, 0, 0, 0, 0})
		return
	}

	// Now perform SOCKS5 handshake with the upstream SOCKS5 proxy through the tunnel
	// Step 1: Send SOCKS5 greeting to upstream
	_, err = stream.Write([]byte{0x05, 0x01, 0x00}) // VER=5, 1 method, NO AUTH
	if err != nil {
		log.Printf("Failed to send SOCKS5 greeting to upstream: %v", err)
		conn.Write([]byte{0x05, 0x01, 0x00, 0x01, 0, 0, 0, 0, 0, 0})
		return
	}

	// Step 2: Read auth response from upstream
	authResp := make([]byte, 2)
	_, err = io.ReadFull(stream, authResp)
	if err != nil {
		log.Printf("Failed to read auth response from upstream: %v", err)
		conn.Write([]byte{0x05, 0x01, 0x00, 0x01, 0, 0, 0, 0, 0, 0})
		return
	}
	if authResp[0] != 0x05 || authResp[1] != 0x00 {
		log.Printf("Upstream rejected auth: %v", authResp)
		conn.Write([]byte{0x05, 0x01, 0x00, 0x01, 0, 0, 0, 0, 0, 0})
		return
	}

	// Step 3: Send CONNECT request to upstream
	_, err = stream.Write(buf[:reqLen])
	if err != nil {
		log.Printf("Failed to send CONNECT request to upstream: %v", err)
		conn.Write([]byte{0x05, 0x01, 0x00, 0x01, 0, 0, 0, 0, 0, 0})
		return
	}

	// Step 4: Read CONNECT response from upstream (at least 10 bytes for IPv4)
	respBuf := make([]byte, 256)
	respN, err := stream.Read(respBuf)
	if err != nil || respN < 10 {
		log.Printf("Failed to read SOCKS5 response: %v (got %d bytes)", err, respN)
		conn.Write([]byte{0x05, 0x01, 0x00, 0x01, 0, 0, 0, 0, 0, 0})
		return
	}

	// Check if upstream connection succeeded
	if respBuf[1] != 0x00 {
		log.Printf("Upstream SOCKS5 connection failed with code: %d", respBuf[1])
	}

	// Forward response to local client
	conn.Write(respBuf[:respN])

	// Bidirectional copy with proper shutdown
	done := make(chan struct{}, 2)

	go func() {
		n, _ := io.Copy(stream, conn)
		atomic.AddInt64(&c.bytesOut, n)
		// Signal we're done reading from conn
		if tcpConn, ok := conn.(*net.TCPConn); ok {
			tcpConn.CloseRead()
		}
		done <- struct{}{}
	}()

	go func() {
		n, _ := io.Copy(conn, stream)
		atomic.AddInt64(&c.bytesIn, n)
		// Signal we're done writing to conn
		if tcpConn, ok := conn.(*net.TCPConn); ok {
			tcpConn.CloseWrite()
		}
		done <- struct{}{}
	}()

	// Wait for both directions to complete or context cancellation
	select {
	case <-ctx.Done():
		return
	case <-done:
	}
	// Wait for the second goroutine
	select {
	case <-ctx.Done():
	case <-done:
	}
}

func (c *Client) statsReporter(ctx context.Context) {
	ticker := time.NewTicker(1 * time.Second)
	defer ticker.Stop()

	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			c.mu.Lock()
			cb := c.callback
			c.mu.Unlock()

			if cb != nil {
				cb.OnBytesTransferred(
					atomic.LoadInt64(&c.bytesIn),
					atomic.LoadInt64(&c.bytesOut),
				)
			}
		}
	}
}

// Stop stops the tunnel.
func (c *Client) Stop() {
	c.mu.Lock()
	if c.cancel != nil {
		c.cancel()
		c.cancel = nil
	}
	if c.listener != nil {
		c.listener.Close()
		c.listener = nil
	}
	if c.pool != nil {
		c.pool.Close()
		c.pool = nil
	}
	c.mu.Unlock()

	c.setState(StateStopped, "Stopped")
}

// =============================================================================
// Resolver Testing API
// =============================================================================

// ResolverResult holds the result of testing a single DNS resolver.
// This is a simplified version for gomobile compatibility.
type ResolverResult struct {
	resolver  string
	success   bool
	latencyMs int64
	errorMsg  string
}

// GetResolver returns the resolver address.
func (r *ResolverResult) GetResolver() string { return r.resolver }

// IsSuccess returns whether the resolver passed the test.
func (r *ResolverResult) IsSuccess() bool { return r.success }

// GetLatencyMs returns the latency in milliseconds.
func (r *ResolverResult) GetLatencyMs() int64 { return r.latencyMs }

// GetError returns the error message if the test failed.
func (r *ResolverResult) GetError() string { return r.errorMsg }

// ResolverList holds a list of resolver test results.
// gomobile doesn't support slices, so we use this wrapper.
type ResolverList struct {
	results []*ResolverResult
}

// Size returns the number of results.
func (l *ResolverList) Size() int {
	if l == nil {
		return 0
	}
	return len(l.results)
}

// Get returns the result at the given index.
func (l *ResolverList) Get(index int) *ResolverResult {
	if l == nil || index < 0 || index >= len(l.results) {
		return nil
	}
	return l.results[index]
}

// GetBest returns the best (fastest successful) resolver, or nil if none succeeded.
func (l *ResolverList) GetBest() *ResolverResult {
	if l == nil || len(l.results) == 0 {
		return nil
	}
	// Results are already sorted by latency, return first successful one
	for _, r := range l.results {
		if r.success {
			return r
		}
	}
	return nil
}

// ResolverCallback is called during resolver testing to report progress.
type ResolverCallback interface {
	OnProgress(tested, total int, currentResolver string)
	OnResult(resolver string, success bool, latencyMs int64, errorMsg string)
}

// TestResolvers tests a list of DNS resolvers and returns them sorted by latency.
// resolvers: newline-separated list of resolver addresses (e.g., "8.8.8.8\n1.1.1.1\n9.9.9.9")
// domain: the dnstt domain to test against (e.g., "t3.rfan.dev")
// timeoutMs: timeout for each resolver test in milliseconds
// concurrency: number of concurrent tests (recommended: 50-100)
// callback: optional callback for progress updates (can be nil)
func TestResolvers(resolvers string, domain string, timeoutMs int64, concurrency int, callback ResolverCallback) *ResolverList {
	// Parse resolvers from newline-separated string
	lines := strings.Split(resolvers, "\n")
	var resolverList []string
	for _, line := range lines {
		line = strings.TrimSpace(line)
		if line != "" && !strings.HasPrefix(line, "#") {
			resolverList = append(resolverList, line)
		}
	}

	if len(resolverList) == 0 {
		log.Printf("no resolvers provided")
		return &ResolverList{}
	}

	// Parse domain
	domainName, err := dnstt.ParseDomain(domain)
	if err != nil {
		log.Printf("invalid domain: %v", err)
		return &ResolverList{}
	}

	timeout := time.Duration(timeoutMs) * time.Millisecond
	if timeout < 500*time.Millisecond {
		timeout = 2000 * time.Millisecond
	}

	if concurrency < 1 {
		concurrency = 50
	}

	log.Printf("testing %d resolvers with concurrency %d, timeout %v", len(resolverList), concurrency, timeout)

	// Test resolvers concurrently
	resultChan := make(chan *ResolverResult, concurrency*2)
	var wg sync.WaitGroup
	sem := make(chan struct{}, concurrency)

	var completed int64
	total := int64(len(resolverList))

	for _, resolver := range resolverList {
		wg.Add(1)
		go func(res string) {
			defer wg.Done()
			sem <- struct{}{}        // Acquire
			defer func() { <-sem }() // Release

			// Use the lib's TestDNSResolver function
			libResult := dnstt.TestDNSResolver(res, domainName, timeout)

			result := &ResolverResult{
				resolver:  res,
				success:   libResult.Success,
				latencyMs: libResult.Latency.Milliseconds(),
				errorMsg:  libResult.Error,
			}
			resultChan <- result

			done := atomic.AddInt64(&completed, 1)
			if callback != nil {
				callback.OnProgress(int(done), int(total), res)
				callback.OnResult(res, result.success, result.latencyMs, result.errorMsg)
			}
		}(resolver)
	}

	// Close channel when all done
	go func() {
		wg.Wait()
		close(resultChan)
	}()

	// Collect results
	var results []*ResolverResult
	for r := range resultChan {
		results = append(results, r)
	}

	// Sort by latency (fastest first), with successful ones before failed ones
	sort.Slice(results, func(i, j int) bool {
		// Successful results come first
		if results[i].success != results[j].success {
			return results[i].success
		}
		// Then sort by latency
		return results[i].latencyMs < results[j].latencyMs
	})

	successCount := 0
	for _, r := range results {
		if r.success {
			successCount++
		}
	}
	log.Printf("resolver test complete: %d/%d passed", successCount, len(results))

	return &ResolverList{results: results}
}

// TestResolversWithTunnel tests resolvers and verifies tunnel connection works.
// This is a more thorough test that actually establishes a tunnel connection.
// pubkeyHex: the server's public key in hex format
// Returns the best working resolver address, or empty string if none work.
func TestResolversWithTunnel(resolvers string, domain string, pubkeyHex string, timeoutMs int64, concurrency int, callback ResolverCallback) string {
	// First, do the DNS-level test
	list := TestResolvers(resolvers, domain, timeoutMs, concurrency, callback)
	if list.Size() == 0 {
		return ""
	}

	// Parse pubkey
	pubkey, err := noise.DecodeKey(pubkeyHex)
	if err != nil {
		log.Printf("invalid pubkey: %v", err)
		return ""
	}

	// Get uTLS fingerprint
	utlsID, err := dnstt.SampleUTLSDistribution("Chrome")
	if err != nil {
		log.Printf("failed to get utls ID: %v", err)
		return ""
	}

	timeout := time.Duration(timeoutMs) * time.Millisecond
	if timeout < 2*time.Second {
		timeout = 5 * time.Second
	}

	// Test tunnel connection with successful resolvers (up to first 20)
	maxToTest := 20
	testedCount := 0
	for i := 0; i < list.Size() && testedCount < maxToTest; i++ {
		result := list.Get(i)
		if !result.success {
			continue
		}

		// Skip resolvers with very high latency
		if result.latencyMs > 500 {
			log.Printf("skipping %s (latency %dms too high)", result.resolver, result.latencyMs)
			continue
		}

		testedCount++
		log.Printf("testing tunnel via %s...", result.resolver)

		err := dnstt.TestTunnelConnection(result.resolver, domain, pubkey, utlsID, timeout)
		if err == nil {
			log.Printf("tunnel verified via %s", result.resolver)
			return result.resolver
		}
		log.Printf("tunnel test failed for %s: %v", result.resolver, err)
	}

	log.Printf("no resolver could establish tunnel connection")
	return ""
}

// StartWithBestResolver tests resolvers and starts the tunnel with the best one.
// This is a convenience function that combines TestResolversWithTunnel and Start.
func (c *Client) StartWithBestResolver(cfg *Config, resolvers string, callback ResolverCallback) error {
	if atomic.LoadInt32(&c.state) == StateConnecting || atomic.LoadInt32(&c.state) == StateConnected {
		return errors.New("tunnel already running")
	}

	c.setState(StateConnecting, "Testing resolvers...")

	// Test resolvers and find the best one
	bestResolver := TestResolversWithTunnel(resolvers, cfg.domain, cfg.pubkeyHex, 3000, 50, callback)
	if bestResolver == "" {
		c.setState(StateError, "No working resolver found")
		return errors.New("no working resolver found")
	}

	c.setState(StateConnecting, fmt.Sprintf("Connecting via %s...", bestResolver))

	// Update config to use UDP transport with the best resolver
	cfg.transportType = "udp"
	cfg.transportAddr = bestResolver

	// Start the tunnel
	return c.Start(cfg)
}

// =============================================================================
// Two-Phase Resolver Testing API (Fast)
// =============================================================================

// TwoPhaseCallback is called during two-phase resolver testing.
type TwoPhaseCallback interface {
	OnPhaseChange(phase int, message string)
	OnProgress(phase int, tested int, total int, currentResolver string)
	OnPhaseComplete(phase int, passedCount int, totalTested int)
	OnResolverFound(resolver string, latencyMs int64)
}

// TwoPhaseConfig holds configuration for two-phase resolver testing.
type TwoPhaseConfig struct {
	phase1Concurrency int   // Number of concurrent DNS tests (default: 500)
	phase1TimeoutMs   int64 // Timeout for each DNS test in ms (default: 2000)
	phase2Concurrency int   // Number of concurrent tunnel tests (default: 30)
	phase2TimeoutMs   int64 // Timeout for each tunnel test in ms (default: 5000)
	phase2MaxToTest   int   // Max resolvers to test in phase 2 (default: 30)
	maxLatencyMs      int64 // Max acceptable latency from phase 1 (default: 500)
}

// NewTwoPhaseConfig creates a default two-phase configuration.
func NewTwoPhaseConfig() *TwoPhaseConfig {
	return &TwoPhaseConfig{
		phase1Concurrency: 100, // Balanced for stability and speed
		phase1TimeoutMs:   2000,
		phase2Concurrency: 20,
		phase2TimeoutMs:   5000,
		phase2MaxToTest:   30,
		maxLatencyMs:      500,
	}
}

// Setter methods for gomobile compatibility
func (c *TwoPhaseConfig) SetPhase1Concurrency(v int)   { c.phase1Concurrency = v }
func (c *TwoPhaseConfig) SetPhase1TimeoutMs(v int64)   { c.phase1TimeoutMs = v }
func (c *TwoPhaseConfig) SetPhase2Concurrency(v int)   { c.phase2Concurrency = v }
func (c *TwoPhaseConfig) SetPhase2TimeoutMs(v int64)   { c.phase2TimeoutMs = v }
func (c *TwoPhaseConfig) SetPhase2MaxToTest(v int)     { c.phase2MaxToTest = v }
func (c *TwoPhaseConfig) SetMaxLatencyMs(v int64)      { c.maxLatencyMs = v }

// FindWorkingResolverTwoPhase tests resolvers using a fast two-phase approach:
// Phase 1: Fast parallel DNS-only scan (high concurrency, short timeout)
// Phase 2: Tunnel verification on top N fastest resolvers (parallel, early termination)
// Returns the first working resolver address, or empty string if none work.
func FindWorkingResolverTwoPhase(resolvers string, domain string, pubkeyHex string, config *TwoPhaseConfig, callback TwoPhaseCallback) string {
	// Use defaults if config is nil
	if config == nil {
		config = NewTwoPhaseConfig()
	}

	// Parse resolvers from newline-separated string
	lines := strings.Split(resolvers, "\n")
	var resolverList []string
	for _, line := range lines {
		line = strings.TrimSpace(line)
		if line != "" && !strings.HasPrefix(line, "#") {
			resolverList = append(resolverList, line)
		}
	}

	if len(resolverList) == 0 {
		log.Printf("no resolvers provided")
		return ""
	}

	// Parse domain
	domainName, err := dnstt.ParseDomain(domain)
	if err != nil {
		log.Printf("invalid domain: %v", err)
		return ""
	}

	// Parse pubkey
	pubkey, err := noise.DecodeKey(pubkeyHex)
	if err != nil {
		log.Printf("invalid pubkey: %v", err)
		return ""
	}

	total := len(resolverList)
	log.Printf("two-phase testing %d resolvers...", total)

	// =========================================================================
	// PHASE 1: Fast parallel DNS-only scan
	// =========================================================================
	if callback != nil {
		callback.OnPhaseChange(1, fmt.Sprintf("Phase 1: Testing %d DNS resolvers...", total))
	}

	phase1Timeout := time.Duration(config.phase1TimeoutMs) * time.Millisecond
	if phase1Timeout < 500*time.Millisecond {
		phase1Timeout = 2000 * time.Millisecond
	}

	phase1Concurrency := config.phase1Concurrency
	if phase1Concurrency < 1 {
		phase1Concurrency = 500
	}

	// Test DNS resolvers using worker pool pattern (avoids spawning thousands of goroutines)
	type dnsResult struct {
		resolver  string
		latencyMs int64
		success   bool
	}

	workChan := make(chan string, total)
	resultChan := make(chan dnsResult, total)
	var tested int64

	// Start worker pool - fixed number of goroutines
	var wg sync.WaitGroup
	for i := 0; i < phase1Concurrency; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for res := range workChan {
				// DNS-only test
				result := dnstt.TestDNSResolver(res, domainName, phase1Timeout)

				done := atomic.AddInt64(&tested, 1)
				if callback != nil {
					callback.OnProgress(1, int(done), total, res)
				}

				resultChan <- dnsResult{
					resolver:  res,
					latencyMs: result.Latency.Milliseconds(),
					success:   result.Success,
				}
			}
		}()
	}

	// Feed work to workers
	go func() {
		for _, resolver := range resolverList {
			workChan <- resolver
		}
		close(workChan)
	}()

	// Close results when all workers done
	go func() {
		wg.Wait()
		close(resultChan)
	}()

	// Collect phase 1 results
	var phase1Results []dnsResult
	for r := range resultChan {
		if r.success {
			phase1Results = append(phase1Results, r)
		}
	}

	// Sort by latency (fastest first)
	sort.Slice(phase1Results, func(i, j int) bool {
		return phase1Results[i].latencyMs < phase1Results[j].latencyMs
	})

	phase1Passed := len(phase1Results)
	log.Printf("phase 1 complete: %d/%d resolvers passed DNS test", phase1Passed, total)

	if callback != nil {
		callback.OnPhaseComplete(1, phase1Passed, total)
	}

	if phase1Passed == 0 {
		log.Printf("no resolvers passed phase 1")
		return ""
	}

	// =========================================================================
	// PHASE 2: Tunnel verification on top N fastest resolvers
	// =========================================================================
	phase2MaxToTest := config.phase2MaxToTest
	if phase2MaxToTest < 1 {
		phase2MaxToTest = 30
	}

	// Filter by max latency and limit to top N
	var phase2Candidates []dnsResult
	for _, r := range phase1Results {
		if r.latencyMs <= config.maxLatencyMs || len(phase2Candidates) == 0 {
			phase2Candidates = append(phase2Candidates, r)
			if len(phase2Candidates) >= phase2MaxToTest {
				break
			}
		}
	}

	// If no candidates within latency limit, take the fastest ones anyway
	if len(phase2Candidates) == 0 && len(phase1Results) > 0 {
		for i := 0; i < phase2MaxToTest && i < len(phase1Results); i++ {
			phase2Candidates = append(phase2Candidates, phase1Results[i])
		}
	}

	phase2Total := len(phase2Candidates)
	log.Printf("phase 2: testing %d fastest resolvers for tunnel connectivity...", phase2Total)

	if callback != nil {
		callback.OnPhaseChange(2, fmt.Sprintf("Phase 2: Verifying %d fastest resolvers...", phase2Total))
	}

	phase2Timeout := time.Duration(config.phase2TimeoutMs) * time.Millisecond
	if phase2Timeout < 2*time.Second {
		phase2Timeout = 5 * time.Second
	}

	phase2Concurrency := config.phase2Concurrency
	if phase2Concurrency < 1 {
		phase2Concurrency = 30
	}

	// Test tunnel connections using worker pool with early termination
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	type tunnelWork struct {
		resolver  string
		latencyMs int64
	}

	workChan2 := make(chan tunnelWork, phase2Total)
	foundChan := make(chan string, 1)
	var phase2Tested int64
	var phase2Passed int64

	// Start worker pool for phase 2
	var wg2 sync.WaitGroup
	for i := 0; i < phase2Concurrency; i++ {
		wg2.Add(1)
		go func() {
			defer wg2.Done()
			for {
				select {
				case <-ctx.Done():
					return
				case work, ok := <-workChan2:
					if !ok {
						return
					}

					// Ensure resolver has port
					resolverWithPort := work.resolver
					if !strings.Contains(work.resolver, ":") {
						resolverWithPort = work.resolver + ":53"
					}

					done := atomic.AddInt64(&phase2Tested, 1)
					if callback != nil {
						callback.OnProgress(2, int(done), phase2Total, work.resolver)
					}

					// Test tunnel connection
					err := dnstt.TestTunnelConnection(resolverWithPort, domain, pubkey, nil, phase2Timeout)
					if err != nil {
						log.Printf("tunnel test failed for %s: %v", work.resolver, err)
						continue
					}

					// Success!
					atomic.AddInt64(&phase2Passed, 1)
					log.Printf("FOUND working resolver: %s (latency: %dms)", resolverWithPort, work.latencyMs)

					select {
					case foundChan <- resolverWithPort:
						cancel() // Cancel all other tests
						if callback != nil {
							callback.OnResolverFound(resolverWithPort, work.latencyMs)
						}
					default:
						// Another goroutine already found one
					}
					return // Exit worker after finding
				}
			}
		}()
	}

	// Feed work to workers
	go func() {
		for _, candidate := range phase2Candidates {
			select {
			case <-ctx.Done():
				break
			case workChan2 <- tunnelWork{resolver: candidate.resolver, latencyMs: candidate.latencyMs}:
			}
		}
		close(workChan2)
	}()

	// Wait for workers to finish
	go func() {
		wg2.Wait()
		close(foundChan)
	}()

	var result string
	for r := range foundChan {
		if r != "" {
			result = r
			break
		}
	}

	if callback != nil {
		callback.OnPhaseComplete(2, int(atomic.LoadInt64(&phase2Passed)), phase2Total)
	}

	if result == "" {
		log.Printf("no working resolver found after two-phase testing")
	}

	return result
}

// FindFirstWorkingResolver tests resolvers sequentially (one at a time) and returns
// the first one that works. This is fast because it stops as soon as it finds a working one.
// Much lighter weight than testing all resolvers in parallel.
// Returns the first working resolver address, or empty string if none work.
func FindFirstWorkingResolver(resolvers string, domain string, pubkeyHex string, timeoutMs int64, callback ResolverCallback) string {
	// Parse resolvers from newline-separated string
	lines := strings.Split(resolvers, "\n")
	var resolverList []string
	for _, line := range lines {
		line = strings.TrimSpace(line)
		if line != "" && !strings.HasPrefix(line, "#") {
			resolverList = append(resolverList, line)
		}
	}

	if len(resolverList) == 0 {
		log.Printf("no resolvers provided")
		return ""
	}

	// Parse domain
	domainName, err := dnstt.ParseDomain(domain)
	if err != nil {
		log.Printf("invalid domain: %v", err)
		return ""
	}

	// Parse pubkey
	pubkey, err := noise.DecodeKey(pubkeyHex)
	if err != nil {
		log.Printf("invalid pubkey: %v", err)
		return ""
	}

	// Get uTLS fingerprint
	utlsID, err := dnstt.SampleUTLSDistribution("Chrome")
	if err != nil {
		log.Printf("failed to get utls ID: %v", err)
		return ""
	}

	timeout := time.Duration(timeoutMs) * time.Millisecond
	if timeout < 1*time.Second {
		timeout = 3 * time.Second
	}

	total := len(resolverList)
	log.Printf("finding first working resolver from %d candidates...", total)

	// Test resolvers sequentially - stop at first success
	for i, resolver := range resolverList {
		if callback != nil {
			callback.OnProgress(i+1, total, resolver)
		}

		// Ensure resolver has port
		resolverWithPort := resolver
		if !strings.Contains(resolver, ":") {
			resolverWithPort = resolver + ":53"
		}

		log.Printf("[%d/%d] testing %s...", i+1, total, resolver)

		// First do quick DNS test
		result := dnstt.TestDNSResolver(resolver, domainName, timeout)
		if !result.Success {
			log.Printf("[%d/%d] %s failed DNS test: %s", i+1, total, resolver, result.Error)
			if callback != nil {
				callback.OnResult(resolver, false, 0, result.Error)
			}
			continue
		}

		log.Printf("[%d/%d] %s passed DNS test (%dms), testing tunnel...", i+1, total, resolver, result.Latency.Milliseconds())

		// Then verify tunnel connection
		err := dnstt.TestTunnelConnection(resolverWithPort, domain, pubkey, utlsID, timeout)
		if err != nil {
			log.Printf("[%d/%d] %s failed tunnel test: %v", i+1, total, resolver, err)
			if callback != nil {
				callback.OnResult(resolver, false, result.Latency.Milliseconds(), err.Error())
			}
			continue
		}

		// Success! Return with port so it can be used directly
		log.Printf("[%d/%d] %s WORKS! (latency %dms)", i+1, total, resolverWithPort, result.Latency.Milliseconds())
		if callback != nil {
			callback.OnResult(resolverWithPort, true, result.Latency.Milliseconds(), "")
		}
		return resolverWithPort
	}

	log.Printf("no working resolver found after testing %d candidates", total)
	return ""
}
