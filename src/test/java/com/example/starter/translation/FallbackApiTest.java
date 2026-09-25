package com.example.starter.translation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 译文回退语种链与缺失段发布拦截测试：配置校验、回退解析、快照固化、缺失诊断与幂等。
 */
class FallbackApiTest extends AbstractIntegrationTest {

    private static final String JA_TO_EN = "[{\"language\":\"ja\",\"fallbackLanguage\":\"en\"}]";
    private static final String CHAIN_FR_JA_EN =
            "[{\"language\":\"fr\",\"fallbackLanguage\":\"ja\"},{\"language\":\"ja\",\"fallbackLanguage\":\"en\"}]";

    @Test
    @DisplayName("回退配置：全量替换成功并查询配置与逐级链；清空配置；未登记语言查询 422")
    void configureAndQueryFallbacks() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\",\"ja\",\"fr\"]", "[]");

        ApiResult updated = updateFallbacks(docId, 1, CHAIN_FR_JA_EN, newRequestId());
        assertThat(updated.status()).isEqualTo(200);
        assertThat(updated.body().get("draftVersion").asInt()).isEqualTo(2);
        assertThat(updated.body().get("fallbacks")).hasSize(2);
        // 按语言稳定排序：fr→ja、ja→en
        assertThat(updated.body().get("fallbacks").get(0).get("language").asText()).isEqualTo("fr");
        assertThat(updated.body().get("fallbacks").get(0).get("fallbackLanguage").asText()).isEqualTo("ja");
        assertThat(updated.body().get("fallbacks").get(1).get("language").asText()).isEqualTo("ja");
        assertThat(updated.body().get("fallbacks").get(1).get("fallbackLanguage").asText()).isEqualTo("en");

        ApiResult config = getJson("/api/documents/" + docId + "/fallbacks");
        assertThat(config.status()).isEqualTo(200);
        assertThat(config.body().get("fallbacks")).hasSize(2);

        // 逐级链解析：fr → ja → en
        ApiResult chain = getJson("/api/documents/" + docId + "/fallbacks/fr");
        assertThat(chain.status()).isEqualTo(200);
        assertThat(chain.body().get("language").asText()).isEqualTo("fr");
        assertThat(chain.body().get("chain").toString()).isEqualTo("[\"fr\",\"ja\",\"en\"]");
        // 无回退配置的语种链仅含自身
        ApiResult selfOnly = getJson("/api/documents/" + docId + "/fallbacks/en");
        assertThat(selfOnly.body().get("chain").toString()).isEqualTo("[\"en\"]");
        // 未登记语种 422；文档不存在 404
        assertThat(getJson("/api/documents/" + docId + "/fallbacks/de").status()).isEqualTo(422);
        assertThat(getJson("/api/documents/999999/fallbacks").status()).isEqualTo(404);

        // 空列表清空全部配置
        ApiResult cleared = updateFallbacks(docId, 2, "[]", newRequestId());
        assertThat(cleared.status()).isEqualTo(200);
        assertThat(cleared.body().get("fallbacks")).isEmpty();
        assertThat(getJson("/api/documents/" + docId + "/fallbacks").body().get("fallbacks")).isEmpty();
        assertThat(getJson("/api/documents/" + docId + "/fallbacks/fr").body().get("chain").toString())
                .isEqualTo("[\"fr\"]");
    }

    @Test
    @DisplayName("回退配置校验：未登记语种/自环/成环/重复 422，期望版本不符 409，失败整次回滚")
    void fallbackConfigValidation() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\",\"ja\"]", "[]");

        // 回退目标未登记
        assertThat(updateFallbacks(docId, 1,
                "[{\"language\":\"en\",\"fallbackLanguage\":\"de\"}]", newRequestId()).status())
                .isEqualTo(422);
        // 配置语言未登记
        assertThat(updateFallbacks(docId, 1,
                "[{\"language\":\"de\",\"fallbackLanguage\":\"en\"}]", newRequestId()).status())
                .isEqualTo(422);
        // 自环
        assertThat(updateFallbacks(docId, 1,
                "[{\"language\":\"en\",\"fallbackLanguage\":\"en\"}]", newRequestId()).status())
                .isEqualTo(422);
        // 成环：en→ja、ja→en
        assertThat(updateFallbacks(docId, 1,
                "[{\"language\":\"en\",\"fallbackLanguage\":\"ja\"},"
                        + "{\"language\":\"ja\",\"fallbackLanguage\":\"en\"}]", newRequestId()).status())
                .isEqualTo(422);
        // 重复配置（语言码归一后相同）
        assertThat(updateFallbacks(docId, 1,
                "[{\"language\":\"en\",\"fallbackLanguage\":\"ja\"},"
                        + "{\"language\":\"EN\",\"fallbackLanguage\":\"ja\"}]", newRequestId()).status())
                .isEqualTo(422);
        // 期望版本不符
        assertThat(updateFallbacks(docId, 9, JA_TO_EN, newRequestId()).status()).isEqualTo(409);

        // 全部失败整次回滚：无配置残留，草稿版本不变
        assertThat(getJson("/api/documents/" + docId + "/fallbacks").body().get("fallbacks")).isEmpty();
        Integer draftVersion = jdbc.queryForObject(
                "SELECT draft_version FROM document WHERE document_id = ?", Integer.class, docId);
        assertThat(draftVersion).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM fallback_config WHERE document_id = ?", Integer.class, docId))
                .isZero();

        // 合法配置成功：部分校验失败不影响后续正确请求
        ApiResult ok = updateFallbacks(docId, 1, JA_TO_EN, newRequestId());
        assertThat(ok.status()).isEqualTo(200);
        assertThat(ok.body().get("draftVersion").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("发布回退解析：缺失语种沿链使用回退译文并固化实际使用语种；直接译文优先；快照幂等重放")
    void publishResolvesFallbackChain() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\",\"ja\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"原文\"}]");
        assertThat(updateFallbacks(docId, 1, JA_TO_EN, newRequestId()).status()).isEqualTo(200);
        submitTranslation(docId, "s1", "en", "alice", "hello", 1, newRequestId());
        approve(docId, "s1", "en", "bob", 1, newRequestId());
        // 当前草稿版本 3、发布版本 0

        // ja 缺译，沿回退链 ja→en 解析成功
        String publishRequestId = newRequestId();
        ApiResult published = publish(docId, 3, 0, publishRequestId);
        assertThat(published.status()).isEqualTo(201);
        assertThat(published.body().get("publishedVersion").asInt()).isEqualTo(1);

        ApiResult release = getJson("/api/documents/" + docId + "/releases/1");
        assertThat(release.status()).isEqualTo(200);
        assertThat(release.body().get("fallbacks").toString()).contains("ja", "en");
        var translations = release.body().get("segments").get(0).get("translations");
        assertThat(translations).hasSize(2);
        // en：直接使用；ja：回退使用 en 译文，固化实际使用语种与译文版本
        assertThat(translations.get(0).get("language").asText()).isEqualTo("en");
        assertThat(translations.get(0).get("usedLanguage").asText()).isEqualTo("en");
        assertThat(translations.get(0).get("fallbackUsed").asBoolean()).isFalse();
        assertThat(translations.get(1).get("language").asText()).isEqualTo("ja");
        assertThat(translations.get(1).get("usedLanguage").asText()).isEqualTo("en");
        assertThat(translations.get(1).get("fallbackUsed").asBoolean()).isTrue();
        assertThat(translations.get(1).get("content").asText()).isEqualTo("hello");
        assertThat(translations.get(1).get("translationVersion").asInt()).isEqualTo(1);

        // 同键同参重放首次完整快照结果，不产生新发布版本
        ApiResult replay = publish(docId, 3, 0, publishRequestId);
        assertThat(replay.status()).isEqualTo(201);
        assertThat(replay.body().get("publishedVersion").asInt()).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_snapshot WHERE document_id = ?", Integer.class, docId))
                .isEqualTo(1);

        // 提交 ja 直接译文后再次发布：直接译文优先于回退译文
        submitTranslation(docId, "s1", "ja", "carol", "こんにちは", 1, newRequestId());
        approve(docId, "s1", "ja", "dave", 1, newRequestId());
        assertThat(publish(docId, 4, 1, newRequestId()).status()).isEqualTo(201);
        ApiResult release2 = getJson("/api/documents/" + docId + "/releases/2");
        var jaEntry = release2.body().get("segments").get(0).get("translations").get(1);
        assertThat(jaEntry.get("usedLanguage").asText()).isEqualTo("ja");
        assertThat(jaEntry.get("fallbackUsed").asBoolean()).isFalse();
        assertThat(jaEntry.get("content").asText()).isEqualTo("こんにちは");

        // 历史快照不被改写：发布版本 1 仍为回退解析结果
        ApiResult release1Again = getJson("/api/documents/" + docId + "/releases/1");
        var jaEntryV1 = release1Again.body().get("segments").get(0).get("translations").get(1);
        assertThat(jaEntryV1.get("usedLanguage").asText()).isEqualTo("en");
        assertThat(jaEntryV1.get("content").asText()).isEqualTo("hello");
    }

    @Test
    @DisplayName("缺失段拦截：回退链全部缺失整次发布 422，稳定排序返回缺失段与已尝试语种，诊断查询一致")
    void publishBlockedWhenChainMissing() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\",\"ja\",\"fr\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"第一段\"},{\"segmentId\":\"s2\",\"sourceText\":\"第二段\"}]");
        assertThat(updateFallbacks(docId, 1, CHAIN_FR_JA_EN, newRequestId()).status()).isEqualTo(200);
        // 仅 s1 的 en 有已批准译文：s1 全链可解析，s2 全链缺失
        submitTranslation(docId, "s1", "en", "alice", "hello", 1, newRequestId());
        approve(docId, "s1", "en", "bob", 1, newRequestId());
        // 当前草稿版本 3

        // 诊断查询：s2 的 en/ja/fr 全部缺失，按 segmentId、语言稳定排序，含已尝试语种
        ApiResult diagnostics = getJson("/api/documents/" + docId + "/publish/missing");
        assertThat(diagnostics.status()).isEqualTo(200);
        assertThat(diagnostics.body().get("missing")).hasSize(3);
        var missing0 = diagnostics.body().get("missing").get(0);
        assertThat(missing0.get("segmentId").asText()).isEqualTo("s2");
        assertThat(missing0.get("language").asText()).isEqualTo("en");
        assertThat(missing0.get("attemptedLanguages").toString()).isEqualTo("[\"en\"]");
        var missing1 = diagnostics.body().get("missing").get(1);
        assertThat(missing1.get("language").asText()).isEqualTo("fr");
        assertThat(missing1.get("attemptedLanguages").toString()).isEqualTo("[\"fr\",\"ja\",\"en\"]");
        var missing2 = diagnostics.body().get("missing").get(2);
        assertThat(missing2.get("language").asText()).isEqualTo("ja");
        assertThat(missing2.get("attemptedLanguages").toString()).isEqualTo("[\"ja\",\"en\"]");

        // 整次发布 422：不得只发布可解析部分
        ApiResult published = publish(docId, 3, 0, newRequestId());
        assertThat(published.status()).isEqualTo(422);
        assertThat(published.body().get("error").asText()).isEqualTo("MISSING_TRANSLATION");
        assertThat(published.body().get("missing")).hasSize(3);
        assertThat(published.body().get("missing").get(1).get("attemptedLanguages").toString())
                .isEqualTo("[\"fr\",\"ja\",\"en\"]");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_snapshot WHERE document_id = ?", Integer.class, docId))
                .isZero();
        Integer publishedVersion = jdbc.queryForObject(
                "SELECT published_version FROM document WHERE document_id = ?", Integer.class, docId);
        assertThat(publishedVersion).isZero();

        // 补齐 s2 的 en 译文后全链可解析，发布成功
        submitTranslation(docId, "s2", "en", "alice", "world", 1, newRequestId());
        approve(docId, "s2", "en", "bob", 1, newRequestId());
        assertThat(getJson("/api/documents/" + docId + "/publish/missing").body().get("missing")).isEmpty();
        assertThat(publish(docId, 4, 0, newRequestId()).status()).isEqualTo(201);
    }

    @Test
    @DisplayName("快照固化：发布后撤回回退语种译文、修改回退配置均不改写历史快照，但拦截后续发布")
    void snapshotFrozenAgainstWithdrawAndConfigChange() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\",\"ja\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"原文\"}]");
        assertThat(updateFallbacks(docId, 1, JA_TO_EN, newRequestId()).status()).isEqualTo(200);
        submitTranslation(docId, "s1", "en", "alice", "hello", 1, newRequestId());
        approve(docId, "s1", "en", "bob", 1, newRequestId());
        assertThat(publish(docId, 3, 0, newRequestId()).status()).isEqualTo(201);

        // 撤回回退来源 en 译文：历史快照不变
        ApiResult withdrawn = withdraw(docId, "s1", "en", newRequestId());
        assertThat(withdrawn.status()).isEqualTo(200);
        assertThat(withdrawn.body().get("draftVersion").asInt()).isEqualTo(4);
        ApiResult release = getJson("/api/documents/" + docId + "/releases/1");
        var jaEntry = release.body().get("segments").get(0).get("translations").get(1);
        assertThat(jaEntry.get("usedLanguage").asText()).isEqualTo("en");
        assertThat(jaEntry.get("content").asText()).isEqualTo("hello");

        // 撤回后回退链整体缺失：后续发布 422，诊断查询可见
        ApiResult republish = publish(docId, 4, 1, newRequestId());
        assertThat(republish.status()).isEqualTo(422);
        assertThat(republish.body().get("error").asText()).isEqualTo("MISSING_TRANSLATION");
        assertThat(getJson("/api/documents/" + docId + "/publish/missing").body().get("missing"))
                .hasSize(2);

        // 修改回退配置不改写历史快照中固化的回退链
        assertThat(updateFallbacks(docId, 4, "[]", newRequestId()).status()).isEqualTo(200);
        ApiResult releaseAgain = getJson("/api/documents/" + docId + "/releases/1");
        assertThat(releaseAgain.body().get("fallbacks").toString()).contains("ja", "en");
        assertThat(releaseAgain.body().get("segments").get(0).get("translations").get(1)
                .get("content").asText()).isEqualTo("hello");
    }

    @Test
    @DisplayName("译文撤回：成功后译文与批准清除；重复撤回或译文不存在 404；撤回失败不占 requestId")
    void withdrawTranslation() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"原文\"}]");
        submitTranslation(docId, "s1", "en", "alice", "hello", 1, newRequestId());
        approve(docId, "s1", "en", "bob", 1, newRequestId());

        ApiResult withdrawn = withdraw(docId, "s1", "en", newRequestId());
        assertThat(withdrawn.status()).isEqualTo(200);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM translation WHERE document_id = ?", Integer.class, docId)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM approval WHERE document_id = ?", Integer.class, docId)).isZero();

        // 重复撤回 404；不存在的段落 404
        assertThat(withdraw(docId, "s1", "en", newRequestId()).status()).isEqualTo(404);
        assertThat(withdraw(docId, "nope", "en", newRequestId()).status()).isEqualTo(404);

        // 撤回后发布缺译 422
        assertThat(publish(docId, 3, 0, newRequestId()).status()).isEqualTo(422);

        // 失败不占键：同一 requestId 先 404 后可成功复用
        String failKey = newRequestId();
        assertThat(withdraw(docId, "s1", "en", failKey).status()).isEqualTo(404);
        submitTranslation(docId, "s1", "en", "alice", "hi", 1, newRequestId());
        assertThat(withdraw(docId, "s1", "en", failKey).status()).isEqualTo(200);
    }

    @Test
    @DisplayName("回退配置幂等：同键同参重放、同键异参 409、失败不占键")
    void fallbackConfigIdempotency() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\",\"ja\"]", "[]");
        String requestId = newRequestId();

        ApiResult first = updateFallbacks(docId, 1, JA_TO_EN, requestId);
        assertThat(first.status()).isEqualTo(200);

        // 同键同参：重放原结果，不重复变更
        ApiResult replay = updateFallbacks(docId, 1, JA_TO_EN, requestId);
        assertThat(replay.status()).isEqualTo(200);
        assertThat(replay.body().get("draftVersion").asInt()).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM fallback_config WHERE document_id = ?", Integer.class, docId))
                .isEqualTo(1);
        Integer draftVersion = jdbc.queryForObject(
                "SELECT draft_version FROM document WHERE document_id = ?", Integer.class, docId);
        assertThat(draftVersion).isEqualTo(2);

        // 同键异参：409
        assertThat(updateFallbacks(docId, 1, "[]", requestId).status()).isEqualTo(409);

        // 失败不占键：先以某 requestId 触发 422（成环），再用同键修正参数后成功
        String failKey = newRequestId();
        assertThat(updateFallbacks(docId, 2,
                "[{\"language\":\"en\",\"fallbackLanguage\":\"ja\"},"
                        + "{\"language\":\"ja\",\"fallbackLanguage\":\"en\"}]", failKey).status())
                .isEqualTo(422);
        ApiResult retried = updateFallbacks(docId, 2,
                "[{\"language\":\"en\",\"fallbackLanguage\":\"ja\"}]", failKey);
        assertThat(retried.status()).isEqualTo(200);
        assertThat(retried.body().get("draftVersion").asInt()).isEqualTo(3);
    }
}
