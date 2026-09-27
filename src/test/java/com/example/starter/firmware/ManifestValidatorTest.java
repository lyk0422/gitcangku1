package com.example.starter.firmware;

import com.example.starter.firmware.api.ShardInput;
import com.example.starter.firmware.domain.DigestRules;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.service.ManifestValidator;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 分片摘要规则与登记校验的纯单元测试（无 Spring、无数据库）。
 */
class ManifestValidatorTest {

    private final ManifestValidator validator = new ManifestValidator();

    private static String digestOf(String content) {
        return DigestRules.sha256Hex(content);
    }

    private static String packageFor(String... shardDigests) {
        return DigestRules.aggregateOfOrderedDigests(List.of(shardDigests));
    }

    @Test
    void 摘要格式_固定64位小写十六进制() {
        String digest = digestOf("abc");
        assertThat(digest).isEqualTo(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        assertThat(DigestRules.isSha256Hex(digest)).isTrue();
        assertThat(DigestRules.isSha256Hex(digest.toUpperCase())).isFalse();
        assertThat(DigestRules.isSha256Hex(digest.substring(1))).isFalse();
        assertThat(DigestRules.isSha256Hex("g" + digest.substring(1))).isFalse();
        assertThat(DigestRules.isSha256Hex(null)).isFalse();
    }

    @Test
    void 聚合摘要_按序号升序拼接_顺序敏感() {
        String a = digestOf("a");
        String b = digestOf("b");
        assertThat(packageFor(a, b)).isEqualTo(DigestRules.sha256Hex(a + b));
        assertThat(packageFor(a, b)).isNotEqualTo(packageFor(b, a));
    }

    @Test
    void 无包摘要无分片_为遗留发布单() {
        assertThat(validator.validate(null, null)).isNull();
        assertThat(validator.validate("  ", List.of())).isNull();
    }

    @Test
    void 仅包摘要或仅分片_422() {
        String d0 = digestOf("s0");
        assertThatThrownBy(() -> validator.validate(packageFor(d0), null))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(ex.code()).isEqualTo("MANIFEST_INCOMPLETE");
                });
        assertThatThrownBy(() -> validator.validate(null, List.of(new ShardInput(0, d0))))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.code()).isEqualTo("MANIFEST_INCOMPLETE"));
    }

    @Test
    void 合法清单_乱序输入规范化为升序() {
        String d0 = digestOf("s0");
        String d1 = digestOf("s1");
        String d2 = digestOf("s2");
        List<ShardInput> normalized = validator.validate(packageFor(d0, d1, d2),
                List.of(new ShardInput(2, d2), new ShardInput(0, d0), new ShardInput(1, d1)));
        assertThat(normalized).extracting(ShardInput::shardIndex).containsExactly(0, 1, 2);
    }

    @Test
    void 重复序号_422() {
        String d0 = digestOf("s0");
        String d1 = digestOf("s1");
        assertThatThrownBy(() -> validator.validate(packageFor(d0, d1),
                List.of(new ShardInput(0, d0), new ShardInput(0, d1))))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.code()).isEqualTo("SHARD_INDEX_DUPLICATE"));
    }

    @Test
    void 序号缺口_422并给出要求值与实际值() {
        String d0 = digestOf("s0");
        String d2 = digestOf("s2");
        assertThatThrownBy(() -> validator.validate(packageFor(d0, d2),
                List.of(new ShardInput(0, d0), new ShardInput(2, d2))))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(ex.code()).isEqualTo("SHARD_INDEX_GAP");
                    assertThat(ex.getMessage()).contains("要求序号 1").contains("实际 2");
                });
    }

    @Test
    void 分片摘要格式非法_422() {
        String d0 = digestOf("s0");
        assertThatThrownBy(() -> validator.validate(packageFor(d0, "ABCD"),
                List.of(new ShardInput(0, d0), new ShardInput(1, "ABCD"))))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.code()).isEqualTo("SHARD_DIGEST_INVALID"));
    }

    @Test
    void 包摘要格式非法_422() {
        String d0 = digestOf("s0");
        assertThatThrownBy(() -> validator.validate("not-a-sha256", List.of(new ShardInput(0, d0))))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.code()).isEqualTo("PACKAGE_DIGEST_INVALID"));
    }

    @Test
    void 聚合摘要不匹配_422() {
        String d0 = digestOf("s0");
        String d1 = digestOf("s1");
        String wrongPackage = digestOf("something-else");
        assertThatThrownBy(() -> validator.validate(wrongPackage,
                List.of(new ShardInput(0, d0), new ShardInput(1, d1))))
                .isInstanceOfSatisfying(ApiException.class, ex ->
                        assertThat(ex.code()).isEqualTo("AGGREGATE_DIGEST_MISMATCH"));
    }
}
