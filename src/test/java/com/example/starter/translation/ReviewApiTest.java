package com.example.starter.translation;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 双阶段法定人数评审 API 主流程与失败分支测试。
 * 通用前置：文档含 en 一个段落；策略 v1 为 LANGUAGE 法定人数 2（r1/r2/r3）、
 * COMPLIANCE 法定人数 1（r2/r4）；alice 提交译文、bob 旧式批准；
 * 此时草稿版本 3、发布版本 0、术语版本 0、策略版本 1、源文/译文版本均为 1。
 */
class ReviewApiTest extends AbstractIntegrationTest {

    /** 通用前置，返回 documentId。 */
    private long setupDocumentWithPolicy() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"原文\"}]");
        ApiResult policy = configurePolicy(docId, "en", 0, 2, "[\"r1\",\"r2\",\"r3\"]",
                1, "[\"r2\",\"r4\"]", newRequestId());
        assertThat(policy.status()).isEqualTo(201);
        assertThat(policy.body().get("policyVersion").asInt()).isEqualTo(1);
        assertThat(policy.body().get("draftVersion").asInt()).isEqualTo(2);
        submitTranslation(docId, "s1", "en", "alice", "hello", 1, newRequestId());
        approve(docId, "s1", "en", "bob", 1, newRequestId());
        return docId;
    }

    private ApiResult vote(long docId, String reviewer, String stage, String decision, String voteKey,
                           int policyVersion, Integer expectedVoteVersion) throws Exception {
        return castVote(docId, "s1", "en", reviewer, voteKey, stage, decision, 1, 1, 0,
                policyVersion, expectedVoteVersion, newRequestId());
    }

    /** 两阶段各投满法定人数的 APPROVE。 */
    private void castPassingVotes(long docId, int policyVersion) throws Exception {
        assertThat(vote(docId, "r1", "LANGUAGE", "APPROVE", newVoteKey(), policyVersion, null).status())
                .isEqualTo(201);
        assertThat(vote(docId, "r2", "LANGUAGE", "APPROVE", newVoteKey(), policyVersion, null).status())
                .isEqualTo(201);
        assertThat(vote(docId, "r4", "COMPLIANCE", "APPROVE", newVoteKey(), policyVersion, null).status())
                .isEqualTo(201);
    }

    private String newVoteKey() {
        return "vk-" + newRequestId();
    }

    /** 从票数组中筛选指定审核人的票（按票版本升序）。 */
    private static List<JsonNode> votesOf(JsonNode votes, String reviewer) {
        List<JsonNode> result = new ArrayList<>();
        for (JsonNode vote : votes) {
            if (vote.get("reviewer").asText().equals(reviewer)) {
                result.add(vote);
            }
        }
        return result;
    }

    @Test
    @DisplayName("主流程：两阶段投票达法定人数后矩阵 PASSED，发布成功并冻结票版本集合")
    void fullReviewAndPublishFlow() throws Exception {
        long docId = setupDocumentWithPolicy();
        castPassingVotes(docId, 1);

        ApiResult matrix = getJson("/api/documents/" + docId + "/review-matrix");
        assertThat(matrix.status()).isEqualTo(200);
        var entry = matrix.body().get("entries").get(0);
        assertThat(entry.get("segmentId").asText()).isEqualTo("s1");
        assertThat(entry.get("policyVersion").asInt()).isEqualTo(1);
        var stages = entry.get("stages");
        assertThat(stages).hasSize(2);
        assertThat(stages.get(0).get("stage").asText()).isEqualTo("LANGUAGE");
        assertThat(stages.get(0).get("status").asText()).isEqualTo("PASSED");
        assertThat(stages.get(0).get("approveCount").asInt()).isEqualTo(2);
        assertThat(stages.get(1).get("stage").asText()).isEqualTo("COMPLIANCE");
        assertThat(stages.get(1).get("status").asText()).isEqualTo("PASSED");
        assertThat(stages.get(1).get("approveCount").asInt()).isEqualTo(1);

        ApiResult published = publish(docId, 3, 0, newRequestId());
        assertThat(published.status()).as("响应体: %s", published.body()).isEqualTo(201);

        ApiResult release = getJson("/api/documents/" + docId + "/releases/1");
        assertThat(release.status()).isEqualTo(200);
        var translation = release.body().get("segments").get(0).get("translations").get(0);
        assertThat(translation.get("policyVersion").asInt()).isEqualTo(1);
        assertThat(translation.get("reviewVotes")).hasSize(3);

        Integer frozen = jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_vote_freeze WHERE document_id = ? AND published_version = 1",
                Integer.class, docId);
        assertThat(frozen).isEqualTo(3);
    }

    @Test
    @DisplayName("配置策略：语言不在目标语言 422；期望策略版本不符 409；法定人数超候选人数 422；候选重复 422；失败不占键")
    void configurePolicyFailures() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"原文\"}]");

        ApiResult wrongLang = configurePolicy(docId, "fr", 0, 1, "[\"r1\"]", 1, "[\"r2\"]", newRequestId());
        assertThat(wrongLang.status()).isEqualTo(422);

        ApiResult wrongExpected = configurePolicy(docId, "en", 3, 1, "[\"r1\"]", 1, "[\"r2\"]", newRequestId());
        assertThat(wrongExpected.status()).isEqualTo(409);

        ApiResult quorumTooBig = configurePolicy(docId, "en", 0, 3, "[\"r1\",\"r2\"]",
                1, "[\"r2\"]", newRequestId());
        assertThat(quorumTooBig.status()).isEqualTo(422);

        ApiResult dupReviewer = configurePolicy(docId, "en", 0, 1, "[\"r1\",\"r1\"]",
                1, "[\"r2\"]", newRequestId());
        assertThat(dupReviewer.status()).isEqualTo(422);

        // 失败不占键：用失败请求的 requestId 修正参数后成功
        String retryKey = newRequestId();
        ApiResult failed = configurePolicy(docId, "en", 0, 5, "[\"r1\"]", 1, "[\"r2\"]", retryKey);
        assertThat(failed.status()).isEqualTo(422);
        ApiResult retried = configurePolicy(docId, "en", 0, 1, "[\"r1\"]", 1, "[\"r2\"]", retryKey);
        assertThat(retried.status()).isEqualTo(201);
        assertThat(retried.body().get("policyVersion").asInt()).isEqualTo(1);

        // 激活后再次以旧期望版本配置 409
        ApiResult stale = configurePolicy(docId, "en", 0, 1, "[\"r1\"]", 1, "[\"r2\"]", newRequestId());
        assertThat(stale.status()).isEqualTo(409);
    }

    @Test
    @DisplayName("投票：未配置策略 422；非候选审核人 422；版本组合不匹配 422；策略版本不匹配 422；未知阶段/决定 422")
    void castVoteFailures() throws Exception {
        long noPolicy = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"原文\"}]");
        submitTranslation(noPolicy, "s1", "en", "alice", "hello", 1, newRequestId());
        ApiResult noPolicyVote = castVote(noPolicy, "s1", "en", "r1", newVoteKey(), "LANGUAGE", "APPROVE",
                1, 1, 0, 1, null, newRequestId());
        assertThat(noPolicyVote.status()).isEqualTo(422);

        long docId = setupDocumentWithPolicy();
        ApiResult stranger = castVote(docId, "s1", "en", "stranger", newVoteKey(), "LANGUAGE", "APPROVE",
                1, 1, 0, 1, null, newRequestId());
        assertThat(stranger.status()).isEqualTo(422);

        ApiResult staleTranslation = castVote(docId, "s1", "en", "r1", newVoteKey(), "LANGUAGE", "APPROVE",
                1, 9, 0, 1, null, newRequestId());
        assertThat(staleTranslation.status()).isEqualTo(422);

        ApiResult staleTerm = castVote(docId, "s1", "en", "r1", newVoteKey(), "LANGUAGE", "APPROVE",
                1, 1, 7, 1, null, newRequestId());
        assertThat(staleTerm.status()).isEqualTo(422);

        ApiResult stalePolicy = castVote(docId, "s1", "en", "r1", newVoteKey(), "LANGUAGE", "APPROVE",
                1, 1, 0, 99, null, newRequestId());
        assertThat(stalePolicy.status()).isEqualTo(422);

        ApiResult badStage = castVote(docId, "s1", "en", "r1", newVoteKey(), "LEGAL", "APPROVE",
                1, 1, 0, 1, null, newRequestId());
        assertThat(badStage.status()).isEqualTo(422);

        ApiResult badDecision = castVote(docId, "s1", "en", "r1", newVoteKey(), "LANGUAGE", "MAYBE",
                1, 1, 0, 1, null, newRequestId());
        assertThat(badDecision.status()).isEqualTo(422);

        // 全部失败后没有任何票写入
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM review_vote", Integer.class)).isZero();
    }

    @Test
    @DisplayName("同一审核人对同一译文只能在一个阶段投票：跨阶段投票 422")
    void sameReviewerSingleStage() throws Exception {
        long docId = setupDocumentWithPolicy();
        // r2 同时在两个阶段的候选集合中
        assertThat(vote(docId, "r2", "LANGUAGE", "APPROVE", newVoteKey(), 1, null).status()).isEqualTo(201);
        ApiResult crossStage = vote(docId, "r2", "COMPLIANCE", "APPROVE", newVoteKey(), 1, null);
        assertThat(crossStage.status()).isEqualTo(422);
        // 同阶段改票不受限
        ApiResult change = vote(docId, "r2", "LANGUAGE", "REJECT", newVoteKey(), 1, 1);
        assertThat(change.status()).isEqualTo(201);
        assertThat(change.body().get("voteVersion").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("当前 REJECT 使阶段 BLOCKED 发布 422；改票需 expectedVoteVersion，改票后 PASSED 发布成功")
    void rejectBlocksAndChangeVote() throws Exception {
        long docId = setupDocumentWithPolicy();
        assertThat(vote(docId, "r1", "LANGUAGE", "APPROVE", newVoteKey(), 1, null).status()).isEqualTo(201);
        assertThat(vote(docId, "r2", "LANGUAGE", "APPROVE", newVoteKey(), 1, null).status()).isEqualTo(201);
        assertThat(vote(docId, "r4", "COMPLIANCE", "REJECT", newVoteKey(), 1, null).status()).isEqualTo(201);

        ApiResult matrix = getJson("/api/documents/" + docId + "/review-matrix");
        var compliance = matrix.body().get("entries").get(0).get("stages").get(1);
        assertThat(compliance.get("stage").asText()).isEqualTo("COMPLIANCE");
        assertThat(compliance.get("status").asText()).isEqualTo("BLOCKED");
        assertThat(compliance.get("rejectCount").asInt()).isEqualTo(1);
        assertThat(compliance.get("rejecters").get(0).asText()).isEqualTo("r4");

        ApiResult blocked = publish(docId, 3, 0, newRequestId());
        assertThat(blocked.status()).isEqualTo(422);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_vote_freeze WHERE document_id = ?", Integer.class, docId))
                .isZero();

        // 改票：期望票版本不符 409；缺失（视为 0）也 409
        ApiResult wrongExpected = vote(docId, "r4", "COMPLIANCE", "APPROVE", newVoteKey(), 1, 7);
        assertThat(wrongExpected.status()).isEqualTo(409);
        ApiResult missingExpected = vote(docId, "r4", "COMPLIANCE", "APPROVE", newVoteKey(), 1, null);
        assertThat(missingExpected.status()).isEqualTo(409);

        ApiResult changed = vote(docId, "r4", "COMPLIANCE", "APPROVE", newVoteKey(), 1, 1);
        assertThat(changed.status()).isEqualTo(201);
        assertThat(changed.body().get("voteVersion").asInt()).isEqualTo(2);

        ApiResult matrixAfter = getJson("/api/documents/" + docId + "/review-matrix");
        assertThat(matrixAfter.body().get("entries").get(0).get("stages").get(1).get("status").asText())
                .isEqualTo("PASSED");
        assertThat(publish(docId, 3, 0, newRequestId()).status()).isEqualTo(201);
    }

    @Test
    @DisplayName("译文修订后旧票保留审计但不计入法定人数：矩阵回退 PENDING，发布 422，改票后通过")
    void revisionInvalidatesVotes() throws Exception {
        long docId = setupDocumentWithPolicy();
        castPassingVotes(docId, 1);

        // 重新提交译文：译文版本 2，草稿版本 4，旧票与旧批准均失效
        submitTranslation(docId, "s1", "en", "alice", "hello v2", 1, newRequestId());
        ApiResult matrix = getJson("/api/documents/" + docId + "/review-matrix");
        var stages = matrix.body().get("entries").get(0).get("stages");
        assertThat(stages.get(0).get("status").asText()).isEqualTo("PENDING");
        assertThat(stages.get(0).get("approveCount").asInt()).isZero();
        assertThat(stages.get(1).get("status").asText()).isEqualTo("PENDING");

        assertThat(publish(docId, 4, 0, newRequestId()).status()).isEqualTo(422);

        // 针对旧译文版本的票 422；重新批准并针对新版本改票后发布成功
        ApiResult staleVote = castVote(docId, "s1", "en", "r3", newVoteKey(), "LANGUAGE", "APPROVE",
                1, 1, 0, 1, null, newRequestId());
        assertThat(staleVote.status()).isEqualTo(422);
        approve(docId, "s1", "en", "bob", 2, newRequestId());
        assertThat(castVote(docId, "s1", "en", "r1", newVoteKey(), "LANGUAGE", "APPROVE",
                1, 2, 0, 1, 1, newRequestId()).status()).isEqualTo(201);
        assertThat(castVote(docId, "s1", "en", "r2", newVoteKey(), "LANGUAGE", "APPROVE",
                1, 2, 0, 1, 1, newRequestId()).status()).isEqualTo(201);
        assertThat(castVote(docId, "s1", "en", "r4", newVoteKey(), "COMPLIANCE", "APPROVE",
                1, 2, 0, 1, 1, newRequestId()).status()).isEqualTo(201);
        assertThat(publish(docId, 4, 0, newRequestId()).status()).isEqualTo(201);

        // 历史票：r1 两版票均在，仅新版本为当前票且计入
        ApiResult history = getJson("/api/documents/" + docId + "/votes?segmentId=s1&language=en");
        assertThat(history.status()).isEqualTo(200);
        assertThat(history.body().get("votes")).hasSize(6);
        List<JsonNode> r1Votes = votesOf(history.body().get("votes"), "r1");
        assertThat(r1Votes).hasSize(2);
        assertThat(r1Votes.get(0).get("current").asBoolean()).isFalse();
        assertThat(r1Votes.get(0).get("counting").asBoolean()).isFalse();
        assertThat(r1Votes.get(1).get("current").asBoolean()).isTrue();
        assertThat(r1Votes.get(1).get("counting").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("策略切换仅作用于新投票：旧策略票不计入，发布后历史快照不受策略切换影响")
    void policySwitchAffectsOnlyNewVotes() throws Exception {
        long docId = setupDocumentWithPolicy();
        castPassingVotes(docId, 1);
        ApiResult firstPublish = publish(docId, 3, 0, newRequestId());
        assertThat(firstPublish.status()).as("响应体: %s", firstPublish.body()).isEqualTo(201);

        // 切换策略 v2：草稿版本 4；旧票（policyVersion 1）不再计入
        ApiResult switched = configurePolicy(docId, "en", 1, 1, "[\"r5\"]", 1, "[\"r6\"]", newRequestId());
        assertThat(switched.status()).isEqualTo(201);
        assertThat(switched.body().get("policyVersion").asInt()).isEqualTo(2);

        ApiResult matrix = getJson("/api/documents/" + docId + "/review-matrix");
        var entry = matrix.body().get("entries").get(0);
        assertThat(entry.get("policyVersion").asInt()).isEqualTo(2);
        assertThat(entry.get("stages").get(0).get("status").asText()).isEqualTo("PENDING");
        assertThat(entry.get("stages").get(1).get("status").asText()).isEqualTo("PENDING");

        // 旧策略版本的票 422；新策略下投票后发布成功
        ApiResult stalePolicyVote = vote(docId, "r5", "LANGUAGE", "APPROVE", newVoteKey(), 1, null);
        assertThat(stalePolicyVote.status()).isEqualTo(422);
        assertThat(vote(docId, "r5", "LANGUAGE", "APPROVE", newVoteKey(), 2, null).status()).isEqualTo(201);
        assertThat(vote(docId, "r6", "COMPLIANCE", "APPROVE", newVoteKey(), 2, null).status()).isEqualTo(201);
        assertThat(publish(docId, 4, 1, newRequestId()).status()).isEqualTo(201);

        // 历史发布快照不受策略切换影响：release 1 仍为策略版本 1 的票集合
        ApiResult release1 = getJson("/api/documents/" + docId + "/releases/1");
        var t1 = release1.body().get("segments").get(0).get("translations").get(0);
        assertThat(t1.get("policyVersion").asInt()).isEqualTo(1);
        assertThat(t1.get("reviewVotes")).hasSize(3);
        ApiResult release2 = getJson("/api/documents/" + docId + "/releases/2");
        var t2 = release2.body().get("segments").get(0).get("translations").get(0);
        assertThat(t2.get("policyVersion").asInt()).isEqualTo(2);
        assertThat(t2.get("reviewVotes")).hasSize(2);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_vote_freeze WHERE document_id = ? AND published_version = 1",
                Integer.class, docId)).isEqualTo(3);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_vote_freeze WHERE document_id = ? AND published_version = 2",
                Integer.class, docId)).isEqualTo(2);
    }

    @Test
    @DisplayName("voteKey 唯一：换请求复用 409；同 requestId 同参重放原结果且不重复写票；同 requestId 异参 409")
    void voteKeyAndRequestIdempotency() throws Exception {
        long docId = setupDocumentWithPolicy();
        String voteKey = newVoteKey();
        String requestId = newRequestId();
        ApiResult first = castVote(docId, "s1", "en", "r1", voteKey, "LANGUAGE", "APPROVE",
                1, 1, 0, 1, null, requestId);
        assertThat(first.status()).isEqualTo(201);

        // 同 requestId 同参：重放，不产生新票
        ApiResult replay = castVote(docId, "s1", "en", "r1", voteKey, "LANGUAGE", "APPROVE",
                1, 1, 0, 1, null, requestId);
        assertThat(replay.status()).isEqualTo(201);
        assertThat(replay.body().toString()).isEqualTo(first.body().toString());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM review_vote", Integer.class)).isEqualTo(1);

        // 同 requestId 异参：409
        ApiResult conflict = castVote(docId, "s1", "en", "r1", newVoteKey(), "LANGUAGE", "REJECT",
                1, 1, 0, 1, null, requestId);
        assertThat(conflict.status()).isEqualTo(409);

        // 换请求复用 voteKey：409
        ApiResult reusedKey = castVote(docId, "s1", "en", "r1", voteKey, "LANGUAGE", "APPROVE",
                1, 1, 0, 1, null, newRequestId());
        assertThat(reusedKey.status()).isEqualTo(409);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM review_vote", Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("发布门禁失败整体回滚：不产生快照与票冻结，发布版本不变")
    void publishGateFailureRollsBack() throws Exception {
        long docId = setupDocumentWithPolicy();
        // 仅 LANGUAGE 达法定人数，COMPLIANCE 未投票
        assertThat(vote(docId, "r1", "LANGUAGE", "APPROVE", newVoteKey(), 1, null).status()).isEqualTo(201);
        assertThat(vote(docId, "r2", "LANGUAGE", "APPROVE", newVoteKey(), 1, null).status()).isEqualTo(201);

        ApiResult result = publish(docId, 3, 0, newRequestId());
        assertThat(result.status()).isEqualTo(422);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_snapshot WHERE document_id = ?", Integer.class, docId)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_vote_freeze WHERE document_id = ?", Integer.class, docId)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT published_version FROM document WHERE document_id = ?", Integer.class, docId)).isZero();
    }

    @Test
    @DisplayName("历史票查询只读：含被取代旧票，支持段落与语言过滤；文档不存在 404")
    void voteHistoryReadOnly() throws Exception {
        long docId = setupDocumentWithPolicy();
        assertThat(vote(docId, "r1", "LANGUAGE", "REJECT", newVoteKey(), 1, null).status()).isEqualTo(201);
        assertThat(vote(docId, "r1", "LANGUAGE", "APPROVE", newVoteKey(), 1, 1).status()).isEqualTo(201);
        assertThat(vote(docId, "r4", "COMPLIANCE", "APPROVE", newVoteKey(), 1, null).status()).isEqualTo(201);

        ApiResult all = getJson("/api/documents/" + docId + "/votes");
        assertThat(all.status()).isEqualTo(200);
        assertThat(all.body().get("votes")).hasSize(3);

        ApiResult filtered = getJson("/api/documents/" + docId + "/votes?segmentId=s1&language=en");
        assertThat(filtered.body().get("votes")).hasSize(3);
        ApiResult none = getJson("/api/documents/" + docId + "/votes?segmentId=other");
        assertThat(none.body().get("votes")).isEmpty();

        List<JsonNode> r1Votes = votesOf(all.body().get("votes"), "r1");
        assertThat(r1Votes).hasSize(2);
        var r1Old = r1Votes.get(0);
        assertThat(r1Old.get("decision").asText()).isEqualTo("REJECT");
        assertThat(r1Old.get("voteVersion").asInt()).isEqualTo(1);
        assertThat(r1Old.get("current").asBoolean()).isFalse();
        var r1New = r1Votes.get(1);
        assertThat(r1New.get("voteVersion").asInt()).isEqualTo(2);
        assertThat(r1New.get("current").asBoolean()).isTrue();
        assertThat(r1New.get("counting").asBoolean()).isTrue();

        assertThat(getJson("/api/documents/999999/votes").status()).isEqualTo(404);
        assertThat(getJson("/api/documents/999999/review-matrix").status()).isEqualTo(404);
    }
}
