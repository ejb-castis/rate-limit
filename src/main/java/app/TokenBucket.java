package app;

public final class TokenBucket {
    private final double capacity;
    private final double refillPerSec;
    private double tokens;
    private long lastNanos;
    private volatile long lastAccessMillis;

    public TokenBucket(double capacity, double refillPerSec, long now) {
        this.capacity = capacity;
        this.refillPerSec = refillPerSec;
        this.tokens = capacity;
        this.lastNanos = now;
        this.lastAccessMillis = System.currentTimeMillis();
    }

    public synchronized double tryConsume(double cost, long nowNanos) {
        refill(nowNanos);
        if (tokens >= cost) {
            tokens -= cost;
            lastAccessMillis = System.currentTimeMillis();
            return tokens;
        }
        return -1.0;
    }

    private void refill(long nowNanos) {
        long elapsed = nowNanos - lastNanos;
        if (elapsed <= 0)
            return;
        double add = (elapsed / 1_000_000_000.0) * refillPerSec;
        tokens = Math.min(capacity, tokens + add);
        lastNanos = nowNanos;
    }

    public long lastAccessMillis() {
        return lastAccessMillis;
    }

    public double getRefillPerSec() {
        return refillPerSec;
    }
}