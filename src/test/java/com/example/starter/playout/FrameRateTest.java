package com.example.starter.playout;

import com.example.starter.playout.api.ApiException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 帧时钟有理数数学的纯单元测试：真实时刻一次性计算，不逐帧累加舍入。
 */
class FrameRateTest {

    @Test
    void thirtyThousandFramesAtDropFrameRateIsExactly1001Seconds() {
        FrameRate rate = FrameRate.FPS_30000_1001;
        // 30000 帧 @ 30000/1001 恰为 1001 秒，无累计误差
        assertThat(rate.floorMs(30000)).isEqualTo(1001L * 1000L);
        assertThat(rate.ceilMs(30000)).isEqualTo(1001L * 1000L);
        // 相邻片段端点相接：第 30000 帧起点恰为 1001 秒处
        assertThat(rate.floorMs(60000)).isEqualTo(2002L * 1000L);
        // 单帧真实时长 1001/30000 秒 ≈ 33.3667ms，显示向下取整
        assertThat(rate.floorMs(1)).isEqualTo(33L);
        assertThat(rate.ceilMs(1)).isEqualTo(34L);
        assertThat(rate.floorMs(3)).isEqualTo(100L);
    }

    @Test
    void floorAndCeilBracketRationalInstant() {
        FrameRate rate = FrameRate.FPS_24;
        // 24fps 第 1 帧真实结束为 41.666...ms
        assertThat(rate.floorMs(1)).isEqualTo(41L);
        assertThat(rate.ceilMs(1)).isEqualTo(42L);
        assertThat(FrameRate.FPS_25.floorMs(1)).isEqualTo(40L);
        assertThat(FrameRate.FPS_25.ceilMs(1)).isEqualTo(40L);
    }

    @Test
    void rationalAxisComparisonsAreExact() {
        FrameRate rate = FrameRate.FPS_24;
        // 第 1 帧真实开始 41.667ms：41ms 处尚未开始，42ms 处已开始
        assertThat(rate.realStartLte(1, 41)).isFalse();
        assertThat(rate.realStartLte(1, 42)).isTrue();
        // 第 1 帧真实结束 41.667ms：41ms 处仍在片段内，42ms 处已结束
        assertThat(rate.realEndGt(1, 41)).isTrue();
        assertThat(rate.realEndGt(1, 42)).isFalse();
    }

    @Test
    void dayBoundaryIsRational() {
        FrameRate rate = FrameRate.FPS_24;
        // 24fps 一天恰为 2073600 帧：等于一天合法，超出一帧非法
        assertThat(rate.withinDay(24L * 86400L)).isTrue();
        assertThat(rate.withinDay(24L * 86400L + 1)).isFalse();
        // 30000/1001 一天不是整数帧：floor 帧数合法，再大一帧越界
        long maxFrame = 86400L * 30000L / 1001L;
        assertThat(rateWithinDay(FrameRate.FPS_30000_1001, maxFrame)).isTrue();
        assertThat(rateWithinDay(FrameRate.FPS_30000_1001, maxFrame + 1)).isFalse();
    }

    private static boolean rateWithinDay(FrameRate rate, long frame) {
        return rate.withinDay(frame);
    }

    @Test
    void durationCheckIsRational() {
        FrameRate rate = FrameRate.FPS_24;
        // 24 帧恰为 1000ms
        assertThat(rate.durationWithin(0, 24, 1000)).isTrue();
        // 1 帧真实 41.667ms：41ms 素材不足一帧（显示取整 41 不能放过），42ms 素材可以
        assertThat(rate.durationWithin(0, 1, 41)).isFalse();
        assertThat(rate.durationWithin(0, 1, 42)).isTrue();
    }

    @Test
    void unsupportedFrameRateRejected() {
        assertThat(FrameRate.of(24, 1)).isEqualTo(FrameRate.FPS_24);
        assertThat(FrameRate.of(25, 1)).isEqualTo(FrameRate.FPS_25);
        assertThat(FrameRate.of(30000, 1001)).isEqualTo(FrameRate.FPS_30000_1001);
        assertThatThrownBy(() -> FrameRate.of(30, 1))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("不支持的帧率");
        assertThatThrownBy(() -> FrameRate.of(60000, 1001))
                .isInstanceOf(ApiException.class);
    }
}
