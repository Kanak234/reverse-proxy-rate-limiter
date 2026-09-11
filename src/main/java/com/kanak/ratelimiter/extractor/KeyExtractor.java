package com.kanak.ratelimiter.extractor;

import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpRequest;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

/**
 * Utility for extracting client identification keys from HTTP requests according to configured strategies.
 */
public final class KeyExtractor {

    private KeyExtractor() {
    }

    /**
     * Extracts a rate-limiting key for the given request and strategy.
     */
    public static String extractKey(HttpRequest request, SocketAddress remoteAddress,
                                    KeyExtractorStrategy strategy, String routePrefix) {
        return switch (strategy) {
            case IP -> extractClientIp(request, remoteAddress);
            case API_KEY -> extractApiKey(request);
            case ROUTE_IP -> routePrefix + ":" + extractClientIp(request, remoteAddress);
            case ROUTE_API_KEY -> routePrefix + ":" + extractApiKey(request);
            case GLOBAL -> "global:" + routePrefix;
        };
    }

    /**
     * Extracts client IP with support for X-Forwarded-For and X-Real-IP reverse proxy headers.
     */
    public static String extractClientIp(HttpRequest request, SocketAddress remoteAddress) {
        if (request != null) {
            HttpHeaders headers = request.headers();

            // 1. Check X-Forwarded-For (leftmost entry is the originating client)
            String xff = headers.get("X-Forwarded-For");
            if (xff != null && !xff.isBlank()) {
                String[] ips = xff.split(",");
                if (ips.length > 0) {
                    String clientIp = ips[0].trim();
                    if (!clientIp.isEmpty()) {
                        return sanitizeIp(clientIp);
                    }
                }
            }

            // 2. Check X-Real-IP
            String realIp = headers.get("X-Real-IP");
            if (realIp != null && !realIp.isBlank()) {
                return sanitizeIp(realIp.trim());
            }
        }

        // 3. Fallback to TCP SocketAddress
        if (remoteAddress instanceof InetSocketAddress inetSocketAddress) {
            var addr = inetSocketAddress.getAddress();
            if (addr != null) {
                return addr.getHostAddress();
            }
            return inetSocketAddress.getHostString();
        }

        return "127.0.0.1";
    }

    /**
     * Extracts API key from X-API-Key or Authorization Bearer header.
     */
    public static String extractApiKey(HttpRequest request) {
        if (request == null) {
            return "anonymous";
        }
        HttpHeaders headers = request.headers();

        // 1. X-API-Key
        String apiKey = headers.get("X-API-Key");
        if (apiKey != null && !apiKey.isBlank()) {
            return apiKey.trim();
        }

        // 2. Authorization Header (Bearer or ApiKey)
        String auth = headers.get("Authorization");
        if (auth != null && !auth.isBlank()) {
            auth = auth.trim();
            if (auth.regionMatches(true, 0, "Bearer ", 0, 7)) {
                return auth.substring(7).trim();
            }
            if (auth.regionMatches(true, 0, "ApiKey ", 0, 7)) {
                return auth.substring(7).trim();
            }
            return auth;
        }

        return "anonymous";
    }

    private static String sanitizeIp(String ip) {
        // Strip IPv6 square brackets or IPv4 port suffixes if present (e.g. 192.168.1.1:8080)
        int colonIdx = ip.indexOf(':');
        if (colonIdx > 0 && !ip.contains("::") && ip.indexOf(':', colonIdx + 1) == -1) {
            // IPv4 with port
            return ip.substring(0, colonIdx);
        }
        return ip;
    }
}
