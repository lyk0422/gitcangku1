package com.example.starter.race.domain;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link PursuitCatchCalculator} 的纯单元测试：合成直线进度，
 * 覆盖题干合成用例、精确有理数、多段插值与公共区间不外推。
 */
class PursuitCatchCalculatorTest {

    @Test
    void 合成用例_A0_B1000_10秒A3000_B2000_最早交会10秒() {
        Optional<CatchTime> catchTime = PursuitCatchCalculator.earliestCatch(
                0L, 1000L,
                List.of(new TrajectoryPoint(10_000L, 3_000L)),
                List.of(new TrajectoryPoint(10_000L, 2_000L)));

        assertThat(catchTime).isPresent();
        assertThat(catchTime.get().numerator()).isEqualTo(10_000L);
        assertThat(catchTime.get().denominator()).isEqualTo(1L);
    }

    @Test
    void 合成用例_A仅前进2999_公共区间内未交会() {
        Optional<CatchTime> catchTime = PursuitCatchCalculator.earliestCatch(
                0L, 1000L,
                List.of(new TrajectoryPoint(10_000L, 2_999L)),
                List.of(new TrajectoryPoint(10_000L, 2_000L)));

        // 零点在 10000000/999 ≈ 10010.01 毫秒，超出公共区间 [0,10000]，禁止外推。
        assertThat(catchTime).isEmpty();
    }

    @Test
    void 交会时刻为分数时保存精确有理数() {
        // 追赶者速度 3/10 毫米每毫秒，被追者静止于 1 毫米处：t = 10/3 毫秒。
        Optional<CatchTime> catchTime = PursuitCatchCalculator.earliestCatch(
                0L, 1L,
                List.of(new TrajectoryPoint(10L, 3L)),
                List.of(new TrajectoryPoint(10L, 0L)));

        assertThat(catchTime).isPresent();
        assertThat(catchTime.get().numerator()).isEqualTo(10L);
        assertThat(catchTime.get().denominator()).isEqualTo(3L);
    }

    @Test
    void 多段折线在中间段交会() {
        // 追赶者前5毫秒静止，之后每毫秒2毫米；被追者静止于4毫米：t = 5 + 4/2 = 7。
        Optional<CatchTime> catchTime = PursuitCatchCalculator.earliestCatch(
                0L, 4L,
                List.of(new TrajectoryPoint(5L, 0L), new TrajectoryPoint(10L, 10L)),
                List.of(new TrajectoryPoint(10L, 0L)));

        assertThat(catchTime).isPresent();
        assertThat(catchTime.get().numerator()).isEqualTo(7L);
        assertThat(catchTime.get().denominator()).isEqualTo(1L);
    }

    @Test
    void 公共区间以较短轨迹为准且端点交会有效() {
        // 被追者轨迹只到 5000 毫秒，追赶者恰在 5000 毫秒追上：端点属于公共区间。
        Optional<CatchTime> catchTime = PursuitCatchCalculator.earliestCatch(
                0L, 500L,
                List.of(new TrajectoryPoint(5_000L, 500L), new TrajectoryPoint(10_000L, 2_000L)),
                List.of(new TrajectoryPoint(5_000L, 0L)));

        assertThat(catchTime).isPresent();
        assertThat(catchTime.get().numerator()).isEqualTo(5_000L);
        assertThat(catchTime.get().denominator()).isEqualTo(1L);
    }

    @Test
    void 超出较短轨迹的交会不外推() {
        // 被追者轨迹只到 5000 毫秒，追赶者在 5000 毫秒时仍落后，之后即使更快也不外推。
        Optional<CatchTime> catchTime = PursuitCatchCalculator.earliestCatch(
                0L, 600L,
                List.of(new TrajectoryPoint(5_000L, 500L), new TrajectoryPoint(10_000L, 2_000L)),
                List.of(new TrajectoryPoint(5_000L, 0L)));

        assertThat(catchTime).isEmpty();
    }

    @Test
    void 折点处恰好交会返回该折点时刻() {
        // 追赶者在 t=5000 折点处位置 1000，与被追者位置精确相等。
        Optional<CatchTime> catchTime = PursuitCatchCalculator.earliestCatch(
                0L, 1_000L,
                List.of(new TrajectoryPoint(5_000L, 1_000L), new TrajectoryPoint(10_000L, 3_000L)),
                List.of(new TrajectoryPoint(5_000L, 0L), new TrajectoryPoint(10_000L, 1_000L)));

        assertThat(catchTime).isPresent();
        assertThat(catchTime.get().numerator()).isEqualTo(5_000L);
        assertThat(catchTime.get().denominator()).isEqualTo(1L);
    }
}
