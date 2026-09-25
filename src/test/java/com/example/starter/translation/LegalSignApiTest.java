package com.example.starter.translation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 译文法律审签测试：审签状态与历史、发布门禁、版本归属、历史快照冻结、诊断查询与幂等。
 */
class LegalSignApiTest extends AbstractIntegrationTest {

    @Test
    @DisplayName("审签成功与历史：APPROVED 落库；同版本再签覆盖先前终态，历史仅保留最后一条")
    void legalSignSuccessAndHistory() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"原文\"}]");
        submitTranslation(docId, "s1", "en", "alice", "hello", 1, newRequestId());
        approve(docId, "s1", "en", "bob", 1, newRequestId());

        ApiResult signed = legalSign(docId, "s1", "en", "erin", 1, "APPROVED", "合规", newRequestId());
        assertThat(signed.status()).isEqualTo(200);
        assertThat(signed.body().get("documentId").asLong()).isEqualTo(docId);
        assertThat(signed.body().get("segmentId").asText()).isEqualTo("s1");
        assertThat(signed.body().get("language").asText()).isEqualTo("en");
        assertThat(signed.body().get("translationVersion").asInt()).isEqualTo(1);
        assertThat(signed.body().get("legalReviewer").asText()).isEqualTo("erin");
        assertThat(signed.body().get("status").asText()).isEqualTo("APPROVED");
        assertThat(signed.body().get("reason").asText()).isEqualTo("合规");

        ApiResult history = getJson("/api/documents/" + docId + "/segments/s1/translations/en/legal-signs");
        assertThat(history.status()).isEqualTo(200);
        assertThat(history.body().get("signs")).hasSize(1);
        assertThat(history.body().get("signs").get(0).get("status").asText()).isEqualTo("APPROVED");

        // 另一法务人员对同一译文版本再签 REJECTED：覆盖先前终态，历史仍只有一条
        ApiResult rejected = legalSign(docId, "s1", "en", "frank", 1, "REJECTED", "存在法律风险",
                newRequestId());
        assertThat(rejected.status()).isEqualTo(200);
        assertThat(rejected.body().get("legalReviewer").asText()).isEqualTo("frank");

        ApiResult historyAfter = getJson("/api/documents/" + docId + "/segments/s1/translations/en/legal-signs");
        assertThat(historyAfter.body().get("signs")).hasSize(1);
        var entry = historyAfter.body().get("signs").get(0);
        assertThat(entry.get("translationVersion").asInt()).isEqualTo(1);
        assertThat(entry.get("legalReviewer").asText()).isEqualTo("frank");
        assertThat(entry.get("status").asText()).isEqualTo("REJECTED");
        assertThat(entry.get("reason").asText()).isEqualTo("存在法律风险");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM legal_sign WHERE document_id = ?", Integer.class, docId))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("审签失败分支：未批准 422；译文版本不符 422；拒绝说明为空 422；非法状态 422；缺失资源 404")
    void legalSignFailures() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\",\"ja\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"原文\"}]");
        submitTranslation(docId, "s1", "en", "alice", "hello", 1, newRequestId());

        // 译文尚未批准：422
        ApiResult notApproved = legalSign(docId, "s1", "en", "erin", 1, "APPROVED", "合规", newRequestId());
        assertThat(notApproved.status()).isEqualTo(422);

        approve(docId, "s1", "en", "bob", 1, newRequestId());

        // expectedVersion 与当前译文版本不符：422
        ApiResult wrongVersion = legalSign(docId, "s1", "en", "erin", 7, "APPROVED", "合规", newRequestId());
        assertThat(wrongVersion.status()).isEqualTo(422);

        // REJECTED 说明为空（空串与缺省）均 422
        ApiResult emptyReason = legalSign(docId, "s1", "en", "erin", 1, "REJECTED", "", newRequestId());
        assertThat(emptyReason.status()).isEqualTo(422);
        ApiResult nullReason = legalSign(docId, "s1", "en", "erin", 1, "REJECTED", null, newRequestId());
        assertThat(nullReason.status()).isEqualTo(422);

        // 非法状态：422
        ApiResult badStatus = legalSign(docId, "s1", "en", "erin", 1, "PENDING", "待定", newRequestId());
        assertThat(badStatus.status()).isEqualTo(422);

        // 段落不存在 404；译文不存在（ja 未提交）404；语言不在目标语言 422
        ApiResult noSegment = legalSign(docId, "nope", "en", "erin", 1, "APPROVED", "合规", newRequestId());
        assertThat(noSegment.status()).isEqualTo(404);
        ApiResult noTranslation = legalSign(docId, "s1", "ja", "erin", 1, "APPROVED", "合规", newRequestId());
        assertThat(noTranslation.status()).isEqualTo(404);
        ApiResult wrongLang = legalSign(docId, "s1", "fr", "erin", 1, "APPROVED", "合规", newRequestId());
        assertThat(wrongLang.status()).isEqualTo(422);

        // 缺少 X-Actor-Id：400
        String body = "{\"requestId\":\"" + newRequestId()
                + "\",\"expectedVersion\":1,\"status\":\"APPROVED\",\"reason\":\"合规\"}";
        ApiResult noActor = postJson("/api/documents/" + docId + "/segments/s1/translations/en/legal-sign",
                body);
        assertThat(noActor.status()).isEqualTo(400);

        // 全部失败不落库
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM legal_sign WHERE document_id = ?", Integer.class, docId))
                .isZero();

        // 状态码归一：小写 approved 视为 APPROVED
        ApiResult lower = legalSign(docId, "s1", "en", "erin", 1, "approved", "合规", newRequestId());
        assertThat(lower.status()).isEqualTo(200);
        assertThat(lower.body().get("status").asText()).isEqualTo("APPROVED");
    }

    @Test
    @DisplayName("发布门禁：缺失或 REJECTED 审签使整次发布 422，稳定排序返回段落、译文版本与原因，不产生部分快照")
    void publishGateBlocksMissingOrRejected() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"第一段\"},{\"segmentId\":\"s2\",\"sourceText\":\"第二段\"}]");
        submitTranslation(docId, "s1", "en", "alice", "one", 1, newRequestId());
        submitTranslation(docId, "s2", "en", "alice", "two", 1, newRequestId());
        approve(docId, "s1", "en", "bob", 1, newRequestId());
        approve(docId, "s2", "en", "bob", 1, newRequestId());
        // 仅 s1 审签通过（草稿版本 3）
        legalSign(docId, "s1", "en", "erin", 1, "APPROVED", "合规", newRequestId());

        // s2 缺少审签：整次发布 422，阻断条目含段落、译文版本与原因
        ApiResult blocked = publish(docId, 3, 0, newRequestId());
        assertThat(blocked.status()).isEqualTo(422);
        assertThat(blocked.body().get("error").asText()).isEqualTo("PUBLISH_BLOCKED");
        assertThat(blocked.body().get("blockers")).hasSize(1);
        var blocker = blocked.body().get("blockers").get(0);
        assertThat(blocker.get("segmentId").asText()).isEqualTo("s2");
        assertThat(blocker.get("language").asText()).isEqualTo("en");
        assertThat(blocker.get("translationVersion").asInt()).isEqualTo(1);
        assertThat(blocker.get("reason").asText()).contains("缺少法律审签");

        // 失败不产生部分快照
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_snapshot WHERE document_id = ?", Integer.class, docId))
                .isZero();

        // s2 审签被拒绝：发布仍 422，原因含拒绝说明
        legalSign(docId, "s2", "en", "frank", 1, "REJECTED", "条款缺失", newRequestId());
        ApiResult rejected = publish(docId, 3, 0, newRequestId());
        assertThat(rejected.status()).isEqualTo(422);
        assertThat(rejected.body().get("blockers")).hasSize(1);
        assertThat(rejected.body().get("blockers").get(0).get("reason").asText())
                .contains("法律审签拒绝").contains("条款缺失");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_snapshot WHERE document_id = ?", Integer.class, docId))
                .isZero();

        // 诊断接口与发布门禁一致
        ApiResult diagnostics = getJson("/api/documents/" + docId + "/publish-diagnostics");
        assertThat(diagnostics.status()).isEqualTo(200);
        assertThat(diagnostics.body().get("blockers")).hasSize(1);
        assertThat(diagnostics.body().get("blockers").get(0).get("segmentId").asText()).isEqualTo("s2");

        // 重新审签通过后整次发布成功，诊断清空
        legalSign(docId, "s2", "en", "erin", 1, "APPROVED", "已补充条款", newRequestId());
        ApiResult published = publish(docId, 3, 0, newRequestId());
        assertThat(published.status()).isEqualTo(201);
        assertThat(getJson("/api/documents/" + docId + "/publish-diagnostics")
                .body().get("blockers")).isEmpty();
    }

    @Test
    @DisplayName("版本归属：译文修订后旧审签仅归属旧版本，新版本须重新审签后方可发布")
    void publishGateVersionOwnership() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"原文\"}]");
        submitTranslation(docId, "s1", "en", "alice", "v1", 1, newRequestId());
        approve(docId, "s1", "en", "bob", 1, newRequestId());
        legalSign(docId, "s1", "en", "erin", 1, "APPROVED", "合规", newRequestId());
        assertThat(publish(docId, 2, 0, newRequestId()).status()).isEqualTo(201);

        // 源文修订并重新提交译文 v2、批准 v2（草稿版本 4）
        putJson("/api/documents/" + docId + "/segments/s1/source",
                "{\"requestId\":\"" + newRequestId() + "\",\"sourceText\":\"原文v2\"}");
        submitTranslation(docId, "s1", "en", "alice", "v2", 2, newRequestId());
        approve(docId, "s1", "en", "bob", 2, newRequestId());

        // 旧审签仅归属 v1：发布 422，提示需对新版本重新审签
        ApiResult blocked = publish(docId, 4, 1, newRequestId());
        assertThat(blocked.status()).isEqualTo(422);
        assertThat(blocked.body().get("blockers")).hasSize(1);
        assertThat(blocked.body().get("blockers").get(0).get("translationVersion").asInt()).isEqualTo(2);
        assertThat(blocked.body().get("blockers").get(0).get("reason").asText()).contains("重新审签");

        // 历史：v1 审签仍在，v2 无审签
        ApiResult history = getJson("/api/documents/" + docId + "/segments/s1/translations/en/legal-signs");
        assertThat(history.body().get("signs")).hasSize(1);
        assertThat(history.body().get("signs").get(0).get("translationVersion").asInt()).isEqualTo(1);

        // 用旧版本号审签 422；对新版本审签后发布成功
        assertThat(legalSign(docId, "s1", "en", "erin", 1, "APPROVED", "合规", newRequestId()).status())
                .isEqualTo(422);
        legalSign(docId, "s1", "en", "erin", 2, "APPROVED", "合规", newRequestId());
        assertThat(publish(docId, 4, 1, newRequestId()).status()).isEqualTo(201);

        // 快照所用审签版本：v1 快照归属 v1 审签，v2 快照归属 v2 审签
        ApiResult signs1 = getJson("/api/documents/" + docId + "/releases/1/legal-signs");
        assertThat(signs1.status()).isEqualTo(200);
        assertThat(signs1.body()).hasSize(1);
        assertThat(signs1.body().get(0).get("translationVersion").asInt()).isEqualTo(1);
        ApiResult signs2 = getJson("/api/documents/" + docId + "/releases/2/legal-signs");
        assertThat(signs2.body().get(0).get("translationVersion").asInt()).isEqualTo(2);
        assertThat(signs2.body().get(0).get("status").asText()).isEqualTo("APPROVED");
    }

    @Test
    @DisplayName("历史快照冻结：发布后法务拒绝同版本不追溯改变历史快照，但该版本不得被新快照再次使用")
    void rejectionDoesNotRetroactivelyChangeSnapshot() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"原文\"}]");
        submitTranslation(docId, "s1", "en", "alice", "hello", 1, newRequestId());
        approve(docId, "s1", "en", "bob", 1, newRequestId());
        legalSign(docId, "s1", "en", "erin", 1, "APPROVED", "合规", newRequestId());
        assertThat(publish(docId, 2, 0, newRequestId()).status()).isEqualTo(201);

        // 法务拒绝已发布快照中的同一译文版本（同一版本仅保留最后一条终态）
        legalSign(docId, "s1", "en", "frank", 1, "REJECTED", "新法规冲突", newRequestId());

        // 历史快照不追溯改变：快照 JSON 与快照审签查询仍为 APPROVED
        ApiResult release = getJson("/api/documents/" + docId + "/releases/1");
        assertThat(release.status()).isEqualTo(200);
        assertThat(release.body().get("segments").get(0).get("translations").get(0)
                .get("legalStatus").asText()).isEqualTo("APPROVED");
        ApiResult releaseSigns = getJson("/api/documents/" + docId + "/releases/1/legal-signs");
        assertThat(releaseSigns.body().get(0).get("status").asText()).isEqualTo("APPROVED");
        assertThat(releaseSigns.body().get(0).get("legalReviewer").asText()).isEqualTo("erin");

        // 但该版本不得被新快照再次使用：整次发布 422
        ApiResult blocked = publish(docId, 2, 1, newRequestId());
        assertThat(blocked.status()).isEqualTo(422);
        assertThat(blocked.body().get("blockers").get(0).get("reason").asText())
                .contains("法律审签拒绝").contains("新法规冲突");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_snapshot WHERE document_id = ?", Integer.class, docId))
                .isEqualTo(1);

        // 重新审签通过后可再次发布
        legalSign(docId, "s1", "en", "erin", 1, "APPROVED", "法规已更新", newRequestId());
        assertThat(publish(docId, 2, 1, newRequestId()).status()).isEqualTo(201);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_snapshot WHERE document_id = ?", Integer.class, docId))
                .isEqualTo(2);
    }

    @Test
    @DisplayName("术语版本变更与审签门禁共同满足：术语更新后重译新版本须重新审签")
    void termChangeRequiresNewSignOff() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"机器学习\"}]");
        updateTerms(docId, 0,
                "[{\"sourceTerm\":\"机器学习\",\"language\":\"en\",\"requiredTranslation\":\"machine learning\"}]",
                newRequestId());
        submitTranslation(docId, "s1", "en", "alice", "machine learning", 1, newRequestId());
        approve(docId, "s1", "en", "bob", 1, newRequestId());
        legalSign(docId, "s1", "en", "erin", 1, "APPROVED", "合规", newRequestId());

        // 术语版本变更（草稿版本 4）：旧译文术语过期，需重新提交
        updateTerms(docId, 1,
                "[{\"sourceTerm\":\"机器学习\",\"language\":\"en\",\"requiredTranslation\":\"ML\"}]",
                newRequestId());
        submitTranslation(docId, "s1", "en", "alice", "ML", 1, newRequestId());
        approve(docId, "s1", "en", "bob", 2, newRequestId());

        // 新译文版本未审签：发布 422（草稿版本 5）
        ApiResult blocked = publish(docId, 5, 0, newRequestId());
        assertThat(blocked.status()).isEqualTo(422);
        assertThat(blocked.body().get("blockers").get(0).get("reason").asText()).contains("重新审签");

        legalSign(docId, "s1", "en", "erin", 2, "APPROVED", "合规", newRequestId());
        assertThat(publish(docId, 5, 0, newRequestId()).status()).isEqualTo(201);
    }

    @Test
    @DisplayName("发布阻断诊断：缺译、缺批准、缺审签逐级消除；快照审签查询 404 分支")
    void publishDiagnosticsAndReleaseSignsQuery() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"原文\"}]");

        // 缺译
        ApiResult missing = getJson("/api/documents/" + docId + "/publish-diagnostics");
        assertThat(missing.body().get("blockers")).hasSize(1);
        assertThat(missing.body().get("blockers").get(0).get("reason").asText()).contains("缺少译文");
        assertThat(missing.body().get("blockers").get(0).get("translationVersion").asInt()).isZero();

        // 缺批准与缺审签并存
        submitTranslation(docId, "s1", "en", "alice", "hello", 1, newRequestId());
        ApiResult noApproval = getJson("/api/documents/" + docId + "/publish-diagnostics");
        assertThat(noApproval.body().get("blockers")).hasSize(2);
        assertThat(noApproval.body().get("blockers").toString()).contains("缺少批准", "缺少法律审签");

        // 批准后仅剩缺审签
        approve(docId, "s1", "en", "bob", 1, newRequestId());
        ApiResult noSign = getJson("/api/documents/" + docId + "/publish-diagnostics");
        assertThat(noSign.body().get("blockers")).hasSize(1);
        assertThat(noSign.body().get("blockers").get(0).get("reason").asText()).contains("缺少法律审签");
        assertThat(noSign.body().get("draftVersion").asInt()).isEqualTo(2);
        assertThat(noSign.body().get("publishedVersion").asInt()).isZero();

        // 审签通过后诊断清空并可发布
        legalSign(docId, "s1", "en", "erin", 1, "APPROVED", "合规", newRequestId());
        assertThat(getJson("/api/documents/" + docId + "/publish-diagnostics")
                .body().get("blockers")).isEmpty();
        assertThat(publish(docId, 2, 0, newRequestId()).status()).isEqualTo(201);

        // 快照审签查询：正常与 404 分支
        ApiResult signs = getJson("/api/documents/" + docId + "/releases/1/legal-signs");
        assertThat(signs.status()).isEqualTo(200);
        assertThat(signs.body()).hasSize(1);
        assertThat(signs.body().get(0).get("segmentId").asText()).isEqualTo("s1");
        assertThat(signs.body().get(0).get("legalReviewer").asText()).isEqualTo("erin");
        assertThat(getJson("/api/documents/" + docId + "/releases/99/legal-signs").status())
                .isEqualTo(404);
        assertThat(getJson("/api/documents/999999/releases/1/legal-signs").status()).isEqualTo(404);
        assertThat(getJson("/api/documents/999999/publish-diagnostics").status()).isEqualTo(404);
    }

    @Test
    @DisplayName("审签幂等：同键同参重放首次响应；指纹含法务人/版本/状态/说明，同键异参 409；失败不占键")
    void legalSignIdempotency() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"原文\"}]");
        submitTranslation(docId, "s1", "en", "alice", "hello", 1, newRequestId());
        approve(docId, "s1", "en", "bob", 1, newRequestId());

        String signKey = newRequestId();
        ApiResult first = legalSign(docId, "s1", "en", "erin", 1, "APPROVED", "合规", signKey);
        assertThat(first.status()).isEqualTo(200);

        // 同键同参：重放首次响应，不重复变更
        ApiResult replay = legalSign(docId, "s1", "en", "erin", 1, "APPROVED", "合规", signKey);
        assertThat(replay.status()).isEqualTo(200);
        assertThat(replay.body().toString()).isEqualTo(first.body().toString());
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM legal_sign WHERE document_id = ?", Integer.class, docId))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = ?", Integer.class, signKey))
                .isEqualTo(1);

        // 同键异参（说明不同 / 状态不同 / 法务人不同 / 版本不同）：均 409
        assertThat(legalSign(docId, "s1", "en", "erin", 1, "APPROVED", "其他说明", signKey).status())
                .isEqualTo(409);
        assertThat(legalSign(docId, "s1", "en", "erin", 1, "REJECTED", "合规", signKey).status())
                .isEqualTo(409);
        assertThat(legalSign(docId, "s1", "en", "frank", 1, "APPROVED", "合规", signKey).status())
                .isEqualTo(409);
        assertThat(legalSign(docId, "s1", "en", "erin", 2, "APPROVED", "合规", signKey).status())
                .isEqualTo(409);

        // 失败不占键：先以某 signKey 触发 422（拒绝说明为空），再用同键修正参数后成功
        String failKey = newRequestId();
        assertThat(legalSign(docId, "s1", "en", "erin", 1, "REJECTED", "", failKey).status())
                .isEqualTo(422);
        ApiResult retried = legalSign(docId, "s1", "en", "erin", 1, "REJECTED", "条款缺失", failKey);
        assertThat(retried.status()).isEqualTo(200);
        assertThat(retried.body().get("status").asText()).isEqualTo("REJECTED");
    }
}
