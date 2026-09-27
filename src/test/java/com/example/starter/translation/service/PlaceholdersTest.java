package com.example.starter.translation.service;

import com.example.starter.translation.api.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 版式占位符工具的纯单元测试：格式边界、规范排序、重复计数、正文提取、差额明细。
 */
class PlaceholdersTest {

    @Test
    @DisplayName("canonical：null 与空列表为空集合；按 Unicode 字典序排序并保留重复")
    void canonicalSortsAndKeepsDuplicates() {
        assertThat(Placeholders.canonical(null)).isEmpty();
        assertThat(Placeholders.canonical(List.of())).isEmpty();
        assertThat(Placeholders.canonical(List.of("{b}", "{a}", "{a}", "{中}")))
                .containsExactly("{a}", "{a}", "{b}", "{中}");
    }

    @Test
    @DisplayName("canonical：空元素、缺花括号、非法名字字符、超长名字一律 422")
    void canonicalRejectsIllegalTokens() {
        assertThatThrownBy(() -> Placeholders.canonical(List.of("a"))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> Placeholders.canonical(List.of("{}"))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> Placeholders.canonical(List.of("{a-b}"))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> Placeholders.canonical(List.of("{a b}"))).isInstanceOf(ApiException.class);
        String tooLong = "{" + "a".repeat(33) + "}";
        assertThatThrownBy(() -> Placeholders.canonical(List.of(tooLong))).isInstanceOf(ApiException.class);
        // 32 位名字合法
        String maxLength = "{" + "a".repeat(32) + "}";
        assertThat(Placeholders.canonical(List.of(maxLength))).containsExactly(maxLength);
    }

    @Test
    @DisplayName("extract：扫描正文中全部占位符，按字典序排序并保留出现次数；非占位符花括号不识别")
    void extractFromContent() {
        assertThat(Placeholders.extract("x {b} y {a} {a} {b}"))
                .containsExactly("{a}", "{a}", "{b}", "{b}");
        // 第三位 'a'(97) < '}'(125)，故 {aa} 排在 {a} 之前
        assertThat(Placeholders.extract("{ a} {a\n} {} {aa}{a}")).containsExactly("{aa}", "{a}");
        assertThat(Placeholders.extract("无占位符")).isEmpty();
        assertThat(Placeholders.extract(null)).isEmpty();
    }

    @Test
    @DisplayName("compare：完全一致返回 null；缺失与额外分别给出要求、实际与差额")
    void compareMultisets() {
        assertThat(Placeholders.compare(List.of("{a}", "{b}"), List.of("{b}", "{a}"))).isNull();

        Placeholders.Mismatch missing = Placeholders.compare(List.of("{a}", "{a}"), List.of("{a}"));
        assertThat(missing).isNotNull();
        assertThat(missing.missing()).hasSize(1);
        assertThat(missing.missing().get(0).placeholder()).isEqualTo("{a}");
        assertThat(missing.missing().get(0).requiredCount()).isEqualTo(2);
        assertThat(missing.missing().get(0).actualCount()).isEqualTo(1);
        assertThat(missing.missing().get(0).difference()).isEqualTo(1);
        assertThat(missing.extra()).isEmpty();

        Placeholders.Mismatch extra = Placeholders.compare(List.of("{a}"), List.of("{a}", "{b}"));
        assertThat(extra).isNotNull();
        assertThat(extra.extra()).hasSize(1);
        assertThat(extra.extra().get(0).placeholder()).isEqualTo("{b}");
        assertThat(extra.extra().get(0).requiredCount()).isZero();
        assertThat(extra.extra().get(0).actualCount()).isEqualTo(1);
        assertThat(extra.extra().get(0).difference()).isEqualTo(1);
    }

    @Test
    @DisplayName("describe：信息中逐条包含实际值、要求值与差额")
    void describeContainsCounts() {
        Placeholders.Mismatch mismatch = Placeholders.compare(List.of("{a}", "{a}"), List.of("{b}"));
        String message = Placeholders.describe("占位符集合不一致", mismatch);
        assertThat(message).contains("占位符集合不一致")
                .contains("{a}").contains("要求 2 次实际 0 次差额 2 次")
                .contains("{b}").contains("要求 0 次实际 1 次差额 1 次");
    }

    @Test
    @DisplayName("指纹：集合以 JSON 结构序列化，元素内含分隔符不会与裸拼接混淆；字段/操作者/类型变化均改变指纹")
    void fingerprintSeparatesCollectionsStructurally() {
        ObjectMapper mapper = new ObjectMapper();
        String f1 = Fingerprint.of(mapper, "SUBMIT_TRANSLATION", "alice", Fingerprint.ordered(
                "documentId", 1L,
                "placeholders", Fingerprint.collection(List.of("{a,b}", "{c}"))));
        String f2 = Fingerprint.of(mapper, "SUBMIT_TRANSLATION", "alice", Fingerprint.ordered(
                "documentId", 1L,
                "placeholders", Fingerprint.collection(List.of("{a}", "{b},{c}"))));
        // 裸分隔符拼接会使两者相同；JSON 转义承载结构后必须不同
        assertThat(f1).isNotEqualTo(f2);
        assertThat(f1).matches("[0-9a-f]{64}");

        String f1Again = Fingerprint.of(mapper, "SUBMIT_TRANSLATION", "alice", Fingerprint.ordered(
                "documentId", 1L,
                "placeholders", Fingerprint.collection(List.of("{a,b}", "{c}"))));
        assertThat(f1).isEqualTo(f1Again);
        assertThat(Fingerprint.of(mapper, "APPROVE_TRANSLATION", "alice",
                Fingerprint.ordered("documentId", 1L))).isNotEqualTo(f1);
        assertThat(Fingerprint.of(mapper, "SUBMIT_TRANSLATION", "bob",
                Fingerprint.ordered("documentId", 1L))).isNotEqualTo(f1);
        assertThat(Fingerprint.of(mapper, "SUBMIT_TRANSLATION", "alice",
                Fingerprint.ordered("documentId", 2L))).isNotEqualTo(f1);
    }
}
