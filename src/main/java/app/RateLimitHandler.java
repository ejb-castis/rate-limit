package app;

import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.handler.codec.http.*;

import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

import org.slf4j.LoggerFactory;
import org.slf4j.Logger;
import org.slf4j.MDC;

import static io.netty.handler.codec.http.HttpResponseStatus.*;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

import io.prometheus.client.Counter;
import io.prometheus.client.Histogram;
import io.prometheus.client.hotspot.DefaultExports;
import io.prometheus.client.CollectorRegistry;
import io.prometheus.client.exporter.common.TextFormat;

@ChannelHandler.Sharable
public final class RateLimitHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
    private static final Logger log = LoggerFactory.getLogger(RateLimitHandler.class);
    private static final Logger access = LoggerFactory.getLogger("access");

    // === Prometheus metrics ===
    private static final Counter REQUESTS = Counter.build()
            .name("rl_requests_total").help("Total requests").labelNames("method", "rule", "outcome", "status_class")
            .register();

    private static final Counter RATE_LIMIT_429 = Counter.build()
            .name("rl_rate_limit_429_total").help("429 via token-bucket").labelNames("rule").register();

    private static final Counter MIN_GAP_429 = Counter.build()
            .name("rl_min_gap_429_total").help("429 via min-gap").labelNames("rule").register();

    private static final Histogram LATENCY = Histogram.build()
            .name("rl_request_latency_seconds").help("Request latency")
            .labelNames("method", "rule", "outcome")
            .buckets(0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1.0, 2.5, 5.0).register();

    static {
        DefaultExports.initialize();
    }

    private static String statusClass(int s) {
        if (s >= 500)
            return "5xx";
        if (s >= 400)
            return "4xx";
        if (s >= 300)
            return "3xx";
        if (s >= 200)
            return "2xx";
        return "1xx";
    }

    private final Config cfg;
    private final RouteRules rules;
    private final UserBuckets store;
    private final MinGapGate minGapGate;

    public RateLimitHandler(Config cfg, RouteRules rules, UserBuckets store, MinGapGate minGapGate) {
        this.cfg = cfg;
        this.rules = rules;
        this.store = store;
        this.minGapGate = minGapGate;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest req) {
        final long startNs = System.nanoTime();
        String ruleForMetrics = "default";
        String outcomeForMetrics = "pass"; // pass|min_gap|rate_limit|error
        int statusForMetrics = 200;

        final String method = req.method().name();
        final String path = req.uri().split("\\?", 2)[0];

        if ("/metrics".equals(path)) {
            try (java.io.StringWriter sw = new java.io.StringWriter()) {
                TextFormat.write004(sw, CollectorRegistry.defaultRegistry.metricFamilySamples());
                byte[] bytes = sw.toString().getBytes(StandardCharsets.UTF_8);
                FullHttpResponse resp = new DefaultFullHttpResponse(HTTP_1_1, OK, Unpooled.wrappedBuffer(bytes));
                HttpHeaders h = resp.headers();
                h.set(HttpHeaderNames.CONTENT_TYPE, TextFormat.CONTENT_TYPE_004);
                h.setInt(HttpHeaderNames.CONTENT_LENGTH, bytes.length);
                ctx.writeAndFlush(resp).addListener(ChannelFutureListener.CLOSE);
            } catch (Exception e) {
                log.error("Error writing /metrics", e);
                writeJson(ctx, INTERNAL_SERVER_ERROR, "{\"error\":\"internal_error\"}", null);
            }
            return;
        }
        Optional<RouteRule> matched = rules.firstMatch(method, path);
        if (matched.isPresent()) {
            RouteRule rule = matched.get();
            ruleForMetrics = rule.id;

            String userKey = resolveUserKey(ctx, req);
            MDC.put("method", method);
            MDC.put("path", path);
            MDC.put("rule", rule.id);
            MDC.put("user", userKey);

            if (rule.kind == RouteRule.Kind.ALLOW) {
                writeJson(ctx, OK, "{\"ok\":true,\"message\":\"whitelisted\"}", null);
                MDC.clear();
                return;
            }

            double cost = (rule.costOrNull != null) ? rule.costOrNull : cfg.defaultRequestCost;
            double capacity = (rule.capacityOverride != null) ? rule.capacityOverride : cfg.capacity;
            double refill = (rule.refillPerSecOverride != null) ? rule.refillPerSecOverride : cfg.refillPerSec;

            // ✅ 1) 최소 간격 검사: 통과하지 못하면 토큰은 소모하지 않음
            Long effMinGap = (rule.minGapMs != null && rule.minGapMs > 0)
                    ? rule.minGapMs
                    : (cfg.requestMinGapMs != null && cfg.requestMinGapMs > 0)
                            ? cfg.requestMinGapMs
                            : null;

            if (effMinGap != null) {
                long now = System.currentTimeMillis();
                long remainMs = minGapGate.check(bucketKey(userKey, rule.id), now, effMinGap);
                if (remainMs > 0) {
                    outcomeForMetrics = "min_gap";
                    statusForMetrics = 429;
                    MIN_GAP_429.labels(rule.id).inc();
                    REQUESTS.labels(method, ruleForMetrics, outcomeForMetrics, statusClass(statusForMetrics)).inc();
                    LATENCY.labels(method, ruleForMetrics, outcomeForMetrics)
                            .observe((System.nanoTime() - startNs) / 1_000_000_000.0);

                    logAccess(ctx, method, path, userKey, rule.id, outcomeForMetrics, statusForMetrics, cost, -1.0,
                            capacity,
                            refill,
                            (System.nanoTime() - startNs) / 1_000_000L, req);

                    // 429 + Retry-After (초) + 남은 ms 헤더
                    Map<String, String> extra = new HashMap<>();
                    long retrySec = (long) Math.ceil(remainMs / 1000.0);
                    extra.put("Retry-After", String.valueOf(retrySec));
                    extra.put("X-MinGap-Remaining-Millis", String.valueOf(remainMs));
                    writeJson(ctx, TOO_MANY_REQUESTS,
                            "{\"error\":\"min_gap\",\"user\":\"" + esc(userKey) + "\"}", extra);

                    log.info("MIN-GAP block user={} rule={} path={} remainMs={}",
                            userKey, rule.id, path, remainMs);
                    MDC.clear();
                    return;
                }
            }

            // ✅ 2) 토큰 버킷 검사/소모
            TokenBucket bucket = store.getWithOverrides(bucketKey(userKey, rule.id), capacity, refill);
            double remaining = bucket.tryConsume(cost, System.nanoTime());
            if (remaining < 0) {
                outcomeForMetrics = "rate_limit";
                statusForMetrics = 429;
                RATE_LIMIT_429.labels(rule.id).inc();
                REQUESTS.labels(method, ruleForMetrics, outcomeForMetrics, statusClass(statusForMetrics)).inc();
                LATENCY.labels(method, ruleForMetrics, outcomeForMetrics)
                        .observe((System.nanoTime() - startNs) / 1_000_000_000.0);
                logAccess(ctx, method, path, userKey, rule.id, outcomeForMetrics, statusForMetrics, cost, -1.0,
                        capacity, refill,
                        (System.nanoTime() - startNs) / 1_000_000L, req);

                long wait = (long) Math.ceil(cost / bucket.getRefillPerSec());
                Map<String, String> extra = new HashMap<>();
                extra.put("Retry-After", String.valueOf(wait));
                writeJson(ctx, TOO_MANY_REQUESTS,
                        "{\"error\":\"rate_limit_exceeded\",\"user\":\"" + esc(userKey) + "\"}", extra);

                log.info("RATELIMIT block user={} rule={} path={} cost={} wait={}",
                        userKey, rule.id, path, cost, wait);
                MDC.clear();
                return;
            }

            String json = "{"
                    + "\"ok\":true,"
                    + "\"user\":\"" + esc(userKey) + "\","
                    + "\"method\":\"" + esc(method) + "\","
                    + "\"path\":\"" + esc(path) + "\","
                    + "\"ruleId\":\"" + rule.id + "\","
                    + "\"capacity\":" + capacity + ","
                    + "\"refillPerSec\":" + refill + ","
                    + "\"cost\":" + cost + ","
                    + "\"remaining\":" + String.format(java.util.Locale.US, "%.2f", remaining)
                    + "}";
            outcomeForMetrics = "pass";
            statusForMetrics = 200;
            REQUESTS.labels(method, ruleForMetrics, outcomeForMetrics, statusClass(statusForMetrics)).inc();
            LATENCY.labels(method, ruleForMetrics, outcomeForMetrics)
                    .observe((System.nanoTime() - startNs) / 1_000_000_000.0);
            logAccess(ctx, method, path, userKey, rule.id, outcomeForMetrics, statusForMetrics, cost, remaining,
                    capacity, refill,
                    (System.nanoTime() - startNs) / 1_000_000L, req);

            writeJson(ctx, OK, json, null);
            if (log.isDebugEnabled()) {
                log.debug("PASS user={} rule={} path={} remaining={}",
                        userKey, rule.id, path, remaining);
            }
            MDC.clear();
            return;
        }

        // 규칙 없음: 전역 버킷만 적용(간격 제한(mingap) 없음)
        String userKey = resolveUserKey(ctx, req);
        outcomeForMetrics = "pass";
        statusForMetrics = 200;
        REQUESTS.labels(method, ruleForMetrics, outcomeForMetrics, statusClass(statusForMetrics)).inc();
        LATENCY.labels(method, ruleForMetrics, outcomeForMetrics)
                .observe((System.nanoTime() - startNs) / 1_000_000_000.0);

        TokenBucket bucket = store.getWithOverrides(bucketKey(userKey, "default"), cfg.capacity, cfg.refillPerSec);
        double remaining = bucket.tryConsume(cfg.defaultRequestCost, System.nanoTime());
        if (remaining < 0) {
            outcomeForMetrics = "rate_limit";
            statusForMetrics = 429;
            RATE_LIMIT_429.labels("default").inc();
            REQUESTS.labels(method, ruleForMetrics, outcomeForMetrics, statusClass(statusForMetrics)).inc();
            LATENCY.labels(method, ruleForMetrics, outcomeForMetrics)
                    .observe((System.nanoTime() - startNs) / 1_000_000_000.0);
            logAccess(ctx, method, path, userKey, "default", outcomeForMetrics, statusForMetrics,
                    cfg.defaultRequestCost, -1.0,
                    cfg.capacity, cfg.refillPerSec, (System.nanoTime() - startNs) / 1_000_000L, req);

            long wait = (long) Math.ceil(cfg.defaultRequestCost / bucket.getRefillPerSec());
            Map<String, String> extra = new HashMap<>();
            extra.put("Retry-After", String.valueOf(wait));
            writeJson(ctx, TOO_MANY_REQUESTS,
                    "{\"error\":\"rate_limit_exceeded\",\"user\":\"" + esc(userKey) + "\"}", extra);

            log.info("RATELIMIT block user={} rule=default path={} cost={} wait={}",
                    userKey, path, cfg.defaultRequestCost, wait);
            MDC.clear();
            return;
        }
        String json = "{"
                + "\"ok\":true,"
                + "\"user\":\"" + esc(userKey) + "\","
                + "\"method\":\"" + esc(method) + "\","
                + "\"path\":\"" + esc(path) + "\","
                + "\"ruleId\":\"default\","
                + "\"capacity\":" + cfg.capacity + ","
                + "\"refillPerSec\":" + cfg.refillPerSec + ","
                + "\"cost\":" + cfg.defaultRequestCost + ","
                + "\"remaining\":" + String.format(java.util.Locale.US, "%.2f", remaining)
                + "}";
        outcomeForMetrics = "pass";
        statusForMetrics = 200;
        REQUESTS.labels(method, ruleForMetrics, outcomeForMetrics, statusClass(statusForMetrics)).inc();
        LATENCY.labels(method, ruleForMetrics, outcomeForMetrics)
                .observe((System.nanoTime() - startNs) / 1_000_000_000.0);
        logAccess(ctx, method, path, userKey, "default", outcomeForMetrics, statusForMetrics, cfg.defaultRequestCost,
                remaining,
                cfg.capacity, cfg.refillPerSec, (System.nanoTime() - startNs) / 1_000_000L, req);

        writeJson(ctx, OK, json, null);
        if (log.isDebugEnabled()) {
            log.debug("PASS user={} rule=default path={} remaining={}",
                    userKey, path, remaining);
        }
        MDC.clear();
    }

    private static String bucketKey(String userKey, String ruleId) {
        return userKey + "|" + ruleId;
    }

    // resolveUserKey / writeJson 등 기존 메서드는 그대로
    private String resolveUserKey(ChannelHandlerContext ctx, FullHttpRequest req) {
        for (String token : cfg.identityOrder) {
            String t = token.trim();
            if (t.startsWith("header:")) {
                String name = t.substring("header:".length());
                String v = req.headers().get(name);
                if (v != null && !v.trim().isEmpty())
                    return v.trim();
            } else if (t.startsWith("cookie:")) {
                String name = t.substring("cookie:".length());
                String cookie = req.headers().get("Cookie");
                if (cookie != null) {
                    for (String pair : cookie.split(";")) {
                        String[] kv = pair.trim().split("=", 2);
                        if (kv.length == 2 && kv[0].trim().equals(name)) {
                            String v = kv[1].trim();
                            if (!v.isEmpty())
                                return v;
                        }
                    }
                }
            } else if (t.startsWith("query:")) {
                String name = t.substring("query:".length());
                String q = req.uri().contains("?") ? req.uri().substring(req.uri().indexOf('?') + 1) : null;
                String v = queryParam(q, name);
                if (v != null && !v.trim().isEmpty())
                    return v.trim();
            }
        }

        String xff = req.headers().get("X-Forwarded-For");
        if (xff != null && !xff.isEmpty())
            return xff.split(",")[0].trim();

        // 실제 remote IP
        InetSocketAddress remote = (InetSocketAddress) ctx.channel().remoteAddress();
        if (remote != null)
            return remote.getAddress().getHostAddress();

        return "unknown";
    }

    private static String queryParam(String raw, String key) {
        if (raw == null)
            return null;
        for (String p : raw.split("&")) {
            String[] kv = p.split("=", 2);
            if (kv.length == 2) {
                try {
                    String k = URLDecoder.decode(kv[0], "UTF-8");
                    if (key.equals(k))
                        return URLDecoder.decode(kv[1], "UTF-8");
                } catch (Exception ignored) {
                }
            }
        }
        return null;
    }

    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private void writeJson(ChannelHandlerContext ctx, HttpResponseStatus status, String body,
            Map<String, String> extraHeaders) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        FullHttpResponse resp = new DefaultFullHttpResponse(HTTP_1_1, status, Unpooled.wrappedBuffer(bytes));
        HttpHeaders h = resp.headers();
        h.set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=utf-8");
        h.setInt(HttpHeaderNames.CONTENT_LENGTH, bytes.length);
        if (extraHeaders != null) {
            extraHeaders.forEach(h::set);
        }
        ctx.writeAndFlush(resp).addListener(ChannelFutureListener.CLOSE);
    }

    private void logAccess(ChannelHandlerContext ctx, String method, String path, String user, String ruleId,
            String outcome, int status,
            double cost, double remaining, double capacity, double refillPerSec,
            long latencyMs, FullHttpRequest req) {
        String clientIp = null;
        String xff = req.headers().get("X-Forwarded-For");
        if (xff != null && !xff.isEmpty()) {
            clientIp = xff.split(",")[0].trim();
        }
        if (clientIp == null || clientIp.isEmpty()) {
            InetSocketAddress remote = (InetSocketAddress) ctx.channel().remoteAddress();
            clientIp = remote.getAddress().getHostAddress();
        }
        String ua = Optional.ofNullable(req.headers().get("User-Agent")).orElse("");
        String origin = Optional.ofNullable(req.headers().get("Origin")).orElse("");
        String referer = Optional.ofNullable(req.headers().get("Referer")).orElse("");

        access.info(
                "method={} path={} user={} rule={} outcome={} status={} latency_ms={} cost={} remaining={} capacity={} "
                        + "refill_per_sec={} ip={} ua=\"{}\" origin=\"{}\" referer=\"{}\"",
                method, path, user, ruleId, outcome, status, latencyMs,
                String.format(java.util.Locale.US, "%.2f", cost),
                String.format(java.util.Locale.US, "%.2f", remaining),
                String.format(java.util.Locale.US, "%.2f", capacity),
                String.format(java.util.Locale.US, "%.2f", refillPerSec),
                clientIp, ua, origin, referer);
    }
}