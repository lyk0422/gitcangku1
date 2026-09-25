package com.example.starter.translation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 译文法律审签与发布快照门禁测试：审签状态、整次发布门禁、版本归属、
 * 历史快照冻结、逐段历史与阻断诊断查询、signKey 幂等。
 */
class LegalSignoffApiTest extends AbstractIntegrationTest {

    /** 建文档（en 单语言、单段落 s1）并提交且批准译文 v1，返回 documentId。 */
    private long approvedDoc() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"原文\"}]");
        submitTranslation(docId, "s1", "en", "alice", "hello", 1, newRequestId());
        approve(docId, "s1", "en", "bob", 1, newRequestId());
        return docId;
    }

    @Test
    @DisplayName("审签：已批准译文可 APPROVED/REJECTED 并附说明；响应含法务人、译文版本、状态与说明")
    void signoffSuccess() throws Exception {
        long docId = approvedDoc();

        ApiResult approved = legalSignoff(docId, "s1", "en", "legal1", 1, "APPROVED", "符合法规",
                newRequestId());
        assertThat(approved.status()).isEqualTo(200);
        assertThat(approved.body().get("legalUser").asText()).isEqualTo("legal1");
        assertThat(approved.body().get("translationVersion").asInt()).isEqualTo(1);
        assertThat(approved.body().get("status").asText()).isEqualTo("APPROVED");
        assertThat(approved.body().get("reason").asText()).isEqualTo("符合法规");

        ApiResult rejected = legalSignoff(docId, "s1", "en", "legal2", 1, "REJECTED", "含违禁表述",
                newRequestId());
        assertThat(rejected.status()).isEqualTo(200);
        assertThat(rejected.body().get("status").asText()).isEqualTo("REJECTED");
        assertThat(rejected.body().get("reason").asText()).isEqualTo("含违禁表述");

        // 同一译文版本仅保留最后一条终态审签
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM legal_signoff WHERE document_id = ? AND translation_version = 1",
                Integer.class, docId)).isEqualTo(1);
        // 审签不改变草稿版本
        assertThat(jdbc.queryForObject(
                "SELECT draft_version FROM document WHERE document_id = ?", Integer.class, docId))
                .isEqualTo(2);
    }

    @Test
    @DisplayName("审签失败分支：未批准 422；expectedVersion 不符 409；拒绝无说明 422；非法状态 400；译文不存在 404")
    void signoffFailures() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"原文\"}]");
        submitTranslation(docId, "s1", "en", "alice", "hello", 1, newRequestId());

        // 译文未批准：422
        ApiResult notApproved = legalSignoff(docId, "s1", "en", "legal1", 1, "APPROVED", null,
                newRequestId());
        assertThat(notApproved.status()).isEqualTo(422);

        approve(docId, "s1", "en", "bob", 1, newRequestId());

        // expectedVersion 与当前译文版本不符：409
        ApiResult versionConflict = legalSignoff(docId, "s1", "en", "legal1", 7, "APPROVED", null,
                newRequestId());
        assertThat(versionConflict.status()).isEqualTo(409);

        // 拒绝说明为空：422（缺失与空白字符串均不允许）
        ApiResult noReason = legalSignoff(docId, "s1", "en", "legal1", 1, "REJECTED", null,
                newRequestId());
        assertThat(noReason.status()).isEqualTo(422);
        ApiResult blankReason = legalSignoff(docId, "s1", "en", "legal1", 1, "REJECTED", "  ",
                newRequestId());
        assertThat(blankReason.status()).isEqualTo(422);

        // 非法状态：400
        ApiResult badStatus = legalSignoff(docId, "s1", "en", "legal1", 1, "PENDING", null,
                newRequestId());
        assertThat(badStatus.status()).isEqualTo(400);

        // 译文不存在：404
        ApiResult missing = legalSignoff(docId, "s1", "ja", "legal1", 1, "APPROVED", null,
                newRequestId());
        assertThat(missing.status()).isEqualTo(404);

        // 全部失败不落审签记录
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM legal_signoff WHERE document_id = ?", Integer.class, docId))
                .isZero();
    }

    @Test
    @DisplayName("逐段审签历史：按译文版本升序；译文修订后新版本需重新审签，旧审签仅归属旧版本")
    void signoffHistoryAndVersionOwnership() throws Exception {
        long docId = approvedDoc();
        legalApprove(docId, "s1", "en", "legal1", 1);

        // 译文修订为 v2 并重新批准；v2 尚未审签
        submitTranslation(docId, "s1", "en", "alice", "hello v2", 1, newRequestId());
        approve(docId, "s1", "en", "bob", 2, newRequestId());
        legalSignoff(docId, "s1", "en", "legal2", 2, "REJECTED", "措辞不当", newRequestId());

        ApiResult history = getJson("/api/documents/" + docId + "/segments/s1/translations/en/legal-signoffs");
        assertThat(history.status()).isEqualTo(200);
        assertThat(history.body().get("signoffs")).hasSize(2);
        var v1 = history.body().get("signoffs").get(0);
        assertThat(v1.get("translationVersion").asInt()).isEqualTo(1);
        assertThat(v1.get("status").asText()).isEqualTo("APPROVED");
        assertThat(v1.get("legalUser").asText()).isEqualTo("legal1");
        var v2 = history.body().get("signoffs").get(1);
        assertThat(v2.get("translationVersion").asInt()).isEqualTo(2);
        assertThat(v2.get("status").asText()).isEqualTo("REJECTED");
        assertThat(v2.get("reason").asText()).isEqualTo("措辞不当");

        // 旧审签仅归属旧版本：v1 的 APPROVED 不能满足 v2 的发布门禁
        ApiResult blockers = getJson("/api/documents/" + docId + "/publish-blockers");
        assertThat(blockers.status()).isEqualTo(200);
        assertThat(blockers.body().get("blockers")).hasSize(1);
        assertThat(blockers.body().get("blockers").get(0).get("translationVersion").asInt()).isEqualTo(2);
        assertThat(blockers.body().get("blockers").get(0).get("reason").asText()).contains("措辞不当");

        ApiResult missingDoc = getJson("/api/documents/999999/segments/s1/translations/en/legal-signoffs");
        assertThat(missingDoc.status()).isEqualTo(404);
    }

    @Test
    @DisplayName("发布门禁：缺失或 REJECTED 审签整次发布 422，稳定排序返回段落、译文版本与原因，不产生部分快照")
    void publishLegalGate() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\",\"ja\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"一\"},{\"segmentId\":\"s2\",\"sourceText\":\"二\"}]");
        for (String seg : new String[]{"s1", "s2"}) {
            submitTranslation(docId, seg, "en", "alice", seg + "-en", 1, newRequestId());
            submitTranslation(docId, seg, "ja", "carol", seg + "-ja", 1, newRequestId());
            approve(docId, seg, "en", "bob", 1, newRequestId());
            approve(docId, seg, "ja", "dave", 1, newRequestId());
        }
        // s1/en 通过，s1/ja 拒绝，s2 两语言未审签
        legalApprove(docId, "s1", "en", "legal1", 1);
        legalSignoff(docId, "s1", "ja", "legal2", 1, "REJECTED", "术语违规", newRequestId());

        ApiResult blocked = publish(docId, 5, 0, newRequestId());
        assertThat(blocked.status()).isEqualTo(422);
        assertThat(blocked.body().get("error").asText()).isEqualTo("LEGAL_GATE");
        var blockers = blocked.body().get("blockers");
        assertThat(blockers).hasSize(3);
        // 稳定排序：按段落、语言；含译文版本与原因
        assertThat(blockers.get(0).get("segmentId").asText()).isEqualTo("s1");
        assertThat(blockers.get(0).get("language").asText()).isEqualTo("ja");
        assertThat(blockers.get(0).get("translationVersion").asInt()).isEqualTo(1);
        assertThat(blockers.get(0).get("reason").asText()).contains("术语违规");
        assertThat(blockers.get(1).get("segmentId").asText()).isEqualTo("s2");
        assertThat(blockers.get(1).get("reason").asText()).contains("缺少法律审签");
        assertThat(blockers.get(2).get("segmentId").asText()).isEqualTo("s2");
        assertThat(blockers.get(2).get("language").asText()).isEqualTo("ja");

        // 不产生部分快照
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_snapshot WHERE document_id = ?", Integer.class, docId))
                .isZero();
        assertThat(jdbc.queryForObject(
                "SELECT published_version FROM document WHERE document_id = ?", Integer.class, docId))
                .isZero();

        // 诊断查询与发布阻断一致；全部补签后诊断为空、发布成功
        ApiResult diagnosis = getJson("/api/documents/" + docId + "/publish-blockers");
        assertThat(diagnosis.body().get("blockers")).hasSize(3);
        legalSignoff(docId, "s1", "ja", "legal3", 1, "APPROVED", "复核通过", newRequestId());
        legalApprove(docId, "s2", "en", "legal1", 1);
        legalApprove(docId, "s2", "ja", "legal2", 1);
        assertThat(getJson("/api/documents/" + docId + "/publish-blockers")
                .body().get("blockers")).isEmpty();

        ApiResult published = publish(docId, 5, 0, newRequestId());
        assertThat(published.status()).isEqualTo(201);
        assertThat(published.body().get("publishedVersion").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("快照所用审签：发布快照固化审签人、状态与译文版本；法务拒绝已发布版本不改写历史快照但阻断新快照")
    void snapshotFreezesAndRejectBlocksReuse() throws Exception {
        long docId = approvedDoc();
        legalSignoff(docId, "s1", "en", "legal1", 1, "APPROVED", "初审通过", newRequestId());
        assertThat(publish(docId, 2, 0, newRequestId()).status()).isEqualTo(201);

        // 快照记录所用审签版本
        ApiResult release = getJson("/api/documents/" + docId + "/releases/1");
        assertThat(release.status()).isEqualTo(200);
        var signoff = release.body().get("segments").get(0).get("translations").get(0).get("legalSignoff");
        assertThat(signoff.get("legalUser").asText()).isEqualTo("legal1");
        assertThat(signoff.get("status").asText()).isEqualTo("APPROVED");
        assertThat(signoff.get("translationVersion").asInt()).isEqualTo(1);

        // 法务拒绝已发布快照中的版本：历史快照不追溯改变
        ApiResult rejected = legalSignoff(docId, "s1", "en", "legal2", 1, "REJECTED", "事后发现违规",
                newRequestId());
        assertThat(rejected.status()).isEqualTo(200);
        ApiResult releaseAgain = getJson("/api/documents/" + docId + "/releases/1");
        assertThat(releaseAgain.body().get("segments").get(0).get("translations").get(0)
                .get("legalSignoff").get("status").asText()).isEqualTo("APPROVED");

        // 该版本不得被新快照再次使用：整次发布 422
        ApiResult blocked = publish(docId, 2, 1, newRequestId());
        assertThat(blocked.status()).isEqualTo(422);
        assertThat(blocked.body().get("error").asText()).isEqualTo("LEGAL_GATE");
        assertThat(blocked.body().get("blockers").get(0).get("reason").asText()).contains("事后发现违规");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_snapshot WHERE document_id = ?", Integer.class, docId))
                .isEqualTo(1);

        // 译文修订为 v2、重新批准并审签后可发布新快照
        submitTranslation(docId, "s1", "en", "alice", "hello v2", 1, newRequestId());
        approve(docId, "s1", "en", "bob", 2, newRequestId());
        legalApprove(docId, "s1", "en", "legal2", 2);
        ApiResult second = publish(docId, 3, 1, newRequestId());
        assertThat(second.status()).isEqualTo(201);
        assertThat(second.body().get("publishedVersion").asInt()).isEqualTo(2);
        ApiResult release2 = getJson("/api/documents/" + docId + "/releases/2");
        assertThat(release2.body().get("segments").get(0).get("translations").get(0)
                .get("legalSignoff").get("translationVersion").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("审签幂等：同 signKey 同参重放首次响应；同键异参 409；失败不占键")
    void signoffIdempotency() throws Exception {
        long docId = approvedDoc();
        String signKey = newRequestId();

        ApiResult first = legalSignoff(docId, "s1", "en", "legal1", 1, "APPROVED", "通过", signKey);
        assertThat(first.status()).isEqualTo(200);

        // 同键同参：重放首次响应，不重复落记录
        ApiResult replay = legalSignoff(docId, "s1", "en", "legal1", 1, "APPROVED", "通过", signKey);
        assertThat(replay.status()).isEqualTo(200);
        assertThat(replay.body().toString()).isEqualTo(first.body().toString());
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM legal_signoff WHERE document_id = ?", Integer.class, docId))
                .isEqualTo(1);

        // 同键异参（不同说明）：409
        ApiResult conflict = legalSignoff(docId, "s1", "en", "legal1", 1, "APPROVED", "改说明", signKey);
        assertThat(conflict.status()).isEqualTo(409);

        // 失败不占键：先以某 signKey 触发 422（拒绝无说明），再用同键修正参数后成功
        String failKey = newRequestId();
        ApiResult failed = legalSignoff(docId, "s1", "en", "legal2", 1, "REJECTED", null, failKey);
        assertThat(failed.status()).isEqualTo(422);
        ApiResult retried = legalSignoff(docId, "s1", "en", "legal2", 1, "REJECTED", "补充说明", failKey);
        assertThat(retried.status()).isEqualTo(200);
        assertThat(retried.body().get("status").asText()).isEqualTo("REJECTED");
    }
}
