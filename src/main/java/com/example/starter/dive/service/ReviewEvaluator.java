package com.example.starter.dive.service;

import com.example.starter.dive.domain.Anomaly;
import com.example.starter.dive.domain.AnomalyType;
import com.example.starter.dive.domain.DepthPoint;
import com.example.starter.dive.domain.DiveSession;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 上浮审核评估器：纯函数，按训练计划限制检查完整点位集合，返回全部具体异常。
 * 通过条件：所有点深度0至计划最大深度、时间严格递增、相邻间隔不超过5分钟、
 * 首末点覆盖计划作业起止、总时长不超过连续作业上限。
 */
public final class ReviewEvaluator {

    /** 相邻点位允许的最大间隔。 */
    public static final Duration MAX_GAP = Duration.ofMinutes(5);

    private ReviewEvaluator() {
    }

    /** 评估点位集合，返回异常列表；空列表表示通过。 */
    public static List<Anomaly> evaluate(DiveSession session, List<DepthPoint> points) {
        List<Anomaly> anomalies = new ArrayList<>();
        if (points.isEmpty()) {
            anomalies.add(Anomaly.global(AnomalyType.NO_POINTS, "作业没有任何深度点位，无法审核"));
            return anomalies;
        }
        BigDecimal maxDepth = session.maxDepthM();
        for (DepthPoint point : points) {
            if (point.depthM().compareTo(BigDecimal.ZERO) < 0) {
                anomalies.add(Anomaly.atPoint(AnomalyType.INVALID_DEPTH,
                        "深度为负值：" + point.depthM() + "米", point, point.depthM()));
            } else if (point.depthM().compareTo(maxDepth) > 0) {
                anomalies.add(Anomaly.atPoint(AnomalyType.EXCEED_DEPTH,
                        "深度" + point.depthM() + "米超过计划最大深度" + maxDepth + "米",
                        point, point.depthM()));
            }
        }
        for (int i = 1; i < points.size(); i++) {
            DepthPoint previous = points.get(i - 1);
            DepthPoint current = points.get(i);
            if (!current.recordedAtUtc().isAfter(previous.recordedAtUtc())) {
                anomalies.add(Anomaly.between(AnomalyType.TIME_REGRESSION,
                        "点位时间未严格递增：" + previous.recordedAtUtc() + " 之后出现 "
                                + current.recordedAtUtc(), previous, current));
            } else {
                Duration gap = Duration.between(previous.recordedAtUtc(), current.recordedAtUtc());
                if (gap.compareTo(MAX_GAP) > 0) {
                    anomalies.add(Anomaly.between(AnomalyType.MEASURE_GAP,
                            "相邻点位间隔" + gap.toMinutes() + "分钟，超过5分钟上限，存在缺测",
                            previous, current));
                }
            }
        }
        DepthPoint first = points.get(0);
        DepthPoint last = points.get(points.size() - 1);
        if (first.recordedAtUtc().isAfter(session.plannedStartUtc())
                || last.recordedAtUtc().isBefore(session.plannedEndUtc())) {
            anomalies.add(Anomaly.global(AnomalyType.COVERAGE_MISSING,
                    "首末点未覆盖计划作业起止：计划[" + session.plannedStartUtc() + ", "
                            + session.plannedEndUtc() + "]，实际[" + first.recordedAtUtc() + ", "
                            + last.recordedAtUtc() + "]"));
        }
        Duration duration = Duration.between(first.recordedAtUtc(), last.recordedAtUtc());
        if (!duration.isNegative()
                && duration.compareTo(Duration.ofMinutes(session.maxDurationMin())) > 0) {
            anomalies.add(Anomaly.global(AnomalyType.DURATION_EXCEEDED,
                    "首末点间隔" + duration.toMinutes() + "分钟，超过连续作业上限"
                            + session.maxDurationMin() + "分钟"));
        }
        return anomalies;
    }
}