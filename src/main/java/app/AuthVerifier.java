package app;

import io.netty.handler.codec.http.FullHttpRequest;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 서버-서버 HMAC 검증 + 재사용 논스 차단(메모리)
 * 메시지 포맷:
 * method + "\n" + path + "\n" + ts + "\n" + nonce + "\n" + userKey + "\n" +
 * cost
 */
public final class AuthVerifier {

    private final String secret; // null/empty면 비활성
    private final long clockSkewSec; // 허용 시계 오차
    private final long nonceTtlSec; // 논스 TTL
    private final ConcurrentHashMap<String, Long> nonceSeen = new ConcurrentHashMap<>();

    public AuthVerifier(String secret, long clockSkewSec, long nonceTtlSec) {
        this.secret = (secret == null || secret.trim().isEmpty()) ? null : secret;
        this.clockSkewSec = clockSkewSec <= 0 ? 60 : clockSkewSec;
        this.nonceTtlSec = nonceTtlSec <= 0 ? 180 : nonceTtlSec;
    }

    /** secret이 없으면 항상 true(검증 비활성). */
    public boolean verify(FullHttpRequest req, String method, String path, String userKey, String costStr) {
        if (secret == null)
            return true; // no-op

        String ts = getHeader(req, "X-RL-Ts");
        String nonce = getHeader(req, "X-RL-Nonce");
        String sig = getHeader(req, "X-RL-Signature");
        if (ts == null || nonce == null || sig == null)
            return false;

        long nowSec = System.currentTimeMillis() / 1000;
        long tsSec;
        try {
            tsSec = Long.parseLong(ts);
        } catch (NumberFormatException e) {
            return false;
        }
        long delta = Math.abs(nowSec - tsSec);
        if (delta > clockSkewSec)
            return false;

        // 논스 재사용 차단: TTL 내 동일 논스는 거부
        Long prev = nonceSeen.putIfAbsent(nonce, nowSec);
        if (prev != null && (nowSec - prev) <= nonceTtlSec) {
            return false;
        }
        // 오래된 항목 일부 정리(얕은 GC) — 비용 최소화용
        if (nonceSeen.size() > 100_000) {
            long cutoff = nowSec - nonceTtlSec;
            nonceSeen.entrySet().removeIf(e -> e.getValue() < cutoff);
        }

        String msg = method + "\n" + path + "\n" + ts + "\n" + nonce + "\n" + userKey + "\n"
                + (costStr == null ? "" : costStr);
        String expect = hmacSha256Hex(secret, msg);
        return slowEquals(expect, sig.toLowerCase(Locale.ROOT));
    }

    private static String getHeader(FullHttpRequest r, String name) {
        String v = r.headers().get(name);
        return (v == null || v.isEmpty()) ? null : v.trim();
    }

    private static String hmacSha256Hex(String secret, String msg) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] out = mac.doFinal(msg.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(out.length * 2);
            for (byte b : out)
                sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /** 타이밍 공격 완화용 비교 */
    private static boolean slowEquals(String a, String b) {
        if (a == null || b == null)
            return false;
        int diff = a.length() ^ b.length();
        for (int i = 0; i < Math.min(a.length(), b.length()); i++) {
            diff |= a.charAt(i) ^ b.charAt(i);
        }
        return diff == 0 && Objects.equals(a, b);
    }
}