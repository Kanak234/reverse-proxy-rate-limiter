package com.kanak.ratelimiter;

import com.kanak.ratelimiter.config.ProxyConfig;
import com.kanak.ratelimiter.config.RateLimitAlgorithm;
import com.kanak.ratelimiter.config.RateLimitRule;
import com.kanak.ratelimiter.extractor.KeyExtractorStrategy;
import com.kanak.ratelimiter.proxy.ReverseProxyServer;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.*;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class ReverseProxyIntegrationTest {

    private static EventLoopGroup backendBoss;
    private static EventLoopGroup backendWorker;
    private static Channel backendChannel;
    private static int backendPort;

    private static ReverseProxyServer proxyServer;
    private static int proxyPort;

    private static HttpClient httpClient;

    @BeforeAll
    static void setUp() throws Exception {
        // 1. Start embedded backend server on random available port
        backendBoss = new NioEventLoopGroup(1);
        backendWorker = new NioEventLoopGroup(2);

        ServerBootstrap backendBoot = new ServerBootstrap();
        backendBoot.group(backendBoss, backendWorker)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ChannelPipeline p = ch.pipeline();
                        p.addLast(new HttpServerCodec());
                        p.addLast(new HttpObjectAggregator(1024 * 1024));
                        p.addLast(new SimpleChannelInboundHandler<FullHttpRequest>() {
                            @Override
                            protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest req) {
                                String body = "{\"status\":\"ok\",\"service\":\"backend\",\"path\":\"" + req.uri() + "\"}";
                                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);

                                FullHttpResponse resp = new DefaultFullHttpResponse(
                                        HttpVersion.HTTP_1_1, HttpResponseStatus.OK,
                                        Unpooled.wrappedBuffer(bytes));
                                resp.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
                                resp.headers().set(HttpHeaderNames.CONTENT_LENGTH, bytes.length);

                                boolean keepAlive = HttpUtil.isKeepAlive(req);
                                HttpUtil.setKeepAlive(resp, keepAlive);
                                ChannelFuture f = ctx.writeAndFlush(resp);
                                if (!keepAlive) {
                                    f.addListener(ChannelFutureListener.CLOSE);
                                }
                            }
                        });
                    }
                });

        backendChannel = backendBoot.bind(0).sync().channel();
        backendPort = ((InetSocketAddress) backendChannel.localAddress()).getPort();

        // 2. Start Reverse Proxy Server targeting backend
        ProxyConfig config = ProxyConfig.builder()
                .serverPort(0) // Bind to random port
                .upstreamHost("127.0.0.1")
                .upstreamPort(backendPort)
                .addRule(RateLimitRule.builder()
                        .routePrefix("/api/rate-limited")
                        .algorithm(RateLimitAlgorithm.TOKEN_BUCKET)
                        .capacity(2)
                        .refillRatePerSecond(0) // No refill for deterministic test
                        .strategy(KeyExtractorStrategy.IP)
                        .build())
                .addRule(RateLimitRule.builder()
                        .routePrefix("/api/open")
                        .algorithm(RateLimitAlgorithm.TOKEN_BUCKET)
                        .capacity(1000)
                        .refillRatePerSecond(500)
                        .strategy(KeyExtractorStrategy.IP)
                        .build())
                .build();

        proxyServer = new ReverseProxyServer(config);
        proxyServer.start();
        proxyPort = proxyServer.getBoundPort();

        httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();
    }

    @AfterAll
    static void tearDown() {
        if (proxyServer != null) {
            proxyServer.close();
        }
        if (backendChannel != null) {
            backendChannel.close().syncUninterruptibly();
        }
        if (backendBoss != null) {
            backendBoss.shutdownGracefully();
        }
        if (backendWorker != null) {
            backendWorker.shutdownGracefully();
        }
    }

    @Test
    @DisplayName("Should successfully proxy request and inject rate limit headers on 200 OK")
    void testSuccessfulProxyForwarding() throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + proxyPort + "/api/open/resource"))
                .GET()
                .build();

        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.body()).contains("backend");
        assertThat(resp.headers().firstValue("X-RateLimit-Limit")).isPresent();
        assertThat(resp.headers().firstValue("X-RateLimit-Remaining")).isPresent();
        assertThat(resp.headers().firstValue("X-RateLimit-Reset")).isPresent();
    }

    @Test
    @DisplayName("Should return HTTP 429 Too Many Requests once rate limit is exceeded")
    void testRateLimitingRejection() throws Exception {
        URI target = URI.create("http://127.0.0.1:" + proxyPort + "/api/rate-limited/test");

        // Request 1: Admitted (Capacity 2 -> 1 remaining)
        HttpRequest req1 = HttpRequest.newBuilder().uri(target).GET().build();
        HttpResponse<String> resp1 = httpClient.send(req1, HttpResponse.BodyHandlers.ofString());
        assertThat(resp1.statusCode()).isEqualTo(200);
        assertThat(resp1.headers().firstValue("X-RateLimit-Remaining")).contains("1");

        // Request 2: Admitted (Capacity 1 -> 0 remaining)
        HttpRequest req2 = HttpRequest.newBuilder().uri(target).GET().build();
        HttpResponse<String> resp2 = httpClient.send(req2, HttpResponse.BodyHandlers.ofString());
        assertThat(resp2.statusCode()).isEqualTo(200);
        assertThat(resp2.headers().firstValue("X-RateLimit-Remaining")).contains("0");

        // Request 3: Rejected with HTTP 429
        HttpRequest req3 = HttpRequest.newBuilder().uri(target).GET().build();
        HttpResponse<String> resp3 = httpClient.send(req3, HttpResponse.BodyHandlers.ofString());
        assertThat(resp3.statusCode()).isEqualTo(429);
        assertThat(resp3.headers().firstValue("Retry-After")).isPresent();
        assertThat(resp3.headers().firstValue("X-RateLimit-Remaining")).contains("0");
        assertThat(resp3.body()).contains("Too Many Requests");
    }

    @Test
    @DisplayName("Should serve /_admin/health and /_admin/metrics without proxying to backend")
    void testAdminEndpoints() throws Exception {
        // Health check
        HttpRequest healthReq = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + proxyPort + "/_admin/health"))
                .GET()
                .build();
        HttpResponse<String> healthResp = httpClient.send(healthReq, HttpResponse.BodyHandlers.ofString());
        assertThat(healthResp.statusCode()).isEqualTo(200);
        assertThat(healthResp.body()).contains("\"status\":\"UP\"");

        // Metrics check
        HttpRequest metricsReq = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + proxyPort + "/_admin/metrics"))
                .GET()
                .build();
        HttpResponse<String> metricsResp = httpClient.send(metricsReq, HttpResponse.BodyHandlers.ofString());
        assertThat(metricsResp.statusCode()).isEqualTo(200);
        assertThat(metricsResp.body()).contains("totalRequests");
        assertThat(metricsResp.body()).contains("admittedRequests");
        assertThat(metricsResp.body()).contains("latencyMicros");
    }
}
