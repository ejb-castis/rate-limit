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
    private static final Logger accessDecide = LoggerFactory.getLogger("access_decide");

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

    // === Decide-specific Prometheus metrics (with service/server labels) ===
    private static final Counter DECIDE_REQUESTS = Counter.build()
            .name("rl_decide_requests_total")
            .help("Total /decide requests")
            .labelNames("svc", "server", "rule", "outcome", "status_class")
            .register();

    private static final Counter DECIDE_RATE_LIMIT_429 = Counter.build()
            .name("rl_decide_rate_limit_429_total")
            .help("429 via token-bucket on /decide")
            .labelNames("svc", "rule")
            .register();

    private static final Counter DECIDE_MIN_GAP_429 = Counter.build()
            .name("rl_decide_min_gap_429_total")
            .help("429 via min-gap on /decide")
            .labelNames("svc", "rule")
            .register();

    private static final Histogram DECIDE_LATENCY = Histogram.build()
            .name("rl_decide_latency_seconds")
            .help("/decide request latency")
            .labelNames("svc", "server", "rule", "outcome")
            .buckets(0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1.0, 2.5, 5.0)
            .register();

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
    private final AuthVerifier auth;

    public RateLimitHandler(Config cfg, RouteRules rules, UserBuckets store, MinGapGate minGapGate) {
        this.cfg = cfg;
        this.rules = rules;
        this.store = store;
        this.minGapGate = minGapGate;
        this.auth = new AuthVerifier(cfg.hmacSecret, cfg.hmacClockSkewSec, cfg.nonceTtlSec);
    }

    // === Refactor helpers ===
    private enum Outcome {
        PASS, MIN_GAP, RATE_LIMIT, ERROR
    }

    private static final class EvalResult {
        String method;
        String path;
        String user;
        String ruleId;
        double capacity;
        double refill;
        double cost;
        double remaining; // -1 when blocked
        Outcome outcome; // PASS / MIN_GAP / RATE_LIMIT / ERROR
        int status; // 2xx/4xx etc
        Long retryAfterSec; // for 429
        Long minGapRemainMs; // for 429 min-gap
        long latencyMs; // computed on finalize
    }

    /** Compute effective rule parameters and evaluate min-gap + token bucket. */
    private EvalResult evaluate(String method, String realPath, String userKey, Double costOverride,
            RouteRule rule, long startNs) {
        EvalResult r = new EvalResult();
        r.method = method;
        r.path = realPath;
        r.user = userKey;
        r.ruleId = (rule != null ? rule.id : "default");

        r.capacity = (rule != null && rule.capacityOverride != null) ? rule.capacityOverride : cfg.capacity;
        r.refill = (rule != null && rule.refillPerSecOverride != null) ? rule.refillPerSecOverride : cfg.refillPerSec;
        r.cost = (costOverride != null) ? costOverride
                : (rule != null && rule.costOrNull != null ? rule.costOrNull : cfg.defaultRequestCost);

        // 1) min-gap (route > global)
        Long effMinGap = null;
        if (rule != null && rule.minGapMs != null && rule.minGapMs > 0)
            effMinGap = rule.minGapMs;
        else if (cfg.requestMinGapMs != null && cfg.requestMinGapMs > 0)
            effMinGap = cfg.requestMinGapMs;

        if (effMinGap != null) {
            long now = System.currentTimeMillis();
            long remainMs = minGapGate.check(bucketKey(userKey, r.ruleId), now, effMinGap);
            if (remainMs > 0) {
                r.outcome = Outcome.MIN_GAP;
                r.status = 429;
                r.minGapRemainMs = remainMs;
                r.retryAfterSec = (long) Math.ceil(remainMs / 1000.0);
                r.remaining = -1.0;
                r.latencyMs = (System.nanoTime() - startNs) / 1_000_000L;
                return r;
            }
        }

        // 2) token bucket
        TokenBucket bucket = store.getWithOverrides(bucketKey(userKey, r.ruleId), r.capacity, r.refill);
        double remaining = bucket.tryConsume(r.cost, System.nanoTime());
        if (remaining < 0) {
            r.outcome = Outcome.RATE_LIMIT;
            r.status = 429;
            r.remaining = -1.0;
            r.retryAfterSec = (long) Math.ceil(r.cost / bucket.getRefillPerSec());
            r.latencyMs = (System.nanoTime() - startNs) / 1_000_000L;
            return r;
        }

        // 3) pass
        r.outcome = Outcome.PASS;
        r.status = 200; // for JSON path; decide path will map to 204 later
        r.remaining = remaining;
        r.latencyMs = (System.nanoTime() - startNs) / 1_000_000L;
        return r;
    }

    /** Record Prometheus metrics for the decision. */
    private void recordMetrics(EvalResult r, boolean isDecide) {
        String outcomeLabel;
        int statusForClass;
        switch (r.outcome) {
            case MIN_GAP:
                outcomeLabel = "min_gap";
                statusForClass = 429;
                MIN_GAP_429.labels(r.ruleId).inc();
                break;
            case RATE_LIMIT:
                outcomeLabel = "rate_limit";
                statusForClass = 429;
                RATE_LIMIT_429.labels(r.ruleId).inc();
                break;
            case ERROR:
                outcomeLabel = "error";
                statusForClass = r.status <= 0 ? 500 : r.status;
                break;
            default:
                outcomeLabel = "pass";
                statusForClass = isDecide ? 204 : 200;
        }
        REQUESTS.labels(r.method, r.ruleId, outcomeLabel, statusClass(statusForClass)).inc();
        LATENCY.labels(r.method, r.ruleId, outcomeLabel).observe(r.latencyMs / 1000.0);
    }

    private void recordDecideMetrics(EvalResult r, String svc, String serverKey) {
        String outcomeLabel;
        int statusForClass;
        switch (r.outcome) {
            case MIN_GAP:
                outcomeLabel = "min_gap";
                statusForClass = 429;
                DECIDE_MIN_GAP_429.labels(svc, r.ruleId).inc();
                break;
            case RATE_LIMIT:
                outcomeLabel = "rate_limit";
                statusForClass = 429;
                DECIDE_RATE_LIMIT_429.labels(svc, r.ruleId).inc();
                break;
            case ERROR:
                outcomeLabel = "error";
                statusForClass = r.status <= 0 ? 500 : r.status;
                break;
            default:
                outcomeLabel = "pass";
                statusForClass = 204;
        }
        DECIDE_REQUESTS.labels(svc, serverKey, r.ruleId, outcomeLabel, statusClass(statusForClass)).inc();
        DECIDE_LATENCY.labels(svc, serverKey, r.ruleId, outcomeLabel).observe(r.latencyMs / 1000.0);
    }

    /** Write access log in one place. */
    private void logDecision(ChannelHandlerContext ctx, FullHttpRequest req, EvalResult r,
            boolean isDecide, String svc, String serverKey) {
        String outcomeLabel = (r.outcome == Outcome.MIN_GAP) ? "min_gap"
                : (r.outcome == Outcome.RATE_LIMIT) ? "rate_limit" : (r.outcome == Outcome.ERROR) ? "error" : "pass";
        int statusForLog = (r.outcome == Outcome.PASS ? (r.status == 200 ? 200 : r.status) : r.status);

        String clientIp = null;
        String xff = req.headers().get("X-Forwarded-For");
        if (xff != null && !xff.isEmpty())
            clientIp = xff.split(",")[0].trim();
        if (clientIp == null || clientIp.isEmpty()) {
            InetSocketAddress remote = (InetSocketAddress) ctx.channel().remoteAddress();
            clientIp = remote.getAddress().getHostAddress();
        }
        String ua = Optional.ofNullable(req.headers().get("User-Agent")).orElse("");
        String origin = Optional.ofNullable(req.headers().get("Origin")).orElse("");
        String referer = Optional.ofNullable(req.headers().get("Referer")).orElse("");

        Logger target = isDecide ? accessDecide : access;
        String svcVal = isDecide ? (svc == null ? "-" : svc) : "-";
        String srvVal = isDecide ? (serverKey == null ? "-" : serverKey) : "-";

        target.info(
                "svc={} server={} method={} path={} user={} rule={} outcome={} status={} latency_ms={} cost={} remaining={} capacity={} "
                        + "refill_per_sec={} ip={} ua=\"{}\" origin=\"{}\" referer=\"{}\"",
                svcVal, srvVal,
                r.method, r.path, r.user, r.ruleId, outcomeLabel, statusForLog, r.latencyMs,
                String.format(java.util.Locale.US, "%.2f", r.cost),
                String.format(java.util.Locale.US, "%.2f", r.remaining),
                String.format(java.util.Locale.US, "%.2f", r.capacity),
                String.format(java.util.Locale.US, "%.2f", r.refill),
                clientIp, ua, origin, referer);
    }

    /** Build JSON body for the normal JSON API. */
    private String buildJson(EvalResult r) {
        if (r.outcome == Outcome.PASS) {
            return "{" +
                    "\"ok\":true," +
                    "\"user\":\"" + esc(r.user) + "\"," +
                    "\"method\":\"" + esc(r.method) + "\"," +
                    "\"path\":\"" + esc(r.path) + "\"," +
                    "\"ruleId\":\"" + esc(r.ruleId) + "\"," +
                    "\"capacity\":" + r.capacity + "," +
                    "\"refillPerSec\":" + r.refill + "," +
                    "\"cost\":" + r.cost + "," +
                    "\"remaining\":" + String.format(java.util.Locale.US, "%.2f", r.remaining) +
                    "}";
        }
        String err = (r.outcome == Outcome.MIN_GAP) ? "min_gap"
                : (r.outcome == Outcome.RATE_LIMIT) ? "rate_limit_exceeded" : "error";
        return "{" +
                "\"error\":\"" + err + "\"," +
                "\"user\":\"" + esc(r.user) + "\"" +
                "}";
    }

    /** Respond for /decide (headers only). */
    private void respondDecide(ChannelHandlerContext ctx, EvalResult r) {
        if (r.outcome == Outcome.PASS) {
            DefaultFullHttpResponse ok = new DefaultFullHttpResponse(HTTP_1_1, NO_CONTENT);
            HttpHeaders h = ok.headers();
            h.set("X-Rate-Remaining", String.format(java.util.Locale.US, "%.2f", r.remaining));
            h.set("X-Rate-Capacity", String.format(java.util.Locale.US, "%.2f", r.capacity));
            h.set("X-Rate-Refill-PerSec", String.format(java.util.Locale.US, "%.2f", r.refill));
            h.set("X-Rate-RuleId", r.ruleId);
            h.set("X-Rate-Cost-Used", String.format(java.util.Locale.US, "%.2f", r.cost));
            ctx.writeAndFlush(ok);
            return;
        }
        DefaultFullHttpResponse too = new DefaultFullHttpResponse(HTTP_1_1, TOO_MANY_REQUESTS);
        HttpHeaders h = too.headers();
        if (r.retryAfterSec != null)
            h.set("Retry-After", String.valueOf(r.retryAfterSec));
        if (r.minGapRemainMs != null)
            h.set("X-MinGap-Remaining-Millis", String.valueOf(r.minGapRemainMs));
        h.set("X-Rate-RuleId", r.ruleId);
        ctx.writeAndFlush(too);
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest req) {
        final long startNs = System.nanoTime();

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
        if (path.equals("/decide")) {
            handleDecide(ctx, req);
            return;
        }

        Optional<RouteRule> matched = rules.firstMatch(method, path);
        if (matched.isPresent()) {
            RouteRule rule = matched.get();

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

            EvalResult r = evaluate(method, path, userKey, null, rule, startNs);
            recordMetrics(r, false);
            logDecision(ctx, req, r, false, null, null);

            if (r.outcome == Outcome.MIN_GAP) {
                Map<String, String> extra = new HashMap<>();
                extra.put("Retry-After", String.valueOf(r.retryAfterSec));
                extra.put("X-MinGap-Remaining-Millis", String.valueOf(r.minGapRemainMs));
                writeJson(ctx, TOO_MANY_REQUESTS, buildJson(r), extra);
                MDC.clear();
                return;
            }
            if (r.outcome == Outcome.RATE_LIMIT) {
                Map<String, String> extra = new HashMap<>();
                extra.put("Retry-After", String.valueOf(r.retryAfterSec));
                writeJson(ctx, TOO_MANY_REQUESTS, buildJson(r), extra);
                MDC.clear();
                return;
            }

            writeJson(ctx, OK, buildJson(r), null);
            if (log.isDebugEnabled()) {
                log.debug("PASS user={} rule={} path={} remaining={}", r.user, r.ruleId, r.path, r.remaining);
            }
            MDC.clear();
            return;
        }

        // No rule (default)
        String userKey = resolveUserKey(ctx, req);
        EvalResult r = evaluate(method, path, userKey, null, null, startNs);
        recordMetrics(r, false);
        logDecision(ctx, req, r, false, null, null);

        if (r.outcome == Outcome.RATE_LIMIT) {
            Map<String, String> extra = new HashMap<>();
            extra.put("Retry-After", String.valueOf(r.retryAfterSec));
            writeJson(ctx, TOO_MANY_REQUESTS, buildJson(r), extra);
            return;
        }

        writeJson(ctx, OK, buildJson(r), null);
        if (log.isDebugEnabled()) {
            log.debug("PASS user={} rule=default path={} remaining={}", r.user, r.path, r.remaining);
        }
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

    private void handleDecide(ChannelHandlerContext ctx, FullHttpRequest req) {
        final long startNs = System.nanoTime();

        String full = req.uri();
        String realPath = extractQueryParam(full, "path");
        if (realPath == null)
            realPath = "/";

        String userKey = resolveUserKey(ctx, req);
        String costHdr = req.headers().get("X-Rate-Cost");
        Double costOverride = null;
        if (costHdr != null) {
            try {
                costOverride = Double.parseDouble(costHdr);
            } catch (Exception ignored) {
            }
        }
        String svc = Optional.ofNullable(req.headers().get("X-Service-Id")).orElse("unknown");
        String serverKey = Optional.ofNullable(req.headers().get("X-Service-Key")).orElse("unknown");

        // HMAC (optional)
        if (!auth.verify(req, "GET", realPath, userKey, costHdr)) {
            EvalResult err = new EvalResult();
            err.method = "GET";
            err.path = realPath;
            err.user = userKey;
            err.ruleId = "default";
            err.outcome = Outcome.ERROR;
            err.status = 403;
            err.latencyMs = (System.nanoTime() - startNs) / 1_000_000L;
            recordDecideMetrics(err, svc, serverKey);
            logDecision(ctx, req, err, false, svc, serverKey);
            writeStatus(ctx, FORBIDDEN);
            return;
        }

        Optional<RouteRule> matched = rules.firstMatch("GET", realPath);
        RouteRule rule = matched.orElse(null);

        EvalResult r = evaluate("GET", realPath, userKey, costOverride, rule, startNs);
        recordDecideMetrics(r, svc, serverKey);
        logDecision(ctx, req, r, true, svc, serverKey);
        respondDecide(ctx, r);
    }

    private static String extractQueryParam(String rawUri, String key) {
        String[] parts = rawUri.split("\\?", 2);
        if (parts.length < 2)
            return null;
        String q = parts[1];
        for (String kv : q.split("&")) {
            int i = kv.indexOf('=');
            if (i <= 0)
                continue;
            String k = kv.substring(0, i);
            String v = kv.substring(i + 1);
            if (k.equals(key)) {
                try {
                    return java.net.URLDecoder.decode(v, java.nio.charset.StandardCharsets.UTF_8.name());
                } catch (Exception ignored) {
                }
            }
        }
        return null;
    }

    private static void writeStatus(ChannelHandlerContext ctx, HttpResponseStatus status) {
        DefaultFullHttpResponse r = new DefaultFullHttpResponse(HTTP_1_1, status);
        ctx.writeAndFlush(r);
    }
}