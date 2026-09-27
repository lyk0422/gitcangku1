package com.example.starter.firmware;

import com.example.starter.firmware.api.FragmentInput;
import com.example.starter.firmware.domain.ReleaseFragment;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.service.DigestSupport;
import com.example.starter.firmware.service.UtcTimes;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 分片规范排序、固定摘要格式与聚合规则的纯单元测试。
 */
class DigestSupportTest {

    private static final String D0 = "0".repeat(64);
    private static final String D1 = "1".repeat(64);
    private static final String D2 = "2".repeat(64);

    @Test
    void 乱序登记_按序号规范排序输出() {
        List<ReleaseFragment> normalized = DigestSupport.normalizeInputs(List.of(
                new FragmentInput(2, D2),
                new FragmentInput(0, D0),
                new FragmentInput(1, D1)));
        assertThat(normalized).extracting(ReleaseFragment::seqNo).containsExactly(0, 1, 2);
        assertThat(normalized).extracting(ReleaseFragment::digest).containsExactly(D0, D1, D2);
    }

    @Test
    void 重复序号_返回422() {
        assertThatThrownBy(() -> DigestSupport.normalizeInputs(List.of(
                new FragmentInput(0, D0),
                new FragmentInput(0, D1))))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(ex.code()).isEqualTo("DUPLICATE_SEQ");
                });
    }

    @Test
    void 序号不从零开始_返回422() {
        assertThatThrownBy(() -> DigestSupport.normalizeInputs(List.of(
                new FragmentInput(1, D0),
                new FragmentInput(2, D1))))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(ex.code()).isEqualTo("SEQ_GAP");
                });
    }

    @Test
    void 序号缺口_返回422() {
        assertThatThrownBy(() -> DigestSupport.normalizeInputs(List.of(
                new FragmentInput(0, D0),
                new FragmentInput(2, D2))))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(ex.code()).isEqualTo("SEQ_GAP");
                });
    }

    @Test
    void 聚合摘要_按序换行拼接再求SHA256_顺序是完整性的一部分() {
        // 独立用 SHA-256("0...0\n1...1") 计算期望，验证聚合算法本身
        String expected = DigestSupport.sha256Hex(D0 + "\n" + D1);
        assertThat(DigestSupport.aggregate(List.of(D0, D1))).isEqualTo(expected);

        // 顺序改变聚合值必须改变（顺序是完整性的一部分）
        assertThat(DigestSupport.aggregate(List.of(D1, D0))).isNotEqualTo(expected);
    }

    @Test
    void 完整包摘要不匹配_返回422() {
        List<ReleaseFragment> normalized = DigestSupport.normalizeInputs(List.of(
                new FragmentInput(0, D0),
                new FragmentInput(1, D1)));
        assertThatThrownBy(() -> DigestSupport.verifyPackageDigest(normalized, "f".repeat(64)))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(ex.code()).isEqualTo("PACKAGE_DIGEST_MISMATCH");
                });
    }

    @Test
    void 完整包摘要匹配_通过() {
        List<ReleaseFragment> normalized = DigestSupport.normalizeInputs(List.of(
                new FragmentInput(0, D0),
                new FragmentInput(1, D1)));
        DigestSupport.verifyPackageDigest(normalized, DigestSupport.aggregate(List.of(D0, D1)));
    }

    @Test
    void 业务时刻_UTC秒精度ISO格式_以Z结尾() {
        Clock fixed = Clock.fixed(Instant.parse("2026-09-28T07:00:00.999Z"), ZoneOffset.UTC);
        assertThat(UtcTimes.now(fixed)).isEqualTo("2026-09-28T07:00:00Z");
    }
}
