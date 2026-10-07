package com.example.starter.translation;

import com.example.starter.translation.api.ApiException;
import com.example.starter.translation.service.SubtitleTiming;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 字幕时段与阅读速率校验的纯单元测试：边界时长、码点计数、精确速率比较与溢出安全。
 */
class SubtitleTimingTest {

    @Test
    @DisplayName("时段：500 毫秒为最短合法时长，499 毫秒与空区间均 422")
    void durationBoundaries() {
        assertThat(SubtitleTiming.durationOf(0, 500)).isEqualTo(500);
        assertThat(SubtitleTiming.durationOf(1000, 2000)).isEqualTo(1000);
        assertThatThrownBy(() -> SubtitleTiming.durationOf(0, 499))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("最短时长");
        assertThatThrownBy(() -> SubtitleTiming.durationOf(1000, 1000))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("endMs 必须大于 startMs");
        assertThatThrownBy(() -> SubtitleTiming.durationOf(2000, 1000))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> SubtitleTiming.durationOf(-1, 1000))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("不能为负数");
    }

    @Test
    @DisplayName("码点计数：去掉 Unicode 空白后计数，补充平面表情计 1 码点")
    void codePointCounting() {
        assertThat(SubtitleTiming.countCodePoints("hello world")).isEqualTo(10);
        assertThat(SubtitleTiming.countCodePoints("a\tb\nc　")).isEqualTo(3);
        // U+1F600 补充平面表情，占 2 个 UTF-16 字符但计 1 码点
        String emoji = "😀";
        assertThat(emoji.length()).isEqualTo(2);
        assertThat(SubtitleTiming.countCodePoints(emoji)).isEqualTo(1);
        assertThat(SubtitleTiming.countCodePoints("ab😀cd")).isEqualTo(5);
        // 插空白不能规避计数：14 码点加大量空白仍是 14
        assertThat(SubtitleTiming.countCodePoints("1 2 3 4 5 6 7 8 9 0 a b c d")).isEqualTo(14);
    }

    @Test
    @DisplayName("阅读速率：1 秒 15 码点允许、16 码点拒绝，计数乘1000与时长乘15精确比较")
    void readingSpeedExactComparison() {
        SubtitleTiming.assertReadingSpeed(15, 1000);
        SubtitleTiming.assertReadingSpeed(0, 500);
        // 500 毫秒允许 7 码点（7*1000=7000 <= 500*15=7500），8 码点拒绝
        SubtitleTiming.assertReadingSpeed(7, 500);
        assertThatThrownBy(() -> SubtitleTiming.assertReadingSpeed(8, 500))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("超速");
        assertThatThrownBy(() -> SubtitleTiming.assertReadingSpeed(16, 1000))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("16 码点 / 1000 毫秒");
    }

    @Test
    @DisplayName("溢出安全：极大时长乘法不溢出且必然通过，零点加法溢出返回 422")
    void overflowSafety() {
        // 时长乘 15 会溢出 long 时，任何可达计数必然通过
        SubtitleTiming.assertReadingSpeed(1000, Long.MAX_VALUE - 1);
        SubtitleTiming.assertReadingSpeed(15, Long.MAX_VALUE / 15);
        // 零点加起止溢出时间轴可表示范围：422 而非静默回绕
        assertThatThrownBy(() -> SubtitleTiming.absoluteInterval(Long.MAX_VALUE, 1000, 2000))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("超出可表示范围");
        long[] interval = SubtitleTiming.absoluteInterval(1_700_000_000_000L, 0, 1000);
        assertThat(interval).containsExactly(1_700_000_000_000L, 1_700_000_001_000L);
    }
}
