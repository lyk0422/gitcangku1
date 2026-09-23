package com.example.starter.maintenance.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.example.starter.maintenance.api.dto.DriftAnchorRequest;
import com.example.starter.maintenance.domain.Reading;

/**
 * 漂移修正纯计算：锚点按采样时刻规范化、区间读数线性插值（0.001 小时）、单调性与边界判定。
 * 不访问数据库，便于单元测试覆盖插值与单调边界。
 */
public final class DriftCalculator {

    /** 校准工时与修正后工时精度：0.001 小时。 */
    public static final int CORRECTION_SCALE = 3;
    /** 分数运算中间精度。 */
    private static final int MATH_SCALE = 12;
    /** 由分钟换算的历史工时精度（6 位小数）。 */
    public static final int LEGACY_SCALE = 6;

    private DriftCalculator() {
    }

    /** 规范化后的锚点：携带重读的当前读数、期望修订号与校准工时。 */
    public record PlanAnchor(Reading reading, int expectedRevisionNo, BigDecimal calibratedHours) {
    }

    /** 区间内单条读数的修正计划。 */
    public record PlannedReading(
            Reading reading,
            boolean anchor,
            Integer segmentIndex,
            boolean frozen,
            BigDecimal oldHours,
            BigDecimal newHours,
            int oldRevisionNo,
            int newRevisionNo) {
    }

    /** 计算结果：violation 为稳定错误码，null 表示可激活。 */
    public record Plan(
            List<PlanAnchor> anchors,
            List<PlannedReading> readings,
            String violation) {

        public boolean valid() {
            return violation == null;
        }

        public Instant intervalStart() {
            return anchors.get(0).reading().sampledAt();
        }

        public Instant intervalEnd() {
            return anchors.get(anchors.size() - 1).reading().sampledAt();
        }
    }

    /** 锚点规范化中间结果：读取数据库前无法完成，故先按请求去重。 */
    public record NormalizedRequest(List<DriftAnchorRequest> anchors, String violation) {
    }

    /**
     * 按读数采样时刻规范化请求锚点。锚点换序提交与顺序提交等价；
     * 同一 readingId 重复出现视为非法。
     */
    public static NormalizedRequest normalizeRequests(List<DriftAnchorRequest> requested,
                                                      Map<String, Reading> readingsById) {
        Map<String, DriftAnchorRequest> distinct = new LinkedHashMap<>();
        for (DriftAnchorRequest anchor : requested) {
            if (distinct.put(anchor.readingId(), anchor) != null) {
                return new NormalizedRequest(null, "CORRECTION_ANCHOR_DUPLICATED");
            }
            if (!readingsById.containsKey(anchor.readingId())) {
                return new NormalizedRequest(null, "CORRECTION_ANCHOR_READING_NOT_FOUND");
            }
        }
        List<DriftAnchorRequest> sorted = requested.stream()
                .sorted(Comparator.comparing(a -> readingsById.get(a.readingId()).sampledAt()))
                .toList();
        return new NormalizedRequest(sorted, null);
    }

    /**
     * 计算修正计划。
     *
     * @param requested       原始请求锚点（允许乱序）
     * @param readingsById    设备内读数（readingId → 当前读数）
     * @param intervalReadings 首尾锚点闭区间内的完整读数（按采样时刻升序）
     * @param frozenReadingIds 被已完成保养记录冻结的读数集合
     */
    public static Plan compute(List<DriftAnchorRequest> requested,
                               Map<String, Reading> readingsById,
                               List<Reading> intervalReadings,
                               java.util.Set<String> frozenReadingIds) {
        NormalizedRequest normalized = normalizeRequests(requested, readingsById);
        if (normalized.violation() != null) {
            return new Plan(List.of(), List.of(), normalized.violation());
        }
        List<DriftAnchorRequest> sortedRequests = normalized.anchors();

        List<PlanAnchor> anchors = new ArrayList<>();
        for (DriftAnchorRequest anchorRequest : sortedRequests) {
            Reading reading = readingsById.get(anchorRequest.readingId());
            // 校准工时精确到 0.001 小时：超过三位小数无法精确表达，整单拒绝。
            if (anchorRequest.calibratedHours().stripTrailingZeros().scale() > CORRECTION_SCALE) {
                return new Plan(List.of(), List.of(), "CORRECTION_ANCHOR_PRECISION");
            }
            anchors.add(new PlanAnchor(reading, anchorRequest.expectedVersion(),
                    anchorRequest.calibratedHours().setScale(CORRECTION_SCALE, RoundingMode.HALF_UP)));
        }

        // 锚点采样时刻必须互不相同；相邻锚点校准工时严格递增。
        for (int i = 1; i < anchors.size(); i++) {
            if (!anchors.get(i).reading().sampledAt().isAfter(anchors.get(i - 1).reading().sampledAt())) {
                return new Plan(anchors, List.of(), "CORRECTION_ANCHOR_TIME_NOT_ORDERED");
            }
            if (anchors.get(i).calibratedHours().compareTo(anchors.get(i - 1).calibratedHours()) <= 0) {
                return new Plan(anchors, List.of(), "CORRECTION_ANCHOR_NOT_STRICT_INCREASING");
            }
        }

        // 锚点期望修订号须与当前读数版本一致（激活时版本变化整单拒绝）。
        for (PlanAnchor anchor : anchors) {
            if (anchor.expectedRevisionNo() != anchor.reading().revisionNo()) {
                return new Plan(anchors, List.of(), "ANCHOR_REVISION_CONFLICT");
            }
        }

        List<PlannedReading> planned = new ArrayList<>();
        int anchorIndex = 0;
        for (Reading reading : intervalReadings) {
            boolean isAnchor = anchorIndex < anchors.size()
                    && reading.readingId().equals(anchors.get(anchorIndex).reading().readingId());
            Integer segmentIndex = null;
            BigDecimal newHours;
            BigDecimal oldHours = reading.cumulativeHours().setScale(LEGACY_SCALE, RoundingMode.HALF_UP);
            if (isAnchor) {
                newHours = anchors.get(anchorIndex).calibratedHours();
                anchorIndex++;
            } else {
                int segment = findSegment(anchors, reading.sampledAt());
                segmentIndex = segment;
                newHours = interpolate(anchors.get(segment - 1), anchors.get(segment),
                        reading.sampledAt());
            }
            planned.add(new PlannedReading(reading, isAnchor, segmentIndex,
                    frozenReadingIds.contains(reading.readingId()),
                    oldHours, newHours, reading.revisionNo(), reading.revisionNo() + 1));
        }

        // 冻结点：只要一个冻结读数落在修正集合内，整单拒绝（不能只修其余读数）；仍返回计划供预览标记。
        boolean frozen = planned.stream().anyMatch(PlannedReading::frozen);

        // 插值后区间内整条序列须单调不减（严格递增线段在 0.001 四舍五入后只可能相等，不允许倒退）。
        boolean regression = false;
        for (int i = 1; i < planned.size(); i++) {
            if (planned.get(i).newHours().compareTo(planned.get(i - 1).newHours()) < 0) {
                regression = true;
                break;
            }
        }

        String violation = null;
        if (frozen) {
            violation = "CORRECTION_FROZEN_READING";
        } else if (regression) {
            violation = "CORRECTION_INTERPOLATED_REGRESSION";
        }
        return new Plan(anchors, List.copyOf(planned), violation);
    }

    /** 给定时刻落在第几段（段序号从 1 开始）；时刻恰为锚点时调用方应按锚点处理。 */
    private static int findSegment(List<PlanAnchor> anchors, Instant sampledAt) {
        for (int i = 1; i < anchors.size(); i++) {
            if (sampledAt.isBefore(anchors.get(i).reading().sampledAt())) {
                return i;
            }
        }
        throw new IllegalStateException("插值时刻不在任何锚点段内：" + sampledAt);
    }

    /** 段内按 UTC 时间线性插值并四舍五入到 0.001 小时。 */
    private static BigDecimal interpolate(PlanAnchor from, PlanAnchor to, Instant sampledAt) {
        long totalNanos = Duration.between(from.reading().sampledAt(), to.reading().sampledAt()).toNanos();
        long elapsedNanos = Duration.between(from.reading().sampledAt(), sampledAt).toNanos();
        BigDecimal fraction = BigDecimal.valueOf(elapsedNanos)
                .divide(BigDecimal.valueOf(totalNanos), MATH_SCALE, RoundingMode.HALF_UP);
        BigDecimal delta = to.calibratedHours().subtract(from.calibratedHours());
        return from.calibratedHours().add(delta.multiply(fraction))
                .setScale(CORRECTION_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 边界冲突判定：修正后首/尾值不得低于区间外左邻、高于区间外右邻。
     *
     * @return 冲突错误码；无冲突为 null
     */
    public static String boundaryViolation(Plan plan, Reading leftNeighbor, Reading rightNeighbor) {
        BigDecimal firstNew = plan.readings().get(0).newHours();
        BigDecimal lastNew = plan.readings().get(plan.readings().size() - 1).newHours();
        if (leftNeighbor != null
                && leftNeighbor.cumulativeHours().setScale(LEGACY_SCALE, RoundingMode.HALF_UP)
                .compareTo(firstNew) > 0) {
            return "CORRECTION_BOUNDARY_CONFLICT";
        }
        if (rightNeighbor != null
                && rightNeighbor.cumulativeHours().setScale(LEGACY_SCALE, RoundingMode.HALF_UP)
                .compareTo(lastNew) < 0) {
            return "CORRECTION_BOUNDARY_CONFLICT";
        }
        return null;
    }
}
