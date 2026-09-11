package com.kanak.ratelimiter.proxy;

import com.kanak.ratelimiter.core.RateLimitResult;
import com.kanak.ratelimiter.metrics.MetricsCollector;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.*;

import java.nio.charset.StandardCharsets;

/**
 * Asynchronous Netty forwarder routing admitted requests to upstream backend servers.
 */
public final class UpstreamClientPool {

    private final String upstreamHost;
    private final int upstreamPort;
    private final EventLoopGroup workerGroup;
    private final MetricsCollector metrics = MetricsCollector.getInstance();

    public UpstreamClientPool(String upstreamHost, int upstreamPort, EventLoopGroup workerGroup) {
        this.upstreamHost = upstreamHost;
        this.upstreamPort = upstreamPort;
        this.workerGroup = workerGroup;
    }

    /**
     * Forwards an admitted HTTP request to the upstream server.
     */
    public void forwardRequest(ChannelHandlerContext clientCtx, FullHttpRequest clientRequest,
                               RateLimitResult rateLimitResult, long startNanos) {
        // Retain clientRequest for forwarding
        clientRequest.retain();

        Bootstrap b = new Bootstrap();
        b.group(workerGroup)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ChannelPipeline p = ch.pipeline();
                        p.addLast(new HttpClientCodec());
                        p.addLast(new HttpObjectAggregator(10 * 1024 * 1024));
                        p.addLast(new SimpleChannelInboundHandler<FullHttpResponse>() {
                            @Override
                            protected void channelRead0(ChannelHandlerContext upstreamCtx, FullHttpResponse upstreamResponse) {
                                long durationMicros = (System.nanoTime() - startNanos) / 1000;
                                metrics.markUpstreamSuccess(durationMicros);

                                // Inject Rate Limit Telemetry Headers
                                upstreamResponse.headers().set("X-RateLimit-Limit", String.valueOf(rateLimitResult.limit()));
                                upstreamResponse.headers().set("X-RateLimit-Remaining", String.valueOf(rateLimitResult.remainingTokens()));
                                upstreamResponse.headers().set("X-RateLimit-Reset", String.valueOf(rateLimitResult.resetEpochSeconds()));

                                // Forward response back to client
                                boolean keepAlive = HttpUtil.isKeepAlive(clientRequest);
                                HttpUtil.setKeepAlive(upstreamResponse, keepAlive);

                                ChannelFuture future = clientCtx.writeAndFlush(upstreamResponse.retain());
                                if (!keepAlive) {
                                    future.addListener(ChannelFutureListener.CLOSE);
                                }
                                upstreamCtx.close();
                            }

                            @Override
                            public void exceptionCaught(ChannelHandlerContext upstreamCtx, Throwable cause) {
                                metrics.markUpstreamError();
                                sendBadGateway(clientCtx, clientRequest, "Upstream read failure: " + cause.getMessage());
                                upstreamCtx.close();
                            }
                        });
                    }
                });

        b.connect(upstreamHost, upstreamPort).addListener((ChannelFutureListener) future -> {
            if (future.isSuccess()) {
                Channel upstreamChannel = future.channel();
                // Update Host header for upstream
                clientRequest.headers().set(HttpHeaderNames.HOST, upstreamHost + ":" + upstreamPort);
                upstreamChannel.writeAndFlush(clientRequest);
            } else {
                metrics.markUpstreamError();
                clientRequest.release();
                sendBadGateway(clientCtx, clientRequest, "Failed to connect to upstream backend: " + future.cause().getMessage());
            }
        });
    }

    private void sendBadGateway(ChannelHandlerContext clientCtx, FullHttpRequest request, String errorDetail) {
        if (!clientCtx.channel().isActive()) {
            return;
        }
        String json = "{\"status\":502,\"error\":\"Bad Gateway\",\"message\":\"" +
                errorDetail.replace("\"", "'") + "\"}";
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);

        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1,
                HttpResponseStatus.BAD_GATEWAY,
                Unpooled.wrappedBuffer(bytes)
        );
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=UTF-8");
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, bytes.length);

        clientCtx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }
}
