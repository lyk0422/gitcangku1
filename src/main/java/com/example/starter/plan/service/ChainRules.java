package com.example.starter.plan.service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 车底交路衔接纯逻辑：站点衔接与最小周转间隔校验，不依赖数据库，便于单元测试。
 *
 * <p>交路链由同一车底、同一运营日的已发布段组成，按始发时刻升序排列；
 * 相邻两段要求前段终到站等于后段始发站（STATION_MISMATCH），
 * 且后段始发时刻不早于前段终到时刻加最小周转分钟数（TURNAROUND_INSUFFICIENT）。
 */
public final class ChainRules {

    /** 断点类型：站点不衔接。 */
    public static final String STATION_MISMATCH = "STATION_MISMATCH";
    /** 断点类型：周转间隔不足。 */
    public static final String TURNAROUND_INSUFFICIENT = "TURNAROUND_INSUFFICIENT";

    private ChainRules() {
    }

    /**
     * 交路段的最小视图。
     *
     * @param planId         计划 id
     * @param scheduleKey    计划业务键
     * @param opDate         运营日
     * @param originStation  始发站
     * @param destinationStation 终到站
     * @param startUtc       始发时刻（取计划最早占用开始）
     * @param endUtc         终到时刻（取计划最晚占用结束）
     */
    public record Segment(long planId, String scheduleKey, LocalDate opDate,
                          String originStation, String destinationStation,
                          Instant startUtc, Instant endUtc) {
    }

    /**
     * 校验若干新段并入既有已发布链后，同一运营日内全部相邻段是否连续。
     *
     * @param existing        车底当前已发布段（改签场景应先剔除被替换的旧段）
     * @param candidates      本次拟发布的新段（单张发布或整批发布；整批段之间同样校验）
     * @param requiredMinutes 车底最小周转分钟数
     * @return 断点明细列表，空列表表示整条链连续
     */
    public static List<Map<String, Object>> validateMerged(List<Segment> existing,
                                                           List<Segment> candidates,
                                                           int requiredMinutes) {
        Map<LocalDate, List<Segment>> byDate = new LinkedHashMap<>();
        for (Segment s : existing) {
            byDate.computeIfAbsent(s.opDate(), k -> new ArrayList<>()).add(s);
        }
        for (Segment s : candidates) {
            byDate.computeIfAbsent(s.opDate(), k -> new ArrayList<>()).add(s);
        }
        List<Map<String, Object>> violations = new ArrayList<>();
        byDate.forEach((date, segments) ->
                violations.addAll(validateSorted(sorted(segments), requiredMinutes)));
        return violations;
    }

    /**
     * 校验车底全部已发布相邻段（用于周转参数修改后的全量重校验），
     * 按运营日分组、组内按始发时刻升序逐对检查。
     */
    public static List<Map<String, Object>> validatePublished(List<Segment> published,
                                                              int requiredMinutes) {
        return validateMerged(published, List.of(), requiredMinutes);
    }

    /**
     * 计算前段→后段的实际周转间隔（分钟，可为负表示时间重叠）。
     */
    public static long gapMinutes(Segment predecessor, Segment successor) {
        return Duration.between(predecessor.endUtc(), successor.startUtc()).toMinutes();
    }

    private static List<Segment> sorted(List<Segment> segments) {
        return segments.stream()
                .sorted(Comparator.comparing(Segment::startUtc)
                        .thenComparing(Segment::planId))
                .toList();
    }

    private static List<Map<String, Object>> validateSorted(List<Segment> sorted, int requiredMinutes) {
        List<Map<String, Object>> violations = new ArrayList<>();
        for (int i = 1; i < sorted.size(); i++) {
            Segment prev = sorted.get(i - 1);
            Segment cur = sorted.get(i);
            long gap = gapMinutes(prev, cur);
            if (!prev.destinationStation().equals(cur.originStation())) {
                violations.add(detail(STATION_MISMATCH, prev, cur, gap, requiredMinutes));
            } else if (gap < requiredMinutes) {
                violations.add(detail(TURNAROUND_INSUFFICIENT, prev, cur, gap, requiredMinutes));
            }
        }
        return violations;
    }

    private static Map<String, Object> detail(String type, Segment prev, Segment cur,
                                              long gapMinutes, int requiredMinutes) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("type", type);
        detail.put("predecessorScheduleKey", prev.scheduleKey());
        detail.put("successorScheduleKey", cur.scheduleKey());
        detail.put("predecessorDestinationStation", prev.destinationStation());
        detail.put("successorOriginStation", cur.originStation());
        detail.put("breakpoint", prev.destinationStation() + "->" + cur.originStation());
        detail.put("predecessorEndUtc", prev.endUtc().toString());
        detail.put("successorStartUtc", cur.startUtc().toString());
        detail.put("actualGapMinutes", gapMinutes);
        detail.put("requiredMinutes", requiredMinutes);
        return detail;
    }
}
