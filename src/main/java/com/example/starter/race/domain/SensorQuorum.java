package com.example.starter.race.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 三源计时共识的纯逻辑判定，不涉及数据库与事务。
 *
 * <p>规则（违反任一则裁定被拒绝，整次不写分段计时）：
 * <ul>
 *   <li>同一选手同一检查点必须集齐恰好三个不同 sensorId 的当前读数；</li>
 *   <li>三条读数均为 1~86400000 的整数毫秒；</li>
 *   <li>最大读数与最小读数之差不超过 20 毫秒；</li>
 *   <li>满足共识时裁定值取三条读数的中位值（排序后中间一条）。</li>
 * </ul>
 */
public final class SensorQuorum {

    /** 三源共识要求的传感器数量。 */
    public static final int REQUIRED_SENSORS = 3;
    /** 三条读数允许的最大离散（毫秒），含端点。 */
    public static final long MAX_SPREAD_MILLIS = 20L;
    /** 单条传感器读数上界（毫秒），含端点：1天。 */
    public static final long MAX_READING_MILLIS = 86_400_000L;

    private SensorQuorum() {
    }

    /**
     * 判定一组传感器读数是否达成共识并给出中位值。
     *
     * @param readings 当前修订号下的传感器读数（可任意顺序）
     * @return 共识结果；{@link QuorumResult#violation()} 非 null 表示未达成共识
     */
    public static QuorumResult adjudicate(List<? extends SensorReadingView> readings) {
        if (readings.size() != REQUIRED_SENSORS) {
            return QuorumResult.rejected(
                    "三源共识需要恰好 " + REQUIRED_SENSORS + " 条不同传感器读数，当前 "
                            + readings.size() + " 条");
        }
        Set<String> sensorIds = new HashSet<>();
        for (SensorReadingView reading : readings) {
            if (!sensorIds.add(reading.sensorId())) {
                return QuorumResult.rejected("同一检查点存在重复传感器读数: " + reading.sensorId());
            }
            if (reading.elapsedMillis() < 1L || reading.elapsedMillis() > MAX_READING_MILLIS) {
                return QuorumResult.rejected(
                        "传感器读数毫秒数必须在 1~86400000 之间: " + reading.sensorId());
            }
        }
        List<SensorReadingView> ordered = new ArrayList<>(readings);
        ordered.sort(Comparator
                .comparingLong(SensorReadingView::elapsedMillis)
                .thenComparing(SensorReadingView::sensorId));
        long min = ordered.getFirst().elapsedMillis();
        long max = ordered.getLast().elapsedMillis();
        if (max - min > MAX_SPREAD_MILLIS) {
            return QuorumResult.rejected(
                    "三源读数离散超过 " + MAX_SPREAD_MILLIS + " 毫秒: max=" + max + ", min=" + min);
        }
        return QuorumResult.accepted(ordered.get(1).elapsedMillis());
    }

    /** 传感器读数视图：传感器ID、读数毫秒与当前修订号。 */
    public interface SensorReadingView {
        /** 传感器ID，同一选手同一检查点内唯一。 */
        String sensorId();

        /** 传感器累计读数（毫秒，1~86400000）。 */
        long elapsedMillis();

        /** 该传感器读数的当前修订号，初始1，每次修订加一。 */
        int sensorRevision();
    }

    /**
     * 共识判定结果。
     *
     * @param violation       未达成共识的原因；达成共识时为 null
     * @param medianMillis    达成共识时的中位值（毫秒）；未达成共识时为 null
     */
    public record QuorumResult(String violation, Long medianMillis) {

        private static QuorumResult rejected(String violation) {
            return new QuorumResult(violation, null);
        }

        private static QuorumResult accepted(long medianMillis) {
            return new QuorumResult(null, medianMillis);
        }

        /** 是否达成共识。 */
        public boolean accepted() {
            return violation == null;
        }
    }
}
