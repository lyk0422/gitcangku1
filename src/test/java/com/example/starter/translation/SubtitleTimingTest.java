package com.example.starter.translation;

import com.example.starter.translation.api.ApiException;
import com.example.starter.translation.domain.Rows.SegmentRow;
import com.example.starter.translation.service.SubtitleTiming;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 字幕时间轴与阅读速率的纯单元测试：码点计数、速率边界、时段合法性与重叠判定。
 */
class SubtitleTimingTest {

    @Test
    @DisplayName("码点计数：去 Unicode 空白，补充平面表情计 1 码点，不按 UTF-16 字符计")
    void codePointCount() {
        assertThat(SubtitleTiming.codePointCount("hello")).isEqualTo(5);
        // 不间断空格（U+00A0）同样不计
        assertThat(SubtitleTiming.codePointCount("a" + Character.toString(0xA0) + "b")).isEqualTo(2);
        // 空格、换行均不计
        assertThat(SubtitleTiming.codePointCount("ab cd\nef gh")).isEqualTo(8);
        // 补充平面表情：UTF-16 为 2 个 char，但计 1 码点
        assertThat(SubtitleTiming.codePointCount("😀")).isEqualTo(1);
        assertThat(SubtitleTiming.codePointCount("😀😀😀")).isEqualTo(3);
        assertThat(SubtitleTiming.codePointCount("a😀b")).isEqualTo(3);
        assertThat(SubtitleTiming.codePointCount("  \t\n  ")).isZero();
    }

    @Test
    @DisplayName("阅读速率：1 秒时段 15 码点允许，16 码点拒绝；计数乘 1000 与时长乘 15 精确比较")
    void readingSpeedBoundary() {
        SubtitleTiming.checkReadingSpeed("a".repeat(15), 1000L, "s1/en");
        assertThatThrownBy(() -> SubtitleTiming.checkReadingSpeed("a".repeat(16), 1000L, "s1/en"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
        // 500 毫秒时段：7 码点允许（7000 <= 7500），8 码点拒绝（8000 > 7500）
        SubtitleTiming.checkReadingSpeed("a".repeat(7), 500L, "s1/en");
        assertThatThrownBy(() -> SubtitleTiming.checkReadingSpeed("a".repeat(8), 500L, "s1/en"))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("时段校验：长度至少 500 毫秒、起止非负、参数齐全")
    void timingValidation() {
        SubtitleTiming.validateTiming("s1", 1700000000000L, 0L, 500L);
        assertThatThrownBy(() -> SubtitleTiming.validateTiming("s1", 0L, 0L, 499L))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> SubtitleTiming.validateTiming("s1", 0L, -1L, 1000L))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> SubtitleTiming.validateTiming("s1", null, 0L, 1000L))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> SubtitleTiming.validateTiming("s1", 0L, 1000L, 1000L))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("时间轴：左闭右开首尾相接不重叠，交叉重叠 422，零点加法溢出 422")
    void timelineOverlap() {
        SegmentRow a = new SegmentRow("s1", "甲", 1, 1000L, 0L, 1000L);
        SegmentRow touching = new SegmentRow("s2", "乙", 1, 1000L, 1000L, 2000L);
        SubtitleTiming.validateTimeline(List.of(a, touching));

        SegmentRow overlapping = new SegmentRow("s2", "乙", 1, 1000L, 999L, 2000L);
        assertThatThrownBy(() -> SubtitleTiming.validateTimeline(List.of(a, overlapping)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));

        SegmentRow overflow = new SegmentRow("s3", "丙", 1, Long.MAX_VALUE, 1000L, 2000L);
        assertThatThrownBy(() -> SubtitleTiming.validateTimeline(List.of(overflow)))
                .isInstanceOf(ApiException.class);
    }
}
