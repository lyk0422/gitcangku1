package com.example.starter.translation;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 生效规则集测试：全局与文档规则合成、整条覆盖、suppressed 抑制、
 * 译文提交与发布校验基于生效规则集且违规返回来源。
 */
class EffectiveTermsApiTest extends AbstractIntegrationTest {

    private static final String GLOBAL_RULES =
            "[{\"sourceTerm\":\"机器学习\",\"language\":\"en\",\"requiredTranslation\":\"machine learning\"},"
                    + "{\"sourceTerm\":\"神经网络\",\"language\":\"en\",\"requiredTranslation\":\"neural network\"},"
                    + "{\"sourceTerm\":\"深度学习\",\"language\":\"en\",\"requiredTranslation\":\"deep learning\"}]";

    /** 建文档并把全局引用升级到最新版本，返回 documentId。 */
    private long createDocumentWithGlossary(String sourceText, String globalRules) throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"" + sourceText + "\"}]");
        assertThat(updateGlobalTerms(0, globalRules, newRequestId()).status()).isEqualTo(201);
        ApiResult upgrade = upgradeGlossary(docId, 0, 1, newRequestId());
        assertThat(upgrade.status()).isEqualTo(200);
        assertThat(upgrade.body().get("globalTermVersion").asInt()).isEqualTo(1);
        assertThat(upgrade.body().get("draftVersion").asInt()).isEqualTo(2);
        return docId;
    }

    @Test
    @DisplayName("生效规则集合成：文档规则整条覆盖全局规则，suppressed 取消全局规则，逐条标明来源并稳定排序")
    void effectiveRulesComposition() throws Exception {
        long docId = createDocumentWithGlossary("机器学习与神经网络与深度学习", GLOBAL_RULES);

        // 文档术语版本：覆盖"机器学习"、抑制"神经网络"，另含文档独有规则
        ApiResult terms = updateTerms(docId, 0,
                "[{\"sourceTerm\":\"机器学习\",\"language\":\"en\",\"requiredTranslation\":\"ML\"},"
                        + "{\"sourceTerm\":\"神经网络\",\"language\":\"en\",\"suppressed\":true},"
                        + "{\"sourceTerm\":\"云计算\",\"language\":\"en\",\"requiredTranslation\":\"cloud computing\"}]",
                newRequestId());
        assertThat(terms.status()).isEqualTo(201);

        ApiResult effective = getJson("/api/documents/" + docId + "/terms/effective");
        assertThat(effective.status()).isEqualTo(200);
        assertThat(effective.body().get("termVersion").asInt()).isEqualTo(1);
        assertThat(effective.body().get("globalTermVersion").asInt()).isEqualTo(1);
        JsonNode rules = effective.body().get("rules");
        assertThat(rules).hasSize(4);
        // 按 sourceTerm 升序（Unicode 码点序）：云计算 < 机器学习 < 深度学习 < 神经网络
        assertThat(rules.get(0).get("sourceTerm").asText()).isEqualTo("云计算");
        assertThat(rules.get(0).get("source").asText()).isEqualTo("DOCUMENT");
        // 文档规则整条覆盖全局规则
        assertThat(rules.get(1).get("sourceTerm").asText()).isEqualTo("机器学习");
        assertThat(rules.get(1).get("source").asText()).isEqualTo("DOCUMENT");
        assertThat(rules.get(1).get("requiredTranslation").asText()).isEqualTo("ML");
        assertThat(rules.get(2).get("sourceTerm").asText()).isEqualTo("深度学习");
        assertThat(rules.get(2).get("source").asText()).isEqualTo("GLOBAL");
        assertThat(rules.get(2).get("requiredTranslation").asText()).isEqualTo("deep learning");
        // suppressed 取消对应全局规则
        assertThat(rules.get(3).get("sourceTerm").asText()).isEqualTo("神经网络");
        assertThat(rules.get(3).get("source").asText()).isEqualTo("SUPPRESSED");
        assertThat(rules.get(3).get("requiredTranslation").isNull()).isTrue();
    }

    @Test
    @DisplayName("译文提交基于生效规则集：覆盖后按文档规则校验，被抑制术语不参与校验，违规 422 含来源")
    void submitTranslationUsesEffectiveRules() throws Exception {
        long docId = createDocumentWithGlossary("机器学习与神经网络与深度学习", GLOBAL_RULES);
        updateTerms(docId, 0,
                "[{\"sourceTerm\":\"机器学习\",\"language\":\"en\",\"requiredTranslation\":\"ML\"},"
                        + "{\"sourceTerm\":\"神经网络\",\"language\":\"en\",\"suppressed\":true}]",
                newRequestId());

        // 被抑制的"神经网络"不再参与校验；覆盖后按文档规则要求 "ML"；全局"深度学习"仍生效
        ApiResult violated = submitTranslation(docId, "s1", "en", "alice", "hello", 1, newRequestId());
        assertThat(violated.status()).isEqualTo(422);
        assertThat(violated.body().get("error").asText()).isEqualTo("TERM_VIOLATION");
        JsonNode violations = violated.body().get("violations");
        assertThat(violations).hasSize(2);
        assertThat(violations.get(0).get("sourceTerm").asText()).isEqualTo("机器学习");
        assertThat(violations.get(0).get("source").asText()).isEqualTo("DOCUMENT");
        assertThat(violations.get(0).get("requiredTranslation").asText()).isEqualTo("ML");
        assertThat(violations.get(1).get("sourceTerm").asText()).isEqualTo("深度学习");
        assertThat(violations.get(1).get("source").asText()).isEqualTo("GLOBAL");
        assertThat(violations.toString()).doesNotContain("神经网络");

        // 满足覆盖后的文档规则与全局规则即可提交（无需被抑制术语的必译文本）
        ApiResult ok = submitTranslation(docId, "s1", "en", "alice", "ML and deep learning", 1, newRequestId());
        assertThat(ok.status()).isEqualTo(200);
        assertThat(ok.body().get("globalTermVersion").asInt()).isEqualTo(1);
        assertThat(ok.body().get("termVersion").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("文档术语输入校验：suppressed 携带必译文本 422；非 suppressed 空必译 400；suppressed 规则入库可查")
    void documentTermInputValidation() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]", "[]");

        ApiResult suppressedWithTranslation = updateTerms(docId, 0,
                "[{\"sourceTerm\":\"机器学习\",\"language\":\"en\",\"requiredTranslation\":\"ML\","
                        + "\"suppressed\":true}]", newRequestId());
        assertThat(suppressedWithTranslation.status()).isEqualTo(422);

        assertThat(updateTerms(docId, 0,
                "[{\"sourceTerm\":\"机器学习\",\"language\":\"en\"}]", newRequestId()).status())
                .isEqualTo(400);
        assertThat(updateTerms(docId, 0,
                "[{\"sourceTerm\":\"机器学习\",\"language\":\"en\",\"requiredTranslation\":\"\"}]",
                newRequestId()).status()).isEqualTo(400);

        ApiResult created = updateTerms(docId, 0,
                "[{\"sourceTerm\":\"机器学习\",\"language\":\"en\",\"suppressed\":true}]", newRequestId());
        assertThat(created.status()).isEqualTo(201);
        ApiResult current = getJson("/api/documents/" + docId + "/terms");
        JsonNode rule = current.body().get("rules").get(0);
        assertThat(rule.get("suppressed").asBoolean()).isTrue();
        assertThat(rule.get("requiredTranslation").isNull()).isTrue();
    }

    @Test
    @DisplayName("发布校验基于生效规则集：发布侧同样按覆盖与抑制后的规则拦截违规译文")
    void publishUsesEffectiveRules() throws Exception {
        long docId = createDocumentWithGlossary("机器学习与神经网络", GLOBAL_RULES);
        updateTerms(docId, 0,
                "[{\"sourceTerm\":\"机器学习\",\"language\":\"en\",\"requiredTranslation\":\"ML\"},"
                        + "{\"sourceTerm\":\"神经网络\",\"language\":\"en\",\"suppressed\":true}]",
                newRequestId());
        submitTranslation(docId, "s1", "en", "alice", "ML", 1, newRequestId());
        approve(docId, "s1", "en", "bob", 1, newRequestId());
        // 当前草稿版本 4

        ApiResult published = publish(docId, 4, 0, newRequestId());
        assertThat(published.status()).isEqualTo(201);

        // 快照固化两个术语版本与生效规则集（含来源）
        ApiResult release = getJson("/api/documents/" + docId + "/releases/1");
        assertThat(release.status()).isEqualTo(200);
        assertThat(release.body().get("termVersion").asInt()).isEqualTo(1);
        assertThat(release.body().get("globalTermVersion").asInt()).isEqualTo(1);
        JsonNode terms = release.body().get("terms");
        assertThat(terms).hasSize(3);
        assertThat(terms.get(0).get("sourceTerm").asText()).isEqualTo("机器学习");
        assertThat(terms.get(0).get("source").asText()).isEqualTo("DOCUMENT");
        assertThat(terms.get(1).get("sourceTerm").asText()).isEqualTo("深度学习");
        assertThat(terms.get(1).get("source").asText()).isEqualTo("GLOBAL");
        assertThat(terms.get(2).get("sourceTerm").asText()).isEqualTo("神经网络");
        assertThat(terms.get(2).get("source").asText()).isEqualTo("SUPPRESSED");
        JsonNode translation = release.body().get("segments").get(0).get("translations").get(0);
        assertThat(translation.get("globalTermVersion").asInt()).isEqualTo(1);
    }
}
