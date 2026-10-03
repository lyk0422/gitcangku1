package com.example.starter.race.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ChipAttribution} 纯逻辑单元测试：左闭右开区间归属与唯一归属校验。
 */
class ChipAttributionTest {

    @Test
    void 生效前读数归旧芯片生效时刻起归新芯片() {
        // 左闭右开：[effectiveAt, ...) 归新芯片
        assertThat(ChipAttribution.chipFor(999L, 1000L, "OLD", "NEW")).isEqualTo("OLD");
        assertThat(ChipAttribution.chipFor(1000L, 1000L, "OLD", "NEW")).isEqualTo("NEW");
        assertThat(ChipAttribution.chipFor(1001L, 1000L, "OLD", "NEW")).isEqualTo("NEW");
    }

    @Test
    void 未归属或已归旧新芯片时可唯一重算() {
        assertThat(ChipAttribution.uniquenessViolation(null, "OLD", "NEW")).isNull();
        assertThat(ChipAttribution.uniquenessViolation("OLD", "OLD", "NEW")).isNull();
        assertThat(ChipAttribution.uniquenessViolation("NEW", "OLD", "NEW")).isNull();
    }

    @Test
    void 已归属第三块芯片时无法唯一归属() {
        assertThat(ChipAttribution.uniquenessViolation("OTHER", "OLD", "NEW"))
                .contains("OTHER")
                .contains("OLD")
                .contains("NEW");
    }
}