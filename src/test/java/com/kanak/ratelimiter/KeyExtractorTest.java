package com.kanak.ratelimiter;

import com.kanak.ratelimiter.extractor.KeyExtractor;
import com.kanak.ratelimiter.extractor.KeyExtractorStrategy;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;

import static org.assertj.core.api.Assertions.assertThat;

class KeyExtractorTest {

    @Test
    @DisplayName("Should extract client IP from leftmost entry in X-Forwarded-For")
    void testXForwardedFor() {
        var req = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/api/resource");
        req.headers().set("X-Forwarded-For", "203.0.113.195, 70.41.3.18, 150.172.238.178");

        String ip = KeyExtractor.extractClientIp(req, new InetSocketAddress("127.0.0.1", 50000));
        assertThat(ip).isEqualTo("203.0.113.195");
    }

    @Test
    @DisplayName("Should extract client IP from X-Real-IP header")
    void testXRealIp() {
        var req = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/api/resource");
        req.headers().set("X-Real-IP", "198.51.100.42");

        String ip = KeyExtractor.extractClientIp(req, new InetSocketAddress("127.0.0.1", 50000));
        assertThat(ip).isEqualTo("198.51.100.42");
    }

    @Test
    @DisplayName("Should fall back to remote socket address when headers are absent")
    void testRemoteSocketFallback() {
        var req = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/api/resource");
        var socketAddr = new InetSocketAddress("192.168.1.100", 45678);

        String ip = KeyExtractor.extractClientIp(req, socketAddr);
        assertThat(ip).isEqualTo("192.168.1.100");
    }

    @Test
    @DisplayName("Should extract API key from X-API-Key and Authorization Bearer headers")
    void testApiKeyExtraction() {
        var req1 = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/api/resource");
        req1.headers().set("X-API-Key", "api-key-test-abc");
        assertThat(KeyExtractor.extractApiKey(req1)).isEqualTo("api-key-test-abc");

        var req2 = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/api/resource");
        req2.headers().set("Authorization", "Bearer token-12345-jwt");
        assertThat(KeyExtractor.extractApiKey(req2)).isEqualTo("token-12345-jwt");
    }

    @Test
    @DisplayName("Should generate correct composite keys for routing strategies")
    void testCompositeStrategies() {
        var req = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/api/v1/auth/login");
        req.headers().set("X-Real-IP", "10.0.0.5");
        req.headers().set("X-API-Key", "sec-key-99");
        var addr = new InetSocketAddress("127.0.0.1", 8080);

        String ipKey = KeyExtractor.extractKey(req, addr, KeyExtractorStrategy.IP, "/api/v1");
        assertThat(ipKey).isEqualTo("10.0.0.5");

        String routeIpKey = KeyExtractor.extractKey(req, addr, KeyExtractorStrategy.ROUTE_IP, "/api/v1/auth");
        assertThat(routeIpKey).isEqualTo("/api/v1/auth:10.0.0.5");

        String routeApiKey = KeyExtractor.extractKey(req, addr, KeyExtractorStrategy.ROUTE_API_KEY, "/api/v1");
        assertThat(routeApiKey).isEqualTo("/api/v1:sec-key-99");

        String globalKey = KeyExtractor.extractKey(req, addr, KeyExtractorStrategy.GLOBAL, "/api/v1");
        assertThat(globalKey).isEqualTo("global:/api/v1");
    }
}
