package com.dnstt.client;

import android.util.Log;

import org.xbill.DNS.*;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Fast native Java DNS resolver tester using dnsjava library.
 * Much more efficient than going through gomobile for thousands of DNS queries.
 */
public class FastDnsTester {
    private static final String TAG = "FastDnsTester";

    public interface Callback {
        void onProgress(int tested, int total, String currentResolver);
        void onPhaseComplete(int passedCount, int totalTested, List<ResolverResult> results);
    }

    public static class ResolverResult implements Comparable<ResolverResult> {
        public final String resolver;
        public final long latencyMs;
        public final boolean success;
        public final String error;

        public ResolverResult(String resolver, long latencyMs, boolean success, String error) {
            this.resolver = resolver;
            this.latencyMs = latencyMs;
            this.success = success;
            this.error = error;
        }

        @Override
        public int compareTo(ResolverResult other) {
            // Sort by success first, then by latency
            if (this.success != other.success) {
                return this.success ? -1 : 1;
            }
            return Long.compare(this.latencyMs, other.latencyMs);
        }
    }

    /**
     * Test DNS resolvers in parallel using native Java.
     * This is MUCH faster than going through gomobile.
     *
     * @param resolvers List of resolver addresses (IP or IP:port)
     * @param domain The DNSTT domain to test (e.g., "t3.example.com")
     * @param timeoutMs Timeout for each DNS query in milliseconds
     * @param concurrency Number of parallel workers
     * @param callback Progress callback
     * @return List of results sorted by latency (fastest first)
     */
    public static List<ResolverResult> testResolvers(
            List<String> resolvers,
            String domain,
            int timeoutMs,
            int concurrency,
            Callback callback) {

        if (resolvers == null || resolvers.isEmpty()) {
            return Collections.emptyList();
        }

        int total = resolvers.size();
        Log.d(TAG, "Testing " + total + " resolvers with " + concurrency + " workers, timeout=" + timeoutMs + "ms");

        // Results queue (thread-safe)
        ConcurrentLinkedQueue<ResolverResult> results = new ConcurrentLinkedQueue<>();
        AtomicInteger tested = new AtomicInteger(0);
        AtomicInteger passed = new AtomicInteger(0);

        // Work queue
        ConcurrentLinkedQueue<String> workQueue = new ConcurrentLinkedQueue<>(resolvers);

        // Create thread pool
        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        CountDownLatch latch = new CountDownLatch(concurrency);

        // Start workers
        for (int i = 0; i < concurrency; i++) {
            executor.submit(() -> {
                try {
                    while (true) {
                        String resolver = workQueue.poll();
                        if (resolver == null) break;

                        ResolverResult result = testSingleResolver(resolver, domain, timeoutMs);
                        results.add(result);

                        int done = tested.incrementAndGet();
                        if (result.success) {
                            passed.incrementAndGet();
                        }

                        // Report progress (throttled in callback)
                        if (callback != null) {
                            callback.onProgress(done, total, resolver);
                        }
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        // Wait for all workers to complete
        try {
            latch.await(timeoutMs * 2L + 30000, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Log.w(TAG, "DNS test interrupted");
        }

        // Shutdown executor
        executor.shutdownNow();

        // Convert to sorted list
        List<ResolverResult> sortedResults = new ArrayList<>(results);
        Collections.sort(sortedResults);

        Log.d(TAG, "DNS test complete: " + passed.get() + "/" + total + " passed");

        if (callback != null) {
            callback.onPhaseComplete(passed.get(), total, sortedResults);
        }

        return sortedResults;
    }

    /**
     * Test a single DNS resolver by sending a TXT query for the DNSTT domain.
     */
    private static ResolverResult testSingleResolver(String resolver, String domain, int timeoutMs) {
        long startTime = System.currentTimeMillis();

        try {
            // Parse resolver address
            String host;
            int port = 53;
            if (resolver.contains(":")) {
                String[] parts = resolver.split(":");
                host = parts[0];
                port = Integer.parseInt(parts[1]);
            } else {
                host = resolver;
            }

            // Create a simple resolver pointing to this specific DNS server
            SimpleResolver simpleResolver = new SimpleResolver(host);
            simpleResolver.setPort(port);
            simpleResolver.setTimeout(Duration.ofMillis(timeoutMs));

            // Build TXT query for DNSTT domain (this is what DNSTT uses)
            // We query for a random subdomain to test the resolver can reach our server
            String queryName = "test." + domain;
            org.xbill.DNS.Record question = org.xbill.DNS.Record.newRecord(Name.fromString(queryName + "."), Type.TXT, DClass.IN);
            Message query = Message.newQuery(question);

            // Send query and wait for response
            Message response = simpleResolver.send(query);

            long latency = System.currentTimeMillis() - startTime;

            // Check if we got a valid response (even NXDOMAIN is fine - means DNS works)
            int rcode = response.getRcode();
            if (rcode == Rcode.NOERROR || rcode == Rcode.NXDOMAIN || rcode == Rcode.SERVFAIL) {
                // DNS resolver is working - it responded to our query
                return new ResolverResult(resolver, latency, true, null);
            } else {
                return new ResolverResult(resolver, latency, false, "rcode=" + Rcode.string(rcode));
            }

        } catch (Exception e) {
            long latency = System.currentTimeMillis() - startTime;
            String error = e.getMessage();
            if (error == null) error = e.getClass().getSimpleName();

            // Truncate long error messages
            if (error.length() > 50) {
                error = error.substring(0, 50) + "...";
            }

            return new ResolverResult(resolver, latency, false, error);
        }
    }

    /**
     * Filter results to get the top N fastest successful resolvers.
     */
    public static List<ResolverResult> getTopFastest(List<ResolverResult> results, int maxCount, long maxLatencyMs) {
        List<ResolverResult> filtered = new ArrayList<>();
        for (ResolverResult r : results) {
            if (r.success && (maxLatencyMs <= 0 || r.latencyMs <= maxLatencyMs)) {
                filtered.add(r);
                if (filtered.size() >= maxCount) break;
            }
        }
        return filtered;
    }
}
