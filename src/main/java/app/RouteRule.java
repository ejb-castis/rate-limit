package app;

import java.util.regex.Pattern;

public final class RouteRule {
    public enum Kind {
        ALLOW, LIMIT
    }

    public final String id;
    public final Kind kind;
    public final String method;
    public final Pattern pathRegex;

    public final Double costOrNull;
    public final Double capacityOverride;
    public final Double refillPerSecOverride;

    // ✅ 추가: 최소 간격 (밀리초), null이면 비활성
    public final Long minGapMs;

    public RouteRule(String id,
            Kind kind,
            String method,
            Pattern pathRegex,
            Double costOrNull,
            Double capacityOverride,
            Double refillPerSecOverride,
            Long minGapMs) {
        this.id = id;
        this.kind = kind;
        this.method = method;
        this.pathRegex = pathRegex;
        this.costOrNull = costOrNull;
        this.capacityOverride = capacityOverride;
        this.refillPerSecOverride = refillPerSecOverride;
        this.minGapMs = minGapMs;
    }

    public boolean matches(String httpMethod, String path) {
        if (!"ANY".equalsIgnoreCase(this.method) &&
                !this.method.equalsIgnoreCase(httpMethod))
            return false;
        return pathRegex.matcher(path).matches();
    }
}