package com.example.starter.translation;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 评审利益冲突主流程与失败分支：登记资格排除、批准/否决拦截、法定人数重算、
 * 解除合规确认与不追溯、批量预校验、幂等、快照固化、UTC 区间边界。
 */
class ConflictApiTest extends AbstractIntegrationTest {

    private static final String T0 = "2026-10-01T00:00:00.500Z";
    private static final String T1 = "2026-10-02T00:00:00Z";

    private String rangeJson(String language, String from, String to, String reason) {
        return "{\"language\":\"" + language + "\",\"validFrom\":\"" + from + "\",\"validTo\":\"" + to
                + "\",\"reason\":\"" + reason + "\"}";
    }

    /** 建文档（默认 en 单段落 s1）→ alice 提交译文 → bob 批准，返回文档 ID。 */
    private long docWithApprovedTranslation() throws Exception {
        clock.setFixed(Instant.parse(T0));
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"原文\"}]");
        assertThat(submitTranslation(docId, "s1", "en", "alice", "hello", 1, newRequestId()).status())
                .isEqualTo(200);
        assertThat(approve(docId, "s1", "en", "bob", 1, newRequestId()).status()).isEqualTo(200);
        return docId;
    }

    @Test
    @DisplayName("登记冲突：201 返回实际登记数，明细含 UTC 毫秒区间与原因，自动追加 DECLARED 历史")
    void registerConflictSuccess() throws Exception {
        clock.setFixed(Instant.parse(T0));
        long docId = createDocument(newRequestId(), "[\"en\"]", "[]");

        ApiResult result = registerConflicts(docId, "bob", "[" + rangeJson("en", T0, T1, "曾任职翻译供应商") + "]",
                newRequestId());
        assertThat(result.status()).isEqualTo(201);
        assertThat(result.body().get("registeredCount").asInt()).isEqualTo(1);
        assertThat(result.body().get("markedConflictReviewCount").asInt()).isZero();
        JsonNode conflict = result.body().get("conflicts").get(0);
        long conflictId = conflict.get("conflictId").asLong();
        assertThat(conflict.get("language").asText()).isEqualTo("en");
        assertThat(conflict.get("validFrom").asText()).isEqualTo(T0);
        assertThat(conflict.get("validTo").asText()).isEqualTo(T1);
        assertThat(conflict.get("reason").asText()).isEqualTo("曾任职翻译供应商");
        assertThat(conflict.get("status").asText()).isEqualTo("ACTIVE");

        ApiResult detail = getJson("/api/documents/" + docId + "/conflicts/" + conflictId);
        assertThat(detail.status()).isEqualTo(200);
        assertThat(detail.body().get("reviewer").asText()).isEqualTo("bob");
        assertThat(detail.body().get("declaredBy").asText()).isEqualTo("bob");
        assertThat(detail.body().get("declaredAt").asText()).isEqualTo(T0);
        assertThat(detail.body().has("resolvedBy")).isFalse();
        JsonNode history = detail.body().get("history");
        assertThat(history).hasSize(1);
        assertThat(history.get(0).get("eventType").asText()).isEqualTo("DECLARED");
        assertThat(history.get(0).get("actorRole").asText()).isEqualTo("REVIEWER");
        assertThat(history.get(0).get("reason").asText()).isEqualTo("曾任职翻译供应商");
    }

    @Test
    @DisplayName("命中有效冲突：批准与否决均 422 REVIEWER_CONFLICT；区间结束后恢复资格（左闭右开）")
    void activeConflictBlocksApproveAndReject() throws Exception {
        long docId = docWithApprovedTranslation();

        ApiResult registered = registerConflicts(docId, "carol",
                "[" + rangeJson("en", T0, T1, "carol 与作者同机构") + "]", newRequestId());
        assertThat(registered.status()).isEqualTo(201);

        ApiResult blockedApprove = approve(docId, "s1", "en", "carol", 1, newRequestId());
        assertThat(blockedApprove.status()).isEqualTo(422);
        assertThat(blockedApprove.body().get("error").asText()).isEqualTo("REVIEWER_CONFLICT");

        ApiResult blockedReject = reject(docId, "s1", "en", "carol", 1, "译文有误", newRequestId());
        assertThat(blockedReject.status()).isEqualTo(422);
        assertThat(blockedReject.body().get("error").asText()).isEqualTo("REVIEWER_CONFLICT");

        // 区间终点（不含）时刻不再命中：carol 可提交评审
        clock.setFixed(Instant.parse(T1));
        ApiResult atEnd = approve(docId, "s1", "en", "carol", 1, newRequestId());
        assertThat(atEnd.status()).isEqualTo(200);

        // 区间内时刻仍命中
        clock.setFixed(Instant.parse("2026-10-01T12:00:00Z"));
        ApiResult insideInterval = approve(docId, "s1", "en", "carol", 1, newRequestId());
        assertThat(insideInterval.status()).isEqualTo(422);
    }

    @Test
    @DisplayName("登记冲突后既有 ACTIVE 评审标记 CONFLICTED 并固化冲突 ID，发布按非冲突评审重算为 422 并给缺失数")
    void existingApprovalMarkedConflictedAndQuorumRecomputed() throws Exception {
        long docId = docWithApprovedTranslation();

        ApiResult registered = registerConflicts(docId, "bob",
                "[" + rangeJson("en", T0, T1, "bob 事后披露关联关系") + "]", newRequestId());
        assertThat(registered.status()).isEqualTo(201);
        long conflictId = registered.body().get("conflicts").get(0).get("conflictId").asLong();
        assertThat(registered.body().get("markedConflictReviewCount").asInt()).isEqualTo(1);

        ApiResult reviews = getJson("/api/documents/" + docId + "/reviews?reviewer=bob");
        assertThat(reviews.status()).isEqualTo(200);
        JsonNode bobReview = reviews.body().get(0);
        assertThat(bobReview.get("reviewStatus").asText()).isEqualTo("CONFLICTED");
        assertThat(bobReview.get("conflictId").asLong()).isEqualTo(conflictId);
        assertThat(bobReview.get("decision").asText()).isEqualTo("APPROVED");

        // 草稿 2（建文档 1 + 提交译文 1）；冲突登记不增加草稿版本
        ApiResult publishFailed = publish(docId, 2, 0, newRequestId());
        assertThat(publishFailed.status()).isEqualTo(422);
        assertThat(publishFailed.body().get("error").asText()).isEqualTo("QUORUM_NOT_MET");
        assertThat(publishFailed.body().get("requiredReviewCount").asInt()).isEqualTo(1);
        assertThat(publishFailed.body().get("missingReviewCount").asInt()).isEqualTo(1);
        JsonNode affected = publishFailed.body().get("affectedSegments");
        assertThat(affected).hasSize(1);
        assertThat(affected.get(0).get("segmentId").asText()).isEqualTo("s1");
        assertThat(affected.get(0).get("languages")).containsExactly(
                objectMapper.getNodeFactory().textNode("en"));
        assertThat(affected.get(0).get("missingReviewCount").asInt()).isEqualTo(1);

        assertThat(jdbc.queryForObject(
                "SELECT published_version FROM document WHERE document_id = ?", Integer.class, docId)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_snapshot WHERE document_id = ?", Integer.class, docId)).isZero();
    }

    @Test
    @DisplayName("多语种排除后法定人数不足：422 稳定列出受影响语种，不发布部分语种")
    void quorumFailureListsAffectedLanguagesAndNoPartialRelease() throws Exception {
        clock.setFixed(Instant.parse(T0));
        long docId = createDocument(newRequestId(), "[\"en\",\"ja\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"原文\"}]");
        submitTranslation(docId, "s1", "en", "alice", "hello", 1, newRequestId());
        submitTranslation(docId, "s1", "ja", "akiko", "こんにちは", 1, newRequestId());
        approve(docId, "s1", "en", "bob", 1, newRequestId());
        approve(docId, "s1", "ja", "dave", 1, newRequestId());
        // 当前草稿版本 3；登记 dave 的 ja 冲突，排除 ja 批准
        registerConflicts(docId, "dave", "[" + rangeJson("ja", T0, T1, "dave 关联日语供应商") + "]",
                newRequestId());

        ApiResult result = publish(docId, 3, 0, newRequestId());
        assertThat(result.status()).isEqualTo(422);
        assertThat(result.body().get("error").asText()).isEqualTo("QUORUM_NOT_MET");
        assertThat(result.body().get("missingReviewCount").asInt()).isEqualTo(1);
        JsonNode affected = result.body().get("affectedSegments");
        assertThat(affected).hasSize(1);
        assertThat(affected.get(0).get("segmentId").asText()).isEqualTo("s1");
        assertThat(affected.get(0).get("languages")).containsExactly(
                objectMapper.getNodeFactory().textNode("ja"));
        // 不发布任何部分语种
        assertThat(jdbc.queryForObject(
                "SELECT published_version FROM document WHERE document_id = ?", Integer.class, docId)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_snapshot WHERE document_id = ?", Integer.class, docId)).isZero();
    }

    @Test
    @DisplayName("解除冲突：须异于声明人的合规角色；解除只影响后续，旧评审保持 CONFLICTED，旧快照不失效")
    void resolveConflictRulesAndNoRetroactiveEffect() throws Exception {
        long docId = docWithApprovedTranslation();
        ApiResult registered = registerConflicts(docId, "bob",
                "[" + rangeJson("en", T0, T1, "bob 关联关系") + "]", newRequestId());
        long conflictId = registered.body().get("conflicts").get(0).get("conflictId").asLong();

        // 非合规角色 403
        ApiResult notCompliance = resolveConflict(docId, conflictId, "erin", "REVIEWER", "解除", newRequestId());
        assertThat(notCompliance.status()).isEqualTo(403);
        // 缺少角色头同样 403
        ApiResult noRole = postJson("/api/documents/" + docId + "/conflicts/" + conflictId + "/resolve",
                "{\"requestId\":\"" + newRequestId() + "\",\"reason\":\"解除\"}", "erin");
        assertThat(noRole.status()).isEqualTo(403);
        // 合规角色但是声明人本人 403
        ApiResult selfResolve = resolveConflict(docId, conflictId, "bob", "COMPLIANCE", "自己解除",
                newRequestId());
        assertThat(selfResolve.status()).isEqualTo(403);
        // 不同合规人解除成功
        ApiResult resolved = resolveConflict(docId, conflictId, "erin", "COMPLIANCE", "合规复核通过",
                newRequestId());
        assertThat(resolved.status()).isEqualTo(200);
        assertThat(resolved.body().get("status").asText()).isEqualTo("RESOLVED");
        assertThat(resolved.body().get("resolvedBy").asText()).isEqualTo("erin");
        assertThat(resolved.body().get("resolveReason").asText()).isEqualTo("合规复核通过");
        JsonNode history = resolved.body().get("history");
        assertThat(history).hasSize(2);
        assertThat(history.get(0).get("eventType").asText()).isEqualTo("DECLARED");
        assertThat(history.get(1).get("eventType").asText()).isEqualTo("RESOLVED");
        assertThat(history.get(1).get("actor").asText()).isEqualTo("erin");
        assertThat(history.get(1).get("actorRole").asText()).isEqualTo("COMPLIANCE");

        // 重复解除 409
        ApiResult again = resolveConflict(docId, conflictId, "erin", "COMPLIANCE", "再次解除",
                newRequestId());
        assertThat(again.status()).isEqualTo(409);

        // 旧评审不自动恢复，仍为 CONFLICTED 且证据未被改写
        JsonNode bobReview = getJson("/api/documents/" + docId + "/reviews?reviewer=bob").body().get(0);
        assertThat(bobReview.get("reviewStatus").asText()).isEqualTo("CONFLICTED");
        assertThat(bobReview.get("conflictId").asLong()).isEqualTo(conflictId);

        // 解除后 bob 可重新提交评审，发布成功
        ApiResult reApprove = approve(docId, "s1", "en", "bob", 1, newRequestId());
        assertThat(reApprove.status()).isEqualTo(200);
        assertThat(reApprove.body().get("reviewStatus").asText()).isEqualTo("ACTIVE");
        ApiResult published = publish(docId, 2, 0, newRequestId());
        assertThat(published.status()).isEqualTo(201);
        assertThat(published.body().get("publishedVersion").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("有效否决阻断发布：422 REVIEW_REJECTED 带否决明细；原因空 400；作者自评 422；版本不符 422")
    void rejectionBlocksPublish() throws Exception {
        clock.setFixed(Instant.parse(T0));
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"原文\"}]");
        submitTranslation(docId, "s1", "en", "alice", "hello", 1, newRequestId());

        ApiResult blankReason = reject(docId, "s1", "en", "bob", 1, "", newRequestId());
        assertThat(blankReason.status()).isEqualTo(400);
        ApiResult byAuthor = reject(docId, "s1", "en", "alice", 1, "我是作者", newRequestId());
        assertThat(byAuthor.status()).isEqualTo(422);
        ApiResult wrongVersion = reject(docId, "s1", "en", "bob", 9, "版本不对", newRequestId());
        assertThat(wrongVersion.status()).isEqualTo(422);

        ApiResult rejected = reject(docId, "s1", "en", "bob", 1, "术语翻译错误", newRequestId());
        assertThat(rejected.status()).isEqualTo(200);
        assertThat(rejected.body().get("decision").asText()).isEqualTo("REJECTED");

        ApiResult publishResult = publish(docId, 2, 0, newRequestId());
        assertThat(publishResult.status()).isEqualTo(422);
        assertThat(publishResult.body().get("error").asText()).isEqualTo("REVIEW_REJECTED");
        JsonNode rejectedReviews = publishResult.body().get("rejectedReviews");
        assertThat(rejectedReviews).hasSize(1);
        assertThat(rejectedReviews.get(0).get("segmentId").asText()).isEqualTo("s1");
        assertThat(rejectedReviews.get(0).get("language").asText()).isEqualTo("en");
        assertThat(rejectedReviews.get(0).get("reviewer").asText()).isEqualTo("bob");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_snapshot WHERE document_id = ?", Integer.class, docId)).isZero();

        // 同一评审人改判批准后（只追加新行）可发布，否决原因作为旧证据保留
        ApiResult changed = approve(docId, "s1", "en", "bob", 1, newRequestId());
        assertThat(changed.status()).isEqualTo(200);
        JsonNode bobReviews = getJson("/api/documents/" + docId + "/reviews?reviewer=bob").body();
        assertThat(bobReviews).hasSize(2);
        assertThat(bobReviews.get(0).get("decision").asText()).isEqualTo("REJECTED");
        assertThat(bobReviews.get(0).get("decisionReason").asText()).isEqualTo("术语翻译错误");
        assertThat(bobReviews.get(1).get("decision").asText()).isEqualTo("APPROVED");
        assertThat(publish(docId, 2, 0, newRequestId()).status()).isEqualTo(201);
    }

    @Test
    @DisplayName("批量预校验：倒置区间/越界语种整批 422 不落库且不改变资格；批次内重叠整批 422")
    void batchPrevalidationIsAtomic() throws Exception {
        long docId = docWithApprovedTranslation();

        // 一个合法区间 + 一个倒置区间：整批拒绝
        String inverted = "[" + rangeJson("en", T0, T1, "合法") + ","
                + rangeJson("en", T1, T0, "倒置") + "]";
        ApiResult invertedResult = registerConflicts(docId, "bob", inverted, newRequestId());
        assertThat(invertedResult.status()).isEqualTo(422);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM conflict_declaration", Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT review_status FROM approval WHERE document_id = ? AND reviewer = 'bob'",
                String.class, docId)).isEqualTo("ACTIVE");

        // 语种越界整批拒绝
        String wrongLanguage = "[" + rangeJson("fr", T0, T1, "不存在的语种") + "]";
        ApiResult wrongLangResult = registerConflicts(docId, "bob", wrongLanguage, newRequestId());
        assertThat(wrongLangResult.status()).isEqualTo(422);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM conflict_declaration", Integer.class)).isZero();

        // 原因空：Bean Validation 400
        String blankReason = "[" + rangeJson("en", T0, T1, "") + "]";
        ApiResult blank = registerConflicts(docId, "bob", blankReason, newRequestId());
        assertThat(blank.status()).isEqualTo(400);

        // 批次内同语种区间重叠（均覆盖登记时刻，必重叠）
        String overlap = "[" + rangeJson("en", T0, T1, "第一段") + ","
                + rangeJson("en", "2026-10-01T12:00:00Z", "2026-10-03T00:00:00Z", "重叠段") + "]";
        ApiResult overlapResult = registerConflicts(docId, "carol", overlap, newRequestId());
        assertThat(overlapResult.status()).isEqualTo(422);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM conflict_declaration", Integer.class)).isZero();
    }

    @Test
    @DisplayName("与同一评审人同语种既有有效声明重叠：返回 409 且不写入；相接区间允许")
    void overlapWithExistingReturns409() throws Exception {
        clock.setFixed(Instant.parse(T0));
        long docId = createDocument(newRequestId(), "[\"en\"]", "[]");
        ApiResult first = registerConflicts(docId, "bob",
                "[" + rangeJson("en", T0, T1, "第一段") + "]", newRequestId());
        assertThat(first.status()).isEqualTo(201);

        ApiResult overlap = registerConflicts(docId, "bob",
                "[" + rangeJson("en", "2026-10-01T12:00:00Z", "2026-10-03T00:00:00Z", "重叠") + "]",
                newRequestId());
        assertThat(overlap.status()).isEqualTo(409);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM conflict_declaration", Integer.class)).isEqualTo(1);

        // 解除旧声明后可登记新区间（不再是有效声明）
        long conflictId = first.body().get("conflicts").get(0).get("conflictId").asLong();
        assertThat(resolveConflict(docId, conflictId, "erin", "COMPLIANCE", "解除", newRequestId()).status())
                .isEqualTo(200);
        ApiResult afterResolve = registerConflicts(docId, "bob",
                "[" + rangeJson("en", T0, "2026-10-03T00:00:00Z", "新声明") + "]", newRequestId());
        assertThat(afterResolve.status()).isEqualTo(201);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM conflict_declaration", Integer.class)).isEqualTo(2);
    }

    @Test
    @DisplayName("幂等：登记冲突同参重放首次完整结果且不重复落库；异参 409；失败不占键")
    void conflictIdempotency() throws Exception {
        clock.setFixed(Instant.parse(T0));
        long docId = createDocument(newRequestId(), "[\"en\"]", "[]");
        String requestId = newRequestId();
        String body = "{\"requestId\":\"" + requestId + "\",\"ranges\":["
                + rangeJson("en", T0, T1, "唯一原因") + "]}";

        ApiResult first = postJson("/api/documents/" + docId + "/conflicts", body, "bob");
        assertThat(first.status()).isEqualTo(201);
        long conflictId = first.body().get("conflicts").get(0).get("conflictId").asLong();

        ApiResult replay = postJson("/api/documents/" + docId + "/conflicts", body, "bob");
        assertThat(replay.status()).isEqualTo(201);
        assertThat(replay.body().get("conflicts").get(0).get("conflictId").asLong()).isEqualTo(conflictId);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM conflict_declaration", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM conflict_event", Integer.class)).isEqualTo(1);
        // 建文档与首次登记各占一个 requestId，重放不新增
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM request_log", Integer.class)).isEqualTo(2);

        String different = "{\"requestId\":\"" + requestId + "\",\"ranges\":["
                + rangeJson("en", T0, T1, "不同原因") + "]}";
        ApiResult conflict = postJson("/api/documents/" + docId + "/conflicts", different, "bob");
        assertThat(conflict.status()).isEqualTo(409);

        // 失败不占键：先用该键提交倒置区间 422，再用同键提交合法区间成功
        String failKey = newRequestId();
        String inverted = "{\"requestId\":\"" + failKey + "\",\"ranges\":["
                + rangeJson("en", T1, T0, "倒置") + "]}";
        assertThat(postJson("/api/documents/" + docId + "/conflicts", inverted, "carol").status())
                .isEqualTo(422);
        String valid = "{\"requestId\":\"" + failKey + "\",\"ranges\":["
                + rangeJson("en", T0, T1, "合法") + "]}";
        assertThat(postJson("/api/documents/" + docId + "/conflicts", valid, "carol").status())
                .isEqualTo(201);
    }

    @Test
    @DisplayName("发布快照固化冲突区间/原因/历史与评审状态；后续解除与改判不影响旧快照")
    void snapshotFreezesConflictsAndReviews() throws Exception {
        long docId = docWithApprovedTranslation();
        ApiResult registered = registerConflicts(docId, "bob",
                "[" + rangeJson("en", T0, T1, "bob 关联关系") + "]", newRequestId());
        long conflictId = registered.body().get("conflicts").get(0).get("conflictId").asLong();
        resolveConflict(docId, conflictId, "erin", "COMPLIANCE", "合规复核通过", newRequestId());
        approve(docId, "s1", "en", "carol", 1, newRequestId());

        ApiResult published = publish(docId, 2, 0, newRequestId());
        assertThat(published.status()).isEqualTo(201);
        ApiResult snapshot = getJson("/api/documents/" + docId + "/releases/1");
        assertThat(snapshot.status()).isEqualTo(200);

        JsonNode conflicts = snapshot.body().get("conflicts");
        assertThat(conflicts).hasSize(1);
        JsonNode frozenConflict = conflicts.get(0);
        assertThat(frozenConflict.get("conflictId").asLong()).isEqualTo(conflictId);
        assertThat(frozenConflict.get("validFrom").asText()).isEqualTo(T0);
        assertThat(frozenConflict.get("validTo").asText()).isEqualTo(T1);
        assertThat(frozenConflict.get("reason").asText()).isEqualTo("bob 关联关系");
        assertThat(frozenConflict.get("status").asText()).isEqualTo("RESOLVED");
        assertThat(frozenConflict.get("history")).hasSize(2);

        JsonNode reviews = snapshot.body().get("segments").get(0).get("translations").get(0).get("reviews");
        JsonNode bobFrozen = reviews.get(0);
        assertThat(bobFrozen.get("reviewer").asText()).isEqualTo("bob");
        assertThat(bobFrozen.get("reviewStatus").asText()).isEqualTo("CONFLICTED");
        assertThat(bobFrozen.get("conflictId").asLong()).isEqualTo(conflictId);
        JsonNode carolFrozen = reviews.get(1);
        assertThat(carolFrozen.get("reviewer").asText()).isEqualTo("carol");
        assertThat(carolFrozen.get("reviewStatus").asText()).isEqualTo("ACTIVE");
        assertThat(carolFrozen.has("conflictId")).isFalse();
        // 兼容字段取有效批准评审人
        assertThat(snapshot.body().get("segments").get(0).get("translations").get(0)
                .get("reviewer").asText()).isEqualTo("carol");

        // 旧快照只读：再登记新冲突不改变版本 1 快照内容
        registerConflicts(docId, "carol", "[" + rangeJson("en", T0, T1, "carol 新冲突") + "]",
                newRequestId());
        ApiResult snapshotAgain = getJson("/api/documents/" + docId + "/releases/1");
        assertThat(snapshotAgain.body().get("conflicts")).hasSize(1);
        assertThat(snapshotAgain.body().get("segments").get(0).get("translations").get(0)
                .get("reviews")).hasSize(2);
    }

    @Test
    @DisplayName("未来区间：登记即把既有评审标记 CONFLICTED 并排除出法定人数；区间生效期拦截提交，到期后可重新提交")
    void futureRangeExcludesImmediatelyButSubmissionWindowIsTimeBased() throws Exception {
        clock.setFixed(Instant.parse(T0));
        long docId = docWithApprovedTranslation();

        // 未来区间 [T1, T1+1d)
        String futureFrom = T1;
        String futureTo = "2026-10-03T00:00:00Z";
        ApiResult registered = registerConflicts(docId, "bob",
                "[" + rangeJson("en", futureFrom, futureTo, "bob 预先披露未来任职") + "]",
                newRequestId());
        assertThat(registered.status()).isEqualTo(201);
        assertThat(registered.body().get("markedConflictReviewCount").asInt()).isEqualTo(1);

        // 登记后既有评审立即从法定人数排除（与区间是否已生效无关）
        assertThat(publish(docId, 2, 0, newRequestId()).status()).isEqualTo(422);

        // 当前时刻（T0）区间未生效：bob 可重新提交评审，法定人数恢复
        ApiResult approveNow = approve(docId, "s1", "en", "bob", 1, newRequestId());
        assertThat(approveNow.status()).isEqualTo(200);
        assertThat(publish(docId, 2, 0, newRequestId()).status()).isEqualTo(201);

        // 推进到区间生效期：bob 新提交被拦截（旧 ACTIVE 评审不追溯改动）
        clock.setFixed(Instant.parse("2026-10-02T12:00:00Z"));
        ApiResult blockedDuring = approve(docId, "s1", "en", "bob", 1, newRequestId());
        assertThat(blockedDuring.status()).isEqualTo(422);
        assertThat(blockedDuring.body().get("error").asText()).isEqualTo("REVIEWER_CONFLICT");

        // 区间到期（左闭右开）后可再次提交
        clock.setFixed(Instant.parse(futureTo));
        assertThat(approve(docId, "s1", "en", "bob", 1, newRequestId()).status()).isEqualTo(200);
    }

    @Test
    @DisplayName("批量登记成功：多个不重叠范围一次登记，返回各范围结果与标记数合计，历史逐条追加")
    void batchRegisterSuccess() throws Exception {
        clock.setFixed(Instant.parse(T0));
        long docId = createDocument(newRequestId(), "[\"en\",\"ja\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"原文\"}]");
        submitTranslation(docId, "s1", "en", "alice", "hello", 1, newRequestId());
        submitTranslation(docId, "s1", "ja", "akiko", "こんにちは", 1, newRequestId());
        approve(docId, "s1", "en", "bob", 1, newRequestId());
        approve(docId, "s1", "ja", "bob", 1, newRequestId());

        // 两个不同语种、各自覆盖当前时刻、互不重叠的范围
        String ranges = "[" + rangeJson("en", T0, T1, "英文关联") + ","
                + rangeJson("ja", T0, T1, "日文关联") + "]";
        ApiResult result = registerConflicts(docId, "bob", ranges, newRequestId());
        assertThat(result.status()).isEqualTo(201);
        assertThat(result.body().get("registeredCount").asInt()).isEqualTo(2);
        assertThat(result.body().get("markedConflictReviewCount").asInt()).isEqualTo(2);
        assertThat(result.body().get("conflicts")).hasSize(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM conflict_event WHERE document_id = ?",
                Integer.class, docId)).isEqualTo(2);
        // 两个语种的批准均被排除
        Integer conflicted = jdbc.queryForObject(
                "SELECT COUNT(*) FROM approval WHERE document_id = ? AND review_status = 'CONFLICTED'",
                Integer.class, docId);
        assertThat(conflicted).isEqualTo(2);
    }

    @Test
    @DisplayName("诊断查询：返回 UTC 评估时刻、评审人资格与命中冲突、段落计数；读取不改变状态")
    void diagnosticsReadOnly() throws Exception {
        long docId = docWithApprovedTranslation();
        registerConflicts(docId, "bob", "[" + rangeJson("en", T0, T1, "bob 关联关系") + "]",
                newRequestId());

        Integer conflictsBefore = jdbc.queryForObject("SELECT COUNT(*) FROM conflict_declaration", Integer.class);
        Integer reviewsBefore = jdbc.queryForObject("SELECT COUNT(*) FROM approval", Integer.class);

        ApiResult diag = getJson("/api/documents/" + docId + "/reviews/diagnostics");
        assertThat(diag.status()).isEqualTo(200);
        assertThat(diag.body().get("evaluatedAt").asText()).isEqualTo(T0);
        assertThat(diag.body().get("requiredReviewCount").asInt()).isEqualTo(1);

        JsonNode bob = diag.body().get("reviewers").get(0);
        assertThat(bob.get("reviewer").asText()).isEqualTo("bob");
        assertThat(bob.get("language").asText()).isEqualTo("en");
        assertThat(bob.get("eligible").asBoolean()).isFalse();
        assertThat(bob.get("activeConflicts")).hasSize(1);
        assertThat(bob.get("activeConflicts").get(0).get("reason").asText()).isEqualTo("bob 关联关系");

        JsonNode summary = diag.body().get("segments").get(0);
        assertThat(summary.get("segmentId").asText()).isEqualTo("s1");
        assertThat(summary.get("activeApprovals").asInt()).isZero();
        assertThat(summary.get("conflictedReviews").asInt()).isEqualTo(1);
        assertThat(summary.get("missingReviewCount").asInt()).isEqualTo(1);
        assertThat(summary.get("quorumMet").asBoolean()).isFalse();

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM conflict_declaration", Integer.class))
                .isEqualTo(conflictsBefore);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM approval", Integer.class)).isEqualTo(reviewsBefore);
    }

    @Test
    @DisplayName("历史查询：按事件 ID 升序返回登记与解除，可按声明过滤；时间保持毫秒精度")
    void historyQueryOrderingAndPrecision() throws Exception {
        clock.setFixed(Instant.parse(T0));
        long docId = createDocument(newRequestId(), "[\"en\",\"ja\"]", "[]");
        ApiResult c1 = registerConflicts(docId, "bob",
                "[" + rangeJson("en", T0, T1, "英文冲突") + "]", newRequestId());
        ApiResult c2 = registerConflicts(docId, "bob",
                "[" + rangeJson("ja", T0, T1, "日文冲突") + "]", newRequestId());
        long id1 = c1.body().get("conflicts").get(0).get("conflictId").asLong();
        long id2 = c2.body().get("conflicts").get(0).get("conflictId").asLong();
        resolveConflict(docId, id2, "erin", "COMPLIANCE", "解除日文", newRequestId());

        ApiResult all = getJson("/api/documents/" + docId + "/conflicts/history");
        assertThat(all.status()).isEqualTo(200);
        assertThat(all.body()).hasSize(3);
        List<Long> eventIds = new ArrayList<>();
        all.body().forEach(e -> eventIds.add(e.get("eventId").asLong()));
        assertThat(eventIds).isSorted();
        assertThat(all.body().get(0).get("createdAt").asText()).endsWith(".500Z");
        assertThat(all.body().get(2).get("eventType").asText()).isEqualTo("RESOLVED");

        ApiResult filtered = getJson("/api/documents/" + docId + "/conflicts/history?conflictId=" + id1);
        assertThat(filtered.body()).hasSize(1);
        assertThat(filtered.body().get(0).get("conflictId").asLong()).isEqualTo(id1);

        // 过滤参数生效
        ApiResult byReviewer = getJson("/api/documents/" + docId + "/conflicts?reviewer=bob&language=ja");
        assertThat(byReviewer.body()).hasSize(1);
        assertThat(byReviewer.body().get(0).get("conflictId").asLong()).isEqualTo(id2);
    }
}
