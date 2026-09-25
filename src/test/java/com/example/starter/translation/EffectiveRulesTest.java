package com.example.starter.translation;

import com.example.starter.translation.domain.EffectiveRules;
import com.example.starter.translation.domain.EffectiveRules.EffectiveRule;
import com.example.starter.translation.domain.EffectiveRules.Origin;
import com.example.starter.translation.domain.Rows.GlobalTermRuleRow;
import com.example.starter.translation.domain.Rows.TermRuleRow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 生效规则集合成逻辑的纯单元测试：覆盖、抑制、语言过滤与稳定排序。
 */
class EffectiveRulesTest {

    @Test
    @DisplayName("合成：文档规则整条覆盖同键全局规则，不同键全局规则保留")
    void documentOverridesGlobal() {
        List<GlobalTermRuleRow> global = List.of(
                new GlobalTermRuleRow("机器学习", "en", "machine learning"),
                new GlobalTermRuleRow("神经网络", "en", "neural network"));
        List<TermRuleRow> document = List.of(
                new TermRuleRow("机器学习", "en", "ML", false));
        List<EffectiveRule> rules = EffectiveRules.compose(global, document, List.of("en"));

        assertThat(rules).hasSize(2);
        EffectiveRule overridden = rules.stream()
                .filter(r -> r.sourceTerm().equals("机器学习")).findFirst().orElseThrow();
        assertThat(overridden.requiredTranslation()).isEqualTo("ML");
        assertThat(overridden.origin()).isEqualTo(Origin.DOCUMENT);
        EffectiveRule kept = rules.stream()
                .filter(r -> r.sourceTerm().equals("神经网络")).findFirst().orElseThrow();
        assertThat(kept.requiredTranslation()).isEqualTo("neural network");
        assertThat(kept.origin()).isEqualTo(Origin.GLOBAL);
    }

    @Test
    @DisplayName("抑制：suppressed 文档规则取消同键全局规则，不参与校验但保留在生效集中")
    void suppressedCancelsGlobal() {
        List<GlobalTermRuleRow> global = List.of(
                new GlobalTermRuleRow("机器学习", "en", "machine learning"));
        List<TermRuleRow> document = List.of(
                new TermRuleRow("机器学习", "en", "-", true));
        List<EffectiveRule> rules = EffectiveRules.compose(global, document, List.of("en"));

        assertThat(rules).hasSize(1);
        assertThat(rules.get(0).origin()).isEqualTo(Origin.SUPPRESSED);
        assertThat(EffectiveRules.activeRules(rules)).isEmpty();
    }

    @Test
    @DisplayName("语言过滤：文档目标语言之外的全局规则不进入生效集；文档独有规则标记 DOCUMENT")
    void globalRulesFilteredByTargetLanguages() {
        List<GlobalTermRuleRow> global = List.of(
                new GlobalTermRuleRow("机器学习", "fr", "apprentissage"),
                new GlobalTermRuleRow("机器学习", "en", "machine learning"));
        List<TermRuleRow> document = List.of(
                new TermRuleRow("自有术语", "en", "own term", false));
        List<EffectiveRule> rules = EffectiveRules.compose(global, document, List.of("en"));

        assertThat(rules).hasSize(2);
        assertThat(rules).noneMatch(r -> r.language().equals("fr"));
        EffectiveRule own = rules.stream()
                .filter(r -> r.sourceTerm().equals("自有术语")).findFirst().orElseThrow();
        assertThat(own.origin()).isEqualTo(Origin.DOCUMENT);
    }

    @Test
    @DisplayName("排序：按 sourceTerm 与目标语言升序稳定排序，与输入顺序无关")
    void stableSortedBySourceTermAndLanguage() {
        List<GlobalTermRuleRow> global = List.of(
                new GlobalTermRuleRow("神经网络", "en", "neural network"),
                new GlobalTermRuleRow("机器学习", "ja", "機械学習"),
                new GlobalTermRuleRow("机器学习", "en", "machine learning"),
                new GlobalTermRuleRow("云端", "en", "cloud"));
        List<EffectiveRule> rules = EffectiveRules.compose(global, List.of(), List.of("en", "ja"));

        assertThat(rules).extracting(EffectiveRule::sourceTerm)
                .containsExactly("云端", "机器学习", "机器学习", "神经网络");
        assertThat(rules).extracting(EffectiveRule::language)
                .containsExactly("en", "en", "ja", "en");
    }

    @Test
    @DisplayName("空集：无全局引用且无文档规则时生效集为空")
    void emptyWhenNoRules() {
        assertThat(EffectiveRules.compose(List.of(), List.of(), List.of("en"))).isEmpty();
    }
}
