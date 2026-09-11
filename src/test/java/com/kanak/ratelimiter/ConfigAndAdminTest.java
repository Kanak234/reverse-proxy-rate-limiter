package com.kanak.ratelimiter;

import com.kanak.ratelimiter.config.ProxyConfig;
import com.kanak.ratelimiter.config.RateLimitAlgorithm;
import com.kanak.ratelimiter.config.RateLimitRule;
import com.kanak.ratelimiter.core.PartitionedRateLimiter;
import com.kanak.ratelimiter.extractor.KeyExtractor;
import com.kanak.ratelimiter.extractor.KeyExtractorStrategy;
import com.kanak.ratelimiter.proxy.AdminHandler;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.*;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfigAndAdminTest {

    @Test
    void testProxyConfigValidation() {
        assertThatThrownBy(() -> ProxyConfig.builder().serverPort(-1).build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ProxyConfig.builder().upstreamPort(70000).build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ProxyConfig.builder().upstreamHost(null).build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ProxyConfig.builder().upstreamHost("").build())
                .isInstanceOf(IllegalArgumentException.class);

        ProxyConfig config = ProxyConfig.builder()
                .serverPort(8080)
                .upstreamHost("127.0.0.1")
                .upstreamPort(8081)
                .bossThreads(2)
                .workerThreads(4)
                .addRule(RateLimitRule.builder()
                        .routePrefix("/api")
                        .algorithm(RateLimitAlgorithm.SLIDING_WINDOW)
                        .capacity(500)
                        .refillRatePerSecond(250)
                        .strategy(KeyExtractorStrategy.ROUTE_API_KEY)
                        .build())
                .build();

        assertThat(config.getServerPort()).isEqualTo(8080);
        assertThat(config.getUpstreamHost()).isEqualTo("127.0.0.1");
        assertThat(config.getUpstreamPort()).isEqualTo(8081);
        assertThat(config.getBossThreads()).isEqualTo(2);
        assertThat(config.getWorkerThreads()).isEqualTo(4);
        assertThat(config.getRules()).hasSize(1);
    }

    @Test
    void testRateLimitRuleValidation() {
        assertThatThrownBy(() -> RateLimitRule.builder().routePrefix(null).build())
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> RateLimitRule.builder().routePrefix("").build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RateLimitRule.builder().routePrefix("/test").capacity(0).build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RateLimitRule.builder().routePrefix("/test").refillRatePerSecond(-1).build())
                .isInstanceOf(IllegalArgumentException.class);

        RateLimitRule rule = RateLimitRule.builder()
                .routePrefix("/v1")
                .capacity(100)
                .refillRatePerSecond(50)
                .algorithm(RateLimitAlgorithm.TOKEN_BUCKET)
                .strategy(KeyExtractorStrategy.GLOBAL)
                .build();

        assertThat(rule.getRoutePrefix()).isEqualTo("/v1");
        assertThat(rule.getCapacity()).isEqualTo(100);
        assertThat(rule.getRefillRatePerSecond()).isEqualTo(50);
        assertThat(rule.getAlgorithm()).isEqualTo(RateLimitAlgorithm.TOKEN_BUCKET);
        assertThat(rule.getStrategy()).isEqualTo(KeyExtractorStrategy.GLOBAL);
    }

    @Test
    void testKeyExtractorEdgeCases() {
        DefaultHttpRequest req = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/test");
        req.headers().set("Authorization", "ApiKey secret-token-123");
        assertThat(KeyExtractor.extractApiKey(req)).isEqualTo("secret-token-123");

        req.headers().set("Authorization", "Basic dXNlcjpwYXNz");
        assertThat(KeyExtractor.extractApiKey(req)).isEqualTo("Basic dXNlcjpwYXNz");

        assertThat(KeyExtractor.extractApiKey(null)).isEqualTo("anonymous");

        // IPv4 with port sanitization
        req.headers().set("X-Real-IP", "192.168.1.50:9090");
        assertThat(KeyExtractor.extractClientIp(req, null)).isEqualTo("192.168.1.50");

        // Fallback to socket address
        InetSocketAddress unresolved = InetSocketAddress.createUnresolved("test.example.com", 80);
        assertThat(KeyExtractor.extractClientIp(null, unresolved)).isEqualTo("test.example.com");

        assertThat(KeyExtractor.extractClientIp(null, null)).isEqualTo("127.0.0.1");
    }

    @Test
    void testAdminEndpoints() {
        PartitionedRateLimiter limiter = new PartitionedRateLimiter();
        AdminHandler admin = new AdminHandler(limiter);

        EmbeddedChannel ch = new EmbeddedChannel(new io.netty.channel.ChannelInboundHandlerAdapter());
        var ctx = ch.pipeline().firstContext();

        FullHttpRequest nonAdmin = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/api/data");
        assertThat(admin.handleIfAdminRequest(ctx, nonAdmin)).isFalse();

        FullHttpRequest health = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/_admin/health");
        assertThat(admin.handleIfAdminRequest(ctx, health)).isTrue();
        FullHttpResponse res1 = ch.readOutbound();
        assertThat(res1.status()).isEqualTo(HttpResponseStatus.OK);
        res1.release();

        FullHttpRequest metrics = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/_admin/metrics");
        assertThat(admin.handleIfAdminRequest(ctx, metrics)).isTrue();
        FullHttpResponse res2 = ch.readOutbound();
        assertThat(res2.status()).isEqualTo(HttpResponseStatus.OK);
        res2.release();

        FullHttpRequest unknown = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/_admin/unknown");
        assertThat(admin.handleIfAdminRequest(ctx, unknown)).isTrue();
        FullHttpResponse res3 = ch.readOutbound();
        assertThat(res3.status()).isEqualTo(HttpResponseStatus.NOT_FOUND);
        res3.release();
    }
}
