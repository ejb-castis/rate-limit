package app;

public final class Main {
    public static void main(String[] args) throws Exception {
        Config cfg = Config.load("config.properties");
        RouteRules rules = RouteRules.load("routes.conf");
        UserBuckets store = new UserBuckets(cfg.capacity, cfg.refillPerSec, cfg.idleEvictMs);
        NettyServer server = new NettyServer(cfg, rules, store);
        server.start();
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop, "shutdown"));
    }
}