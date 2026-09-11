package com.kanak.ratelimiter;

import com.kanak.ratelimiter.benchmark.BenchmarkResult;
import com.kanak.ratelimiter.benchmark.LoadHarness;
import com.kanak.ratelimiter.config.ProxyConfig;
import com.kanak.ratelimiter.config.RateLimitAlgorithm;
import com.kanak.ratelimiter.config.RateLimitRule;
import com.kanak.ratelimiter.core.PartitionedRateLimiter;
import com.kanak.ratelimiter.extractor.KeyExtractorStrategy;
import com.kanak.ratelimiter.proxy.ReverseProxyServer;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.*;

import java.net.URI;
import java.nio.charset.StandardCharsets;

/**
 * CLI Entrypoint for the Reverse Proxy Rate Limiter.
 */
public final class Main {

    public static void main(String[] args) throws Exception {
        if (args.length == 0 || args[0].equals("--help") || args[0].equals("-h")) {
            printUsage();
            return;
        }

        String command = args[0];
        switch (command) {
            case "server" -> startServer(args);
            case "upstream" -> startUpstreamServer(args);
            case "benchmark" -> runBenchmark(args);
            case "bench-memory" -> runInMemoryBenchmark(args);
            default -> {
                System.err.println("Unknown command: " + command);
                printUsage();
                System.exit(1);
            }
        }
    }

    private static void startServer(String[] args) throws Exception {
        int port = 8080;
        String upstreamHost = "127.0.0.1";
        int upstreamPort = 8081;
        long capacity = 1000;
        long refillRate = 500;
        RateLimitAlgorithm algo = RateLimitAlgorithm.TOKEN_BUCKET;

        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--port", "-p" -> port = Integer.parseInt(args[++i]);
                case "--upstream-host" -> upstreamHost = args[++i];
                case "--upstream-port" -> upstreamPort = Integer.parseInt(args[++i]);
                case "--capacity", "-c" -> capacity = Long.parseLong(args[++i]);
                case "--refill", "-r" -> refillRate = Long.parseLong(args[++i]);
                case "--algorithm", "-a" -> algo = RateLimitAlgorithm.valueOf(args[++i].toUpperCase());
            }
        }

        ProxyConfig config = ProxyConfig.builder()
                .serverPort(port)
                .upstreamHost(upstreamHost)
                .upstreamPort(upstreamPort)
                .addRule(RateLimitRule.builder()
                        .routePrefix("/")
                        .algorithm(algo)
                        .capacity(capacity)
                        .refillRatePerSecond(refillRate)
                        .strategy(KeyExtractorStrategy.IP)
                        .build())
                .build();

        ReverseProxyServer server = new ReverseProxyServer(config);
        server.start();

        System.out.printf("""
                ========================================================================
                 Reverse Proxy Rate Limiter started on port %d
                 Forwarding to Upstream: %s:%d
                 Rate Limiter Algorithm: %s (Capacity: %,d, Refill: %,d/s)
                 Admin Endpoints:
                   - Health:  http://localhost:%d/_admin/health
                   - Metrics: http://localhost:%d/_admin/metrics
                ========================================================================
                Press Ctrl+C to terminate.
                %n""", port, upstreamHost, upstreamPort, algo, capacity, refillRate, port, port);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Shutting down reverse proxy server...");
            server.close();
        }));

        Thread.currentThread().join();
    }

    private static void startUpstreamServer(String[] args) throws Exception {
        int port = 8081;
        for (int i = 1; i < args.length; i++) {
            if (args[i].equals("--port") || args[i].equals("-p")) {
                port = Integer.parseInt(args[++i]);
            }
        }

        EventLoopGroup boss = new NioEventLoopGroup(1);
        EventLoopGroup worker = new NioEventLoopGroup();

        ServerBootstrap b = new ServerBootstrap();
        b.group(boss, worker)
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
                                String json = "{\"status\":200,\"message\":\"Upstream backend response OK\",\"path\":\""
                                        + req.uri() + "\"}";
                                byte[] bytes = json.getBytes(StandardCharsets.UTF_8);

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

        Channel channel = b.bind(port).sync().channel();
        System.out.println("Mock upstream HTTP server running on port " + port);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            boss.shutdownGracefully();
            worker.shutdownGracefully();
        }));

        channel.closeFuture().sync();
    }

    private static void runBenchmark(String[] args) throws Exception {
        String target = "http://localhost:8080/api/test";
        int requests = 10_000;
        int threads = 16;

        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--target", "-t" -> target = args[++i];
                case "--requests", "-n" -> requests = Integer.parseInt(args[++i]);
                case "--threads", "-c" -> threads = Integer.parseInt(args[++i]);
            }
        }

        System.out.printf("Starting HTTP Benchmark: %s (Requests: %,d, Threads: %d)...%n",
                target, requests, threads);

        BenchmarkResult result = LoadHarness.runHttpBenchmark(URI.create(target), requests, threads);
        System.out.println(result.formatReport());
    }

    private static void runInMemoryBenchmark(String[] args) {
        int requests = 1_000_000;
        int threads = 16;
        int keys = 1000;
        long capacity = 500_000;
        long refill = 1_000_000;

        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--requests", "-n" -> requests = Integer.parseInt(args[++i]);
                case "--threads", "-c" -> threads = Integer.parseInt(args[++i]);
                case "--keys", "-k" -> keys = Integer.parseInt(args[++i]);
            }
        }

        System.out.printf("Starting In-Memory Microbenchmark (Requests: %,d, Threads: %d, Keys: %,d)...%n",
                requests, threads, keys);

        try (PartitionedRateLimiter limiter = new PartitionedRateLimiter()) {
            RateLimitRule rule = RateLimitRule.builder()
                    .routePrefix("/")
                    .algorithm(RateLimitAlgorithm.TOKEN_BUCKET)
                    .capacity(capacity)
                    .refillRatePerSecond(refill)
                    .build();

            BenchmarkResult result = LoadHarness.runInMemoryBenchmark(limiter, rule, requests, threads, keys);
            System.out.println(result.formatReport());
        }
    }

    private static void printUsage() {
        System.out.println("""
                Usage: java -jar reverse-proxy-rate-limiter.jar <command> [options]

                Commands:
                  server           Start high-throughput reverse proxy with rate limiting
                    --port, -p           Server listening port (default: 8080)
                    --upstream-host      Upstream backend host (default: 127.0.0.1)
                    --upstream-port      Upstream backend port (default: 8081)
                    --capacity, -c       Token bucket burst capacity (default: 1000)
                    --refill, -r         Refill rate per second (default: 500)
                    --algorithm, -a      Algorithm: TOKEN_BUCKET or SLIDING_WINDOW (default: TOKEN_BUCKET)

                  upstream         Start embedded mock upstream echo server
                    --port, -p           Listening port (default: 8081)

                  benchmark        Run HTTP load benchmark against running proxy
                    --target, -t         Target URL (default: http://localhost:8080/api/test)
                    --requests, -n       Total requests (default: 10000)
                    --threads, -c        Worker threads (default: 16)

                  bench-memory     Run core in-memory rate limiter microbenchmark
                    --requests, -n       Total requests (default: 1000000)
                    --threads, -c        Worker threads (default: 16)
                    --keys, -k           Distinct client keys (default: 1000)
                """);
    }
}
