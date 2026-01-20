# dnstt-fast

## **For a Free Iran**

![Lion and Sun Flag](https://upload.wikimedia.org/wikipedia/commons/thumb/f/fd/State_flag_of_Iran_%281964–1980%29.svg/330px-State_flag_of_Iran_%281964–1980%29.svg.png)

**High Performance DNS Tunnel with Compression & Parallel Queries**

[![Release](https://img.shields.io/github/v/release/mohjaf67/dnstt-fast-tunnel?style=flat-square)](https://github.com/mohjaf67/dnstt-fast-tunnel/releases)
[![License](https://img.shields.io/badge/license-MIT-blue?style=flat-square)](LICENSE)

> Forked from [dnstt](https://www.bamsoftware.com/git/dnstt.git) by David Fifield
>
> Original project: https://www.bamsoftware.com/software/dnstt/

---

## Features

### Client Optimizations

| Feature | Flag | Description |
|---------|------|-------------|
| Zstd Compression | `-zstd` | Compresses tunnel traffic for faster transfers |
| Parallel DNS Queries | `-parallel N` | Send multiple DNS queries simultaneously |
| Parallel Tunnels | `-tunnels N` | Multiple concurrent connections for higher throughput |
| Auto-Connect | `-auto-connect` | Automatically finds and connects to fastest resolver |
| Resolver Testing | `-test-resolvers` | Built-in DNS resolver tester with latency measurement |

### Server Optimizations

- **Zstd Compression** — Enabled by default, automatically compresses all traffic
- **Buffer Pooling** — `sync.Pool` for packet buffers reduces GC pressure
- **RWMutex** — Read-write mutex for concurrent reads
- **Larger Queues** — Channel buffer increased from 100 to 1024

### Infrastructure

- **One-liner Install** — `curl | bash` deployment
- **Self-Updating** — Script updates itself automatically
- **Pre-built Binaries** — No compilation needed

---

## Quick Start

### 1. DNS Setup

Add these records to your domain:

```
A     tns.example.com    YOUR_SERVER_IP
NS    t.example.com      tns.example.com
```

### 2. Server Installation

```bash
curl -sLO https://raw.githubusercontent.com/mohjaf67/dnstt-fast-tunnel/main/dnstt-deploy.sh && sudo bash dnstt-deploy.sh
```

### 3. Client Setup

Download the client for your platform:

```bash
# Linux x64
curl -sLO https://github.com/mohjaf67/dnstt-fast-tunnel/releases/latest/download/dnstt-client-linux-amd64
chmod +x dnstt-client-linux-amd64

# macOS Apple Silicon
curl -sLO https://github.com/mohjaf67/dnstt-fast-tunnel/releases/latest/download/dnstt-client-darwin-arm64
chmod +x dnstt-client-darwin-arm64
```

### 4. Connect

```bash
./dnstt-client -zstd -parallel 16 -auto-connect -test-resolvers ir_dns_servers.txt \
  -pubkey-file server.pub -tunnels 8 t.example.com 127.0.0.1:1080
```

---

## Server Deployment

### One-liner Install

```bash
curl -sLO https://raw.githubusercontent.com/mohjaf67/dnstt-fast-tunnel/main/dnstt-deploy.sh && sudo bash dnstt-deploy.sh
```

### Manual Install

```bash
curl -sLO https://raw.githubusercontent.com/mohjaf67/dnstt-fast-tunnel/main/dnstt-deploy.sh
chmod +x dnstt-deploy.sh
sudo ./dnstt-deploy.sh
```

### Menu Options

After installation, run `dnstt-deploy` from anywhere:

| Option | Description |
|--------|-------------|
| 1 | Install / Reconfigure |
| 2 | Update binary |
| 3 | Update script |
| 4 | Service status |
| 5 | View logs |
| 6 | Show config & public key |

### What It Does

- Downloads pre-built binary from GitHub releases
- Creates dedicated `dnstt` system user
- Generates encryption keypair
- Configures iptables (port 53 → 5300)
- Sets up Dante SOCKS proxy (optional)
- Creates systemd service with auto-restart

### Supported Systems

| Distribution | Package Manager |
|--------------|-----------------|
| Debian / Ubuntu | apt |
| Fedora / Rocky / CentOS | dnf / yum |

**Architectures:** `amd64`, `arm64`

---

## Client Usage

### Download

| Platform | Download |
|----------|----------|
| Linux x64 | [`dnstt-client-linux-amd64`](https://github.com/mohjaf67/dnstt-fast-tunnel/releases/latest/download/dnstt-client-linux-amd64) |
| Linux ARM64 | [`dnstt-client-linux-arm64`](https://github.com/mohjaf67/dnstt-fast-tunnel/releases/latest/download/dnstt-client-linux-arm64) |
| macOS Intel | [`dnstt-client-darwin-amd64`](https://github.com/mohjaf67/dnstt-fast-tunnel/releases/latest/download/dnstt-client-darwin-amd64) |
| macOS Apple Silicon | [`dnstt-client-darwin-arm64`](https://github.com/mohjaf67/dnstt-fast-tunnel/releases/latest/download/dnstt-client-darwin-arm64) |
| Windows x64 | [`dnstt-client-windows-amd64.exe`](https://github.com/mohjaf67/dnstt-fast-tunnel/releases/latest/download/dnstt-client-windows-amd64.exe) |
| Android | Coming soon |

### Examples

#### Auto-Connect (Recommended)

```bash
./dnstt-client -zstd -parallel 16 -auto-connect -test-resolvers ir_dns_servers.txt \
  -pubkey-file server.pub -tunnels 8 t.example.com 127.0.0.1:1080
```

#### Manual — DoH (DNS over HTTPS)

```bash
./dnstt-client -zstd -parallel 8 -doh https://dns.google/dns-query \
  -pubkey-file server.pub -tunnels 8 t.example.com 127.0.0.1:1080
```

#### Manual — DoT (DNS over TLS)

```bash
./dnstt-client -zstd -parallel 8 -dot dns.google:853 \
  -pubkey-file server.pub -tunnels 8 t.example.com 127.0.0.1:1080
```

#### Manual — UDP

```bash
./dnstt-client -zstd -parallel 8 -udp 1.1.1.1:53 \
  -pubkey-file server.pub -tunnels 8 t.example.com 127.0.0.1:1080
```

### Command Breakdown

| Flag | Description |
|------|-------------|
| `-zstd` | Enable zstd compression |
| `-parallel 16` | Send 16 DNS queries in parallel |
| `-auto-connect` | Auto-select fastest resolver |
| `-test-resolvers FILE` | Test resolvers from file |
| `-pubkey-file FILE` | Server public key file |
| `-tunnels 8` | Create 8 parallel tunnels |
| `t.example.com` | Your NS subdomain |
| `127.0.0.1:1080` | Local SOCKS5 proxy address |

---

## Client Options

### Connection

| Flag | Description |
|------|-------------|
| `-doh URL` | DoH resolver (e.g., `https://dns.google/dns-query`) |
| `-dot ADDR` | DoT resolver (e.g., `dns.google:853`) |
| `-udp ADDR` | UDP DNS server (e.g., `1.1.1.1:53`) |
| `-pubkey KEY` | Server public key (hex) |
| `-pubkey-file FILE` | Server public key file |

### Performance

| Flag | Default | Description |
|------|---------|-------------|
| `-tunnels N` | 8 | Parallel tunnel connections |
| `-parallel N` | 1 | Parallel DNS queries per request |
| `-zstd` | off | Enable zstd compression |
| `-mtu N` | 1232 | Max DNS response size |

### Auto-Connect

| Flag | Default | Description |
|------|---------|-------------|
| `-auto-connect` | off | Auto-select fastest resolver |
| `-test-resolvers FILE` | — | File with resolver IPs (one per line) |
| `-test-timeout MS` | 2000 | Resolver test timeout |
| `-test-concurrency N` | 500 | Concurrent tests |
| `-test-top N` | 50 | Show top N results |
| `-test-output FILE` | — | Save working resolvers |

### TLS

| Flag | Default | Description |
|------|---------|-------------|
| `-utls SPEC` | random | Fingerprint: `chrome`, `firefox`, `safari`, `ios`, `android`, `random` |

---

## Resolver Testing

Test resolvers without connecting:

```bash
./dnstt-client -test-resolvers ir_dns_servers.txt t.example.com
```

Save working resolvers:

```bash
./dnstt-client -test-resolvers ir_dns_servers.txt -test-output working.txt t.example.com
```

---

## Testing Your Setup

### Server Logs

```bash
journalctl -u dnstt-server -f
```

### Test Connection

```bash
curl --proxy socks5h://127.0.0.1:1080 https://ifconfig.me
```

### Speed Test

```bash
curl --proxy socks5h://127.0.0.1:1080 -o /dev/null \
  https://speed.cloudflare.com/__down?bytes=10000000
```

---

## Architecture

```
┌────────┐         ┌───────────┐         ┌────────┐
│ Client │◄─DoH───►│ Recursive │◄──UDP──►│ Server │
│        │  DoT    │ Resolver  │   DNS   │        │
└────────┘         └───────────┘         └────────┘
    │                                        │
┌────────┐                              ┌────────┐
│  App   │         CENSORED             │ Proxy  │
└────────┘                              └────────┘
```

The tunnel encrypts data end-to-end using the **Noise protocol**. DoH/DoT hides the tunnel destination from local observers.

---

## Performance Tips

### Client

- Use `-tunnels 8-32` depending on resolver capacity
- Use `-parallel 8-16` for higher throughput
- Enable `-zstd` for compression
- Auto-connect picks resolvers with <500ms latency

### Server

Optimizations are enabled by default:
- Zstd compression enabled automatically
- Buffer pool reuses packet memory
- RWMutex allows parallel reads
- Larger channel buffers reduce packet drops

---

## License

This project is a fork of [dnstt](https://www.bamsoftware.com/software/dnstt/) by David Fifield.
