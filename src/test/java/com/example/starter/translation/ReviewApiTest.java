package com.example.starter.translation;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 双阶段法定人数评审主流程与失败分支测试（真实 H2 数据库）。
 */
class ReviewApiTest extends AbstractIntegrationTest {

    /** 建单段落单语言文档、配置策略并提交译文，返回当前草稿版本。 */
    private Setup setupSingleLanguageDoc() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"原文\"}]");
        // 语言阶段：bob/carol/erin，法定 2；合规阶段：carol/dave，法定 1（集合重叠）
        ApiResult policy = putPolicy(docId, "en", 0,
                "[\"bob\",\"carol\",\"erin\"]", 2, "[\"carol\",\"dave\"]", 1, newRequestId());
        assertThat(policy.status()).isEqualTo(201);
        assertThat(policy.body().get("policyVersion").asInt()).isEqualTo(1);
        ApiResult translation = submitTranslation(docId, "s1", "en", "alice", "hello", 1, newRequestId());
        assertThat(translation.status()).isEqualTo(200);
        return new Setup(docId, 3);
    }

    private record Setup(long docId, int draftVersion) {
    }

    @Test
    @DisplayName("策略配置：201 且草稿版本加一；期望版本不符 409；切换生成新版本且旧版本不可变")
    void configureAndSwitchPolicy() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"原文\"}]");

        ApiResult first = putPolicy(docId, "en", 0, "[\"bob\"]", 1, "[\"carol\"]", 1, newRequestId());
        assertThat(first.status()).isEqualTo(201);
        assertThat(first.body().get("languageStage").get("reviewers").get(0).asText()).isEqualTo("bob");
        assertThat(first.body().get("complianceStage").get("quorum").asInt()).isEqualTo(1);

        // 期望版本不符
        ApiResult mismatch = putPolicy(docId, "en", 0, "[\"bob\"]", 1, "[\"carol\"]", 1, newRequestId());
        assertThat(mismatch.status()).isEqualTo(409);

        // 非法策略：候选重复 422；法定人数超过候选人数 422；语言不在目标语言 422
        ApiResult dup = putPolicy(docId, "en", 1, "[\"bob\",\"bob\"]", 1, "[\"carol\"]", 1, newRequestId());
        assertThat(dup.status()).isEqualTo(422);
        ApiResult badQuorum = putPolicy(docId, "en", 1, "[\"bob\"]", 2, "[\"carol\"]", 1, newRequestId());
        assertThat(badQuorum.status()).isEqualTo(422);
        ApiResult wrongLang = putPolicy(docId, "fr", 0, "[\"bob\"]", 1, "[\"carol\"]", 1, newRequestId());
        assertThat(wrongLang.status()).isEqualTo(422);

        // 切换为版本 2：草稿版本再加一
        ApiResult second = putPolicy(docId, "en", 1, "[\"bob\",\"erin\"]", 2, "[\"dave\"]", 1, newRequestId());
        assertThat(second.status()).isEqualTo(201);
        assertThat(second.body().get("policyVersion").asInt()).isEqualTo(2);

        // 旧策略版本仍不可变可查（通过矩阵反映激活版本为 2）
        ApiResult matrix = getMatrix(docId);
        assertThat(matrix.status()).isEqualTo(200);
        assertThat(matrix.body().get("units").get(0).get("policyVersion").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("投票主流程：PENDING→批准达标 PASSED；两阶段均通过后发布成功并冻结票版本集合")
    void voteQuorumAndPublish() throws Exception {
        Setup setup = setupSingleLanguageDoc();
        long docId = setup.docId();

        // 语言阶段第一票：PENDING
        ApiResult first = castVote(docId, "s1", "en", "bob", "LANGUAGE", "APPROVE", 1, 1, 0, 1,
                null, "vk-1", newRequestId());
        assertThat(first.status()).isEqualTo(201);
        assertThat(first.body().get("voteVersion").asInt()).isEqualTo(1);
        assertThat(first.body().get("stageStatus").asText()).isEqualTo("PENDING");

        // 非候选审核人 422
        ApiResult outsider = castVote(docId, "s1", "en", "zoe", "LANGUAGE", "APPROVE", 1, 1, 0, 1,
                null, "vk-out", newRequestId());
        assertThat(outsider.status()).isEqualTo(422);

        // 语言阶段第二票：PASSED
        ApiResult second = castVote(docId, "s1", "en", "erin", "LANGUAGE", "APPROVE", 1, 1, 0, 1,
                null, "vk-2", newRequestId());
        assertThat(second.status()).isEqualTo(201);
        assertThat(second.body().get("stageStatus").asText()).isEqualTo("PASSED");

        // carol 已在合规阶段候选，先投语言阶段后不得再投合规阶段
        ApiResult carolLanguage = castVote(docId, "s1", "en", "carol", "LANGUAGE", "APPROVE", 1, 1, 0, 1,
                null, "vk-carol-l", newRequestId());
        assertThat(carolLanguage.status()).isEqualTo(201);
        ApiResult carolOtherStage = castVote(docId, "s1", "en", "carol", "COMPLIANCE", "APPROVE", 1, 1, 0, 1,
                null, "vk-carol-c", newRequestId());
        assertThat(carolOtherStage.status()).isEqualTo(422);

        // 合规阶段由 dave 批准：PASSED
        ApiResult compliance = castVote(docId, "s1", "en", "dave", "COMPLIANCE", "APPROVE", 1, 1, 0, 1,
                null, "vk-3", newRequestId());
        assertThat(compliance.status()).isEqualTo(201);
        assertThat(compliance.body().get("stageStatus").asText()).isEqualTo("PASSED");

        ApiResult matrix = getMatrix(docId);
        assertThat(matrix.status()).isEqualTo(200);
        JsonNode unit = matrix.body().get("units").get(0);
        assertThat(unit.get("sourceVersion").asInt()).isEqualTo(1);
        assertThat(unit.get("translationVersion").asInt()).isEqualTo(1);
        assertThat(unit.get("termVersion").asInt()).isZero();
        JsonNode stages = unit.get("stages");
        assertThat(stages.get(0).get("stage").asText()).isEqualTo("LANGUAGE");
        assertThat(stages.get(0).get("status").asText()).isEqualTo("PASSED");
        assertThat(stages.get(0).get("approveCount").asInt()).isEqualTo(3);
        assertThat(stages.get(1).get("status").asText()).isEqualTo("PASSED");

        // 发布成功：草稿版本未因投票改变（仍为 3）
        ApiResult published = publish(docId, setup.draftVersion(), 0, newRequestId());
        assertThat(published.status()).isEqualTo(201);
        assertThat(published.body().get("publishedVersion").asInt()).isEqualTo(1);

        // 冻结票版本集合：语言阶段 3 张当前 APPROVE + 合规阶段 1 张 = 4
        Integer frozen = jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_vote_freeze WHERE document_id = ? AND published_version = 1",
                Integer.class, docId);
        assertThat(frozen).isEqualTo(4);

        // 快照固化两阶段采用票
        ApiResult release = getJson("/api/documents/" + docId + "/releases/1");
        JsonNode review = release.body().get("segments").get(0).get("translations").get(0).get("review");
        assertThat(review.get("policyVersion").asInt()).isEqualTo(1);
        assertThat(review.get("stages")).hasSize(2);
        assertThat(review.get("stages").get(0).get("status").asText()).isEqualTo("PASSED");
        assertThat(review.get("stages").get(0).get("votes")).hasSize(3);
        assertThat(review.get("stages").get(1).get("votes")).hasSize(1);
    }

    @Test
    @DisplayName("REJECT 使阶段 BLOCKED 且发布整体回滚；改票匹配 expectedVoteVersion 后转为 PASSED")
    void rejectBlocksAndChangeVote() throws Exception {
        Setup setup = setupSingleLanguageDoc();
        long docId = setup.docId();

        castVote(docId, "s1", "en", "bob", "LANGUAGE", "APPROVE", 1, 1, 0, 1, null, "vk-1",
                newRequestId());
        ApiResult reject = castVote(docId, "s1", "en", "erin", "LANGUAGE", "REJECT", 1, 1, 0, 1, null,
                "vk-2", newRequestId());
        assertThat(reject.status()).isEqualTo(201);
        assertThat(reject.body().get("stageStatus").asText()).isEqualTo("BLOCKED");

        ApiResult blockedPublish = publish(docId, setup.draftVersion(), 0, newRequestId());
        assertThat(blockedPublish.status()).isEqualTo(422);
        // 整体回滚：无快照、无冻结、发布版本不变
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_snapshot WHERE document_id = ?", Integer.class, docId)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_vote_freeze WHERE document_id = ?", Integer.class, docId)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT published_version FROM document WHERE document_id = ?", Integer.class, docId)).isZero();

        // 改票：expectedVoteVersion 不符 409；首次改票须带期望版本（null 409）
        ApiResult wrongExpected = castVote(docId, "s1", "en", "erin", "LANGUAGE", "APPROVE", 1, 1, 0, 1,
                99, "vk-3", newRequestId());
        assertThat(wrongExpected.status()).isEqualTo(409);
        ApiResult missingExpected = castVote(docId, "s1", "en", "erin", "LANGUAGE", "APPROVE", 1, 1, 0, 1,
                null, "vk-4", newRequestId());
        assertThat(missingExpected.status()).isEqualTo(409);

        // 匹配当前票版本 1 改票：生成票版本 2，仅新版本计入
        ApiResult changed = castVote(docId, "s1", "en", "erin", "LANGUAGE", "APPROVE", 1, 1, 0, 1,
                1, "vk-5", newRequestId());
        assertThat(changed.status()).isEqualTo(201);
        assertThat(changed.body().get("voteVersion").asInt()).isEqualTo(2);
        assertThat(changed.body().get("stageStatus").asText()).isEqualTo("PASSED");

        // 合规阶段通过后发布成功
        castVote(docId, "s1", "en", "dave", "COMPLIANCE", "APPROVE", 1, 1, 0, 1, null, "vk-6",
                newRequestId());
        assertThat(publish(docId, setup.draftVersion(), 0, newRequestId()).status()).isEqualTo(201);
    }

    @Test
    @DisplayName("任一版本变化：旧票保留审计但不计入当前法定人数，矩阵重置为 PENDING")
    void versionChangesInvalidateVotes() throws Exception {
        Setup setup = setupSingleLanguageDoc();
        long docId = setup.docId();
        castVote(docId, "s1", "en", "bob", "LANGUAGE", "APPROVE", 1, 1, 0, 1, null, "vk-1",
                newRequestId());
        castVote(docId, "s1", "en", "erin", "LANGUAGE", "APPROVE", 1, 1, 0, 1, null, "vk-2",
                newRequestId());
        castVote(docId, "s1", "en", "dave", "COMPLIANCE", "APPROVE", 1, 1, 0, 1, null, "vk-3",
                newRequestId());

        // 修订源文并基于新版本重新提交译文（译文版本 2、草稿版本加 2 → 5）
        putJson("/api/documents/" + docId + "/segments/s1/source",
                "{\"requestId\":\"" + newRequestId() + "\",\"sourceText\":\"修订原文\"}");
        ApiResult resubmit = submitTranslation(docId, "s1", "en", "alice", "hello v2", 2, newRequestId());
        assertThat(resubmit.status()).isEqualTo(200);
        assertThat(resubmit.body().get("translationVersion").asInt()).isEqualTo(2);

        ApiResult matrix = getMatrix(docId);
        JsonNode unit = matrix.body().get("units").get(0);
        assertThat(unit.get("sourceVersion").asInt()).isEqualTo(2);
        assertThat(unit.get("translationVersion").asInt()).isEqualTo(2);
        assertThat(unit.get("stages").get(0).get("status").asText()).isEqualTo("PENDING");
        assertThat(unit.get("stages").get(0).get("approveCount").asInt()).isZero();

        // 历史票仍可查且 current=false
        ApiResult history = getVotes(docId, "s1", "en");
        assertThat(history.status()).isEqualTo(200);
        assertThat(history.body().get("votes")).hasSize(3);
        for (JsonNode vote : history.body().get("votes")) {
            assertThat(vote.get("current").asBoolean()).isFalse();
        }

        // 发布 422 且无快照
        assertThat(publish(docId, 5, 0, newRequestId()).status()).isEqualTo(422);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_snapshot WHERE document_id = ?", Integer.class, docId)).isZero();

        // 用旧版本投票 422；用当前版本重新投票后通过
        ApiResult staleVote = castVote(docId, "s1", "en", "bob", "LANGUAGE", "APPROVE", 1, 1, 0, 1,
                null, "vk-stale", newRequestId());
        assertThat(staleVote.status()).isEqualTo(422);
        castVote(docId, "s1", "en", "bob", "LANGUAGE", "APPROVE", 2, 2, 0, 1, null, "vk-4",
                newRequestId());
        castVote(docId, "s1", "en", "erin", "LANGUAGE", "APPROVE", 2, 2, 0, 1, null, "vk-5",
                newRequestId());
        castVote(docId, "s1", "en", "dave", "COMPLIANCE", "APPROVE", 2, 2, 0, 1, null, "vk-6",
                newRequestId());
        assertThat(publish(docId, 5, 0, newRequestId()).status()).isEqualTo(201);

        // 冻结只采用新版本票（票版本均为新系列 1），旧票不混入
        Integer frozenV2 = jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_vote_freeze WHERE document_id = ? AND published_version = 1 "
                        + "AND source_version = 2 AND translation_version = 2", Integer.class, docId);
        assertThat(frozenV2).isEqualTo(3);
    }

    @Test
    @DisplayName("策略切换：旧策略票保留审计但不计入，须按新激活策略版本重新投票")
    void policySwitchInvalidatesVotes() throws Exception {
        Setup setup = setupSingleLanguageDoc();
        long docId = setup.docId();
        castVote(docId, "s1", "en", "bob", "LANGUAGE", "APPROVE", 1, 1, 0, 1, null, "vk-1",
                newRequestId());
        castVote(docId, "s1", "en", "erin", "LANGUAGE", "APPROVE", 1, 1, 0, 1, null, "vk-2",
                newRequestId());
        castVote(docId, "s1", "en", "dave", "COMPLIANCE", "APPROVE", 1, 1, 0, 1, null, "vk-3",
                newRequestId());

        // 切换策略为版本 2（草稿版本 3 → 4）
        ApiResult switched = putPolicy(docId, "en", 1,
                "[\"bob\",\"frank\"]", 1, "[\"grace\"]", 1, newRequestId());
        assertThat(switched.status()).isEqualTo(201);
        assertThat(switched.body().get("policyVersion").asInt()).isEqualTo(2);

        // 旧 policyVersion 投票 409
        ApiResult oldPolicyVote = castVote(docId, "s1", "en", "bob", "LANGUAGE", "APPROVE", 1, 1, 0, 1,
                null, "vk-old", newRequestId());
        assertThat(oldPolicyVote.status()).isEqualTo(409);

        // 矩阵按新策略计票：旧票全部不计入
        JsonNode unit = getMatrix(docId).body().get("units").get(0);
        assertThat(unit.get("policyVersion").asInt()).isEqualTo(2);
        assertThat(unit.get("stages").get(0).get("status").asText()).isEqualTo("PENDING");

        // 历史票保留，policyVersion=1 且 current=false
        JsonNode votes = getVotes(docId, "s1", "en").body().get("votes");
        assertThat(votes).hasSize(3);
        assertThat(votes.get(0).get("policyVersion").asInt()).isEqualTo(1);
        assertThat(votes.get(0).get("current").asBoolean()).isFalse();

        // 按新策略投票后发布成功（草稿版本 4）
        castVote(docId, "s1", "en", "frank", "LANGUAGE", "APPROVE", 1, 1, 0, 2, null, "vk-4",
                newRequestId());
        castVote(docId, "s1", "en", "grace", "COMPLIANCE", "APPROVE", 1, 1, 0, 2, null, "vk-5",
                newRequestId());
        assertThat(publish(docId, 4, 0, newRequestId()).status()).isEqualTo(201);
    }

    @Test
    @DisplayName("术语版本变化：旧票不计入法定人数")
    void termVersionChangeInvalidatesVotes() throws Exception {
        Setup setup = setupSingleLanguageDoc();
        long docId = setup.docId();
        castVote(docId, "s1", "en", "bob", "LANGUAGE", "APPROVE", 1, 1, 0, 1, null, "vk-1",
                newRequestId());
        castVote(docId, "s1", "en", "erin", "LANGUAGE", "APPROVE", 1, 1, 0, 1, null, "vk-2",
                newRequestId());
        castVote(docId, "s1", "en", "dave", "COMPLIANCE", "APPROVE", 1, 1, 0, 1, null, "vk-3",
                newRequestId());

        // 新增术语版本（0 → 1，草稿版本 3 → 4），票绑定术语版本 0 不再计入
        updateTerms(docId, 0, "[]", newRequestId());
        JsonNode unit = getMatrix(docId).body().get("units").get(0);
        assertThat(unit.get("termVersion").asInt()).isEqualTo(1);
        assertThat(unit.get("stages").get(0).get("status").asText()).isEqualTo("PENDING");

        // 用旧术语版本投票 422
        ApiResult stale = castVote(docId, "s1", "en", "bob", "LANGUAGE", "APPROVE", 1, 1, 0, 1,
                null, "vk-stale", newRequestId());
        assertThat(stale.status()).isEqualTo(422);
    }

    @Test
    @DisplayName("voteKey 唯一：换请求复用 409；失败不占键")
    void voteKeyUniquenessAndFailedVoteDoesNotOccupyKey() throws Exception {
        Setup setup = setupSingleLanguageDoc();
        long docId = setup.docId();

        ApiResult ok = castVote(docId, "s1", "en", "bob", "LANGUAGE", "APPROVE", 1, 1, 0, 1,
                null, "shared-key", newRequestId());
        assertThat(ok.status()).isEqualTo(201);

        // 换新 requestId 复用 voteKey：409
        ApiResult reuse = castVote(docId, "s1", "en", "erin", "LANGUAGE", "APPROVE", 1, 1, 0, 1,
                null, "shared-key", newRequestId());
        assertThat(reuse.status()).isEqualTo(409);

        // 失败投票（非候选）不占键：同一 voteKey 随后由合法审核人成功使用
        ApiResult failed = castVote(docId, "s1", "en", "zoe", "LANGUAGE", "APPROVE", 1, 1, 0, 1,
                null, "retry-key", newRequestId());
        assertThat(failed.status()).isEqualTo(422);
        ApiResult retried = castVote(docId, "s1", "en", "erin", "LANGUAGE", "APPROVE", 1, 1, 0, 1,
                null, "retry-key", newRequestId());
        assertThat(retried.status()).isEqualTo(201);
    }

    @Test
    @DisplayName("发布门禁：部分目标语言阶段未通过时整体 422，无快照无冻结")
    void publishRequiresAllLanguagesAndStages() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\",\"ja\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"原文\"}]");
        for (String language : new String[]{"en", "ja"}) {
            putPolicy(docId, language, 0, "[\"bob\"]", 1, "[\"dave\"]", 1, newRequestId());
            submitTranslation(docId, "s1", language, "alice", "t-" + language, 1, newRequestId());
        }
        // 仅 en 完整两阶段通过；ja 只投语言阶段
        castVote(docId, "s1", "en", "bob", "LANGUAGE", "APPROVE", 1, 1, 0, 1, null, "vk-en-l",
                newRequestId());
        castVote(docId, "s1", "en", "dave", "COMPLIANCE", "APPROVE", 1, 1, 0, 1, null, "vk-en-c",
                newRequestId());
        castVote(docId, "s1", "ja", "bob", "LANGUAGE", "APPROVE", 1, 1, 0, 1, null, "vk-ja-l",
                newRequestId());

        // 建文档 1 + 2 次策略 + 2 次译文 = 草稿版本 5
        ApiResult result = publish(docId, 5, 0, newRequestId());
        assertThat(result.status()).isEqualTo(422);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_snapshot WHERE document_id = ?", Integer.class, docId)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_vote_freeze WHERE document_id = ?", Integer.class, docId)).isZero();

        // 补齐 ja 合规阶段后发布成功，冻结 2 语言 × 2 票 = 4
        castVote(docId, "s1", "ja", "dave", "COMPLIANCE", "APPROVE", 1, 1, 0, 1, null, "vk-ja-c",
                newRequestId());
        assertThat(publish(docId, 5, 0, newRequestId()).status()).isEqualTo(201);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_vote_freeze WHERE document_id = ? AND published_version = 1",
                Integer.class, docId)).isEqualTo(4);
    }

    @Test
    @DisplayName("发布后投票/改票不影响已发布快照；二次发布冻结独立票集合")
    void freezeImmutableAfterPublish() throws Exception {
        Setup setup = setupSingleLanguageDoc();
        long docId = setup.docId();
        castVote(docId, "s1", "en", "bob", "LANGUAGE", "APPROVE", 1, 1, 0, 1, null, "vk-1",
                newRequestId());
        castVote(docId, "s1", "en", "erin", "LANGUAGE", "APPROVE", 1, 1, 0, 1, null, "vk-2",
                newRequestId());
        castVote(docId, "s1", "en", "dave", "COMPLIANCE", "APPROVE", 1, 1, 0, 1, null, "vk-3",
                newRequestId());
        assertThat(publish(docId, setup.draftVersion(), 0, newRequestId()).status()).isEqualTo(201);

        // 发布后 erin 改票为 REJECT（新票版本），已发布快照与冻结不变
        ApiResult changed = castVote(docId, "s1", "en", "erin", "LANGUAGE", "REJECT", 1, 1, 0, 1,
                1, "vk-7", newRequestId());
        assertThat(changed.status()).isEqualTo(201);
        assertThat(changed.body().get("voteVersion").asInt()).isEqualTo(2);

        JsonNode release = getJson("/api/documents/" + docId + "/releases/1").body();
        assertThat(release.get("segments").get(0).get("translations").get(0)
                .get("review").get("stages").get(0).get("votes")).hasSize(2);
        Integer frozen = jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_vote_freeze WHERE document_id = ? AND published_version = 1",
                Integer.class, docId);
        assertThat(frozen).isEqualTo(3);
        Integer frozenErinV1 = jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_vote_freeze WHERE document_id = ? AND published_version = 1 "
                        + "AND reviewer = 'erin' AND vote_version = 1 AND decision = 'APPROVE'",
                Integer.class, docId);
        assertThat(frozenErinV1).isEqualTo(1);

        // erin 改回 APPROVE（须基于当前票版本 2，生成票版本 3），第二次发布：投票不改草稿版本，仍为 3
        ApiResult backToApprove = castVote(docId, "s1", "en", "erin", "LANGUAGE", "APPROVE", 1, 1, 0, 1,
                2, "vk-8", newRequestId());
        assertThat(backToApprove.status()).isEqualTo(201);
        assertThat(backToApprove.body().get("voteVersion").asInt()).isEqualTo(3);
        ApiResult second = publish(docId, setup.draftVersion(), 1, newRequestId());
        assertThat(second.status()).isEqualTo(201);
        assertThat(second.body().get("publishedVersion").asInt()).isEqualTo(2);

        // 两次发布各自独立冻结：均 3 票；第二版采用 erin 票版本 3，第一版仍为票版本 1
        Integer frozenV1 = jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_vote_freeze WHERE document_id = ? AND published_version = 1",
                Integer.class, docId);
        Integer frozenV2Count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_vote_freeze WHERE document_id = ? AND published_version = 2",
                Integer.class, docId);
        Integer frozenV2ErinV3 = jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_vote_freeze WHERE document_id = ? AND published_version = 2 "
                        + "AND reviewer = 'erin' AND vote_version = 3", Integer.class, docId);
        assertThat(frozenV1).isEqualTo(3);
        assertThat(frozenV2Count).isEqualTo(3);
        assertThat(frozenV2ErinV3).isEqualTo(1);
    }

    @Test
    @DisplayName("矩阵与历史票只读：文档/段落不存在返回 404")
    void readOnlyQueriesNotFound() throws Exception {
        Setup setup = setupSingleLanguageDoc();
        long docId = setup.docId();
        assertThat(getJson("/api/documents/999999/review-matrix").status()).isEqualTo(404);
        assertThat(getVotes(docId, "nope", "en").status()).isEqualTo(404);
        ApiResult votes = getVotes(docId, "s1", "en");
        assertThat(votes.status()).isEqualTo(200);
        assertThat(votes.body().get("votes").isArray()).isTrue();
    }
}
