package com.example.starter.kiln;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 温度轨迹校核：覆盖边界、采样间隔、重复时间戳与各段允许偏差。
 */
@Component
public class TrajectoryValidator {

    /** 最大采样间隔：5 分钟。 */
    public static final Duration MAX_GAP = Duration.ofMinutes(5);

    /**
     * 对整段轨迹做放行前校核，返回全部违规项（空列表表示通过）。
     *
     * @param segments 按 seq 升序、首尾相接的程序段
     * @param points   按采样时刻升序的测温点
     */
    public List<KilnDtos.Violation> validate(List<KilnRepository.SegmentRow> segments,
                                             List<KilnRepository.PointRow> points) {
        List<KilnDtos.Violation> violations = new ArrayList<>();
        if (points.isEmpty()) {
            violations.add(new KilnDtos.Violation("NO_SAMPLES", null, null,
                    "批次没有任何测温点，不能放行"));
            return violations;
        }
        Instant start = segments.get(0).startUtc();
        Instant end = segments.get(segments.size() - 1).endUtc();
        if (points.get(0).measuredAt().isAfter(start)) {
            violations.add(new KilnDtos.Violation("MISSING_START_BOUNDARY",
                    segments.get(0).seq(), start,
                    "缺少覆盖烧制起点 " + start + " 的采样"));
        }
        if (points.get(points.size() - 1).measuredAt().isBefore(end)) {
            violations.add(new KilnDtos.Violation("MISSING_END_BOUNDARY",
                    segments.get(segments.size() - 1).seq(), end,
                    "缺少覆盖烧制终点 " + end + " 的采样"));
        }
        for (int i = 1; i < points.size(); i++) {
            Instant prev = points.get(i - 1).measuredAt();
            Instant curr = points.get(i).measuredAt();
            if (curr.equals(prev)) {
                violations.add(new KilnDtos.Violation("DUPLICATE_TIMESTAMP",
                        locate(segments, curr).seq(), curr, "存在重复采样时刻 " + curr));
                continue;
            }
            Duration gap = Duration.between(prev, curr);
            if (gap.compareTo(MAX_GAP) > 0) {
                violations.add(new KilnDtos.Violation("SAMPLING_GAP",
                        locate(segments, prev).seq(), prev,
                        "采样间隔 " + gap.toMinutes() + " 分钟超过 5 分钟上限，起于 " + prev));
            }
        }
        for (KilnRepository.PointRow point : points) {
            KilnRepository.SegmentRow segment = locate(segments, point.measuredAt());
            double deviation = Math.abs(point.temperature() - segment.targetTemp());
            if (deviation > segment.tolerance()) {
                violations.add(new KilnDtos.Violation("OUT_OF_TOLERANCE",
                        segment.seq(), point.measuredAt(),
                        "温度 " + point.temperature() + " 超出段 " + segment.seq()
                                + " 允许范围（目标 " + segment.targetTemp()
                                + "，偏差上限 " + segment.tolerance() + "）"));
            }
        }
        return violations;
    }

    /**
     * 计算各段最坏偏差（无采样时段返回 null 偏差、0 样本）。
     */
    public List<KilnDtos.SegmentDeviation> deviations(List<KilnRepository.SegmentRow> segments,
                                                      List<KilnRepository.PointRow> points) {
        List<KilnDtos.SegmentDeviation> result = new ArrayList<>();
        for (KilnRepository.SegmentRow segment : segments) {
            double worst = Double.NEGATIVE_INFINITY;
            int count = 0;
            for (KilnRepository.PointRow point : points) {
                if (locate(segments, point.measuredAt()).seq() == segment.seq()) {
                    worst = Math.max(worst, Math.abs(point.temperature() - segment.targetTemp()));
                    count++;
                }
            }
            result.add(new KilnDtos.SegmentDeviation(segment.seq(),
                    count == 0 ? 0.0 : worst, count));
        }
        return result;
    }

    /** 定位时刻所属程序段：早于首段起点归入首段，晚于末段终点归入末段。 */
    private KilnRepository.SegmentRow locate(List<KilnRepository.SegmentRow> segments, Instant time) {
        for (KilnRepository.SegmentRow segment : segments) {
            if (time.isBefore(segment.endUtc())) {
                return segment;
            }
        }
        return segments.get(segments.size() - 1);
    }
}