package com.example.starter.playout;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 帧时钟纯单元测试：有理数时刻精确换算、显示取整、一天上限与素材时长比较。
 * 不依赖数据库与系统时钟，所有时刻均为固定合成值。
 */
class FrameClockTest {

    @Test
    void onlySupportedRatesAccepted() {
        assertThat(FrameClock.of(24, 1)).isEqualTo(FrameClock.FPS_24);
        assertThat(FrameClock.of(25, 1)).isEqualTo(FrameClock.FPS_25);
        assertThat(FrameClock.of(30000, 1001)).isEqualTo(FrameClock.FPS_30000_1001);
        assertThatThrownBy(() -> FrameClock.of(30, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FrameClock.of(24000, 1001))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void thirtyThousandFramesAreExactlyOneThousandOneSeconds() {
        FrameClock clock = FrameClock.FPS_30000_1001;
        // 30000 帧 × 1001 / 30000 = 恰 1001 秒
        assertThat(clock.floorMs(30000)).isEqualTo(1_001_000L);
        // 逐帧向下取整累加只会得到 990000ms，帧时钟不积累该误差
        assertThat(30_000L * clock.floorMs(1)).isNotEqualTo(clock.floorMs(30_000));
        // 第 30000 帧开始的片段起点与上一片段终点为同一真实时刻（端点相接）
        assertThat(clock.frameLeMs(30_000, 1_001_000)).isTrue();
        assertThat(clock.frameGeMs(30_000, 1_001_000)).isTrue();
    }

    @Test
    void displayFloorsButComparisonsStayRational() {
        FrameClock clock = FrameClock.FPS_30000_1001;
        // 第 1 帧真实结束为 33.3666...ms，显示值向下取整为 33ms
        assertThat(clock.floorMs(1)).isEqualTo(33L);
        // 有理数比较：33ms 时刻尚未到达第 1 帧结束，34ms 已超过
        assertThat(clock.frameLeMs(1, 33)).isFalse();
        assertThat(clock.frameGeMs(1, 33)).isTrue();
        assertThat(clock.frameLeMs(1, 34)).isTrue();
        assertThat(clock.frameGeMs(1, 34)).isFalse();
    }

    @Test
    void dayBoundsAreExact() {
        assertThat(FrameClock.FPS_24.maxEndFrame()).isEqualTo(2_073_600L);
        assertThat(FrameClock.FPS_25.maxEndFrame()).isEqualTo(2_160_000L);
        // floor(86400 * 30000 / 1001) = 2589410
        FrameClock clock = FrameClock.FPS_30000_1001;
        assertThat(clock.maxEndFrame()).isEqualTo(2_589_410L);
        assertThat(clock.withinDay(2_589_410L)).isTrue();
        assertThat(clock.withinDay(2_589_411L)).isFalse();
        assertThat(clock.withinDay(-1)).isFalse();
    }

    @Test
    void framesDurationComparesRationally() {
        FrameClock clock = FrameClock.FPS_30000_1001;
        // 1 帧真实时长 33.3666...ms：33ms 素材不足一帧，34ms 可以
        assertThat(clock.framesWithinDuration(1, 33)).isFalse();
        assertThat(clock.framesWithinDuration(1, 34)).isTrue();
        // 30000 帧恰 1001 秒：1001000ms 刚好，1000999ms 不足
        assertThat(clock.framesWithinDuration(30_000, 1_001_000)).isTrue();
        assertThat(clock.framesWithinDuration(30_000, 1_000_999)).isFalse();
    }
}
