package com.kanak.ratelimiter.proxy;

import com.kanak.ratelimiter.config.ProxyConfig;
import com.kanak.ratelimiter.core.PartitionedRateLimiter;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;

import java.net.InetSocketAddress;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * High-concurrency Netty reverse proxy server with integrated rate limiting defense.
 */
public final class ReverseProxyServer implements AutoCloseable {

    private final ProxyConfig config;
    private final PartitionedRateLimiter rateLimiter;
    private final EventLoopGroup bossGroup;
    private final EventLoopGroup workerGroup;
    private final ScheduledExecutorService cleanupExecutor;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private Channel serverChannel;
    private int boundPort;

    public ReverseProxyServer(ProxyConfig config) {
        this.config = config;
        this.rateLimiter = new PartitionedRateLimiter();
        this.bossGroup = new NioEventLoopGroup(config.getBossThreads());
        this.workerGroup = new NioEventLoopGroup(config.getWorkerThreads());
        this.cleanupExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "rate-limiter-cleanup");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Starts the reverse proxy server and binds to configured port.
     */
    public synchronized void start() throws InterruptedException {
        if (running.compareAndSet(false, true)) {
            UpstreamClientPool upstreamClientPool = new UpstreamClientPool(
                    config.getUpstreamHost(),
                    config.getUpstreamPort(),
                    workerGroup
            );
            AdminHandler adminHandler = new AdminHandler(rateLimiter);

            ServerBootstrap b = new ServerBootstrap();
            b.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .option(ChannelOption.SO_BACKLOG, 16384)
                    .childOption(ChannelOption.SO_KEEPALIVE, true)
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ChannelPipeline p = ch.pipeline();
                            p.addLast(new HttpServerCodec());
                            p.addLast(new HttpObjectAggregator(config.getMaxContentLengthBytes()));
                            p.addLast(new InboundRateLimitHandler(config, rateLimiter, upstreamClientPool, adminHandler));
                        }
                    });

            ChannelFuture future = b.bind(config.getServerPort()).sync();
            this.serverChannel = future.channel();
            this.boundPort = ((InetSocketAddress) serverChannel.localAddress()).getPort();

            // Periodic cleanup of idle rate-limiter keys every 60 seconds
            cleanupExecutor.scheduleAtFixedRate(() -> {
                try {
                    rateLimiter.cleanupIdleKeys(300_000L); // 5 minutes idle
                } catch (Exception ignored) {
                }
            }, 60, 60, TimeUnit.SECONDS);
        }
    }

    public int getBoundPort() {
        return boundPort;
    }

    public PartitionedRateLimiter getRateLimiter() {
        return rateLimiter;
    }

    @Override
    public synchronized void close() {
        if (running.compareAndSet(true, false)) {
            cleanupExecutor.shutdownNow();
            if (serverChannel != null) {
                serverChannel.close().syncUninterruptibly();
            }
            bossGroup.shutdownGracefully(100, 500, TimeUnit.MILLISECONDS);
            workerGroup.shutdownGracefully(100, 500, TimeUnit.MILLISECONDS);
            rateLimiter.close();
        }
    }
}
