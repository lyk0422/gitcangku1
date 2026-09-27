package com.example.starter.translation;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 译文评审利益冲突主流程、失败分支与事务边界测试（真实 H2 MySQL 兼容库）。
 * 区间为 UTC、左闭右开；登记人 bob、合规解除人 eve。
 */
class ConflictApiTest extends AbstractIntegrationTest {

    /** 覆盖测试运行时刻的超长有效区间。 */
    private static final String EFFECTIVE_FROM = "2000-01-01T00:00:00Z";
    private static final String EFFECTIVE_TO = "2100-01-01T00:00:00Z";
    private static final String FUTURE_FROM = "2100-01-01T00:00:00Z";
    private static final String FUTURE_TO = "2100-02-01T00:00:00Z";
    private static final String PAST_FROM = "2000-01-01T00:00:00Z";
    private static final String PAST_TO = "2000-02-01T00:00:00Z";

    private long readyDocument() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\",\"ja\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"原文\"}]");
        submitTranslation(docId, "s1", "en", "alice", "hello-en", 1, newRequestId());
        submitTranslation(docId, "s1", "ja", "alice", "hello-ja", 1, newRequestId());
        approve(docId, "s1", "en", "bob", 1, newRequestId());
        approve(docId, "s1", "ja", "carol", 1, newRequestId());
        return docId;
    }

    @Test
    @DisplayName("登记命中当前时刻的冲突：201，已有评审标记 CONFLICTED，诊断可见有效冲突与排除原因")
    void declareConflictMarksExistingApprovalConflicted() throws Exception {
        long docId = readyDocument();

        ApiResult declared = declareConflict(docId, "bob", "en", EFFECTIVE_FROM, EFFECTIVE_TO,
                "bob 与本文档英文译者存在雇佣关系", newRequestId());
        assertThat(declared.status()).isEqualTo(201);
        assertThat(declared.body().get("status").asText()).isEqualTo("ACTIVE");
        assertThat(declared.body().get("reviewer").asText()).isEqualTo("bob");
        assertThat(declared.body().get("declaredBy").asText()).isEqualTo("bob");
        assertThat(declared.body().get("releasedAt").isNull()).isTrue();

        JsonNode approval = jdbcQuery(
                "SELECT review_status FROM approval WHERE document_id = ? AND segment_id = 's1' AND language = 'en'",
                docId);
        assertThat(approval.get("review_status").asText()).isEqualTo("CONFLICTED");

        ApiResult diagnostics = getJson("/api/documents/" + docId + "/conflicts/diagnostics");
        assertThat(diagnostics.status()).isEqualTo(200);
        assertThat(diagnostics.body().get("activeConflicts")).hasSize(1);
        assertThat(diagnostics.body().get("requiredApprovals").asInt()).isEqualTo(2);
        assertThat(diagnostics.body().get("effectiveApprovals").asInt()).isEqualTo(1);
        assertThat(diagnostics.body().get("missingApprovals").asInt()).isEqualTo(1);
        JsonNode bobReview = diagnostics.body().get("reviews").get(0);
        // 评审按 segment_id, language 排序：en 在 ja 前
        assertThat(bobReview.get("reviewer").asText()).isEqualTo("bob");
        assertThat(bobReview.get("eligible").asBoolean()).isFalse();
        assertThat(bobReview.get("blockingDeclarationId").asLong())
                .isEqualTo(declared.body().get("declarationId").asLong());
    }

    @Test
    @DisplayName("命中有效冲突后发布 422 QUORUM_NOT_MET：返回缺失总数与受影响段落明细（实际值/要求值/差额），不发布部分语种")
    void publishBlockedByConflictReturnsStableDeficits() throws Exception {
        long docId = readyDocument();
        long declarationId = declareConflict(docId, "bob", "en", EFFECTIVE_FROM, EFFECTIVE_TO,
                "利益冲突", newRequestId()).body().get("declarationId").asLong();
        // 登记冲突不改草稿版本，当前草稿版本 3（建文档 1 + 两次译文提交）
        ApiResult result = publish(docId, 3, 0, newRequestId());
        assertThat(result.status()).isEqualTo(422);
        assertThat(result.body().get("error").asText()).isEqualTo("QUORUM_NOT_MET");
        assertThat(result.body().get("missingApprovals").asInt()).isEqualTo(1);
        assertThat(result.body().get("deficits")).hasSize(1);
        JsonNode deficit = result.body().get("deficits").get(0);
        assertThat(deficit.get("segmentId").asText()).isEqualTo("s1");
        assertThat(deficit.get("language").asText()).isEqualTo("en");
        assertThat(deficit.get("requiredApprovals").asInt()).isEqualTo(1);
        assertThat(deficit.get("effectiveApprovals").asInt()).isEqualTo(0);
        assertThat(deficit.get("missingApprovals").asInt()).isEqualTo(1);
        assertThat(deficit.get("reason").asText()).contains(String.valueOf(declarationId));

        // 不发布任何部分语种：无快照、发布版本仍为 0
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_snapshot WHERE document_id = ?", Integer.class, docId)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT published_version FROM document WHERE document_id = ?", Integer.class, docId)).isZero();
    }

    @Test
    @DisplayName("命中有效冲突不得提交批准或否决：403，不写入评审与历史")
    void conflictBlocksReviewSubmission() throws Exception {
        long docId = readyDocument();
        declareConflict(docId, "bob", "en", EFFECTIVE_FROM, EFFECTIVE_TO, "利益冲突", newRequestId());

        ApiResult approve = approve(docId, "s1", "en", "bob", 1, newRequestId());
        assertThat(approve.status()).isEqualTo(403);
        assertThat(approve.body().get("error").asText()).isEqualTo("FORBIDDEN");
        ApiResult reject = reject(docId, "s1", "en", "bob", 1, newRequestId());
        assertThat(reject.status()).isEqualTo(403);

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM review_history WHERE document_id = ? AND reviewer = 'bob'",
                Integer.class, docId)).isEqualTo(2); // 原始批准 VALID + 登记冲突追加 CONFLICTED，阻止的提交不入历史
    }

    @Test
    @DisplayName("冲突区间校验：倒置 422、原因空白 400、语种越界 422、语言码归一为小写")
    void invalidConflictRanges() throws Exception {
        long docId = readyDocument();

        ApiResult inverted = declareConflict(docId, "bob", "en", EFFECTIVE_TO, EFFECTIVE_FROM, "原因",
                newRequestId());
        assertThat(inverted.status()).isEqualTo(422);
        ApiResult sameEndpoints = declareConflict(docId, "bob", "en",
                "2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z", "原因", newRequestId());
        assertThat(sameEndpoints.status()).isEqualTo(422);

        ApiResult blankReason = declareConflict(docId, "bob", "en", EFFECTIVE_FROM, EFFECTIVE_TO, "   ",
                newRequestId());
        assertThat(blankReason.status()).isEqualTo(400);

        ApiResult wrongLanguage = declareConflict(docId, "bob", "fr", EFFECTIVE_FROM, EFFECTIVE_TO, "原因",
                newRequestId());
        assertThat(wrongLanguage.status()).isEqualTo(422);

        ApiResult normalized = declareConflict(docId, "bob", "EN", EFFECTIVE_FROM, EFFECTIVE_TO, "原因",
                newRequestId());
        assertThat(normalized.status()).isEqualTo(201);
        assertThat(normalized.body().get("language").asText()).isEqualTo("en");
    }

    @Test
    @DisplayName("同评审人同语种重叠声明 409；相邻端点不重叠可登记；不同语种互不冲突")
    void overlappingDeclarationsRejected() throws Exception {
        long docId = readyDocument();
        ApiResult first = declareConflict(docId, "bob", "en",
                "2026-01-01T00:00:00Z", "2026-01-10T00:00:00Z", "第一次", newRequestId());
        assertThat(first.status()).isEqualTo(201);

        ApiResult overlap = declareConflict(docId, "bob", "en",
                "2026-01-09T00:00:00Z", "2026-01-20T00:00:00Z", "重叠", newRequestId());
        assertThat(overlap.status()).isEqualTo(409);

        // 左闭右开：[01-10, 01-20) 与上一条端点相邻，不算重叠
        ApiResult adjacent = declareConflict(docId, "bob", "en",
                "2026-01-10T00:00:00Z", "2026-01-20T00:00:00Z", "相邻", newRequestId());
        assertThat(adjacent.status()).isEqualTo(201);

        ApiResult otherLanguage = declareConflict(docId, "bob", "ja",
                "2026-01-01T00:00:00Z", "2026-01-20T00:00:00Z", "另一语种", newRequestId());
        assertThat(otherLanguage.status()).isEqualTo(201);
    }

    @Test
    @DisplayName("批量登记：全部合法时一次写入多条；任一范围非法整批回滚，评审资格不变且无半成品")
    void batchDeclareAtomicity() throws Exception {
        long docId = readyDocument();

        // 含一条区间倒置：整批失败
        String invalidBatch = "[{"
                + "\"language\":\"en\",\"validFrom\":\"" + EFFECTIVE_FROM + "\",\"validTo\":\"" + EFFECTIVE_TO
                + "\",\"reason\":\"英文冲突\"},"
                + "{\"language\":\"ja\",\"validFrom\":\"" + EFFECTIVE_TO + "\",\"validTo\":\"" + EFFECTIVE_FROM
                + "\",\"reason\":\"倒置\"}]";
        ApiResult invalid = batchDeclareConflict(docId, "bob", invalidBatch, newRequestId());
        assertThat(invalid.status()).isEqualTo(422);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM conflict_declaration WHERE document_id = ?", Integer.class, docId)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM conflict_event WHERE document_id = ?", Integer.class, docId)).isZero();
        // 两条原始批准各留一条历史；整批回滚不追加任何资格变化记录
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM review_history WHERE document_id = ?", Integer.class, docId)).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM approval WHERE document_id = ? AND review_status = 'VALID'",
                Integer.class, docId)).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM approval WHERE document_id = ? AND review_status = 'CONFLICTED'",
                Integer.class, docId)).isZero();

        // 批内相互重叠：整批 409
        String overlappingBatch = "[{"
                + "\"language\":\"en\",\"validFrom\":\"2026-01-01T00:00:00Z\",\"validTo\":\"2026-01-10T00:00:00Z\","
                + "\"reason\":\"a\"},"
                + "{\"language\":\"en\",\"validFrom\":\"2026-01-05T00:00:00Z\",\"validTo\":\"2026-01-20T00:00:00Z\","
                + "\"reason\":\"b\"}]";
        assertThat(batchDeclareConflict(docId, "bob", overlappingBatch, newRequestId()).status()).isEqualTo(409);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM conflict_declaration WHERE document_id = ?", Integer.class, docId)).isZero();

        // 合法批量：同一评审人两条互不重叠的 en 区间（一条当前生效、一条未来生效）
        ApiResult ok = batchDeclareConflict(docId, "bob",
                "[{\"language\":\"en\",\"validFrom\":\"" + EFFECTIVE_FROM + "\",\"validTo\":\"" + EFFECTIVE_TO
                        + "\",\"reason\":\"英文冲突\"},"
                        + "{\"language\":\"en\",\"validFrom\":\"" + FUTURE_FROM + "\",\"validTo\":\"" + FUTURE_TO
                        + "\",\"reason\":\"未来英文冲突\"}]",
                newRequestId());
        assertThat(ok.status()).isEqualTo(201);
        assertThat(ok.body().get("declaredCount").asInt()).isEqualTo(2);
        assertThat(ok.body().get("declarations")).hasSize(2);
        // 第一条当前生效：评审被排除，法定人数缺 1；第二条未来生效：暂不排除
        ApiResult diagnostics = getJson("/api/documents/" + docId + "/conflicts/diagnostics");
        assertThat(diagnostics.body().get("missingApprovals").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("解除冲突：不同于声明人的 COMPLIANCE 确认后评审恢复 VALID、追加历史，随后发布成功")
    void releaseConflictRestoresEligibility() throws Exception {
        long docId = readyDocument();
        long declarationId = declareConflict(docId, "bob", "en", EFFECTIVE_FROM, EFFECTIVE_TO,
                "利益冲突", newRequestId()).body().get("declarationId").asLong();
        assertThat(publish(docId, 3, 0, newRequestId()).status()).isEqualTo(422);

        // 非合规角色 / 声明人本人均不得解除
        assertThat(releaseConflict(docId, declarationId, "eve", "REVIEWER", newRequestId()).status())
                .isEqualTo(403);
        assertThat(releaseConflict(docId, declarationId, "bob", "COMPLIANCE", newRequestId()).status())
                .isEqualTo(403);

        ApiResult released = releaseConflict(docId, declarationId, "eve", "COMPLIANCE", newRequestId());
        assertThat(released.status()).isEqualTo(200);
        assertThat(released.body().get("status").asText()).isEqualTo("RELEASED");
        assertThat(released.body().get("releasedBy").asText()).isEqualTo("eve");
        assertThat(released.body().get("releasedAt").isNull()).isFalse();

        // 评审资格恢复，发布成功
        assertThat(jdbc.queryForObject(
                "SELECT review_status FROM approval WHERE document_id = ? AND reviewer = 'bob'",
                String.class, docId)).isEqualTo("VALID");
        assertThat(publish(docId, 3, 0, newRequestId()).status()).isEqualTo(201);

        // 重复解除 409
        assertThat(releaseConflict(docId, declarationId, "eve", "COMPLIANCE", newRequestId()).status())
                .isEqualTo(409);
    }

    @Test
    @DisplayName("历史记录稳定：冲突事件 DECLARED/RELEASED 与评审 VALID/CONFLICTED/VALID 顺序、字段与 null 语义不变")
    void historyStableAndAppendOnly() throws Exception {
        long docId = readyDocument();
        long declarationId = declareConflict(docId, "bob", "en", EFFECTIVE_FROM, EFFECTIVE_TO,
                "利益冲突原因", newRequestId()).body().get("declarationId").asLong();
        releaseConflict(docId, declarationId, "eve", "COMPLIANCE", newRequestId());

        ApiResult events = getJson("/api/documents/" + docId + "/conflicts/events?declarationId=" + declarationId);
        assertThat(events.status()).isEqualTo(200);
        assertThat(events.body()).hasSize(2);
        assertThat(events.body().get(0).get("eventType").asText()).isEqualTo("DECLARED");
        assertThat(events.body().get(0).get("actorRole").isNull()).isTrue();
        assertThat(events.body().get(0).get("reason").asText()).isEqualTo("利益冲突原因");
        assertThat(events.body().get(1).get("eventType").asText()).isEqualTo("RELEASED");
        assertThat(events.body().get(1).get("actor").asText()).isEqualTo("eve");
        assertThat(events.body().get(1).get("actorRole").asText()).isEqualTo("COMPLIANCE");
        // 解除事件仍固化登记时的区间与原因
        assertThat(events.body().get(1).get("validFrom").asText()).isEqualTo(EFFECTIVE_FROM);
        assertThat(events.body().get(1).get("validTo").asText()).isEqualTo(EFFECTIVE_TO);

        ApiResult history = getJson(
                "/api/documents/" + docId + "/conflicts/reviews/history?segmentId=s1&language=en");
        assertThat(history.status()).isEqualTo(200);
        assertThat(history.body()).hasSize(3);
        assertThat(history.body().get(0).get("decision").asText()).isEqualTo("APPROVED");
        assertThat(history.body().get(0).get("reviewStatus").asText()).isEqualTo("VALID");
        assertThat(history.body().get(0).get("conflictDeclarationId").isNull()).isTrue();
        assertThat(history.body().get(1).get("reviewStatus").asText()).isEqualTo("CONFLICTED");
        assertThat(history.body().get(1).get("conflictDeclarationId").asLong()).isEqualTo(declarationId);
        assertThat(history.body().get(2).get("reviewStatus").asText()).isEqualTo("VALID");
        assertThat(history.body().get(2).get("actor").asText()).isEqualTo("eve");
        assertThat(history.body().get(2).get("conflictDeclarationId").isNull()).isTrue();

        // 全部事件按事件 ID 全局升序
        ApiResult allEvents = getJson("/api/documents/" + docId + "/conflicts/events");
        assertThat(allEvents.body()).hasSize(2);
        assertThat(allEvents.body().get(0).get("eventId").asLong())
                .isLessThan(allEvents.body().get(1).get("eventId").asLong());
    }

    @Test
    @DisplayName("解除不追溯旧快照：冲突前发布的快照保持原样，快照固化发布时冲突证据")
    void releaseDoesNotInvalidateSnapshot() throws Exception {
        long docId = readyDocument();
        assertThat(publish(docId, 3, 0, newRequestId()).status()).isEqualTo(201);

        // 发布后登记冲突并解除：历史快照不变
        long declarationId = declareConflict(docId, "bob", "en", EFFECTIVE_FROM, EFFECTIVE_TO,
                "利益冲突", newRequestId()).body().get("declarationId").asLong();
        releaseConflict(docId, declarationId, "eve", "COMPLIANCE", newRequestId());

        ApiResult release1 = getJson("/api/documents/" + docId + "/releases/1");
        assertThat(release1.body().get("conflicts")).isEmpty();
        assertThat(release1.body().get("segments").get(0).get("translations").get(0)
                .get("reviewStatus").asText()).isEqualTo("VALID");

        // 再发布：快照固化声明（已 RELEASED，effectiveAtPublish=false）
        // 译文与源文均未变化，草稿版本仍为 3，但发布版本已为 1
        assertThat(publish(docId, 3, 1, newRequestId()).status()).isEqualTo(201);
        ApiResult release2 = getJson("/api/documents/" + docId + "/releases/2");
        assertThat(release2.body().get("conflicts")).hasSize(1);
        JsonNode frozenConflict = release2.body().get("conflicts").get(0);
        assertThat(frozenConflict.get("declarationId").asLong()).isEqualTo(declarationId);
        assertThat(frozenConflict.get("status").asText()).isEqualTo("RELEASED");
        assertThat(frozenConflict.get("effectiveAtPublish").asBoolean()).isFalse();
        assertThat(frozenConflict.get("reason").asText()).isEqualTo("利益冲突");
        JsonNode review = release2.body().get("segments").get(0).get("translations").get(0);
        assertThat(review.get("reviewer").asText()).isEqualTo("bob");
        assertThat(review.get("decision").asText()).isEqualTo("APPROVED");
        assertThat(review.get("reviewTermVersion").asInt()).isZero();
        assertThat(review.get("reviewedAt").isNull()).isFalse();
    }

    @Test
    @DisplayName("未来区间与已过区间当前不生效：不标记、不阻止发布")
    void inactiveWindowsDoNotExclude() throws Exception {
        long docId = readyDocument();
        assertThat(declareConflict(docId, "bob", "en", FUTURE_FROM, FUTURE_TO, "未来冲突",
                newRequestId()).status()).isEqualTo(201);
        assertThat(declareConflict(docId, "carol", "ja", PAST_FROM, PAST_TO, "已过冲突",
                newRequestId()).status()).isEqualTo(201);

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM approval WHERE review_status = 'CONFLICTED' AND document_id = ?",
                Integer.class, docId)).isZero();
        ApiResult diagnostics = getJson("/api/documents/" + docId + "/conflicts/diagnostics");
        assertThat(diagnostics.body().get("activeConflicts")).isEmpty();
        assertThat(diagnostics.body().get("missingApprovals").asInt()).isZero();
        // 未来区间不阻止评审提交
        assertThat(approve(docId, "s1", "en", "bob", 1, newRequestId()).status()).isEqualTo(200);
        assertThat(publish(docId, 3, 0, newRequestId()).status()).isEqualTo(201);
    }

    @Test
    @DisplayName("否决不计入法定人数：422 QUORUM_NOT_MET；改由其他评审人批准后发布成功")
    void rejectionExcludedFromQuorum() throws Exception {
        long docId = readyDocument();
        // dave 否决 en（当前 bob 已批准，先需要一个“仅否决”的场景：换一篇新文档）
        long docId2 = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"原文\"}]");
        submitTranslation(docId2, "s1", "en", "alice", "hello", 1, newRequestId());
        ApiResult rejected = reject(docId2, "s1", "en", "dave", 1, newRequestId());
        assertThat(rejected.status()).isEqualTo(200);
        assertThat(rejected.body().get("decision").asText()).isEqualTo("REJECTED");
        assertThat(rejected.body().get("reviewStatus").asText()).isEqualTo("VALID");

        ApiResult publishBlocked = publish(docId2, 2, 0, newRequestId());
        assertThat(publishBlocked.status()).isEqualTo(422);
        assertThat(publishBlocked.body().get("error").asText()).isEqualTo("QUORUM_NOT_MET");
        assertThat(publishBlocked.body().get("deficits").get(0).get("reason").asText()).contains("否决");

        // 另一评审人批准：覆盖该段落语种的当前评审为有效批准
        assertThat(approve(docId2, "s1", "en", "bob", 1, newRequestId()).status()).isEqualTo(200);
        assertThat(publish(docId2, 2, 0, newRequestId()).status()).isEqualTo(201);
    }

    @Test
    @DisplayName("冲突写操作幂等：同键同参重放、同键异参 409、失败不占键")
    void conflictIdempotency() throws Exception {
        long docId = readyDocument();
        String requestId = newRequestId();
        ApiResult first = declareConflict(docId, "bob", "en", EFFECTIVE_FROM, EFFECTIVE_TO, "原因", requestId);
        assertThat(first.status()).isEqualTo(201);

        ApiResult replay = declareConflict(docId, "bob", "en", EFFECTIVE_FROM, EFFECTIVE_TO, "原因", requestId);
        assertThat(replay.status()).isEqualTo(201);
        assertThat(replay.body().get("declarationId").asLong())
                .isEqualTo(first.body().get("declarationId").asLong());
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM conflict_declaration WHERE document_id = ?", Integer.class, docId))
                .isEqualTo(1);

        ApiResult different = declareConflict(docId, "bob", "en", EFFECTIVE_FROM, EFFECTIVE_TO, "不同原因",
                requestId);
        assertThat(different.status()).isEqualTo(409);

        // 失败不占键：先用倒置区间触发 422，再用同键修正后成功
        String failKey = newRequestId();
        ApiResult failed = declareConflict(docId, "carol", "ja", EFFECTIVE_TO, EFFECTIVE_FROM, "倒置", failKey);
        assertThat(failed.status()).isEqualTo(422);
        ApiResult retried = declareConflict(docId, "carol", "ja", EFFECTIVE_FROM, EFFECTIVE_TO, "合法", failKey);
        assertThat(retried.status()).isEqualTo(201);
    }

    @Test
    @DisplayName("诊断与查询只读：重复调用不产生历史/状态变化；文档不存在 404")
    void readsDoNotChangeState() throws Exception {
        long docId = readyDocument();
        declareConflict(docId, "bob", "en", EFFECTIVE_FROM, EFFECTIVE_TO, "利益冲突", newRequestId());

        Integer declarationsBefore = jdbc.queryForObject(
                "SELECT COUNT(*) FROM conflict_declaration WHERE document_id = ?", Integer.class, docId);
        Integer historyBefore = jdbc.queryForObject(
                "SELECT COUNT(*) FROM review_history WHERE document_id = ?", Integer.class, docId);
        for (int i = 0; i < 3; i++) {
            assertThat(getJson("/api/documents/" + docId + "/conflicts/diagnostics").status()).isEqualTo(200);
            assertThat(getJson("/api/documents/" + docId + "/conflicts").status()).isEqualTo(200);
            assertThat(getJson("/api/documents/" + docId + "/conflicts/events").status()).isEqualTo(200);
            assertThat(getJson("/api/documents/" + docId + "/conflicts/reviews/history").status()).isEqualTo(200);
        }
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM conflict_declaration WHERE document_id = ?", Integer.class, docId))
                .isEqualTo(declarationsBefore);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM review_history WHERE document_id = ?", Integer.class, docId))
                .isEqualTo(historyBefore);

        assertThat(getJson("/api/documents/999999/conflicts").status()).isEqualTo(404);
        assertThat(getJson("/api/documents/" + docId + "/conflicts/events?declarationId=999999").status())
                .isEqualTo(404);
    }

    private JsonNode jdbcQuery(String sql, long documentId) {
        return jdbc.queryForObject(sql, (rs, n) -> objectMapper.createObjectNode()
                .put("review_status", rs.getString("review_status")), documentId);
    }
}
