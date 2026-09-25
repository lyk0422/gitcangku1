package com.example.starter.translation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 语种回退链与缺失段发布拦截的真实 H2 数据库测试：
 * 覆盖链配置校验/成环回滚、段落逐级解析、直接译文优先、快照固化实际语种与版本、
 * 撤回不改写历史快照、缺失段稳定诊断与写操作幂等。
 */
class FallbackApiTest extends AbstractIntegrationTest {

    @Test
    @DisplayName("回退配置：成功建链与查询；未登记语种/自环 422；expectedVersion 冲突 409")
    void configureFallbackAndQueryChains() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\",\"ja\",\"fr\"]", "[]");

        ApiResult ja = configureFallback(docId, "ja", 1, "en", newRequestId());
        assertThat(ja.status()).isEqualTo(200);
        assertThat(ja.body().get("fallbackLanguage").asText()).isEqualTo("en");
        assertThat(ja.body().get("draftVersion").asInt()).isEqualTo(2);

        ApiResult fr = configureFallback(docId, "fr", 2, "ja", newRequestId());
        assertThat(fr.status()).isEqualTo(200);
        assertThat(fr.body().get("draftVersion").asInt()).isEqualTo(3);

        ApiResult chains = getJson("/api/documents/" + docId + "/fallbacks");
        assertThat(chains.status()).isEqualTo(200);
        assertThat(chains.body().get("chains").get(0).get("language").asText()).isEqualTo("en");
        assertThat(chains.body().get("chains").get(0).get("chain").toString()).isEqualTo("[\"en\"]");
        assertThat(chains.body().get("chains").get(1).get("chain").toString()).isEqualTo("[\"ja\",\"en\"]");
        assertThat(chains.body().get("chains").get(2).get("chain").toString()).isEqualTo("[\"fr\",\"ja\",\"en\"]");

        assertThat(configureFallback(docId, "en", 3, "en", newRequestId()).status()).isEqualTo(422);
        assertThat(configureFallback(docId, "en", 3, "zz", newRequestId()).status()).isEqualTo(422);
        assertThat(configureFallback(docId, "zz", 3, "en", newRequestId()).status()).isEqualTo(422);
        assertThat(configureFallback(docId, "ja", 99, "en", newRequestId()).status()).isEqualTo(409);

        // 成环配置整次回滚：ja→en、fr→ja 已存在，改 ja→fr 形成 ja→fr→ja
        ApiResult cyclic = configureFallback(docId, "ja", 3, "fr", newRequestId());
        assertThat(cyclic.status()).isEqualTo(422);
        ApiResult afterRollback = getJson("/api/documents/" + docId + "/fallbacks");
        assertThat(afterRollback.body().get("chains").get(1).get("chain").toString())
                .isEqualTo("[\"ja\",\"en\"]");
        assertThat(jdbc.queryForObject(
                "SELECT fallback_language FROM locale_fallback WHERE document_id = ? AND language = 'ja'",
                String.class, docId)).isEqualTo("en");
        assertThat(jdbc.queryForObject(
                "SELECT draft_version FROM document WHERE document_id = ?", Integer.class, docId)).isEqualTo(3);

        // 清除回退：fr 链恢复单节点，草稿版本加一
        ApiResult cleared = configureFallback(docId, "fr", 3, null, newRequestId());
        assertThat(cleared.status()).isEqualTo(200);
        assertThat(cleared.body().get("fallbackLanguage").isNull());
        assertThat(getJson("/api/documents/" + docId + "/fallbacks").body().get("chains").get(2)
                .get("chain").toString()).isEqualTo("[\"fr\"]");
    }

    @Test
    @DisplayName("段落解析：直接译文优先；缺失时逐级使用回退译文并在快照固化实际语种与译文版本")
    void publishResolvesAlongFallbackChain() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\",\"ja\",\"fr\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"原文1\"},{\"segmentId\":\"s2\",\"sourceText\":\"原文2\"}]");
        configureFallback(docId, "ja", 1, "en", newRequestId());
        configureFallback(docId, "fr", 2, "ja", newRequestId());
        // 草稿版本 3
        submitTranslation(docId, "s1", "en", "alice", "s1-en-v1", 1, newRequestId()); // draft 4
        approve(docId, "s1", "en", "bob", 1, newRequestId());
        submitTranslation(docId, "s2", "en", "alice", "s2-en-v1", 1, newRequestId()); // draft 5
        approve(docId, "s2", "en", "bob", 1, newRequestId());
        submitTranslation(docId, "s2", "ja", "carol", "s2-ja-v1", 1, newRequestId()); // draft 6
        approve(docId, "s2", "ja", "dave", 1, newRequestId());

        ApiResult published = publish(docId, 6, 0, newRequestId());
        assertThat(published.status()).isEqualTo(201);

        ApiResult release = getJson("/api/documents/" + docId + "/releases/1");
        assertThat(release.status()).isEqualTo(200);
        assertThat(release.body().get("publishedLanguages").toString())
                .isEqualTo("[\"en\",\"ja\",\"fr\"]");
        var s1 = release.body().get("segments").get(0);
        assertThat(s1.get("segmentId").asText()).isEqualTo("s1");
        // s1 仅 en 有已批准译文：ja、fr 均逐级回退到 en
        assertThat(s1.get("translations").get(0).get("usedLanguage").asText()).isEqualTo("en");
        assertThat(s1.get("translations").get(0).get("content").asText()).isEqualTo("s1-en-v1");
        assertThat(s1.get("translations").get(1).get("language").asText()).isEqualTo("ja");
        assertThat(s1.get("translations").get(1).get("usedLanguage").asText()).isEqualTo("en");
        assertThat(s1.get("translations").get(1).get("translationVersion").asInt()).isEqualTo(1);
        assertThat(s1.get("translations").get(1).get("reviewer").asText()).isEqualTo("bob");
        assertThat(s1.get("translations").get(2).get("usedLanguage").asText()).isEqualTo("en");
        // s2：en 直接、ja 直接（优先于 en）、fr 经 ja 回退
        var s2 = release.body().get("segments").get(1);
        assertThat(s2.get("translations").get(0).get("usedLanguage").asText()).isEqualTo("en");
        assertThat(s2.get("translations").get(1).get("usedLanguage").asText()).isEqualTo("ja");
        assertThat(s2.get("translations").get(1).get("content").asText()).isEqualTo("s2-ja-v1");
        assertThat(s2.get("translations").get(2).get("language").asText()).isEqualTo("fr");
        assertThat(s2.get("translations").get(2).get("usedLanguage").asText()).isEqualTo("ja");

        // 快照固化发布时回退链
        assertThat(release.body().get("fallbackChains").get(2).get("chain").toString())
                .isEqualTo("[\"fr\",\"ja\",\"en\"]");
    }

    @Test
    @DisplayName("发布拦截：回退链全部缺失整次 422 并稳定排序返回缺失段与已尝试语种，不产生部分快照")
    void publishBlockedByMissingSegments() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\",\"ja\"]",
                "[{\"segmentId\":\"s2\",\"sourceText\":\"原文2\"},{\"segmentId\":\"s1\",\"sourceText\":\"原文1\"}]");
        configureFallback(docId, "ja", 1, "en", newRequestId());
        // s1 仅 en 已批准：s1 直接与回退均可解析；s2 两种语种都缺失
        submitTranslation(docId, "s1", "en", "alice", "s1-en", 1, newRequestId());
        approve(docId, "s1", "en", "bob", 1, newRequestId());
        // 草稿版本 3

        ApiResult blocked = publish(docId, 3, 0, newRequestId());
        assertThat(blocked.status()).isEqualTo(422);
        assertThat(blocked.body().get("error").asText()).isEqualTo("MISSING_SEGMENTS");
        assertThat(blocked.body().get("missing")).hasSize(2);
        assertThat(blocked.body().get("missing").get(0).get("segmentId").asText()).isEqualTo("s2");
        assertThat(blocked.body().get("missing").get(0).get("language").asText()).isEqualTo("en");
        assertThat(blocked.body().get("missing").get(0).get("attemptedLanguages").toString())
                .isEqualTo("[\"en\"]");
        assertThat(blocked.body().get("missing").get(1).get("segmentId").asText()).isEqualTo("s2");
        assertThat(blocked.body().get("missing").get(1).get("language").asText()).isEqualTo("ja");
        assertThat(blocked.body().get("missing").get(1).get("attemptedLanguages").toString())
                .isEqualTo("[\"ja\",\"en\"]");

        // 诊断查询返回相同结果；失败不产生部分快照、不递增发布版本
        ApiResult diagnostic = getJson("/api/documents/" + docId + "/missing-segments");
        assertThat(diagnostic.status()).isEqualTo(200);
        assertThat(diagnostic.body().get("missing")).hasSize(2);
        assertThat(diagnostic.body().get("missing").get(1).get("attemptedLanguages").toString())
                .isEqualTo("[\"ja\",\"en\"]");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_snapshot WHERE document_id = ?", Integer.class, docId)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT published_version FROM document WHERE document_id = ?", Integer.class, docId)).isZero();

        // 补齐 s2/en 批准后发布成功，ja 的 s2 经回退解析
        submitTranslation(docId, "s2", "en", "alice", "s2-en", 1, newRequestId());
        approve(docId, "s2", "en", "bob", 1, newRequestId());
        assertThat(publish(docId, 4, 0, newRequestId()).status()).isEqualTo(201);
        assertThat(getJson("/api/documents/" + docId + "/missing-segments").body().get("missing")).isEmpty();
    }

    @Test
    @DisplayName("单语种发布：只发布指定语种，缺失同样拦截；未登记语种 422")
    void publishSingleLanguage() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\",\"ja\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"原文\"}]");
        configureFallback(docId, "ja", 1, "en", newRequestId());
        // 两种语种均无译文：单发布 ja 也 422
        assertThat(publishLanguage(docId, 2, 0, "ja", newRequestId()).status()).isEqualTo(422);
        assertThat(publishLanguage(docId, 2, 0, "zz", newRequestId()).status()).isEqualTo(422);

        submitTranslation(docId, "s1", "en", "alice", "s1-en", 1, newRequestId());
        approve(docId, "s1", "en", "bob", 1, newRequestId());
        // 草稿版本 3；仅发布 ja：经 en 回退成功
        ApiResult published = publishLanguage(docId, 3, 0, "ja", newRequestId());
        assertThat(published.status()).isEqualTo(201);
        ApiResult release = getJson("/api/documents/" + docId + "/releases/1");
        assertThat(release.body().get("publishedLanguages").toString()).isEqualTo("[\"ja\"]");
        assertThat(release.body().get("segments").get(0).get("translations")).hasSize(1);
        assertThat(release.body().get("segments").get(0).get("translations").get(0)
                .get("usedLanguage").asText()).isEqualTo("en");
    }

    @Test
    @DisplayName("撤回：删除批准且不影响历史快照；撤回使后续发布改走回退或判缺失；无批准撤回 404")
    void withdrawDoesNotRewriteSnapshot() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\",\"ja\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"原文\"}]");
        configureFallback(docId, "ja", 1, "en", newRequestId());
        submitTranslation(docId, "s1", "en", "alice", "en-v1", 1, newRequestId());
        approve(docId, "s1", "en", "bob", 1, newRequestId());
        submitTranslation(docId, "s1", "ja", "carol", "ja-v1", 1, newRequestId());
        approve(docId, "s1", "ja", "dave", 1, newRequestId());
        // 草稿版本 4
        assertThat(publish(docId, 4, 0, newRequestId()).status()).isEqualTo(201);

        // 撤回 ja 批准：历史快照仍固化直接 ja 译文
        ApiResult withdrawn = withdraw(docId, "s1", "ja", "dave", newRequestId());
        assertThat(withdrawn.status()).isEqualTo(200);
        ApiResult release1 = getJson("/api/documents/" + docId + "/releases/1");
        assertThat(release1.body().get("segments").get(0).get("translations").get(1)
                .get("usedLanguage").asText()).isEqualTo("ja");
        assertThat(release1.body().get("segments").get(0).get("translations").get(1)
                .get("content").asText()).isEqualTo("ja-v1");

        // 重复撤回 404
        assertThat(withdraw(docId, "s1", "ja", "dave", newRequestId()).status()).isEqualTo(404);

        // 再次发布：ja 改走 en 回退，新快照 usedLanguage=en；发布版本递增
        ApiResult second = publish(docId, 4, 1, newRequestId());
        assertThat(second.status()).isEqualTo(201);
        assertThat(second.body().get("publishedVersion").asInt()).isEqualTo(2);
        ApiResult release2 = getJson("/api/documents/" + docId + "/releases/2");
        assertThat(release2.body().get("segments").get(0).get("translations").get(1)
                .get("usedLanguage").asText()).isEqualTo("en");
        assertThat(release2.body().get("segments").get(0).get("translations").get(1)
                .get("content").asText()).isEqualTo("en-v1");

        // 撤回 en 批准：回退链全部缺失，发布 422
        withdraw(docId, "s1", "en", "bob", newRequestId());
        ApiResult blocked = publish(docId, 4, 2, newRequestId());
        assertThat(blocked.status()).isEqualTo(422);
        assertThat(blocked.body().get("missing").get(0).get("attemptedLanguages").toString())
                .isEqualTo("[\"en\"]");
        assertThat(blocked.body().get("missing").get(1).get("attemptedLanguages").toString())
                .isEqualTo("[\"ja\",\"en\"]");
    }

    @Test
    @DisplayName("回退配置幂等：同键同参重放且草稿版本只加一；同键异参 409；成环失败不占键")
    void fallbackConfigIdempotency() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\",\"ja\"]", "[]");
        String requestId = newRequestId();

        ApiResult first = configureFallback(docId, "ja", 1, "en", requestId);
        assertThat(first.status()).isEqualTo(200);
        ApiResult replay = configureFallback(docId, "ja", 1, "en", requestId);
        assertThat(replay.status()).isEqualTo(200);
        assertThat(replay.body().get("draftVersion").asInt()).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM locale_fallback WHERE document_id = ?", Integer.class, docId))
                .isEqualTo(1);

        assertThat(configureFallback(docId, "ja", 1, null, requestId).status()).isEqualTo(409);

        // 成环失败不占键：同键先尝试 en→ja（与已有 ja→en 成环）422，再改为清除 en 回退成功
        String failKey = newRequestId();
        assertThat(configureFallback(docId, "en", 2, "ja", failKey).status()).isEqualTo(422);
        ApiResult retried = configureFallback(docId, "en", 2, null, failKey);
        assertThat(retried.status()).isEqualTo(200);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM locale_fallback WHERE document_id = ?", Integer.class, docId))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("回退译文术语过期：回退源译文绑定旧术语版本时不可用，沿链继续查找或判缺失")
    void fallbackTranslationTermStale() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\",\"ja\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"机器学习\"}]");
        configureFallback(docId, "ja", 1, "en", newRequestId());
        // 先在术语版本 0 提交并批准 en，再建立术语版本 1：en 译文术语过期
        submitTranslation(docId, "s1", "en", "alice", "old", 1, newRequestId());
        approve(docId, "s1", "en", "bob", 1, newRequestId());
        updateTerms(docId, 0,
                "[{\"sourceTerm\":\"机器学习\",\"language\":\"en\",\"requiredTranslation\":\"machine learning\"}]",
                newRequestId());
        // 当前草稿版本 4、术语版本 1；ja 回退到的 en 译文过期，发布判缺失
        ApiResult blocked = publish(docId, 4, 0, newRequestId());
        assertThat(blocked.status()).isEqualTo(422);
        assertThat(blocked.body().get("error").asText()).isEqualTo("MISSING_SEGMENTS");
        assertThat(blocked.body().get("missing").get(1).get("attemptedLanguages").toString())
                .isEqualTo("[\"ja\",\"en\"]");

        // 用当前术语版本重新提交并批准后，ja 经 en 回退发布成功
        submitTranslation(docId, "s1", "en", "alice", "machine learning", 1, newRequestId());
        approve(docId, "s1", "en", "bob", 2, newRequestId());
        assertThat(publish(docId, 5, 0, newRequestId()).status()).isEqualTo(201);
    }
}
