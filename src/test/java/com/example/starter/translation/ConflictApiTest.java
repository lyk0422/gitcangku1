package com.example.starter.translation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 评审利益冲突主流程、失败分支、事务边界、并发与幂等测试。
 * 全部基于真实 H2（MODE=MySQL）与固定 UTC 时钟，断言业务结果与最终数据，不依赖休眠。
 */
class ConflictApiTest extends AbstractIntegrationTest {

    private static final long T0 = 1_700_000_000_000L;

    @Test
    @DisplayName("登记冲突后：命中评审人不能批准/否决，已有评审 CONFLICTED 并排除出法定人数，快照固化证据")
    void conflictBlocksReviewAndExcludesFromQuorum() throws Exception {
        fixClock(T0);
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"原文\"}]");
        submitTranslation(docId, "s1", "en", "alice", "hello", 1, newRequestId());
        approve(docId, "s1", "en", "bob", 1, newRequestId());

        ApiResult declared = declareConflict(docId, "bob", "en", T0 - 1000, T0 + 10_000,
                "bob 近一年任职于供应商翻译团队", newRequestId());
        assertThat(declared.status()).isEqualTo(201);
        long conflictId = declared.body().get("conflictId").asLong();
        assertThat(declared.body().get("active").asBoolean()).isTrue();
        assertThat(declared.body().get("status").asText()).isEqualTo("DECLARED");
        assertThat(declared.body().get("durationMillis").asLong()).isEqualTo(11_000L);
        assertThat(declared.body().get("validFrom").asLong()).isEqualTo(T0 - 1000);
        assertThat(declared.body().get("validTo").asLong()).isEqualTo(T0 + 10_000);
        assertThat(declared.body().get("declaredAt").asLong()).isEqualTo(T0);
        assertThat(declared.body().get("liftedAt").isNull()).isTrue();

        // 已有评审被标记 CONFLICTED，行内固化声明 ID、区间与原因
        assertThat(jdbc.queryForObject(
                "SELECT status FROM review WHERE document_id = ? AND reviewer = 'bob'",
                String.class, docId)).isEqualTo("CONFLICTED");
        assertThat(jdbc.queryForObject(
                "SELECT conflict_id FROM review WHERE document_id = ? AND reviewer = 'bob'",
                Long.class, docId)).isEqualTo(conflictId);
        assertThat(jdbc.queryForObject(
                "SELECT conflict_reason FROM review WHERE document_id = ? AND reviewer = 'bob'",
                String.class, docId)).contains("任职于供应商");

        // 命中有效冲突：批准与否决都被 422 拦截
        ApiResult blockedApprove = approve(docId, "s1", "en", "bob", 1, newRequestId());
        assertThat(blockedApprove.status()).isEqualTo(422);
        assertThat(blockedApprove.body().get("message").asText())
                .contains("UTC", "[" + (T0 - 1000), ", " + (T0 + 10_000) + ")", "任职于供应商");
        ApiResult blockedReject = reject(docId, "s1", "en", "bob", 1, "译文有误", newRequestId());
        assertThat(blockedReject.status()).isEqualTo(422);

        // 诊断：bob 的评审不计入法定人数，要求 1、实际 0、差额 1
        ApiResult diagnostics = getReviewDiagnostics(docId);
        assertThat(diagnostics.status()).isEqualTo(200);
        var diag = diagnostics.body().get("translations").get(0);
        assertThat(diag.get("requiredReviewCount").asInt()).isEqualTo(1);
        assertThat(diag.get("effectiveApprovals").asInt()).isZero();
        assertThat(diag.get("missingApprovals").asInt()).isEqualTo(1);
        assertThat(diag.get("reviews").get(0).get("effective").asBoolean()).isFalse();

        // 排除后法定人数不足：422 稳定返回缺失数与受影响段落（含实际/要求/差额）
        ApiResult publishBlocked = publish(docId, 2, 0, newRequestId());
        assertThat(publishBlocked.status()).isEqualTo(422);
        assertThat(publishBlocked.body().get("error").asText()).isEqualTo("QUORUM_INSUFFICIENT");
        assertThat(publishBlocked.body().get("missingReviews").asInt()).isEqualTo(1);
        assertThat(publishBlocked.body().get("affectedSegments")).hasSize(1);
        assertThat(publishBlocked.body().get("affectedSegments").get(0).asText())
                .contains("s1/en", "实际有效批准 0", "要求 1", "差额 1");

        // 非冲突评审人 carol 批准后法定人数满足，发布成功
        ApiResult carol = approve(docId, "s1", "en", "carol", 1, newRequestId());
        assertThat(carol.status()).isEqualTo(200);
        ApiResult published = publish(docId, 2, 0, newRequestId());
        assertThat(published.status()).isEqualTo(201);

        // 快照固化评审明细、排除证据与冲突声明
        ApiResult release = getJson("/api/documents/" + docId + "/releases/1");
        assertThat(release.status()).isEqualTo(200);
        var tr = release.body().get("segments").get(0).get("translations").get(0);
        assertThat(tr.get("requiredReviewCount").asInt()).isEqualTo(1);
        assertThat(tr.get("effectiveApprovals").asInt()).isEqualTo(1);
        assertThat(tr.get("reviewer").asText()).isEqualTo("carol");
        var reviews = tr.get("reviews");
        assertThat(reviews).hasSize(2);
        var bobReview = reviews.get(0);
        assertThat(bobReview.get("reviewer").asText()).isEqualTo("bob");
        assertThat(bobReview.get("status").asText()).isEqualTo("CONFLICTED");
        assertThat(bobReview.get("effective").asBoolean()).isFalse();
        assertThat(bobReview.get("conflictedNow").asBoolean()).isTrue();
        assertThat(bobReview.get("conflictId").asLong()).isEqualTo(conflictId);
        assertThat(bobReview.get("conflictReason").asText()).contains("任职于供应商");
        assertThat(bobReview.get("conflictValidFrom").asLong()).isEqualTo(T0 - 1000);
        assertThat(bobReview.get("conflictValidTo").asLong()).isEqualTo(T0 + 10_000);
        var frozenConflict = release.body().get("conflicts").get(0);
        assertThat(frozenConflict.get("conflictId").asLong()).isEqualTo(conflictId);
        assertThat(frozenConflict.get("activeAtPublish").asBoolean()).isTrue();
        assertThat(frozenConflict.get("reason").asText()).contains("任职于供应商");
    }

    @Test
    @DisplayName("声明校验：区间倒置 422、原因空白 422、语种越界 422；相邻区间允许、重叠 409；失败不留数据")
    void conflictValidationAndOverlap() throws Exception {
        fixClock(T0);
        long docId = createDocument(newRequestId(), "[\"en\",\"ja\"]", "[]");

        assertThat(declareConflict(docId, "bob", "en", 100, 100, "倒置区间", newRequestId()).status())
                .isEqualTo(422);
        assertThat(declareConflict(docId, "bob", "en", 200, 100, "倒置区间", newRequestId()).status())
                .isEqualTo(422);
        assertThat(declareConflict(docId, "bob", "en", 100, 200, "   ", newRequestId()).status())
                .isEqualTo(422);
        ApiResult wrongLanguage = declareConflict(docId, "bob", "fr", 100, 200, "语种越界", newRequestId());
        assertThat(wrongLanguage.status()).isEqualTo(422);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reviewer_conflict", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM conflict_history", Integer.class)).isZero();

        assertThat(declareConflict(docId, "bob", "en", 100, 200, "原因一", newRequestId()).status())
                .isEqualTo(201);
        // 同评审人同语种区间重叠：409
        ApiResult overlap = declareConflict(docId, "bob", "en", 150, 300, "重叠", newRequestId());
        assertThat(overlap.status()).isEqualTo(409);
        // 相邻区间（前者 validTo == 后者 validFrom）不算重叠：201
        assertThat(declareConflict(docId, "bob", "en", 200, 300, "相邻", newRequestId()).status())
                .isEqualTo(201);
        // 不同语种：允许
        assertThat(declareConflict(docId, "bob", "ja", 150, 300, "另一语种", newRequestId()).status())
                .isEqualTo(201);
        // 不同评审人：允许
        assertThat(declareConflict(docId, "carol", "en", 150, 300, "另一评审人", newRequestId()).status())
                .isEqualTo(201);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reviewer_conflict", Integer.class)).isEqualTo(4);

        // 已自然到期但未解除的历史声明仍占用范围：与其重叠的新声明 409；相邻区间允许
        ApiResult expired = declareConflict(docId, "dave", "en", T0 - 200, T0, "已到期", newRequestId());
        assertThat(expired.status()).isEqualTo(201);
        assertThat(expired.body().get("active").asBoolean()).isFalse();
        ApiResult overlapExpired = declareConflict(docId, "dave", "en", T0 - 50, T0 + 10, "与到期区间重叠",
                newRequestId());
        assertThat(overlapExpired.status()).isEqualTo(409);
        assertThat(declareConflict(docId, "dave", "en", T0, T0 + 10, "与到期区间相邻", newRequestId()).status())
                .isEqualTo(201);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reviewer_conflict", Integer.class)).isEqualTo(6);
    }

    @Test
    @DisplayName("批量登记：先预校验全部范围，任一非法整批不变更评审资格；成功批按顺序返回")
    void batchPrevalidationAtomicity() throws Exception {
        fixClock(T0);
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"原文\"}]");
        submitTranslation(docId, "s1", "en", "alice", "hello", 1, newRequestId());
        approve(docId, "s1", "en", "bob", 1, newRequestId());

        // 第二条区间倒置：整批 422
        String invalidBatch = "[{\"language\":\"en\",\"validFrom\":" + (T0 + 100) + ",\"validTo\":"
                + (T0 + 200) + ",\"reason\":\"合法\"},"
                + "{\"language\":\"en\",\"validFrom\":500,\"validTo\":400,\"reason\":\"倒置\"}]";
        ApiResult invalid = declareConflictsBatch(docId, "bob", invalidBatch, newRequestId());
        assertThat(invalid.status()).isEqualTo(422);
        assertThat(invalid.body().get("message").asText()).contains("第 1 条", "validFrom < validTo");

        // 批内区间互重叠：整批 409
        String internalOverlap = "[{\"language\":\"en\",\"validFrom\":" + (T0 + 100) + ",\"validTo\":"
                + (T0 + 300) + ",\"reason\":\"a\"},"
                + "{\"language\":\"en\",\"validFrom\":" + (T0 + 200) + ",\"validTo\":" + (T0 + 400)
                + ",\"reason\":\"b\"}]";
        assertThat(declareConflictsBatch(docId, "bob", internalOverlap, newRequestId()).status()).isEqualTo(409);

        // 整批失败不留半成品：无声明、无历史、已有评审仍 ACTIVE、失败不占 requestId
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reviewer_conflict", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM conflict_history", Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT status FROM review WHERE document_id = ? AND reviewer = 'bob'", String.class, docId))
                .isEqualTo("ACTIVE");

        // 与既有声明重叠：整批 409
        declareConflict(docId, "bob", "en", T0 + 500, T0 + 600, "既有", newRequestId());
        String overlapExisting = "[{\"language\":\"en\",\"validFrom\":" + (T0 + 550) + ",\"validTo\":"
                + (T0 + 650) + ",\"reason\":\"新\"}]";
        assertThat(declareConflictsBatch(docId, "bob", overlapExisting, newRequestId()).status()).isEqualTo(409);

        // 合法批：首条在 T0 已生效（与既有 [T0+500,T0+600) 相邻），第二条为相邻的未来区间，按请求顺序返回
        String validBatch = "[{\"language\":\"en\",\"validFrom\":" + (T0 - 1000) + ",\"validTo\":"
                + (T0 + 500) + ",\"reason\":\"批量一\"},"
                + "{\"language\":\"en\",\"validFrom\":" + (T0 + 600) + ",\"validTo\":" + (T0 + 700)
                + ",\"reason\":\"批量二\"}]";
        ApiResult ok = declareConflictsBatch(docId, "bob", validBatch, newRequestId());
        assertThat(ok.status()).isEqualTo(201);
        assertThat(ok.body().get("conflicts")).hasSize(2);
        assertThat(ok.body().get("conflicts").get(0).get("reason").asText()).isEqualTo("批量一");
        assertThat(ok.body().get("conflicts").get(1).get("reason").asText()).isEqualTo("批量二");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM reviewer_conflict WHERE document_id = ?", Integer.class, docId))
                .isEqualTo(3);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM review WHERE document_id = ? AND reviewer = 'bob'", String.class, docId))
                .isEqualTo("CONFLICTED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM conflict_history WHERE document_id = ?",
                Integer.class, docId)).isEqualTo(3);
    }

    @Test
    @DisplayName("解除冲突：合规角色且不同于声明人；非合规/本人/重复解除分别 403/403/409；历史追加、证据保留")
    void liftConflictRulesAndHistory() throws Exception {
        fixClock(T0);
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"原文\"}]");
        submitTranslation(docId, "s1", "en", "alice", "hello", 1, newRequestId());
        approve(docId, "s1", "en", "bob", 1, newRequestId());
        ApiResult declared = declareConflict(docId, "bob", "en", T0 - 100, T0 + 10_000, "利益相关",
                newRequestId());
        long conflictId = declared.body().get("conflictId").asLong();
        // 声明登记后 bob 的已有评审被标记 CONFLICTED，发布缺人
        assertThat(publish(docId, 2, 0, newRequestId()).status()).isEqualTo(422);

        // 非合规角色 403
        ApiResult wrongRole = liftConflict(conflictId, "amy", "REVIEWER", "越权", newRequestId());
        assertThat(wrongRole.status()).isEqualTo(403);
        assertThat(wrongRole.body().get("error").asText()).isEqualTo("FORBIDDEN_ROLE");
        // 声明人本人即便带合规角色也 403
        ApiResult selfLift = liftConflict(conflictId, "bob", "COMPLIANCE", "自行解除", newRequestId());
        assertThat(selfLift.status()).isEqualTo(403);
        assertThat(selfLift.body().get("error").asText()).isEqualTo("FORBIDDEN_SELF_LIFT");
        // 缺少 X-Actor-Role：400
        String body = "{\"requestId\":\"" + newRequestId() + "\",\"liftReason\":null}";
        ApiResult noRole = postJson("/api/documents/conflicts/" + conflictId + "/lift", body, "amy");
        assertThat(noRole.status()).isEqualTo(400);

        // 合规角色 amy 解除成功
        fixClock(T0 + 500);
        ApiResult lifted = liftConflict(conflictId, "amy", "COMPLIANCE", "复核后解除", newRequestId());
        assertThat(lifted.status()).isEqualTo(200);
        assertThat(lifted.body().get("status").asText()).isEqualTo("LIFTED");
        assertThat(lifted.body().get("liftedBy").asText()).isEqualTo("amy");
        assertThat(lifted.body().get("liftedAt").asLong()).isEqualTo(T0 + 500);
        assertThat(lifted.body().get("active").asBoolean()).isFalse();
        // 声明字段不被改写
        assertThat(lifted.body().get("reason").asText()).isEqualTo("利益相关");
        assertThat(lifted.body().get("validFrom").asLong()).isEqualTo(T0 - 100);
        assertThat(lifted.body().get("declaredBy").asText()).isEqualTo("bob");

        // 重复解除 409；不存在 404
        assertThat(liftConflict(conflictId, "amy", "COMPLIANCE", "再次解除", newRequestId()).status())
                .isEqualTo(409);
        assertThat(liftConflict(999_999, "amy", "COMPLIANCE", null, newRequestId()).status()).isEqualTo(404);

        // 评审恢复 ACTIVE 但排除证据列保留；解除后可重新评审并发布
        assertThat(jdbc.queryForObject(
                "SELECT status FROM review WHERE document_id = ? AND reviewer = 'bob'", String.class, docId))
                .isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject(
                "SELECT conflict_reason FROM review WHERE document_id = ? AND reviewer = 'bob'",
                String.class, docId)).isEqualTo("利益相关");
        assertThat(approve(docId, "s1", "en", "bob", 1, newRequestId()).status()).isEqualTo(200);
        assertThat(publish(docId, 2, 0, newRequestId()).status()).isEqualTo(201);

        // 历史只追加：DECLARED 在前、LIFTED 在后，字段稳定
        ApiResult history = getConflictHistory(docId);
        assertThat(history.status()).isEqualTo(200);
        var entries = history.body().get("history");
        assertThat(entries).hasSize(2);
        assertThat(entries.get(0).get("action").asText()).isEqualTo("DECLARED");
        assertThat(entries.get(0).get("actor").asText()).isEqualTo("bob");
        assertThat(entries.get(0).get("actorRole").asText()).isEqualTo("REVIEWER");
        assertThat(entries.get(0).get("reason").asText()).isEqualTo("利益相关");
        assertThat(entries.get(1).get("action").asText()).isEqualTo("LIFTED");
        assertThat(entries.get(1).get("actor").asText()).isEqualTo("amy");
        assertThat(entries.get(1).get("actorRole").asText()).isEqualTo("COMPLIANCE");
        assertThat(entries.get(1).get("reason").asText()).isEqualTo("复核后解除");
        assertThat(entries.get(0).get("validFrom").asLong()).isEqualTo(T0 - 100);
    }

    @Test
    @DisplayName("法定人数不足发布 422：跨语种稳定汇总缺失数与受影响段落，不产生部分语种快照")
    void quorumFailureAggregatesAcrossLanguages() throws Exception {
        fixClock(T0);
        long docId = createDocument(newRequestId(), "[\"en\",\"ja\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"原文\"}]", 2);
        submitTranslation(docId, "s1", "en", "alice", "hello", 1, newRequestId());
        submitTranslation(docId, "s1", "ja", "carol", "こんにちは", 1, newRequestId());
        approve(docId, "s1", "en", "bob", 1, newRequestId());
        approve(docId, "s1", "en", "dave", 1, newRequestId());
        approve(docId, "s1", "ja", "eve", 1, newRequestId());
        // en：dave 被排除后缺 1；ja：只有 eve 一人，缺 1（草稿版本 3）
        declareConflict(docId, "dave", "en", T0 - 100, T0 + 100_000, "dave 利益相关", newRequestId());

        ApiResult failed = publish(docId, 3, 0, newRequestId());
        assertThat(failed.status()).isEqualTo(422);
        assertThat(failed.body().get("error").asText()).isEqualTo("QUORUM_INSUFFICIENT");
        assertThat(failed.body().get("missingReviews").asInt()).isEqualTo(2);
        List<String> affected = new ArrayList<>();
        failed.body().get("affectedSegments").forEach(n -> affected.add(n.asText()));
        assertThat(affected).containsExactly(
                "s1/en（实际有效批准 1，要求 2，差额 1）",
                "s1/ja（实际有效批准 1，要求 2，差额 1）");
        // 无部分快照、发布版本未前进
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM release_snapshot WHERE document_id = ?",
                Integer.class, docId)).isZero();
        assertThat(jdbc.queryForObject("SELECT published_version FROM document WHERE document_id = ?",
                Integer.class, docId)).isZero();

        // eve 也被排除：ja 缺 2，总缺失 3，错误内容随之稳定变化
        declareConflict(docId, "eve", "ja", T0 - 100, T0 + 100_000, "eve 利益相关", newRequestId());
        ApiResult failedWorse = publish(docId, 3, 0, newRequestId());
        assertThat(failedWorse.body().get("missingReviews").asInt()).isEqualTo(3);
        assertThat(failedWorse.body().get("affectedSegments").get(1).asText())
                .contains("实际有效批准 0", "要求 2", "差额 2");

        // 补齐后整体发布，两个语种同时进入同一快照
        approve(docId, "s1", "en", "frank", 1, newRequestId());
        approve(docId, "s1", "ja", "frank", 1, newRequestId());
        approve(docId, "s1", "ja", "grace", 1, newRequestId());
        ApiResult published = publish(docId, 3, 0, newRequestId());
        assertThat(published.status()).isEqualTo(201);
        ApiResult release = getJson("/api/documents/" + docId + "/releases/1");
        assertThat(release.body().get("segments").get(0).get("translations")).hasSize(2);
    }

    @Test
    @DisplayName("有效否决阻断发布（REVIEW_REJECTED）；否决人被冲突排除或改批后不再阻断，快照固化否决明细")
    void rejectionBlocksPublishUntilExcludedOrChanged() throws Exception {
        fixClock(T0);
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"原文\"}]", 2);
        submitTranslation(docId, "s1", "en", "alice", "hello", 1, newRequestId());
        assertThat(reject(docId, "s1", "en", "bob", 1, "术语错误", newRequestId()).status()).isEqualTo(200);
        assertThat(publish(docId, 2, 0, newRequestId()).status()).isEqualTo(422);
        ApiResult rejectedPublish = publish(docId, 2, 0, newRequestId());
        assertThat(rejectedPublish.body().get("error").asText()).isEqualTo("REVIEW_REJECTED");

        // bob 改为批准，且 carol 批准达到两人：发布成功
        assertThat(approve(docId, "s1", "en", "bob", 1, newRequestId()).status()).isEqualTo(200);
        assertThat(approve(docId, "s1", "en", "carol", 1, newRequestId()).status()).isEqualTo(200);
        assertThat(publish(docId, 2, 0, newRequestId()).status()).isEqualTo(201);

        // 有效否决与足够批准并存时仍阻断：dave 否决，bob/carol 批准
        long docId2 = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"原文2\"}]", 2);
        submitTranslation(docId2, "s1", "en", "alice", "hello2", 1, newRequestId());
        approve(docId2, "s1", "en", "bob", 1, newRequestId());
        approve(docId2, "s1", "en", "carol", 1, newRequestId());
        reject(docId2, "s1", "en", "dave", 1, "风格不符", newRequestId());
        assertThat(publish(docId2, 2, 0, newRequestId()).status()).isEqualTo(422);
        // dave 被冲突排除后否决不再有效，发布成功；快照固化否决与冲突证据
        declareConflict(docId2, "dave", "en", T0 - 100, T0 + 100_000, "dave 与供应商有关联", newRequestId());
        assertThat(publish(docId2, 2, 0, newRequestId()).status()).isEqualTo(201);
        ApiResult release = getJson("/api/documents/" + docId2 + "/releases/1");
        var reviewsInSnapshot = release.body().get("segments").get(0).get("translations").get(0).get("reviews");
        var daveReview = reviewsInSnapshot.get(reviewsInSnapshot.size() - 1);
        assertThat(daveReview.get("reviewer").asText()).isEqualTo("dave");
        assertThat(daveReview.get("decision").asText()).isEqualTo("REJECTED");
        assertThat(daveReview.get("status").asText()).isEqualTo("CONFLICTED");
        assertThat(daveReview.get("effective").asBoolean()).isFalse();
        assertThat(daveReview.get("conflictReason").asText()).contains("供应商");
    }

    @Test
    @DisplayName("UTC 左闭右开边界：validFrom 时刻命中、validTo 时刻不命中；未来区间不限制当前评审")
    void intervalBoundariesAndFutureRange() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"原文\"}]");
        submitTranslation(docId, "s1", "en", "alice", "hello", 1, newRequestId());

        fixClock(T0);
        declareConflict(docId, "bob", "en", T0, T0 + 100, "边界区间", newRequestId());
        ApiResult atStart = getConflicts(docId);
        assertThat(atStart.body().get("conflicts").get(0).get("active").asBoolean()).isTrue();
        assertThat(approve(docId, "s1", "en", "bob", 1, newRequestId()).status()).isEqualTo(422);

        fixClock(T0 + 100);
        ApiResult atEnd = getConflicts(docId);
        assertThat(atEnd.body().get("conflicts").get(0).get("active").asBoolean()).isFalse();
        assertThat(approve(docId, "s1", "en", "bob", 1, newRequestId()).status()).isEqualTo(200);
        assertThat(publish(docId, 2, 0, newRequestId()).status()).isEqualTo(201);

        // 未来区间不限制当前评审，但与其他未来区间重叠仍 409
        long docId2 = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"原文\"}]");
        submitTranslation(docId2, "s1", "en", "alice", "hello", 1, newRequestId());
        fixClock(T0);
        ApiResult future = declareConflict(docId2, "carol", "en", T0 + 1_000_000, T0 + 2_000_000,
                "未来回避", newRequestId());
        assertThat(future.body().get("active").asBoolean()).isFalse();
        assertThat(approve(docId2, "s1", "en", "carol", 1, newRequestId()).status()).isEqualTo(200);
        assertThat(declareConflict(docId2, "carol", "en", T0 + 1_500_000, T0 + 1_800_000, "未来重叠",
                newRequestId()).status()).isEqualTo(409);
    }

    @Test
    @DisplayName("解除不追溯旧快照：解除后再发布，两个快照各自固化发布时刻的冲突与评审状态")
    void liftDoesNotInvalidateOldSnapshots() throws Exception {
        fixClock(T0);
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"原文\"}]");
        submitTranslation(docId, "s1", "en", "alice", "hello", 1, newRequestId());
        approve(docId, "s1", "en", "bob", 1, newRequestId());
        ApiResult declared = declareConflict(docId, "bob", "en", T0 - 100, T0 + 100_000, "利益相关",
                newRequestId());
        long conflictId = declared.body().get("conflictId").asLong();
        approve(docId, "s1", "en", "carol", 1, newRequestId());
        assertThat(publish(docId, 2, 0, newRequestId()).status()).isEqualTo(201);

        // 解除后 bob 恢复有效；carol 的批准与 bob 均计入，再次发布成功
        liftConflict(conflictId, "amy", "COMPLIANCE", "合规复核通过", newRequestId());
        ApiResult second = publish(docId, 2, 1, newRequestId());
        assertThat(second.status()).isEqualTo(201);

        ApiResult release1 = getJson("/api/documents/" + docId + "/releases/1");
        ApiResult release2 = getJson("/api/documents/" + docId + "/releases/2");
        var conflict1 = release1.body().get("conflicts").get(0);
        assertThat(conflict1.get("status").asText()).isEqualTo("DECLARED");
        assertThat(conflict1.get("activeAtPublish").asBoolean()).isTrue();
        var bobInRelease1 = release1.body().get("segments").get(0).get("translations").get(0)
                .get("reviews").get(0);
        assertThat(bobInRelease1.get("status").asText()).isEqualTo("CONFLICTED");
        assertThat(bobInRelease1.get("effective").asBoolean()).isFalse();

        var conflict2 = release2.body().get("conflicts").get(0);
        assertThat(conflict2.get("status").asText()).isEqualTo("LIFTED");
        assertThat(conflict2.get("activeAtPublish").asBoolean()).isFalse();
        var bobInRelease2 = release2.body().get("segments").get(0).get("translations").get(0)
                .get("reviews").get(0);
        assertThat(bobInRelease2.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(bobInRelease2.get("effective").asBoolean()).isTrue();
        // 旧证据在新快照中仍可追溯
        assertThat(bobInRelease2.get("conflictReason").asText()).isEqualTo("利益相关");
    }

    @Test
    @DisplayName("冲突写操作幂等：同键同参重放完整快照、异参 409、失败不占键；解除同理")
    void conflictIdempotency() throws Exception {
        fixClock(T0);
        long docId = createDocument(newRequestId(), "[\"en\"]", "[]");
        String requestId = newRequestId();
        String body = "{\"requestId\":\"" + requestId + "\",\"language\":\"en\",\"validFrom\":" + T0
                + ",\"validTo\":" + (T0 + 100) + ",\"reason\":\"幂等原因\"}";

        ApiResult first = postJson("/api/documents/" + docId + "/conflicts", body, "bob");
        assertThat(first.status()).isEqualTo(201);
        ApiResult replay = postJson("/api/documents/" + docId + "/conflicts", body, "bob");
        assertThat(replay.status()).isEqualTo(201);
        assertThat(replay.body().get("conflictId").asLong())
                .isEqualTo(first.body().get("conflictId").asLong());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reviewer_conflict WHERE document_id = ?",
                Integer.class, docId)).isEqualTo(1);

        String different = "{\"requestId\":\"" + requestId + "\",\"language\":\"en\",\"validFrom\":" + T0
                + ",\"validTo\":" + (T0 + 200) + ",\"reason\":\"幂等原因\"}";
        assertThat(postJson("/api/documents/" + docId + "/conflicts", different, "bob").status())
                .isEqualTo(409);

        // 失败不占键：先倒置 422，再用同键改合法区间 201
        String failKey = newRequestId();
        String inverted = "{\"requestId\":\"" + failKey + "\",\"language\":\"en\",\"validFrom\":200,"
                + "\"validTo\":100,\"reason\":\"倒置\"}";
        assertThat(postJson("/api/documents/" + docId + "/conflicts", inverted, "bob").status()).isEqualTo(422);
        String fixed = "{\"requestId\":\"" + failKey + "\",\"language\":\"en\",\"validFrom\":" + (T0 + 1000)
                + ",\"validTo\":" + (T0 + 2000) + ",\"reason\":\"合法\"}";
        assertThat(postJson("/api/documents/" + docId + "/conflicts", fixed, "bob").status()).isEqualTo(201);

        // 解除：同键同参重放不重复追加历史，异参 409
        long conflictId = first.body().get("conflictId").asLong();
        String liftKey = newRequestId();
        ApiResult lift1 = liftConflict(conflictId, "amy", "COMPLIANCE", "解除", liftKey);
        assertThat(lift1.status()).isEqualTo(200);
        ApiResult lift2 = liftConflict(conflictId, "amy", "COMPLIANCE", "解除", liftKey);
        assertThat(lift2.status()).isEqualTo(200);
        assertThat(lift2.body().get("liftedBy").asText()).isEqualTo("amy");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM conflict_history WHERE conflict_id = ? AND action = 'LIFTED'",
                Integer.class, conflictId)).isEqualTo(1);
        assertThat(liftConflict(conflictId, "amy", "COMPLIANCE", "不同说明", liftKey).status())
                .isEqualTo(409);
    }

    @Test
    @DisplayName("并发冲突登记：同评审人同语种重叠区间仅一个成功，其余 409，声明与历史计数一致")
    void concurrentConflictDeclarations() throws Exception {
        fixClock(T0);
        long docId = createDocument(newRequestId(), "[\"en\"]", "[]");
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<ApiResult>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final int n = i;
            futures.add(pool.submit(() -> {
                gate.await();
                return declareConflict(docId, "bob", "en", T0, T0 + 1000,
                        "并发原因-" + n, newRequestId());
            }));
        }
        gate.countDown();
        int success = 0;
        int conflict = 0;
        for (Future<ApiResult> future : futures) {
            int status = future.get(30, TimeUnit.SECONDS).status();
            if (status == 201) {
                success++;
            } else if (status == 409) {
                conflict++;
            }
        }
        pool.shutdown();
        assertThat(success).isEqualTo(1);
        assertThat(conflict).isEqualTo(threads - 1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM reviewer_conflict WHERE document_id = ? AND reviewer = 'bob'",
                Integer.class, docId)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM conflict_history WHERE document_id = ?", Integer.class, docId))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("发布与冲突登记并发：发布先于登记则快照固化无冲突；登记先于发布则 422，无部分快照")
    void concurrentPublishAndConflictDeclaration() throws Exception {
        fixClock(T0);
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"原文\"}]");
        submitTranslation(docId, "s1", "en", "alice", "hello", 1, newRequestId());
        approve(docId, "s1", "en", "bob", 1, newRequestId());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        Future<ApiResult> publishFuture = pool.submit(() -> {
            gate.await();
            return publish(docId, 2, 0, newRequestId());
        });
        Future<ApiResult> conflictFuture = pool.submit(() -> {
            gate.await();
            return declareConflict(docId, "bob", "en", T0 - 100, T0 + 100_000, "并发利益相关",
                    newRequestId());
        });
        gate.countDown();
        ApiResult publishResult = publishFuture.get(30, TimeUnit.SECONDS);
        ApiResult conflictResult = conflictFuture.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(conflictResult.status()).isEqualTo(201);
        assertThat(publishResult.status()).isIn(201, 422);

        Integer publishedVersion = jdbc.queryForObject(
                "SELECT published_version FROM document WHERE document_id = ?", Integer.class, docId);
        Integer snapshots = jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_snapshot WHERE document_id = ?", Integer.class, docId);
        assertThat(snapshots).isEqualTo(publishedVersion);
        if (publishResult.status() == 201) {
            // 发布先于登记：恰好一个快照，快照内无冲突且 bob 有效
            assertThat(publishedVersion).isEqualTo(1);
            ApiResult release = getJson("/api/documents/" + docId + "/releases/1");
            assertThat(release.body().get("conflicts")).isEmpty();
            var bobReview = release.body().get("segments").get(0).get("translations").get(0)
                    .get("reviews").get(0);
            assertThat(bobReview.get("reviewer").asText()).isEqualTo("bob");
            assertThat(bobReview.get("effective").asBoolean()).isTrue();
        } else {
            // 登记先于发布：法定人数不足，无快照
            assertThat(publishedVersion).isZero();
            assertThat(publishResult.body().get("error").asText()).isEqualTo("QUORUM_INSUFFICIENT");
            assertThat(publishResult.body().get("missingReviews").asInt()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("未来区间生效后发布：无需重新登记，发布时同步排除评审并固化当前声明证据")
    void futureConflictExcludesAtPublishTime() throws Exception {
        fixClock(T0);
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"原文\"}]");
        submitTranslation(docId, "s1", "en", "alice", "hello", 1, newRequestId());
        approve(docId, "s1", "en", "bob", 1, newRequestId());
        ApiResult future = declareConflict(docId, "bob", "en", T0 + 1_000, T0 + 2_000, "未来回避",
                newRequestId());
        long conflictId = future.body().get("conflictId").asLong();
        // 登记时区间未生效：评审仍 ACTIVE，发布在区间生效前成功并固化无冲突快照
        assertThat(publish(docId, 2, 0, newRequestId()).status()).isEqualTo(201);
        ApiResult release1 = getJson("/api/documents/" + docId + "/releases/1");
        assertThat(release1.body().get("conflicts").get(0).get("activeAtPublish").asBoolean()).isFalse();

        // 时钟推进到区间内：诊断显示 bob 无效；发布 422 且评审行被同步标记 CONFLICTED、证据指向该声明
        fixClock(T0 + 1_500);
        ApiResult diagnostics = getReviewDiagnostics(docId);
        var bobDiag = diagnostics.body().get("translations").get(0).get("reviews").get(0);
        assertThat(bobDiag.get("effective").asBoolean()).isFalse();
        ApiResult blocked = publish(docId, 2, 1, newRequestId());
        assertThat(blocked.status()).isEqualTo(422);
        assertThat(blocked.body().get("error").asText()).isEqualTo("QUORUM_INSUFFICIENT");
        assertThat(blocked.body().get("missingReviews").asInt()).isEqualTo(1);
        // 失败发布整体回滚不留半成品：评审行仍为 ACTIVE，但诊断按当前有效冲突判定无效
        assertThat(jdbc.queryForObject(
                "SELECT status FROM review WHERE document_id = ? AND reviewer = 'bob'", String.class, docId))
                .isEqualTo("ACTIVE");

        // carol 补批后发布成功；成功事务把 bob 同步标记 CONFLICTED，快照证据指向该声明
        approve(docId, "s1", "en", "carol", 1, newRequestId());
        assertThat(publish(docId, 2, 1, newRequestId()).status()).isEqualTo(201);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM review WHERE document_id = ? AND reviewer = 'bob'", String.class, docId))
                .isEqualTo("CONFLICTED");
        assertThat(jdbc.queryForObject(
                "SELECT conflict_id FROM review WHERE document_id = ? AND reviewer = 'bob'", Long.class, docId))
                .isEqualTo(conflictId);
        ApiResult release2 = getJson("/api/documents/" + docId + "/releases/2");
        var bobReview = release2.body().get("segments").get(0).get("translations").get(0)
                .get("reviews").get(0);
        assertThat(bobReview.get("reviewer").asText()).isEqualTo("bob");
        assertThat(bobReview.get("status").asText()).isEqualTo("CONFLICTED");
        assertThat(bobReview.get("conflictId").asLong()).isEqualTo(conflictId);
        assertThat(release2.body().get("conflicts").get(0).get("activeAtPublish").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("解除已到期声明但相邻声明当前生效：评审保持 CONFLICTED，证据刷新到仍生效声明")
    void liftExpiredWhileAdjacentDeclarationActive() throws Exception {
        fixClock(T0);
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"原文\"}]");
        submitTranslation(docId, "s1", "en", "alice", "hello", 1, newRequestId());
        approve(docId, "s1", "en", "bob", 1, newRequestId());
        // 两段相邻声明：第一段登记即生效并排除 bob
        ApiResult first = declareConflict(docId, "bob", "en", T0, T0 + 100, "第一段", newRequestId());
        long firstId = first.body().get("conflictId").asLong();
        assertThat(declareConflict(docId, "bob", "en", T0 + 100, T0 + 200, "第二段", newRequestId()).status())
                .isEqualTo(201);
        assertThat(jdbc.queryForObject(
                "SELECT conflict_id FROM review WHERE document_id = ? AND reviewer = 'bob'", Long.class, docId))
                .isEqualTo(firstId);

        // 时钟进入第二段后解除第一段（已到期）：bob 仍被第二段排除，证据刷新
        fixClock(T0 + 150);
        ApiResult lifted = liftConflict(firstId, "amy", "COMPLIANCE", "第一段到期解除", newRequestId());
        assertThat(lifted.status()).isEqualTo(200);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM review WHERE document_id = ? AND reviewer = 'bob'", String.class, docId))
                .isEqualTo("CONFLICTED");
        assertThat(jdbc.queryForObject(
                "SELECT conflict_reason FROM review WHERE document_id = ? AND reviewer = 'bob'",
                String.class, docId)).isEqualTo("第二段");
        assertThat(publish(docId, 2, 0, newRequestId()).status()).isEqualTo(422);

        // 第二段也解除后评审恢复 ACTIVE（证据列保留为最近一次排除的第二段）
        Long secondId = jdbc.queryForObject(
                "SELECT conflict_id FROM reviewer_conflict WHERE document_id = ? AND reason = '第二段'",
                Long.class, docId);
        liftConflict(secondId, "amy", "COMPLIANCE", "第二段解除", newRequestId());
        assertThat(jdbc.queryForObject(
                "SELECT status FROM review WHERE document_id = ? AND reviewer = 'bob'", String.class, docId))
                .isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject(
                "SELECT conflict_reason FROM review WHERE document_id = ? AND reviewer = 'bob'",
                String.class, docId)).isEqualTo("第二段");
        assertThat(publish(docId, 2, 0, newRequestId()).status()).isEqualTo(201);
    }

    @Test
    @DisplayName("只读查询不改变状态：明细、历史、诊断重复读取结果稳定")
    void readQueriesDoNotMutateState() throws Exception {
        fixClock(T0);
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"原文\"}]");
        submitTranslation(docId, "s1", "en", "alice", "hello", 1, newRequestId());
        approve(docId, "s1", "en", "bob", 1, newRequestId());
        declareConflict(docId, "bob", "en", T0 - 100, T0 + 100, "原因", newRequestId());

        ApiResult conflicts1 = getConflicts(docId);
        ApiResult conflicts2 = getConflicts(docId);
        assertThat(conflicts1.body().toString()).isEqualTo(conflicts2.body().toString());
        ApiResult history1 = getConflictHistory(docId);
        ApiResult history2 = getConflictHistory(docId);
        assertThat(history1.body().toString()).isEqualTo(history2.body().toString());
        ApiResult diag1 = getReviewDiagnostics(docId);
        ApiResult diag2 = getReviewDiagnostics(docId);
        assertThat(diag1.body().toString()).isEqualTo(diag2.body().toString());

        // 文档不存在统一 404
        assertThat(getConflicts(999_999).status()).isEqualTo(404);
        assertThat(getConflictHistory(999_999).status()).isEqualTo(404);
        assertThat(getReviewDiagnostics(999_999).status()).isEqualTo(404);
    }
}
