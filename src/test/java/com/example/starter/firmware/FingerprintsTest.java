package com.example.starter.firmware;

import com.example.starter.firmware.service.Fingerprints;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 幂等指纹结构化编码单元测试：分隔符碰撞、null 与空串、字段顺序。
 */
class FingerprintsTest {

    @Test
    void 相同输入产生相同指纹() {
        assertThat(Fingerprints.of("device.maintenance", "ops", "d1", "1", "5"))
                .isEqualTo(Fingerprints.of("device.maintenance", "ops", "d1", "1", "5"));
    }

    @Test
    void 分隔符碰撞被结构化编码避免() {
        // 朴素 join("|") 下两者相同，长度前缀编码必须不同
        assertThat(Fingerprints.of("a|b", "c")).isNotEqualTo(Fingerprints.of("a", "b|c"));
        assertThat(Fingerprints.of("ab", "c")).isNotEqualTo(Fingerprints.of("a", "bc"));
        assertThat(Fingerprints.of("1:ab")).isNotEqualTo(Fingerprints.of("1", "ab"));
    }

    @Test
    void null与空串编码不同() {
        assertThat(Fingerprints.of("op", (String) null)).isNotEqualTo(Fingerprints.of("op", ""));
        assertThat(Fingerprints.of("op", (String) null)).isNotEqualTo(Fingerprints.of("op"));
    }

    @Test
    void 字段顺序与个数敏感() {
        assertThat(Fingerprints.of("a", "b")).isNotEqualTo(Fingerprints.of("b", "a"));
        assertThat(Fingerprints.of("a", "b")).isNotEqualTo(Fingerprints.of("a", "b", "b"));
    }
}
