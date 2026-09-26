package com.example.starter.firmware;

import com.example.starter.firmware.domain.Digests;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 摘要规则黄金向量测试：分片摘要格式校验与聚合摘要规则（按序号升序拼接后SHA-256）稳定不变。
 */
class DigestsTest {

    @Test
    void 摘要格式校验_仅接受64位小写十六进制() {
        String valid = "686f129b14e7426e5a7cc273291665f6c911c92e9e8cb4f6561b127676b8f58a";
        assertThat(Digests.isValidDigest(valid)).isTrue();
        assertThat(Digests.isValidDigest(valid.toUpperCase())).isFalse();
        assertThat(Digests.isValidDigest("abc")).isFalse();
        assertThat(Digests.isValidDigest(null)).isFalse();
        assertThat(Digests.isValidDigest(valid + "00")).isFalse();
    }

    @Test
    void 聚合摘要黄金向量_规则稳定() {
        String d0 = Digests.aggregateHex(List.of("demo-shard-0"));
        String d1 = Digests.aggregateHex(List.of("demo-shard-1"));
        assertThat(d0).isEqualTo("686f129b14e7426e5a7cc273291665f6c911c92e9e8cb4f6561b127676b8f58a");
        assertThat(d1).isEqualTo("d2bc88d04cb2fbf2b7283e166372e5910f7fca8d7241334860dc578b8f3dd6cd");
        // 聚合：按规范顺序拼接分片摘要字符串后再求SHA-256
        assertThat(Digests.aggregateHex(List.of(d0, d1)))
                .isEqualTo("2a0a31b4eada4fea8cebda9162f0a66b00d772029b03a58963e2d1a7987917cb");
        // 顺序敏感：交换顺序聚合值不同
        assertThat(Digests.aggregateHex(List.of(d1, d0)))
                .isNotEqualTo(Digests.aggregateHex(List.of(d0, d1)));
    }
}
