package com.example.starter.translation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;

/**
 * 译文评审利益冲突 API 测试：声明、资格排除、法定人数重算、解除与历史，
 * 覆盖主流程、失败分支与状态边界；业务时间由可控时钟提供（UTC）。
 */
class ConflictApiTest extends AbstractIntegrationTest {

    private static final Instant BASE = Instant.parse("2026-09-27T00:00:00Z");
    /** 覆盖 BASE 时刻的有效区间。 */
    private static final String EFFECTIVE_FROM = "2026-09-26T00:00:00Z";
    private static final String EFFECTIVE_TO = "2026-09-28T00:00:00Z";

    @MockitoBean
    private Clock clock;

    private final AtomicReference<Instant> now = new AtomicReference<>(BASE);

    @BeforeEach
    void stubClock() {
        now.set(BASE);
        lenient().when(clock.instant()).thenAnswer(invocation -> now.get());
    }

    private static String declaration(String reviewer, String language, String validFrom, String validTo,
                                      String reason) {
        return "{\"reviewer\":\"" + reviewer + "\",\"language\":\"" + language + "\",\"validFrom\":\""
                + validFrom + "\",\"validTo\":\"" + validTo + "\",\"reason\":\"" + reason + "\"}";
    }

    @Test
    @DisplayName("主流程：有效冲突排除既有评审，发布 422 返回缺失数与受影响段落；解除后重新批准可发布")
    void declareExcludeLiftAndRepublish() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\",\"ja\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"原文\"}]");
        submitTranslation(docId, "s1", "en", "alice", "hello", 1, newRequestId());
        submitTranslation(docId, "s1", "ja", "carol", "こんにちは", 1, newRequestId());
        approve(docId, "s1", "en", "bob", 1, newRequestId());
        approve(docId, "s1", "ja", "dave", 1, newRequestId());
        // 当前草稿版本 3、发布版本 0

        // 登记 bob 在 en 的有效冲突：既有批准被排除
        ApiResult declared = declareConflicts(docId, "bob",
                "[" + declaration("bob", "en", EFFECTIVE_FROM, EFFECTIVE_TO, "与作者存在利益关系") + "]",
                newRequestId());
        assertThat(declared.status()).isEqualTo(201);
        assertThat(declared.body().get("declaredCount").asInt()).isEqualTo(1);
        assertThat(declared.body().get("excludedReviewCount").asInt()).isEqualTo(1);
        long conflictId = declared.body().get("conflicts").get(0).get("conflictId").asLong();
        assertThat(declared.body().get("conflicts").get(0).get("status").asText()).isEqualTo("ACTIVE");
        assertThat(declared.body().get("conflicts").get(0).get("validFrom").asText())
                .isEqualTo(EFFECTIVE_FROM);
        assertThat(declared.body().get("conflicts").get(0).get("validTo").asText())
                .isEqualTo(EFFECTIVE_TO);
        assertThat(declared.body().get("conflicts").get(0).get("declaredBy").asText()).isEqualTo("bob");

        // 排除证据固化：批准标记 CONFLICTED，排除记录固化当时版本
        assertThat(jdbc.queryForObject("SELECT status FROM approval WHERE document_id = ? AND language = 'en'",
                String.class, docId)).isEqualTo("CONFLICTED");
        assertThat(jdbc.queryForObject(
                "SELECT conflict_id FROM approval WHERE document_id = ? AND language = 'en'",
                Long.class, docId)).isEqualTo(conflictId);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM review_exclusion WHERE document_id = ? AND conflict_id = ? "
                        + "AND source_version = 1 AND translation_version = 1",
                Integer.class, docId, conflictId)).isEqualTo(1);

        // 法定人数不足：发布 422，稳定返回缺失/要求/实际评审数与受影响段落，不产生部分快照
        ApiResult publish = publish(docId, 3, 0, newRequestId());
        assertThat(publish.status()).isEqualTo(422);
        assertThat(publish.body().get("error").asText()).isEqualTo("QUORUM_NOT_MET");
        assertThat(publish.body().get("missingReviews").asInt()).isEqualTo(1);
        assertThat(publish.body().get("requiredReviews").asInt()).isEqualTo(2);
        assertThat(publish.body().get("actualReviews").asInt()).isEqualTo(1);
        assertThat(publish.body().get("affectedScopes")).hasSize(1);
        assertThat(publish.body().get("affectedScopes").get(0).get("segmentId").asText()).isEqualTo("s1");
        assertThat(publish.body().get("affectedScopes").get(0).get("language").asText()).isEqualTo("en");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_snapshot WHERE document_id = ?", Integer.class, docId))
                .isZero();
        assertThat(jdbc.queryForObject(
                "SELECT published_version FROM document WHERE document_id = ?", Integer.class, docId))
                .isZero();

        // 诊断：缺失 1、被排除 1、当前有效冲突 1
        ApiResult diagnostics = getJson("/api/documents/" + docId + "/reviewer-conflicts/diagnostics");
        assertThat(diagnostics.status()).isEqualTo(200);
        assertThat(diagnostics.body().get("requiredReviews").asInt()).isEqualTo(2);
        assertThat(diagnostics.body().get("effectiveReviews").asInt()).isEqualTo(1);
        assertThat(diagnostics.body().get("missingReviews").asInt()).isEqualTo(1);
        assertThat(diagnostics.body().get("conflictedReviewCount").asInt()).isEqualTo(1);
        assertThat(diagnostics.body().get("effectiveConflicts")).hasSize(1);

        // 命中有效冲突的评审人不得再批准该范围译文
        ApiResult blockedApprove = approve(docId, "s1", "en", "bob", 1, newRequestId());
        assertThat(blockedApprove.status()).isEqualTo(422);

        // 合规角色（非声明人）解除：追加历史，只影响后续评审与发布
        ApiResult lifted = liftConflict(docId, conflictId, "officer1", "COMPLIANCE", "已签署回避协议",
                newRequestId());
        assertThat(lifted.status()).isEqualTo(200);
        assertThat(lifted.body().get("status").asText()).isEqualTo("LIFTED");
        assertThat(lifted.body().get("liftedBy").asText()).isEqualTo("officer1");
        assertThat(lifted.body().get("liftReason").asText()).isEqualTo("已签署回避协议");

        // 历史：DECLARED 与 LIFTED 按序追加，登记时区间与原因固化
        ApiResult history = getJson("/api/documents/" + docId + "/reviewer-conflicts/history");
        assertThat(history.status()).isEqualTo(200);
        assertThat(history.body().get("records")).hasSize(2);
        var first = history.body().get("records").get(0);
        assertThat(first.get("action").asText()).isEqualTo("DECLARED");
        assertThat(first.get("actor").asText()).isEqualTo("bob");
        assertThat(first.get("reason").asText()).isEqualTo("与作者存在利益关系");
        assertThat(first.get("validFrom").asText()).isEqualTo(EFFECTIVE_FROM);
        var second = history.body().get("records").get(1);
        assertThat(second.get("action").asText()).isEqualTo("LIFTED");
        assertThat(second.get("actor").asText()).isEqualTo("officer1");
        assertThat(second.get("reason").asText()).isEqualTo("已签署回避协议");
        assertThat(second.get("historyId").asLong()).isGreaterThan(first.get("historyId").asLong());

        // 解除不恢复已排除的批准：重新批准后发布成功，快照固化法定人数
        assertThat(jdbc.queryForObject("SELECT status FROM approval WHERE document_id = ? AND language = 'en'",
                String.class, docId)).isEqualTo("CONFLICTED");
        ApiResult reApprove = approve(docId, "s1", "en", "bob", 1, newRequestId());
        assertThat(reApprove.status()).isEqualTo(200);
        ApiResult published = publish(docId, 3, 0, newRequestId());
        assertThat(published.status()).isEqualTo(201);
        ApiResult release = getJson("/api/documents/" + docId + "/releases/1");
        assertThat(release.body().get("quorum").get("requiredReviews").asInt()).isEqualTo(2);
        assertThat(release.body().get("quorum").get("actualReviews").asInt()).isEqualTo(2);

        // 明细查询：声明状态 LIFTED，解除字段非空
        ApiResult list = getJson("/api/documents/" + docId + "/reviewer-conflicts");
        assertThat(list.body().get("conflicts")).hasSize(1);
        var conflictView = list.body().get("conflicts").get(0);
        assertThat(conflictView.get("status").asText()).isEqualTo("LIFTED");
        assertThat(conflictView.get("liftedBy").asText()).isEqualTo("officer1");
        assertThat(conflictView.get("liftedAt").asText()).isNotBlank();
    }

    @Test
    @DisplayName("声明校验：区间倒置或为空 422、原因空 400、语言越界 422；批量任一非法整批不生效")
    void declareValidationAndBatchAtomicity() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"原文\"}]");
        submitTranslation(docId, "s1", "en", "alice", "hello", 1, newRequestId());
        approve(docId, "s1", "en", "bob", 1, newRequestId());

        // 区间倒置
        ApiResult inverted = declareConflicts(docId, "bob",
                "[" + declaration("bob", "en", EFFECTIVE_TO, EFFECTIVE_FROM, "倒置") + "]",
                newRequestId());
        assertThat(inverted.status()).isEqualTo(422);
        // 空区间（端点相等）
        ApiResult empty = declareConflicts(docId, "bob",
                "[" + declaration("bob", "en", EFFECTIVE_FROM, EFFECTIVE_FROM, "空区间") + "]",
                newRequestId());
        assertThat(empty.status()).isEqualTo(422);
        // 原因为空
        ApiResult blankReason = declareConflicts(docId, "bob",
                "[{\"reviewer\":\"bob\",\"language\":\"en\",\"validFrom\":\"" + EFFECTIVE_FROM
                        + "\",\"validTo\":\"" + EFFECTIVE_TO + "\",\"reason\":\"  \"}]",
                newRequestId());
        assertThat(blankReason.status()).isEqualTo(400);
        // 语言不在目标语言中
        ApiResult wrongLanguage = declareConflicts(docId, "bob",
                "[" + declaration("bob", "fr", EFFECTIVE_FROM, EFFECTIVE_TO, "语言越界") + "]",
                newRequestId());
        assertThat(wrongLanguage.status()).isEqualTo(422);
        // 空批量
        ApiResult emptyBatch = declareConflicts(docId, "bob", "[]", newRequestId());
        assertThat(emptyBatch.status()).isEqualTo(400);

        // 批量预校验：第二条非法，整批不生效（第一条合法也不登记、不排除）
        ApiResult batch = declareConflicts(docId, "bob",
                "[" + declaration("bob", "en", EFFECTIVE_FROM, EFFECTIVE_TO, "合法") + ","
                        + declaration("bob", "en", EFFECTIVE_TO, EFFECTIVE_FROM, "倒置") + "]",
                newRequestId());
        assertThat(batch.status()).isEqualTo(422);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM reviewer_conflict WHERE document_id = ?", Integer.class, docId))
                .isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM reviewer_conflict_history WHERE document_id = ?",
                Integer.class, docId)).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM approval WHERE document_id = ?",
                String.class, docId)).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("重叠声明：与既有声明或批内重叠返回 409；半开区间端点相接不算重叠")
    void overlappingDeclarations() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\",\"ja\"]", "[]");
        // 既有声明 [00:00, 02:00)
        ApiResult first = declareConflicts(docId, "bob",
                "[" + declaration("bob", "en", "2026-09-27T00:00:00Z", "2026-09-27T02:00:00Z", "原因一")
                        + "]", newRequestId());
        assertThat(first.status()).isEqualTo(201);

        // 与既有声明重叠 [01:00, 03:00) → 409
        ApiResult overlap = declareConflicts(docId, "bob",
                "[" + declaration("bob", "en", "2026-09-27T01:00:00Z", "2026-09-27T03:00:00Z", "重叠")
                        + "]", newRequestId());
        assertThat(overlap.status()).isEqualTo(409);

        // 端点相接 [02:00, 03:00) 不重叠 → 201
        ApiResult touching = declareConflicts(docId, "bob",
                "[" + declaration("bob", "en", "2026-09-27T02:00:00Z", "2026-09-27T03:00:00Z", "相接")
                        + "]", newRequestId());
        assertThat(touching.status()).isEqualTo(201);

        // 不同语种同区间不冲突 → 201
        ApiResult otherLanguage = declareConflicts(docId, "bob",
                "[" + declaration("bob", "ja", "2026-09-27T00:00:00Z", "2026-09-27T02:00:00Z", "其他语种")
                        + "]", newRequestId());
        assertThat(otherLanguage.status()).isEqualTo(201);

        // 批内重叠 → 409，整批不生效
        ApiResult batchOverlap = declareConflicts(docId, "dave",
                "[" + declaration("dave", "en", "2026-09-27T00:00:00Z", "2026-09-27T02:00:00Z", "一")
                        + "," + declaration("dave", "en", "2026-09-27T01:00:00Z",
                        "2026-09-27T03:00:00Z", "二") + "]", newRequestId());
        assertThat(batchOverlap.status()).isEqualTo(409);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM reviewer_conflict WHERE document_id = ? AND reviewer = 'dave'",
                Integer.class, docId)).isZero();
    }

    @Test
    @DisplayName("解除约束：非合规角色 422、确认人等于声明人 422、缺角色头 400、不存在 404、重复解除 409")
    void liftConstraints() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]", "[]");
        ApiResult declared = declareConflicts(docId, "bob",
                "[" + declaration("bob", "en", EFFECTIVE_FROM, EFFECTIVE_TO, "原因") + "]",
                newRequestId());
        long conflictId = declared.body().get("conflicts").get(0).get("conflictId").asLong();

        // 非合规角色
        ApiResult wrongRole = liftConflict(docId, conflictId, "officer1", "REVIEWER", "解除",
                newRequestId());
        assertThat(wrongRole.status()).isEqualTo(422);
        // 确认人与声明人相同
        ApiResult sameActor = liftConflict(docId, conflictId, "bob", "COMPLIANCE", "解除",
                newRequestId());
        assertThat(sameActor.status()).isEqualTo(422);
        // 缺少 X-Actor-Role 头
        ApiResult noRole = liftConflict(docId, conflictId, "officer1", null, "解除", newRequestId());
        assertThat(noRole.status()).isEqualTo(400);
        // 冲突声明不存在
        ApiResult missing = liftConflict(docId, 999999, "officer1", "COMPLIANCE", "解除",
                newRequestId());
        assertThat(missing.status()).isEqualTo(404);
        // 失败均不改变状态
        assertThat(jdbc.queryForObject(
                "SELECT status FROM reviewer_conflict WHERE conflict_id = ?", String.class, conflictId))
                .isEqualTo("ACTIVE");

        // 合规解除成功，再次解除 409
        ApiResult lifted = liftConflict(docId, conflictId, "officer1", "COMPLIANCE", "解除",
                newRequestId());
        assertThat(lifted.status()).isEqualTo(200);
        ApiResult again = liftConflict(docId, conflictId, "officer2", "COMPLIANCE", "重复解除",
                newRequestId());
        assertThat(again.status()).isEqualTo(409);

        // 历史只有 DECLARED 与一次 LIFTED，登记记录未被改写
        ApiResult history = getJson("/api/documents/" + docId + "/reviewer-conflicts/history");
        assertThat(history.body().get("records")).hasSize(2);
        assertThat(history.body().get("records").get(0).get("action").asText()).isEqualTo("DECLARED");
        assertThat(history.body().get("records").get(0).get("reason").asText()).isEqualTo("原因");
    }

    @Test
    @DisplayName("未来区间声明不立即排除；时钟进入区间后发布时重算并惰性排除，诊断只读不改状态")
    void futureConflictAndLazyExclusionAtPublish() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"原文\"}]");
        submitTranslation(docId, "s1", "en", "alice", "hello", 1, newRequestId());
        approve(docId, "s1", "en", "bob", 1, newRequestId());
        // 未来区间 [BASE+2h, BASE+3h)
        ApiResult declared = declareConflicts(docId, "bob",
                "[" + declaration("bob", "en", "2026-09-27T02:00:00Z", "2026-09-27T03:00:00Z",
                        "未来冲突") + "]", newRequestId());
        assertThat(declared.status()).isEqualTo(201);
        assertThat(declared.body().get("excludedReviewCount").asInt()).isEqualTo(0);
        assertThat(jdbc.queryForObject("SELECT status FROM approval WHERE document_id = ?",
                String.class, docId)).isEqualTo("ACTIVE");

        // 冲突未生效：发布成功
        ApiResult published = publish(docId, 2, 0, newRequestId());
        assertThat(published.status()).isEqualTo(201);

        // 修订并重新提交、批准（时钟仍在区间外）
        putJson("/api/documents/" + docId + "/segments/s1/source",
                "{\"requestId\":\"" + newRequestId() + "\",\"sourceText\":\"原文v2\"}");
        submitTranslation(docId, "s1", "en", "alice", "hello v2", 2, newRequestId());
        approve(docId, "s1", "en", "bob", 2, newRequestId());

        // 时钟进入冲突区间：诊断报告缺口但不改状态
        now.set(Instant.parse("2026-09-27T02:30:00Z"));
        ApiResult diagnostics = getJson("/api/documents/" + docId + "/reviewer-conflicts/diagnostics");
        assertThat(diagnostics.body().get("missingReviews").asInt()).isEqualTo(1);
        assertThat(diagnostics.body().get("effectiveConflicts")).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT status FROM approval WHERE document_id = ?",
                String.class, docId)).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM review_exclusion WHERE document_id = ?", Integer.class, docId))
                .isZero();

        // 发布时重算：惰性排除命中冲突的批准，法定人数不足 422
        ApiResult publish = publish(docId, 4, 1, newRequestId());
        assertThat(publish.status()).isEqualTo(422);
        assertThat(publish.body().get("error").asText()).isEqualTo("QUORUM_NOT_MET");
        assertThat(publish.body().get("missingReviews").asInt()).isEqualTo(1);
        assertThat(publish.body().get("requiredReviews").asInt()).isEqualTo(1);
        assertThat(publish.body().get("actualReviews").asInt()).isEqualTo(0);
        // 发布失败整体回滚：惰性排除不持久化，查询不到半成品
        assertThat(jdbc.queryForObject("SELECT status FROM approval WHERE document_id = ?",
                String.class, docId)).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM review_exclusion WHERE document_id = ?", Integer.class, docId))
                .isZero();
        // 已发布快照不受后续排除影响
        ApiResult release = getJson("/api/documents/" + docId + "/releases/1");
        assertThat(release.status()).isEqualTo(200);
        assertThat(release.body().get("segments").get(0).get("translations").get(0)
                .get("reviewer").asText()).isEqualTo("bob");

        // 时钟走出原区间后登记新的当前有效冲突：登记时持久化排除证据（固化当时译文版本 2）
        now.set(Instant.parse("2026-09-27T03:30:00Z"));
        ApiResult secondDeclare = declareConflicts(docId, "bob",
                "[" + declaration("bob", "en", "2026-09-27T03:00:00Z", "2026-09-27T05:00:00Z",
                        "新的有效冲突") + "]", newRequestId());
        assertThat(secondDeclare.status()).isEqualTo(201);
        assertThat(secondDeclare.body().get("excludedReviewCount").asInt()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM approval WHERE document_id = ?",
                String.class, docId)).isEqualTo("CONFLICTED");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM review_exclusion WHERE document_id = ? AND translation_version = 2",
                Integer.class, docId)).isEqualTo(1);
    }

    @Test
    @DisplayName("解除不追溯：已发布快照在冲突登记与解除后保持有效且内容不变")
    void liftDoesNotInvalidateOldSnapshots() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"原文\"}]");
        submitTranslation(docId, "s1", "en", "alice", "hello", 1, newRequestId());
        approve(docId, "s1", "en", "bob", 1, newRequestId());
        assertThat(publish(docId, 2, 0, newRequestId()).status()).isEqualTo(201);

        ApiResult declared = declareConflicts(docId, "bob",
                "[" + declaration("bob", "en", EFFECTIVE_FROM, EFFECTIVE_TO, "原因") + "]",
                newRequestId());
        long conflictId = declared.body().get("conflicts").get(0).get("conflictId").asLong();
        liftConflict(docId, conflictId, "officer1", "COMPLIANCE", "解除", newRequestId());

        ApiResult release = getJson("/api/documents/" + docId + "/releases/1");
        assertThat(release.status()).isEqualTo(200);
        assertThat(release.body().get("segments").get(0).get("translations").get(0)
                .get("content").asText()).isEqualTo("hello");
        assertThat(release.body().get("segments").get(0).get("translations").get(0)
                .get("reviewer").asText()).isEqualTo("bob");
    }

    @Test
    @DisplayName("幂等：声明同键同参重放原结果，同键异参 409，失败不占键")
    void declareIdempotency() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]", "[]");
        String declarations = "[" + declaration("bob", "en", EFFECTIVE_FROM, EFFECTIVE_TO, "原因") + "]";
        String requestId = newRequestId();
        ApiResult first = declareConflicts(docId, "bob", declarations, requestId);
        assertThat(first.status()).isEqualTo(201);

        // 同键同参：重放，不产生新声明
        ApiResult replay = declareConflicts(docId, "bob", declarations, requestId);
        assertThat(replay.status()).isEqualTo(201);
        assertThat(replay.body().get("conflicts").get(0).get("conflictId").asLong())
                .isEqualTo(first.body().get("conflicts").get(0).get("conflictId").asLong());
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM reviewer_conflict WHERE document_id = ?", Integer.class, docId))
                .isEqualTo(1);

        // 同键异参：409
        ApiResult conflict = declareConflicts(docId, "bob",
                "[" + declaration("bob", "en", EFFECTIVE_FROM, EFFECTIVE_TO, "另一个原因") + "]",
                requestId);
        assertThat(conflict.status()).isEqualTo(409);

        // 失败不占键：先用某键触发 422，再同键合法声明成功
        String failKey = newRequestId();
        ApiResult failed = declareConflicts(docId, "bob",
                "[" + declaration("bob", "en", EFFECTIVE_TO, EFFECTIVE_FROM, "倒置") + "]", failKey);
        assertThat(failed.status()).isEqualTo(422);
        ApiResult retried = declareConflicts(docId, "bob",
                "[" + declaration("dave", "en", EFFECTIVE_FROM, EFFECTIVE_TO, "合法") + "]", failKey);
        assertThat(retried.status()).isEqualTo(201);
    }
}
