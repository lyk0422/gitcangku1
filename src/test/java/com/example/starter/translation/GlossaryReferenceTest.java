package com.example.starter.translation;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 全局术语库引用失效与升级测试：引用落后导致译文过期、既有批准不再满足发布条件、
 * 显式引用升级携带两个期望版本、发布快照固化两个术语版本且历史发布不被改写。
 */
class GlossaryReferenceTest extends AbstractIntegrationTest {

    private static final String GLOBAL_V1 =
            "[{\"sourceTerm\":\"机器学习\",\"language\":\"en\",\"requiredTranslation\":\"machine learning\"}]";
    private static final String GLOBAL_V2 =
            "[{\"sourceTerm\":\"机器学习\",\"language\":\"en\",\"requiredTranslation\":\"ML\"}]";

    /** 建文档、建全局 v1、升级引用、提交并批准译文，推进到可发布状态（草稿版本 3）。 */
    private long createPublishableDocument() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"机器学习\"}]");
        assertThat(updateGlobalTerms(0, GLOBAL_V1, newRequestId()).status()).isEqualTo(201);
        assertThat(upgradeGlossary(docId, 0, 1, newRequestId()).status()).isEqualTo(200);
        assertThat(submitTranslation(docId, "s1", "en", "alice", "machine learning", 1, newRequestId())
                .status()).isEqualTo(200);
        assertThat(approve(docId, "s1", "en", "bob", 1, newRequestId()).status()).isEqualTo(200);
        return docId;
    }

    @Test
    @DisplayName("引用落后：全局更新后译文视为术语过期，既有批准不再满足发布条件，发布 422")
    void laggingReferenceStaleTranslations() throws Exception {
        long docId = createPublishableDocument();
        // 当前草稿版本 3、引用全局版本 1

        // 全局术语库更新到 v2：不改变文档草稿版本，但文档引用落后
        assertThat(updateGlobalTerms(1, GLOBAL_V2, newRequestId()).status()).isEqualTo(201);
        Integer draftVersion = jdbc.queryForObject(
                "SELECT draft_version FROM document WHERE document_id = ?", Integer.class, docId);
        assertThat(draftVersion).isEqualTo(3);

        // 译文视为术语过期
        ApiResult status = getJson("/api/documents/" + docId + "/terms/status");
        assertThat(status.body().get("globalTermVersion").asInt()).isEqualTo(1);
        JsonNode entry = status.body().get("translations").get(0);
        assertThat(entry.get("globalTermVersion").asInt()).isEqualTo(1);
        assertThat(entry.get("termStale").asBoolean()).isTrue();

        // 既有批准不再满足发布条件：发布 422
        assertThat(publish(docId, 3, 0, newRequestId()).status()).isEqualTo(422);
    }

    @Test
    @DisplayName("引用升级：携带两个期望版本，不符 409；已是最新 422；升级后草稿版本加一并可重新发布")
    void upgradeReferenceFlow() throws Exception {
        long docId = createPublishableDocument();
        assertThat(updateGlobalTerms(1, GLOBAL_V2, newRequestId()).status()).isEqualTo(201);

        // 期望引用版本不符 409
        assertThat(upgradeGlossary(docId, 0, 3, newRequestId()).status()).isEqualTo(409);
        // 期望草稿版本不符 409
        assertThat(upgradeGlossary(docId, 1, 2, newRequestId()).status()).isEqualTo(409);
        // 文档不存在 404
        assertThat(upgradeGlossary(999999, 0, 1, newRequestId()).status()).isEqualTo(404);

        // 升级成功：引用推进到 v2，草稿版本加一
        ApiResult upgraded = upgradeGlossary(docId, 1, 3, newRequestId());
        assertThat(upgraded.status()).isEqualTo(200);
        assertThat(upgraded.body().get("globalTermVersion").asInt()).isEqualTo(2);
        assertThat(upgraded.body().get("draftVersion").asInt()).isEqualTo(4);

        // 已是最新：再次升级 422
        assertThat(upgradeGlossary(docId, 2, 4, newRequestId()).status()).isEqualTo(422);

        // 升级后旧译文绑定旧全局版本，仍术语过期：发布 422
        assertThat(publish(docId, 4, 0, newRequestId()).status()).isEqualTo(422);

        // 生效规则集已切换到新全局版本：按新必译文本重新提交并批准
        ApiResult violated = submitTranslation(docId, "s1", "en", "alice", "machine learning", 1,
                newRequestId());
        assertThat(violated.status()).isEqualTo(422);
        assertThat(violated.body().get("violations").get(0).get("requiredTranslation").asText()).isEqualTo("ML");
        assertThat(violated.body().get("violations").get(0).get("source").asText()).isEqualTo("GLOBAL");

        ApiResult resubmit = submitTranslation(docId, "s1", "en", "alice", "ML", 1, newRequestId());
        assertThat(resubmit.status()).isEqualTo(200);
        assertThat(resubmit.body().get("globalTermVersion").asInt()).isEqualTo(2);
        assertThat(approve(docId, "s1", "en", "bob", 2, newRequestId()).status()).isEqualTo(200);
        // 草稿版本：4（升级后）+ 1（成功提交；422 违规提交回滚不占版本）= 5
        assertThat(publish(docId, 5, 0, newRequestId()).status()).isEqualTo(201);

        // 快照固化两个术语版本与生效规则集
        ApiResult release = getJson("/api/documents/" + docId + "/releases/1");
        assertThat(release.body().get("globalTermVersion").asInt()).isEqualTo(2);
        assertThat(release.body().get("terms")).hasSize(1);
        assertThat(release.body().get("terms").get(0).get("requiredTranslation").asText()).isEqualTo("ML");
        assertThat(release.body().get("terms").get(0).get("source").asText()).isEqualTo("GLOBAL");
    }

    @Test
    @DisplayName("发布快照固化：发布后全局与文档术语更新不改写历史发布查询")
    void releaseSnapshotFreezesBothVersions() throws Exception {
        long docId = createPublishableDocument();
        assertThat(publish(docId, 3, 0, newRequestId()).status()).isEqualTo(201);

        // 全局更新到 v2、文档术语版本更新
        assertThat(updateGlobalTerms(1, GLOBAL_V2, newRequestId()).status()).isEqualTo(201);
        assertThat(upgradeGlossary(docId, 1, 3, newRequestId()).status()).isEqualTo(200);
        assertThat(updateTerms(docId, 0,
                "[{\"sourceTerm\":\"云计算\",\"language\":\"en\",\"requiredTranslation\":\"cloud computing\"}]",
                newRequestId()).status()).isEqualTo(201);

        // 历史发布查询仍返回发布时的两个术语版本与生效规则集
        ApiResult release = getJson("/api/documents/" + docId + "/releases/1");
        assertThat(release.status()).isEqualTo(200);
        assertThat(release.body().get("termVersion").asInt()).isZero();
        assertThat(release.body().get("globalTermVersion").asInt()).isEqualTo(1);
        assertThat(release.body().get("terms")).hasSize(1);
        assertThat(release.body().get("terms").get(0).get("requiredTranslation").asText())
                .isEqualTo("machine learning");
        assertThat(release.body().get("terms").get(0).get("source").asText()).isEqualTo("GLOBAL");

        // 当前生效规则集已切换
        ApiResult effective = getJson("/api/documents/" + docId + "/terms/effective");
        assertThat(effective.body().get("globalTermVersion").asInt()).isEqualTo(2);
        assertThat(effective.body().get("termVersion").asInt()).isEqualTo(1);
        assertThat(effective.body().get("rules")).hasSize(2);
    }

    @Test
    @DisplayName("引用升级幂等：同键同参重放、同键异参 409、失败不占键")
    void upgradeIdempotency() throws Exception {
        long docId = createPublishableDocument();
        assertThat(updateGlobalTerms(1, GLOBAL_V2, newRequestId()).status()).isEqualTo(201);

        String requestId = newRequestId();
        ApiResult first = upgradeGlossary(docId, 1, 3, requestId);
        assertThat(first.status()).isEqualTo(200);
        assertThat(first.body().get("globalTermVersion").asInt()).isEqualTo(2);

        // 同键同参：重放原结果，草稿版本不再增加
        ApiResult replay = upgradeGlossary(docId, 1, 3, requestId);
        assertThat(replay.status()).isEqualTo(200);
        assertThat(replay.body().get("draftVersion").asInt()).isEqualTo(4);
        Integer draftVersion = jdbc.queryForObject(
                "SELECT draft_version FROM document WHERE document_id = ?", Integer.class, docId);
        assertThat(draftVersion).isEqualTo(4);

        // 同键异参：409
        assertThat(upgradeGlossary(docId, 1, 4, requestId).status()).isEqualTo(409);

        // 失败不占键：已是最新触发 422，requestId 不落 request_log
        String failKey = newRequestId();
        assertThat(upgradeGlossary(docId, 2, 4, failKey).status()).isEqualTo(422);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = ?", Integer.class, failKey)).isZero();
    }
}
