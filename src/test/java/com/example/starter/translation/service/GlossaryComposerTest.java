package com.example.starter.translation.service;

import com.example.starter.translation.domain.Rows.GlobalTermRuleRow;
import com.example.starter.translation.domain.Rows.TermRuleRow;
import com.example.starter.translation.service.GlossaryComposer.EffectiveRule;
import com.example.starter.translation.service.GlossaryComposer.Source;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 生效规则集合成纯逻辑单元测试：覆盖全局直出、文档整条覆盖、抑制、语言过滤与稳定排序。
 */
class GlossaryComposerTest {

    private GlobalTermRuleRow global(String term, String language, String required) {
        return new GlobalTermRuleRow(1, term, language, required);
    }

    @Test
    @DisplayName("无文档规则：全局规则以 GLOBAL 直出，按 sourceTerm+语言升序稳定排序")
    void globalRulesPassThroughSorted() {
        List<EffectiveRule> effective = GlossaryComposer.compose(
                List.of(global("云", "en", "cloud"), global("机器学习", "en", "machine learning"),
                        global("机器学习", "ja", "機械学習")),
                List.of(), Set.of("en", "ja"));

        assertThat(effective).hasSize(3);
        assertThat(effective).extracting(EffectiveRule::source).containsOnly(Source.GLOBAL);
        assertThat(effective.get(0).sourceTerm()).isEqualTo("云");
        assertThat(effective.get(1)).satisfies(rule -> {
            assertThat(rule.sourceTerm()).isEqualTo("机器学习");
            assertThat(rule.language()).isEqualTo("en");
        });
        assertThat(effective.get(2).language()).isEqualTo("ja");
    }

    @Test
    @DisplayName("文档规则整条覆盖同 sourceTerm+语言的全局规则，必译文本以文档为准且来源为 DOCUMENT")
    void documentRuleOverridesGlobal() {
        List<EffectiveRule> effective = GlossaryComposer.compose(
                List.of(global("机器学习", "en", "machine learning")),
                List.of(new TermRuleRow("机器学习", "en", "ML", false)),
                Set.of("en"));

        assertThat(effective).hasSize(1);
        EffectiveRule rule = effective.get(0);
        assertThat(rule.source()).isEqualTo(Source.DOCUMENT);
        assertThat(rule.requiredTranslation()).isEqualTo("ML");
    }

    @Test
    @DisplayName("suppressed 文档规则取消对应全局规则：生效集保留 SUPPRESSED 条目且不携带必译文本")
    void suppressedHidesGlobalRule() {
        List<EffectiveRule> effective = GlossaryComposer.compose(
                List.of(global("机器学习", "en", "machine learning"),
                        global("神经网络", "en", "neural network")),
                List.of(new TermRuleRow("机器学习", "en", null, true)),
                Set.of("en"));

        assertThat(effective).hasSize(2);
        EffectiveRule suppressed = effective.get(0);
        assertThat(suppressed.sourceTerm()).isEqualTo("机器学习");
        assertThat(suppressed.source()).isEqualTo(Source.SUPPRESSED);
        assertThat(suppressed.requiredTranslation()).isNull();
        assertThat(effective.get(1).source()).isEqualTo(Source.GLOBAL);
        assertThat(effective.get(1).requiredTranslation()).isEqualTo("neural network");
    }

    @Test
    @DisplayName("文档独有规则以 DOCUMENT 加入；无对应全局规则的 suppressed 声明仍标明 SUPPRESSED")
    void documentOnlyAndDanglingSuppress() {
        List<EffectiveRule> effective = GlossaryComposer.compose(
                List.of(global("云", "en", "cloud")),
                List.of(new TermRuleRow("区块链", "en", "blockchain", false),
                        new TermRuleRow("量子", "en", null, true)),
                Set.of("en"));

        assertThat(effective).hasSize(3);
        assertThat(effective).extracting(EffectiveRule::sourceTerm)
                .containsExactly("云", "区块链", "量子");
        assertThat(effective).extracting(EffectiveRule::source)
                .containsExactly(Source.GLOBAL, Source.DOCUMENT, Source.SUPPRESSED);
    }

    @Test
    @DisplayName("合成仅保留文档目标语言内的规则")
    void filtersByTargetLanguages() {
        List<EffectiveRule> effective = GlossaryComposer.compose(
                List.of(global("云", "en", "cloud"), global("云", "ja", "クラウド")),
                List.of(new TermRuleRow("云", "fr", "nuage", false)),
                Set.of("en"));

        assertThat(effective).hasSize(1);
        assertThat(effective.get(0).language()).isEqualTo("en");
        assertThat(effective.get(0).source()).isEqualTo(Source.GLOBAL);
    }
}
