package app;

import org.slf4j.LoggerFactory;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.*;

import org.slf4j.Logger;

public final class NettyServer {
    private static final Logger log = LoggerFactory.getLogger(NettyServer.class);

    private final Config cfg;
    private final RouteRules rules;
    private final UserBuckets store;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;

    public NettyServer(Config cfg, RouteRules rules, UserBuckets store) {
        this.cfg = cfg;
        this.rules = rules;
        this.store = store;
    }

    public void start() throws InterruptedException {
        bossGroup = new NioEventLoopGroup(cfg.bossThreads <= 0 ? 1 : cfg.bossThreads);
        workerGroup = new NioEventLoopGroup(cfg.workerThreads <= 0 ? 0 : cfg.workerThreads);
        MinGapGate gate = new MinGapGate();
        final RateLimitHandler rateHandler = new RateLimitHandler(cfg, rules, store, gate);

        ServerBootstrap b = new ServerBootstrap();
        b.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<Channel>() {
                    @Override
                    protected void initChannel(Channel ch) {
                        ChannelPipeline p = ch.pipeline();
                        p.addLast(new HttpServerCodec());
                        p.addLast(new HttpObjectAggregator(cfg.maxContentLength));
                        p.addLast(rateHandler);
                    }
                });
        ChannelFuture f = b.bind(cfg.port).sync();
        serverChannel = f.channel();
        log.info("Server listen :{} (cap={}, refill/s={}, defaultCost={}, requestMinGapMs={})",
                cfg.port, cfg.capacity, cfg.refillPerSec, cfg.defaultRequestCost, cfg.requestMinGapMs);
    }

    public void stop() {
        try {
            if (serverChannel != null)
                serverChannel.close().syncUninterruptibly();
        } catch (Exception ignored) {
            log.error("Error closing server channel", ignored);
        }
        if (workerGroup != null)
            workerGroup.shutdownGracefully();
        if (bossGroup != null)
            bossGroup.shutdownGracefully();
        log.info("server stopped");
    }
}