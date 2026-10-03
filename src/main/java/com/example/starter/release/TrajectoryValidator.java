package com.example.starter.release;

import com.example.starter.program.SegmentInfo;
import com.example.starter.reading.TemperatureReading;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 温度轨迹校核：采样间隔不超过5分钟、无重复时间戳、无时间断层、
 * 覆盖起止边界、所有采样温度处于所属程序段允许范围。纯函数，不依赖数据库。
 */
public final class TrajectoryValidator {

    /**
     * 最大允许采样间隔：5分钟。
     */
    public static final Duration MAX_INTERVAL = Duration.ofMinutes(5);

    private TrajectoryValidator() {
    }

    /**
     * 校核违例。
     *
     * @param type       类型：MISSING_START_BOUNDARY/MISSING_END_BOUNDARY/DUPLICATE_TIMESTAMP/
     *                   SAMPLING_GAP/OUT_OF_TOLERANCE/OUT_OF_WINDOW
     * @param segmentSeq 相关程序段序号，无关联段时为 null
     * @param time       异常时刻，UTC
     * @param message    说明
     */
    public record Violation(String type, Integer segmentSeq, Instant time, String message) {
    }

    /**
     * 程序段最坏偏差。
     *
     * @param seq             段序号
     * @param worstDeviationC 段内最坏偏差（|实测-目标|），段内无采样时为 null，摄氏度
     * @param worstAt         最坏偏差出现时刻，UTC
     */
    public record SegmentDeviation(int seq, BigDecimal worstDeviationC, Instant worstAt) {
    }

    /**
     * 对一批测温点做全轨迹校核，返回全部违例（空列表表示可放行）。
     */
    public static List<Violation> validate(List<SegmentInfo> segments,
                                           List<TemperatureReading> readings) {
        List<Violation> violations = new ArrayList<>();
        Instant start = segments.get(0).startTime();
        Instant end = segments.get(segments.size() - 1).endTime();
        if (readings.isEmpty()) {
            violations.add(new Violation("MISSING_START_BOUNDARY", segments.get(0).seq(), start,
                    "缺少覆盖起点边界的采样"));
            violations.add(new Violation("MISSING_END_BOUNDARY",
                    segments.get(segments.size() - 1).seq(), end, "缺少覆盖终点边界的采样"));
            return violations;
        }
        if (!readings.get(0).measuredAt().equals(start)) {
            violations.add(new Violation("MISSING_START_BOUNDARY", segments.get(0).seq(), start,
                    "首个采样未覆盖起点边界 " + start));
        }
        if (!readings.get(readings.size() - 1).measuredAt().equals(end)) {
            violations.add(new Violation("MISSING_END_BOUNDARY",
                    segments.get(segments.size() - 1).seq(), end, "末个采样未覆盖终点边界 " + end));
        }
        for (int i = 1; i < readings.size(); i++) {
            Instant prev = readings.get(i - 1).measuredAt();
            Instant cur = readings.get(i).measuredAt();
            if (cur.equals(prev)) {
                violations.add(new Violation("DUPLICATE_TIMESTAMP", seqOf(segments, cur), cur,
                        "重复时间戳: " + cur));
            } else if (Duration.between(prev, cur).compareTo(MAX_INTERVAL) > 0) {
                violations.add(new Violation("SAMPLING_GAP", seqOf(segments, cur), cur,
                        "采样间隔超过5分钟: " + prev + " -> " + cur));
            }
        }
        for (TemperatureReading r : readings) {
            SegmentInfo seg = segmentOf(segments, r.measuredAt());
            if (seg == null) {
                violations.add(new Violation("OUT_OF_WINDOW", null, r.measuredAt(),
                        "采样时刻不在任何程序段内: " + r.measuredAt()));
                continue;
            }
            BigDecimal deviation = r.temperatureC().subtract(seg.targetTempC()).abs();
            if (deviation.compareTo(seg.toleranceC()) > 0) {
                violations.add(new Violation("OUT_OF_TOLERANCE", seg.seq(), r.measuredAt(),
                        "温度超差: 实测 " + r.temperatureC() + "，段" + seg.seq()
                                + " 目标 " + seg.targetTempC() + "±" + seg.toleranceC()));
            }
        }
        return violations;
    }

    /**
     * 计算各程序段的最坏偏差，段内无采样时偏差为 null。
     */
    public static List<SegmentDeviation> worstDeviations(List<SegmentInfo> segments,
                                                         List<TemperatureReading> readings) {
        List<SegmentDeviation> result = new ArrayList<>();
        for (SegmentInfo seg : segments) {
            BigDecimal worst = null;
            Instant worstAt = null;
            for (TemperatureReading r : readings) {
                SegmentInfo owner = segmentOf(segments, r.measuredAt());
                if (owner == null || owner.seq() != seg.seq()) {
                    continue;
                }
                BigDecimal deviation = r.temperatureC().subtract(seg.targetTempC()).abs();
                if (worst == null || deviation.compareTo(worst) > 0) {
                    worst = deviation;
                    worstAt = r.measuredAt();
                }
            }
            result.add(new SegmentDeviation(seg.seq(), worst, worstAt));
        }
        return result;
    }

    /**
     * 定位时刻所属程序段：段含起点不含终点，烧制区间终点归属末段。
     */
    public static SegmentInfo segmentOf(List<SegmentInfo> segments, Instant time) {
        for (SegmentInfo seg : segments) {
            if (!time.isBefore(seg.startTime()) && time.isBefore(seg.endTime())) {
                return seg;
            }
        }
        SegmentInfo last = segments.get(segments.size() - 1);
        return time.equals(last.endTime()) ? last : null;
    }

    private static Integer seqOf(List<SegmentInfo> segments, Instant time) {
        SegmentInfo seg = segmentOf(segments, time);
        return seg == null ? null : seg.seq();
    }
}