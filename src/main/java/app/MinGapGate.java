package app;

import java.util.concurrent.ConcurrentHashMap;

/** (userKey|ruleId) 별 마지막 통과 시각(ms)을 저장하고, mingap 미충족이면 남은 ms를 반환 */
public final class MinGapGate {
    private final ConcurrentHashMap<String, Long> lastPass = new ConcurrentHashMap<>();

    /**
     * @return 0 이면 통과(스탬프 갱신됨), 0보다 크면 차단되어 남은 대기 ms
     */
    public long check(String key, long nowMs, long gapMs) {
        for (;;) {
            Long prev = lastPass.get(key);
            if (prev != null) {
                long elapsed = nowMs - prev;
                if (elapsed < gapMs) {
                    return gapMs - elapsed; // 아직 간격 부족
                }
                // 간격 충족 → prev→now로 교체 시도
                if (lastPass.replace(key, prev, nowMs)) {
                    return 0L;
                }
                // 경합 시 재시도
            } else {
                // 최초 진입
                if (lastPass.putIfAbsent(key, nowMs) == null) {
                    return 0L;
                }
            }
        }
    }
}