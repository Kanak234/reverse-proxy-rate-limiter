package com.kanak.ratelimiter.proxy;

import com.kanak.ratelimiter.config.ProxyConfig;
import com.kanak.ratelimiter.config.RateLimitRule;
import com.kanak.ratelimiter.core.PartitionedRateLimiter;
import com.kanak.ratelimiter.core.RateLimitResult;
import com.kanak.ratelimiter.extractor.KeyExtractor;
import com.kanak.ratelimiter.metrics.MetricsCollector;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.*;

import java.nio.charset.StandardCharsets;

/**
 * Netty inbound handler executing wire-speed rate limiting checks and routing admitted traffic to upstream.
 */
public final class InboundRateLimitHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    private final ProxyConfig config;
    private final PartitionedRateLimiter rateLimiter;
    private final UpstreamClientPool upstreamClientPool;
    private final AdminHandler adminHandler;
    private final MetricsCollector metrics = MetricsCollector.getInstance();

    public InboundRateLimitHandler(ProxyConfig config,
                                   PartitionedRateLimiter rateLimiter,
                                   UpstreamClientPool upstreamClientPool,
                                   AdminHandler adminHandler) {
        this.config = config;
        this.rateLimiter = rateLimiter;
        this.upstreamClientPool = upstreamClientPool;
        this.adminHandler = adminHandler;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
        long startNanos = System.nanoTime();
        metrics.markRequestReceived();

        // 1. Check administrative endpoints
        if (adminHandler.handleIfAdminRequest(ctx, request)) {
            return;
        }

        // 2. Extract path without query parameters
        String uri = request.uri();
        String path = uri;
        int queryIdx = uri.indexOf('?');
        if (queryIdx >= 0) {
            path = uri.substring(0, queryIdx);
        }

        // 3. Match rate limiting rule
        RateLimitRule rule = config.findMatchingRule(path);
        if (rule == null) {
            // No matching rule, forward directly
            RateLimitResult unrestricted = RateLimitResult.admitted(1000, 1000, System.currentTimeMillis() / 1000L);
            metrics.markAdmitted();
            upstreamClientPool.forwardRequest(ctx, request, unrestricted, startNanos);
            return;
        }

        // 4. Extract rate limiting partition key
        String key = KeyExtractor.extractKey(request, ctx.channel().remoteAddress(),
                rule.getStrategy(), rule.getRoutePrefix());

        // 5. Evaluate rate limit
        RateLimitResult result = rateLimiter.tryAcquire(key, rule);

        if (result.allowed()) {
            metrics.markAdmitted();
            upstreamClientPool.forwardRequest(ctx, request, result, startNanos);
        } else {
            metrics.markRejected();
            long durationMicros = (System.nanoTime() - startNanos) / 1000;
            metrics.recordLatency(durationMicros);

            sendRateLimitResponse(ctx, request, result);
        }
    }

    private void sendRateLimitResponse(ChannelHandlerContext ctx, FullHttpRequest request, RateLimitResult result) {
        long retrySec = result.retryAfterSeconds();
        String json = "{\"status\":429,\"error\":\"Too Many Requests\",\"message\":\"Rate limit exceeded. Please retry after specified interval.\",\"retryAfterSeconds\":"
                + retrySec + "}";
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);

        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1,
                HttpResponseStatus.TOO_MANY_REQUESTS,
                Unpooled.wrappedBuffer(bytes)
        );

        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=UTF-8");
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, bytes.length);
        response.headers().set(HttpHeaderNames.RETRY_AFTER, String.valueOf(retrySec));
        response.headers().set("X-RateLimit-Limit", String.valueOf(result.limit()));
        response.headers().set("X-RateLimit-Remaining", "0");
        response.headers().set("X-RateLimit-Reset", String.valueOf(result.resetEpochSeconds()));

        boolean keepAlive = HttpUtil.isKeepAlive(request);
        HttpUtil.setKeepAlive(response, keepAlive);

        ChannelFuture future = ctx.writeAndFlush(response);
        if (!keepAlive) {
            future.addListener(ChannelFutureListener.CLOSE);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        ctx.close();
    }
}
