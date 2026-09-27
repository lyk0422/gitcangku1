package com.example.starter.maintenance;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 维护窗口与自动合并的纯规则：UTC 左闭右开区间判定、命中匹配与胜出版本选择。
 * 不依赖数据库与时钟，便于单元测试。
 */
final class MaintenanceRules {

    private MaintenanceRules() {
    }

    /**
     * 左闭右开区间 [start, end) 是否包含时刻 t：start &lt;= t &lt; end。
     */
    static boolean contains(Instant start, Instant end, Instant t) {
        return !t.isBefore(start) && t.isBefore(end);
    }

    /**
     * 两个左闭右开区间是否重叠：aStart &lt; bEnd 且 bStart &lt; aEnd（端点相接不算重叠）。
     */
    static boolean overlaps(Instant aStart, Instant aEnd, Instant bStart, Instant bEnd) {
        return aStart.isBefore(bEnd) && bStart.isBefore(aEnd);
    }

    /**
     * 判断观测提交时刻是否命中窗口。
     */
    static boolean hits(MaintenanceWindow window, Instant submittedAtUtc) {
        return window.status() == WindowStatus.ACTIVE
                && contains(window.startAtUtc(), window.endAtUtc(), submittedAtUtc);
    }

    /**
     * 自动合并胜出版本选择：受维护影响观测不可胜出；
     * 在未受影响观测中取提交时刻最晚者，时刻相同按观测标识升序取第一条（确定的平局规则）。
     * 全部受影响或无证据时返回 empty，表示结果应为待人工裁决。
     */
    static Optional<SnapshotEvidence> pickWinner(List<SnapshotEvidence> evidence) {
        SnapshotEvidence winner = null;
        for (SnapshotEvidence candidate : evidence) {
            if (candidate.maintenanceAffected()) {
                continue;
            }
            if (winner == null
                    || candidate.submittedAtUtc().isAfter(winner.submittedAtUtc())
                    || (candidate.submittedAtUtc().equals(winner.submittedAtUtc())
                    && candidate.observationId().compareTo(winner.observationId()) < 0)) {
                winner = candidate;
            }
        }
        return Optional.ofNullable(winner);
    }
}
