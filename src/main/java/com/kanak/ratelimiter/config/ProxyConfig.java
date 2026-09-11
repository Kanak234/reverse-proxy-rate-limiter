package com.kanak.ratelimiter.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Global configuration settings for the reverse proxy server.
 */
public final class ProxyConfig {

    private final int serverPort;
    private final String upstreamHost;
    private final int upstreamPort;
    private final int bossThreads;
    private final int workerThreads;
    private final int maxContentLengthBytes;
    private final List<RateLimitRule> rules;

    private ProxyConfig(Builder builder) {
        this.serverPort = builder.serverPort;
        this.upstreamHost = Objects.requireNonNull(builder.upstreamHost, "upstreamHost must not be null");
        this.upstreamPort = builder.upstreamPort;
        this.bossThreads = builder.bossThreads;
        this.workerThreads = builder.workerThreads;
        this.maxContentLengthBytes = builder.maxContentLengthBytes;
        this.rules = Collections.unmodifiableList(new ArrayList<>(builder.rules));
    }

    public static Builder builder() {
        return new Builder();
    }

    public int getServerPort() {
        return serverPort;
    }

    public String getUpstreamHost() {
        return upstreamHost;
    }

    public int getUpstreamPort() {
        return upstreamPort;
    }

    public int getBossThreads() {
        return bossThreads;
    }

    public int getWorkerThreads() {
        return workerThreads;
    }

    public int getMaxContentLengthBytes() {
        return maxContentLengthBytes;
    }

    public List<RateLimitRule> getRules() {
        return rules;
    }

    /**
     * Finds the longest prefix matching RateLimitRule for the requested path.
     */
    public RateLimitRule findMatchingRule(String path) {
        RateLimitRule bestMatch = null;
        int maxMatchLength = -1;

        for (RateLimitRule rule : rules) {
            String prefix = rule.getRoutePrefix();
            if (path.startsWith(prefix) && prefix.length() > maxMatchLength) {
                bestMatch = rule;
                maxMatchLength = prefix.length();
            }
        }
        return bestMatch;
    }

    public static final class Builder {
        private int serverPort = 8080;
        private String upstreamHost = "127.0.0.1";
        private int upstreamPort = 8081;
        private int bossThreads = 1;
        private int workerThreads = Runtime.getRuntime().availableProcessors() * 2;
        private int maxContentLengthBytes = 10 * 1024 * 1024; // 10MB
        private final List<RateLimitRule> rules = new ArrayList<>();

        public Builder serverPort(int serverPort) {
            this.serverPort = serverPort;
            return this;
        }

        public Builder upstreamHost(String upstreamHost) {
            this.upstreamHost = upstreamHost;
            return this;
        }

        public Builder upstreamPort(int upstreamPort) {
            this.upstreamPort = upstreamPort;
            return this;
        }

        public Builder bossThreads(int bossThreads) {
            this.bossThreads = bossThreads;
            return this;
        }

        public Builder workerThreads(int workerThreads) {
            this.workerThreads = workerThreads;
            return this;
        }

        public Builder maxContentLengthBytes(int maxContentLengthBytes) {
            this.maxContentLengthBytes = maxContentLengthBytes;
            return this;
        }

        public Builder addRule(RateLimitRule rule) {
            this.rules.add(Objects.requireNonNull(rule, "rule must not be null"));
            return this;
        }

        public ProxyConfig build() {
            if (rules.isEmpty()) {
                // Default catch-all rule: TokenBucket with capacity 1000, 500 refill/s
                rules.add(RateLimitRule.builder()
                        .routePrefix("/")
                        .algorithm(RateLimitAlgorithm.TOKEN_BUCKET)
                        .capacity(1000)
                        .refillRatePerSecond(500)
                        .build());
            }
            return new ProxyConfig(this);
        }
    }
}
