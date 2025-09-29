package app;

import java.io.*;
import java.util.Properties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Config {
    private static final Logger log = LoggerFactory.getLogger(Config.class);

    public final int port;
    public final double capacity;
    public final double refillPerSec;
    public final long idleEvictMs;
    public final double defaultRequestCost;
    public final String[] identityOrder;
    public final int bossThreads;
    public final int workerThreads;
    public final int maxContentLength;
    public final Long requestMinGapMs;

    private Config(int port, double capacity, double refillPerSec, long idleEvictMs,
            double defaultRequestCost, String[] identityOrder,
            int bossThreads, int workerThreads, int maxContentLength, Long requestMinGapMs) {
        this.port = port;
        this.capacity = capacity;
        this.refillPerSec = refillPerSec;
        this.idleEvictMs = idleEvictMs;
        this.defaultRequestCost = defaultRequestCost;
        this.identityOrder = identityOrder;
        this.bossThreads = bossThreads;
        this.workerThreads = workerThreads;
        this.maxContentLength = maxContentLength;
        this.requestMinGapMs = requestMinGapMs;
        log.info(
                "Config: port={}, capacity={}, refill/s={}, idleEvictMs={}, defaultCost={}, " +
                        "idOrder={}, bossThreads={}, workerThreads={}, maxContentLength={}, requestMinGapMs={}",
                port, capacity, refillPerSec, idleEvictMs, defaultRequestCost,
                String.join(",", identityOrder), bossThreads, workerThreads, maxContentLength, requestMinGapMs);
    }

    public static Config load(String path) throws IOException {
        Properties p = new Properties();
        try (InputStream in = new FileInputStream(path)) {
            p.load(in);
        }
        int port = Integer.parseInt(p.getProperty("app.port", "8080"));
        double cap = Double.parseDouble(p.getProperty("bucket.capacity", "150"));
        double rps = Double.parseDouble(p.getProperty("bucket.refill_per_sec", "2.5"));
        long idle = Long.parseLong(p.getProperty("bucket.idle_evict_ms", "600000"));
        String mg = p.getProperty("request.mingap_ms", "").trim();
        double defCost = Double.parseDouble(p.getProperty("request.default_cost", "1"));
        String[] order = p.getProperty("identity.order",
                "header:X-User-Id,cookie:uid,query:user").split("\\s*,\\s*");
        int boss = Integer.parseInt(p.getProperty("server.boss_threads", "1"));
        int worker = Integer.parseInt(p.getProperty("server.worker_threads", "0"));
        int maxLen = Integer.parseInt(p.getProperty("http.max_content_length", "1048576"));

        Long reqMinGap = null;
        if (!mg.isEmpty()) {
            try {
                reqMinGap = Long.parseLong(mg);
                if (reqMinGap < 0) {
                    log.warn("Invalid request.mingap_ms {}, ignored", mg);
                    reqMinGap = null;
                }
            } catch (NumberFormatException e) {
                log.warn("Invalid request.mingap_ms {}, ignored", mg);
            }
        }

        return new Config(port, cap, rps, idle, defCost, order, boss, worker, maxLen, reqMinGap);
    }
}