package app;

import java.io.*;
import java.util.*;
import java.util.regex.*;

import org.slf4j.LoggerFactory;
import org.slf4j.Logger;

public final class RouteRules {
    private static final Logger log = LoggerFactory.getLogger(RouteRules.class);
    private final List<RouteRule> rules;

    private RouteRules(List<RouteRule> rules) {
        this.rules = rules;
    }

    public static RouteRules load(String path) throws IOException {
        File file = new File(path);
        List<RouteRule> list = new ArrayList<>();
        if (!file.exists())
            return new RouteRules(list);

        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            String line;
            int ln = 0;
            int seq = 0;
            while ((line = br.readLine()) != null) {
                ln++;
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#"))
                    continue;

                String[] parts = line.split("\\s+");
                if (parts.length < 3)
                    continue;

                RouteRule.Kind kind;
                if ("allow".equalsIgnoreCase(parts[0]))
                    kind = RouteRule.Kind.ALLOW;
                else if ("limit".equalsIgnoreCase(parts[0]))
                    kind = RouteRule.Kind.LIMIT;
                else
                    continue;

                String method = parts[1].toUpperCase(Locale.ROOT);
                String regex = parts[2];

                Double cost = null, capOverride = null, refillOverride = null;
                Long minGapMs = null;

                for (int i = 3; i < parts.length; i++) {
                    String kv = parts[i];
                    if (kv.startsWith("cost=")) {
                        try {
                            cost = Double.parseDouble(kv.substring(5));
                        } catch (Exception ignored) {
                        }
                    } else if (kv.startsWith("capacity=")) {
                        try {
                            capOverride = Double.parseDouble(kv.substring(9));
                        } catch (Exception ignored) {
                        }
                    } else if (kv.startsWith("refill=")) {
                        try {
                            refillOverride = Double.parseDouble(kv.substring(7));
                        } catch (Exception ignored) {
                        }
                    } else if (kv.startsWith("mingap=")) {
                        try {
                            minGapMs = Long.parseLong(kv.substring(7));
                        } catch (Exception ignored) {
                        }
                    }
                }

                try {
                    Pattern pat = Pattern.compile(regex);
                    String id = "r" + (++seq) + "@L" + ln;
                    list.add(new RouteRule(id, kind, method, pat, cost, capOverride, refillOverride, minGapMs));
                } catch (PatternSyntaxException ex) {
                    log.error("Invalid regex at line {}: {}", ln, regex);
                }
            }
        }
        return new RouteRules(list);
    }

    public Optional<RouteRule> firstMatch(String method, String path) {
        for (RouteRule r : rules)
            if (r.matches(method, path))
                return Optional.of(r);
        return Optional.empty();
    }
}