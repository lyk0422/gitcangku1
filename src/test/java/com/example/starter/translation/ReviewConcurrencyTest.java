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
 * 双阶段评审的幂等与并发边界测试：真实并发 + 门闩协调 + 超时断言最终数据状态（真实 H2）。
 */
class ReviewConcurrencyTest extends AbstractIntegrationTest {

    private long preparedDoc() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"原文\"}]");
        assertThat(putPolicy(docId, "en", 0, "[\"bob\",\"erin\"]", 2, "[\"dave\"]", 1,
                newRequestId()).status()).isEqualTo(201);
        assertThat(submitTranslation(docId, "s1", "en", "alice", "hello", 1, newRequestId()).status())
                .isEqualTo(200);
        return docId;
    }

    @Test
    @DisplayName("投票幂等：同 requestId 同参重放同一票且不重复落库；同键异参 409；失败不占 requestId")
    void voteIdempotency() throws Exception {
        long docId = preparedDoc();
        String requestId = newRequestId();
        String body = "{\"requestId\":\"" + requestId + "\",\"voteKey\":\"vk-idem\","
                + "\"stage\":\"LANGUAGE\",\"decision\":\"APPROVE\",\"sourceVersion\":1,"
                + "\"translationVersion\":1,\"termVersion\":0,\"policyVersion\":1}";

        ApiResult first = postJson(
                "/api/documents/" + docId + "/segments/s1/translations/en/votes", body, "bob");
        assertThat(first.status()).isEqualTo(201);
        long voteId = first.body().get("voteId").asLong();

        ApiResult replay = postJson(
                "/api/documents/" + docId + "/segments/s1/translations/en/votes", body, "bob");
        assertThat(replay.status()).isEqualTo(201);
        assertThat(replay.body().get("voteId").asLong()).isEqualTo(voteId);
        assertThat(replay.body().get("voteVersion").asInt()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM review_vote WHERE document_id = ?",
                Integer.class, docId)).isEqualTo(1);
        // 建文档/策略/译文 3 条 + 本次投票 1 条 = 4；重放不新增
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM request_log", Integer.class)).isEqualTo(4);

        // 同键异参（改 decision）→ 409
        String different = body.replace("APPROVE", "REJECT");
        ApiResult conflict = postJson(
                "/api/documents/" + docId + "/segments/s1/translations/en/votes", different, "bob");
        assertThat(conflict.status()).isEqualTo(409);

        // 失败不占 requestId：非候选审核人先 422，同键改为合法审核人后成功
        String failKey = newRequestId();
        String failBody = "{\"requestId\":\"" + failKey + "\",\"voteKey\":\"vk-fail\","
                + "\"stage\":\"LANGUAGE\",\"decision\":\"APPROVE\",\"sourceVersion\":1,"
                + "\"translationVersion\":1,\"termVersion\":0,\"policyVersion\":1}";
        assertThat(postJson("/api/documents/" + docId + "/segments/s1/translations/en/votes",
                failBody, "zoe").status()).isEqualTo(422);
        assertThat(postJson("/api/documents/" + docId + "/segments/s1/translations/en/votes",
                failBody, "erin").status()).isEqualTo(201);
    }

    @Test
    @DisplayName("并发不同审核人投票：全部成功，法定人数批准数无丢失")
    void concurrentDistinctReviewers() throws Exception {
        long docId = preparedDoc();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<ApiResult>> futures = new ArrayList<>();
        futures.add(pool.submit(() -> {
            gate.await();
            return castVote(docId, "s1", "en", "bob", "LANGUAGE", "APPROVE", 1, 1, 0, 1,
                    null, "vk-bob", newRequestId());
        }));
        futures.add(pool.submit(() -> {
            gate.await();
            return castVote(docId, "s1", "en", "erin", "LANGUAGE", "APPROVE", 1, 1, 0, 1,
                    null, "vk-erin", newRequestId());
        }));
        gate.countDown();
        for (Future<ApiResult> future : futures) {
            assertThat(future.get(30, TimeUnit.SECONDS).status()).isEqualTo(201);
        }
        pool.shutdown();

        JsonNodeLike unit = matrixUnit(docId);
        assertThat(unit.languageApproveCount()).isEqualTo(2);
        assertThat(unit.languageStatus()).isEqualTo("PASSED");
    }

    @Test
    @DisplayName("并发同审核人改票：仅一个 expectedVoteVersion 成功，其余 409，票版本无丢失更新")
    void concurrentChangeVoteOptimistic() throws Exception {
        long docId = preparedDoc();
        assertThat(castVote(docId, "s1", "en", "bob", "LANGUAGE", "APPROVE", 1, 1, 0, 1,
                null, "vk-v1", newRequestId()).status()).isEqualTo(201);

        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<ApiResult>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final int n = i;
            futures.add(pool.submit(() -> {
                gate.await();
                return castVote(docId, "s1", "en", "bob", "LANGUAGE",
                        n % 2 == 0 ? "REJECT" : "APPROVE", 1, 1, 0, 1, 1,
                        "vk-change-" + n, newRequestId());
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
        Integer maxVersion = jdbc.queryForObject(
                "SELECT MAX(vote_version) FROM review_vote WHERE document_id = ? AND reviewer = 'bob'",
                Integer.class, docId);
        assertThat(maxVersion).isEqualTo(2);
        Integer bobVotes = jdbc.queryForObject(
                "SELECT COUNT(*) FROM review_vote WHERE document_id = ? AND reviewer = 'bob'",
                Integer.class, docId);
        assertThat(bobVotes).isEqualTo(2);
    }

    @Test
    @DisplayName("并发同一 voteKey：仅一个投票成功，其余 409，唯一约束兜底")
    void concurrentSameVoteKey() throws Exception {
        long docId = preparedDoc();
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<ApiResult>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final String reviewer = i == 0 ? "bob" : (i == 1 ? "erin" : "bob");
            futures.add(pool.submit(() -> {
                gate.await();
                return castVote(docId, "s1", "en", reviewer, "LANGUAGE", "APPROVE", 1, 1, 0, 1,
                        null, "vk-race", newRequestId());
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
                "SELECT COUNT(*) FROM review_vote WHERE vote_key = 'vk-race'", Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("并发策略切换：同一期望版本仅一个成功，激活版本无丢失更新")
    void concurrentPolicySwitch() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]", "[]");
        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<ApiResult>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                gate.await();
                return putPolicy(docId, "en", 0, "[\"bob\"]", 1, "[\"dave\"]", 1, newRequestId());
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
        Integer active = jdbc.queryForObject(
                "SELECT policy_version FROM review_policy_active WHERE document_id = ? AND language = 'en'",
                Integer.class, docId);
        assertThat(active).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM review_policy WHERE document_id = ?", Integer.class, docId)).isEqualTo(1);
    }

    @Test
    @DisplayName("并发投票与发布：发布要么计入新票成功要么因未达标失败，不产生混合快照")
    void concurrentVoteAndPublish() throws Exception {
        long docId = preparedDoc();
        // 合规先通过；语言阶段仅 bob 一票（法定 2，PENDING），决定性一票 erin 与发布并发
        assertThat(castVote(docId, "s1", "en", "bob", "LANGUAGE", "APPROVE", 1, 1, 0, 1,
                null, "vk-bob", newRequestId()).status()).isEqualTo(201);
        assertThat(castVote(docId, "s1", "en", "dave", "COMPLIANCE", "APPROVE", 1, 1, 0, 1,
                null, "vk-dave", newRequestId()).status()).isEqualTo(201);
        // 草稿版本：建文档 1 + 策略 1 + 译文 1 = 3；投票不改变草稿版本
        int draftAtPublish = 3;

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        Future<ApiResult> voteFuture = pool.submit(() -> {
            gate.await();
            return castVote(docId, "s1", "en", "erin", "LANGUAGE", "APPROVE", 1, 1, 0, 1,
                    null, "vk-erin", newRequestId());
        });
        Future<ApiResult> publishFuture = pool.submit(() -> {
            gate.await();
            return publish(docId, draftAtPublish, 0, newRequestId());
        });
        gate.countDown();
        ApiResult voteResult = voteFuture.get(30, TimeUnit.SECONDS);
        ApiResult publishResult = publishFuture.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(voteResult.status()).isEqualTo(201);
        assertThat(publishResult.status()).isIn(201, 422);

        Integer snapshots = jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_snapshot WHERE document_id = ?", Integer.class, docId);
        Integer freezes = jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_vote_freeze WHERE document_id = ?", Integer.class, docId);
        // 快照与冻结严格成对出现，无部分冻结
        if (publishResult.status() == 201) {
            // erin 的票先于发布计入：冻结 bob/erin/dave 三票
            assertThat(snapshots).isEqualTo(1);
            assertThat(freezes).isEqualTo(3);
        } else {
            // 发布先于决定性一票：PENDING，整体回滚
            assertThat(snapshots).isZero();
            assertThat(freezes).isZero();
        }
    }

    private JsonNodeLike matrixUnit(long docId) throws Exception {
        ApiResult matrix = getMatrix(docId);
        var stage = matrix.body().get("units").get(0).get("stages");
        return new JsonNodeLike(stage.get(0).get("approveCount").asInt(), stage.get(0).get("status").asText());
    }

    private record JsonNodeLike(int languageApproveCount, String languageStatus) {
    }
}
