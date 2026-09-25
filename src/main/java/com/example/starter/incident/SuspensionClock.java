package com.example.starter.incident;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 遏制时限挂起计算（纯函数，无持久化可变剩余值）。
 *
 * <p>口径：遏制计时从接管时刻 {@code startAt} 起算，原始时限 {@code originalLimit}；
 * 挂起区间整体从已消耗时长中排除。任意时刻 {@code now}：
 * <pre>
 *   已挂起总时长 S(now) = Σ 各区间截至 now 的时长（生效中区间以 now 封口）
 *   有效期限(now)      = startAt + originalLimit + S(now)
 *   已消耗活跃时长      = max(0, now - startAt) - S(now)
 *   剩余时限           = originalLimit - 已消耗活跃时长（负值表示已超时）
 * </pre>
 * 生效中的挂起会让有效期限随当前时刻同步顺延，挂起期间墙钟流逝不计消耗、不触发超时。
 */
public final class SuspensionClock {

    private SuspensionClock() {
    }

    /** 截至 now 各挂起区间累计时长（含当前生效中区间到 now 的部分）。 */
    public static Duration totalSuspended(List<Suspension> intervals, Instant now) {
        long millis = intervals.stream().mapToLong(s -> s.suspendedMillisUntil(now)).sum();
        return Duration.ofMillis(millis);
    }

    /** 已封口（恢复）区间的累计挂起时长，不含当前生效中区间。 */
    public static Duration totalClosedSuspended(List<Suspension> intervals) {
        long millis = intervals.stream()
                .filter(s -> !s.isOpen())
                .mapToLong(s -> s.suspendedMillisUntil(Instant.MAX))
                .sum();
        return Duration.ofMillis(millis);
    }

    /** 当前生效中的挂起区间（每事件至多一条），无则空。 */
    public static Optional<Suspension> findOpen(List<Suspension> intervals) {
        return intervals.stream().filter(Suspension::isOpen).findFirst();
    }

    /**
     * 按当前时钟实时计算的有效遏制期限：原始期限叠加截至 now 的全部挂起时长。
     * 生效中挂起未恢复时，该值随 now 顺延；恢复后由封口区间固定顺延量。
     */
    public static Instant effectiveDeadline(Instant startAt, Duration originalLimit,
                                            List<Suspension> intervals, Instant now) {
        return startAt.plus(originalLimit).plus(totalSuspended(intervals, now));
    }

    /** 截至 now 的已消耗活跃时长（墙钟减去挂起区间），不为负。 */
    public static Duration consumedActive(Instant startAt, Instant now, List<Suspension> intervals) {
        Duration wall = Duration.between(startAt, now);
        if (wall.isNegative()) {
            wall = Duration.ZERO;
        }
        Duration active = wall.minus(totalSuspended(intervals, now));
        return active.isNegative() ? Duration.ZERO : active;
    }

    /** 截至 now 的剩余时限；挂起期间冻结不递减，负值表示已超时（绝对值为超时时长）。 */
    public static Duration remaining(Instant startAt, Duration originalLimit,
                                     List<Suspension> intervals, Instant now) {
        return originalLimit.minus(consumedActive(startAt, now, intervals));
    }

    /** 按当前时钟判定是否已达到/超过有效遏制期限。 */
    public static boolean isOverdue(Instant startAt, Duration originalLimit,
                                    List<Suspension> intervals, Instant now) {
        return !now.isBefore(effectiveDeadline(startAt, originalLimit, intervals, now));
    }
}
