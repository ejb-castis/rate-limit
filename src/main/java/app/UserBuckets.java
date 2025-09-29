package app;

import java.util.concurrent.ConcurrentHashMap;

public final class UserBuckets {
    private final ConcurrentHashMap<String, TokenBucket> buckets = new ConcurrentHashMap<>();
    private final double defaultCapacity;
    private final double defaultRefillPerSec;
    private final long idleEvictMs;

    public UserBuckets(double capacity, double refillPerSec, long idleEvictMs) {
        this.defaultCapacity = capacity;
        this.defaultRefillPerSec = refillPerSec;
        this.idleEvictMs = idleEvictMs;
        startCleaner();
    }

    /** 전역 설정으로 버킷을 가져옴 (기존 호환) */
    public TokenBucket get(String key) {
        return getWithOverrides(key, defaultCapacity, defaultRefillPerSec);
    }

    /** route별 capacity/refill을 반영하여 버킷을 가져옴 */
    public TokenBucket getWithOverrides(String key, double capacity, double refillPerSec) {
        long now = System.nanoTime();
        return buckets.computeIfAbsent(key, k -> new TokenBucket(capacity, refillPerSec, now));
    }

    private void startCleaner() {
        Thread t = new Thread(() -> {
            try {
                while (true) {
                    Thread.sleep(60_000);
                    long cutoff = System.currentTimeMillis() - idleEvictMs;
                    buckets.entrySet().removeIf(e -> e.getValue().lastAccessMillis() < cutoff);
                }
            } catch (InterruptedException ignored) {
            }
        }, "bucket-cleaner");
        t.setDaemon(true);
        t.start();
    }
}