package com.example.starter.race.domain;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link WaveRules} 纯逻辑单元测试：波次起跑时刻不得晚于参赛者首个有效分段计时。
 */
class WaveRulesTest {

    private static final long BASE = 1_767_225_600_000L;

    @Test
    void 波次毫秒差不晚于首个分段计时则合法() {
        // 波次晚于基准 500ms，首个分段累计耗时 500ms：起跑不晚于分段（相等允许）。
        assertThat(WaveRules.validateEntrant(500L, 500L)).isNull();
        assertThat(WaveRules.validateEntrant(499L, 500L)).isNull();
        // 尚无分段记录：不校验。
        assertThat(WaveRules.validateEntrant(999_999L, null)).isNull();
    }

    @Test
    void 波次毫秒差晚于首个分段计时则违规() {
        assertThat(WaveRules.validateEntrant(501L, 500L))
                .isEqualTo("波次起跑时刻不得晚于该参赛者首个有效分段计时");
    }

    @Test
    void 批量校验返回首个违规参赛号() {
        Map<String, Long> waveStartByBib = Map.of(
                "a", BASE + 100L,
                "b", BASE + 900L,
                "c", BASE + 200L);
        Map<String, Long> earliestSplitByBib = Map.of(
                "a", 100L,
                "b", 800L,
                "c", 500L);

        // b 的偏移 900 > 首个分段 800，违规；a 相等合法，c 无分段问题。
        assertThat(WaveRules.findViolation(waveStartByBib, BASE, earliestSplitByBib))
                .isEqualTo("b");
    }

    @Test
    void 全部合法时返回null() {
        Map<String, Long> waveStartByBib = Map.of(
                "a", BASE - 500L,
                "b", BASE + 300L);
        Map<String, Long> earliestSplitByBib = Map.of("b", 300L);

        assertThat(WaveRules.findViolation(waveStartByBib, BASE, earliestSplitByBib))
                .isNull();
    }
}
