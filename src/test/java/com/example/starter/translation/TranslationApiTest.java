package com.example.starter.translation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 多语种段落发布 API 主流程与失败分支测试。
 */
class TranslationApiTest extends AbstractIntegrationTest {

    @Test
    @DisplayName("建文档：201，初始草稿版本 1、发布版本 0，语言码归一为小写")
    void createDocumentSuccess() throws Exception {
        ApiResult result = postJson("/api/documents",
                "{\"requestId\":\"" + newRequestId() + "\",\"targetLanguages\":[\"EN\",\"ja\"],"
                        + "\"segments\":[{\"segmentId\":\"s1\",\"sourceText\":\"你好世界\"}]}");
        assertThat(result.status()).isEqualTo(201);
        assertThat(result.body().get("documentId").asLong()).isPositive();
        assertThat(result.body().get("draftVersion").asInt()).isEqualTo(1);
        assertThat(result.body().get("publishedVersion").asInt()).isEqualTo(0);
        assertThat(result.body().get("targetLanguages").toString()).contains("en", "ja");
    }

    @Test
    @DisplayName("建文档：目标语言为 0 或 6 种时返回 400")
    void createDocumentInvalidLanguageCount() throws Exception {
        ApiResult zero = postJson("/api/documents",
                "{\"requestId\":\"" + newRequestId() + "\",\"targetLanguages\":[],\"segments\":[]}");
        assertThat(zero.status()).isEqualTo(400);
        ApiResult six = postJson("/api/documents",
                "{\"requestId\":\"" + newRequestId() + "\",\"targetLanguages\":"
                        + "[\"en\",\"ja\",\"fr\",\"de\",\"es\",\"it\"],\"segments\":[]}");
        assertThat(six.status()).isEqualTo(400);
    }

    @Test
    @DisplayName("建文档：目标语言重复返回 422，初始段落 segmentId 重复返回 422")
    void createDocumentDuplicates() throws Exception {
        ApiResult dupLang = postJson("/api/documents",
                "{\"requestId\":\"" + newRequestId() + "\",\"targetLanguages\":[\"en\",\"EN\"],\"segments\":[]}");
        assertThat(dupLang.status()).isEqualTo(422);
        ApiResult dupSegment = postJson("/api/documents",
                "{\"requestId\":\"" + newRequestId() + "\",\"targetLanguages\":[\"en\"],"
                        + "\"segments\":[{\"segmentId\":\"s1\",\"sourceText\":\"a\"},"
                        + "{\"segmentId\":\"s1\",\"sourceText\":\"b\"}]}");
        assertThat(dupSegment.status()).isEqualTo(422);
    }

    @Test
    @DisplayName("增加段落：草稿版本加一；重复 segmentId 返回 409")
    void addSegmentAndConflict() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]", "[]");
        ApiResult added = postJson("/api/documents/" + docId + "/segments",
                "{\"requestId\":\"" + newRequestId() + "\",\"segmentId\":\"s1\",\"sourceText\":\"第一段\"}");
        assertThat(added.status()).isEqualTo(201);
        assertThat(added.body().get("sourceVersion").asInt()).isEqualTo(1);
        assertThat(added.body().get("draftVersion").asInt()).isEqualTo(2);

        ApiResult duplicate = postJson("/api/documents/" + docId + "/segments",
                "{\"requestId\":\"" + newRequestId() + "\",\"segmentId\":\"s1\",\"sourceText\":\"重复\"}");
        assertThat(duplicate.status()).isEqualTo(409);
    }

    @Test
    @DisplayName("源文修订：源文版本与草稿版本加一；段落不存在返回 404")
    void reviseSource() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"原文\"}]");
        ApiResult revised = putJson("/api/documents/" + docId + "/segments/s1/source",
                "{\"requestId\":\"" + newRequestId() + "\",\"sourceText\":\"修订后\"}");
        assertThat(revised.status()).isEqualTo(200);
        assertThat(revised.body().get("sourceVersion").asInt()).isEqualTo(2);
        assertThat(revised.body().get("draftVersion").asInt()).isEqualTo(2);

        ApiResult missing = putJson("/api/documents/" + docId + "/segments/nope/source",
                "{\"requestId\":\"" + newRequestId() + "\",\"sourceText\":\"x\"}");
        assertThat(missing.status()).isEqualTo(404);
    }

    @Test
    @DisplayName("译文提交：成功；源文版本不匹配 422；语言不在目标语言 422；缺少 X-Actor-Id 400")
    void submitTranslation() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"原文\"}]");
        ApiResult ok = submitTranslation(docId, "s1", "en", "alice", "hello", 1, newRequestId());
        assertThat(ok.status()).isEqualTo(200);
        assertThat(ok.body().get("translationVersion").asInt()).isEqualTo(1);
        assertThat(ok.body().get("sourceVersion").asInt()).isEqualTo(1);
        assertThat(ok.body().get("draftVersion").asInt()).isEqualTo(2);

        ApiResult stale = submitTranslation(docId, "s1", "en", "alice", "hi", 99, newRequestId());
        assertThat(stale.status()).isEqualTo(422);

        ApiResult wrongLang = submitTranslation(docId, "s1", "fr", "alice", "bonjour", 1, newRequestId());
        assertThat(wrongLang.status()).isEqualTo(422);

        String body = "{\"requestId\":\"" + newRequestId() + "\",\"content\":\"hi\",\"sourceVersion\":1}";
        ApiResult noActor = putJson("/api/documents/" + docId + "/segments/s1/translations/en", body);
        assertThat(noActor.status()).isEqualTo(400);
    }

    @Test
    @DisplayName("译文批准：作者不能审核自己的译文 422；译文版本不匹配 422；成功批准")
    void approveTranslation() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"原文\"}]");
        submitTranslation(docId, "s1", "en", "alice", "hello", 1, newRequestId());

        ApiResult byAuthor = approve(docId, "s1", "en", "alice", 1, newRequestId());
        assertThat(byAuthor.status()).isEqualTo(422);

        ApiResult wrongVersion = approve(docId, "s1", "en", "bob", 7, newRequestId());
        assertThat(wrongVersion.status()).isEqualTo(422);

        ApiResult ok = approve(docId, "s1", "en", "bob", 1, newRequestId());
        assertThat(ok.status()).isEqualTo(200);
        assertThat(ok.body().get("reviewer").asText()).isEqualTo("bob");
    }

    @Test
    @DisplayName("批准失效：源文修订后旧批准失效，发布 422；重新提交并批准后发布成功")
    void approvalInvalidatedBySourceRevision() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"原文\"}]");
        submitTranslation(docId, "s1", "en", "alice", "hello", 1, newRequestId());
        approve(docId, "s1", "en", "bob", 1, newRequestId());

        // 修订源文：源文版本 2，旧批准失效
        putJson("/api/documents/" + docId + "/segments/s1/source",
                "{\"requestId\":\"" + newRequestId() + "\",\"sourceText\":\"新原文\"}");
        ApiResult stalePublish = publish(docId, 3, 0, newRequestId());
        assertThat(stalePublish.status()).isEqualTo(422);

        // 基于旧源文版本提交译文 422；基于当前版本提交后旧批准失效，发布仍 422
        ApiResult staleSubmit = submitTranslation(docId, "s1", "en", "alice", "hi", 1, newRequestId());
        assertThat(staleSubmit.status()).isEqualTo(422);
        ApiResult resubmit = submitTranslation(docId, "s1", "en", "alice", "new hello", 2, newRequestId());
        assertThat(resubmit.status()).isEqualTo(200);
        assertThat(resubmit.body().get("translationVersion").asInt()).isEqualTo(2);
        ApiResult stillStale = publish(docId, 4, 0, newRequestId());
        assertThat(stillStale.status()).isEqualTo(422);

        // 用旧译文版本批准 422；用当前版本批准后发布成功
        ApiResult staleApprove = approve(docId, "s1", "en", "bob", 1, newRequestId());
        assertThat(staleApprove.status()).isEqualTo(422);
        ApiResult reApprove = approve(docId, "s1", "en", "bob", 2, newRequestId());
        assertThat(reApprove.status()).isEqualTo(200);
        ApiResult published = publish(docId, 4, 0, newRequestId());
        assertThat(published.status()).isEqualTo(201);
        assertThat(published.body().get("publishedVersion").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("发布：缺译 422；期望版本不符 409；失败后不产生部分快照")
    void publishFailures() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\",\"ja\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"原文\"}]");
        submitTranslation(docId, "s1", "en", "alice", "hello", 1, newRequestId());
        approve(docId, "s1", "en", "bob", 1, newRequestId());

        // ja 缺译（当前草稿版本为 2）
        ApiResult missing = publish(docId, 2, 0, newRequestId());
        assertThat(missing.status()).isEqualTo(422);

        // 期望版本不符
        ApiResult conflict = publish(docId, 99, 0, newRequestId());
        assertThat(conflict.status()).isEqualTo(409);
        ApiResult conflictPublished = publish(docId, 2, 5, newRequestId());
        assertThat(conflictPublished.status()).isEqualTo(409);

        // 失败不产生部分快照
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_snapshot WHERE document_id = ?", Integer.class, docId))
                .isZero();
        Integer publishedVersion = jdbc.queryForObject(
                "SELECT published_version FROM document WHERE document_id = ?", Integer.class, docId);
        assertThat(publishedVersion).isZero();
    }

    @Test
    @DisplayName("发布成功并查询快照：快照只读，后续修订不影响已发布快照；版本不存在 404")
    void publishAndQuerySnapshot() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\",\"ja\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"你好\"},{\"segmentId\":\"s2\",\"sourceText\":\"世界\"}]");
        for (String seg : new String[]{"s1", "s2"}) {
            submitTranslation(docId, seg, "en", "alice", seg + "-en", 1, newRequestId());
            submitTranslation(docId, seg, "ja", "carol", seg + "-ja", 1, newRequestId());
            approve(docId, seg, "en", "bob", 1, newRequestId());
            approve(docId, seg, "ja", "dave", 1, newRequestId());
        }
        ApiResult published = publish(docId, 5, 0, newRequestId());
        assertThat(published.status()).isEqualTo(201);
        assertThat(published.body().get("publishedVersion").asInt()).isEqualTo(1);

        ApiResult release = getJson("/api/documents/" + docId + "/releases/1");
        assertThat(release.status()).isEqualTo(200);
        assertThat(release.body().get("documentId").asLong()).isEqualTo(docId);
        assertThat(release.body().get("publishedVersion").asInt()).isEqualTo(1);
        assertThat(release.body().get("segments")).hasSize(2);
        var firstSegment = release.body().get("segments").get(0);
        assertThat(firstSegment.get("sourceText").asText()).isEqualTo("你好");
        assertThat(firstSegment.get("translations")).hasSize(2);
        assertThat(firstSegment.get("translations").get(0).get("reviewer").asText()).isEqualTo("bob");

        // 修订源文不影响已发布快照
        putJson("/api/documents/" + docId + "/segments/s1/source",
                "{\"requestId\":\"" + newRequestId() + "\",\"sourceText\":\"你好（修订）\"}");
        ApiResult releaseAgain = getJson("/api/documents/" + docId + "/releases/1");
        assertThat(releaseAgain.body().get("segments").get(0).get("sourceText").asText()).isEqualTo("你好");

        ApiResult missing = getJson("/api/documents/" + docId + "/releases/99");
        assertThat(missing.status()).isEqualTo(404);
        ApiResult missingDoc = getJson("/api/documents/999999/releases/1");
        assertThat(missingDoc.status()).isEqualTo(404);
    }

    @Test
    @DisplayName("二次发布：重新翻译并批准后发布版本递增为 2，两个快照均可查询")
    void secondPublish() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"原文\"}]");
        submitTranslation(docId, "s1", "en", "alice", "v1", 1, newRequestId());
        approve(docId, "s1", "en", "bob", 1, newRequestId());
        assertThat(publish(docId, 2, 0, newRequestId()).status()).isEqualTo(201);

        putJson("/api/documents/" + docId + "/segments/s1/source",
                "{\"requestId\":\"" + newRequestId() + "\",\"sourceText\":\"原文v2\"}");
        submitTranslation(docId, "s1", "en", "alice", "v2", 2, newRequestId());
        approve(docId, "s1", "en", "bob", 2, newRequestId());
        ApiResult second = publish(docId, 4, 1, newRequestId());
        assertThat(second.status()).isEqualTo(201);
        assertThat(second.body().get("publishedVersion").asInt()).isEqualTo(2);

        ApiResult release1 = getJson("/api/documents/" + docId + "/releases/1");
        assertThat(release1.body().get("segments").get(0).get("translations").get(0)
                .get("content").asText()).isEqualTo("v1");
        ApiResult release2 = getJson("/api/documents/" + docId + "/releases/2");
        assertThat(release2.body().get("segments").get(0).get("translations").get(0)
                .get("content").asText()).isEqualTo("v2");
    }

    @Test
    @DisplayName("幂等：同键同参重放原成功结果且不重复变更；同键异参 409；失败不占键")
    void idempotency() throws Exception {
        String requestId = newRequestId();
        String body = "{\"requestId\":\"" + requestId + "\",\"targetLanguages\":[\"en\"],"
                + "\"segments\":[{\"segmentId\":\"s1\",\"sourceText\":\"原文\"}]}";
        ApiResult first = postJson("/api/documents", body);
        assertThat(first.status()).isEqualTo(201);

        // 同键同参：重放原结果，不产生新文档
        ApiResult replay = postJson("/api/documents", body);
        assertThat(replay.status()).isEqualTo(201);
        assertThat(replay.body().get("documentId").asLong())
                .isEqualTo(first.body().get("documentId").asLong());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM document", Integer.class)).isEqualTo(1);

        // 同键异参：409
        String different = "{\"requestId\":\"" + requestId + "\",\"targetLanguages\":[\"en\",\"ja\"],"
                + "\"segments\":[]}";
        ApiResult conflict = postJson("/api/documents", different);
        assertThat(conflict.status()).isEqualTo(409);

        // 失败不占键：先以某 requestId 触发 422，再用同键修正参数后成功
        String failKey = newRequestId();
        ApiResult failed = postJson("/api/documents",
                "{\"requestId\":\"" + failKey + "\",\"targetLanguages\":[\"en\",\"en\"],\"segments\":[]}");
        assertThat(failed.status()).isEqualTo(422);
        ApiResult retried = postJson("/api/documents",
                "{\"requestId\":\"" + failKey + "\",\"targetLanguages\":[\"en\"],\"segments\":[]}");
        assertThat(retried.status()).isEqualTo(201);
    }
}
