package com.kanak.ratelimiter.proxy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kanak.ratelimiter.core.PartitionedRateLimiter;
import com.kanak.ratelimiter.metrics.MetricsCollector;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.*;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Handles administrative and health endpoints (/_admin/health, /_admin/metrics).
 */
public final class AdminHandler {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final PartitionedRateLimiter rateLimiter;
    private final MetricsCollector metrics = MetricsCollector.getInstance();

    public AdminHandler(PartitionedRateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter;
    }

    public boolean handleIfAdminRequest(ChannelHandlerContext ctx, FullHttpRequest request) {
        String uri = request.uri();
        if (!uri.startsWith("/_admin/") && !uri.equals("/health") && !uri.startsWith("/health?")) {
            return false;
        }

        if (uri.equals("/_admin/health") || uri.startsWith("/_admin/health?")
                || uri.equals("/health") || uri.startsWith("/health?")) {
            sendJsonResponse(ctx, request, HttpResponseStatus.OK, "{\"status\":\"UP\"}");
            return true;
        }

        if (uri.equals("/_admin/metrics") || uri.startsWith("/_admin/metrics?")) {
            try {
                long activeKeys = rateLimiter.totalActiveKeys();
                Map<String, Object> snapshot = metrics.getSnapshot(activeKeys);
                String json = OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(snapshot);
                sendJsonResponse(ctx, request, HttpResponseStatus.OK, json);
            } catch (Exception e) {
                sendJsonResponse(ctx, request, HttpResponseStatus.INTERNAL_SERVER_ERROR,
                        "{\"error\":\"Failed to serialize metrics: " + e.getMessage() + "\"}");
            }
            return true;
        }

        sendJsonResponse(ctx, request, HttpResponseStatus.NOT_FOUND, "{\"error\":\"Unknown admin endpoint\"}");
        return true;
    }

    private void sendJsonResponse(ChannelHandlerContext ctx, FullHttpRequest request,
                                  HttpResponseStatus status, String jsonContent) {
        byte[] bytes = jsonContent.getBytes(StandardCharsets.UTF_8);
        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1,
                status,
                Unpooled.wrappedBuffer(bytes)
        );
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=UTF-8");
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, bytes.length);

        boolean keepAlive = HttpUtil.isKeepAlive(request);
        HttpUtil.setKeepAlive(response, keepAlive);

        ChannelFuture future = ctx.writeAndFlush(response);
        if (!keepAlive) {
            future.addListener(ChannelFutureListener.CLOSE);
        }
    }
}
