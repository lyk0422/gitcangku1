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
 * 双阶段评审并发边界测试：真实并发调用，通过门闩协调同时发起，设置超时并断言最终数据状态。
 */
class ReviewConcurrencyTest extends AbstractIntegrationTest {

    /** 通用前置：文档 + 策略 v1（LANGUAGE 法定 2：r1/r2/r3；COMPLIANCE 法定 1：r2/r4）+ 译文 + 旧式批准。 */
    private long setupDocumentWithPolicy() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"原文\"}]");
        assertThat(configurePolicy(docId, "en", 0, 2, "[\"r1\",\"r2\",\"r3\"]",
                1, "[\"r2\",\"r4\"]", newRequestId()).status()).isEqualTo(201);
        submitTranslation(docId, "s1", "en", "alice", "hello", 1, newRequestId());
        approve(docId, "s1", "en", "bob", 1, newRequestId());
        return docId;
    }

    private ApiResult vote(long docId, String reviewer, String stage, String decision, String voteKey,
                           Integer expectedVoteVersion) throws Exception {
        return castVote(docId, "s1", "en", reviewer, voteKey, stage, decision, 1, 1, 0, 1,
                expectedVoteVersion, newRequestId());
    }

    @Test
    @DisplayName("并发投票：不同审核人同时投票全部成功，法定人数统计无丢失")
    void concurrentVotesDistinctReviewers() throws Exception {
        long docId = setupDocumentWithPolicy();
        String[][] votes = {{"r1", "LANGUAGE"}, {"r2", "LANGUAGE"}, {"r3", "LANGUAGE"}, {"r4", "COMPLIANCE"}};
        ExecutorService pool = Executors.newFixedThreadPool(votes.length);
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<ApiResult>> futures = new ArrayList<>();
        for (String[] vote : votes) {
            futures.add(pool.submit(() -> {
                gate.await();
                return vote(docId, vote[0], vote[1], "APPROVE", "vk-" + vote[0], null);
            }));
        }
        gate.countDown();
        for (Future<ApiResult> future : futures) {
            assertThat(future.get(30, TimeUnit.SECONDS).status()).isEqualTo(201);
        }
        pool.shutdown();

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM review_vote WHERE document_id = ?", Integer.class, docId))
                .isEqualTo(votes.length);
        ApiResult matrix = getJson("/api/documents/" + docId + "/review-matrix");
        var stages = matrix.body().get("entries").get(0).get("stages");
        assertThat(stages.get(0).get("stage").asText()).isEqualTo("LANGUAGE");
        assertThat(stages.get(0).get("status").asText()).isEqualTo("PASSED");
        assertThat(stages.get(0).get("approveCount").asInt()).isEqualTo(3);
        assertThat(stages.get(1).get("stage").asText()).isEqualTo("COMPLIANCE");
        assertThat(stages.get(1).get("status").asText()).isEqualTo("PASSED");
    }

    @Test
    @DisplayName("并发复用同一 voteKey：仅一个请求成功，其余 409，最终只有一票")
    void concurrentSameVoteKey() throws Exception {
        long docId = setupDocumentWithPolicy();
        String voteKey = "vk-shared";
        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<ApiResult>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                gate.await();
                return vote(docId, "r1", "LANGUAGE", "APPROVE", voteKey, null);
            }));
        }
        gate.countDown();
        int created = 0;
        int conflict = 0;
        for (Future<ApiResult> future : futures) {
            int status = future.get(30, TimeUnit.SECONDS).status();
            if (status == 201) {
                created++;
            } else if (status == 409) {
                conflict++;
            }
        }
        pool.shutdown();

        assertThat(created).isEqualTo(1);
        assertThat(conflict).isEqualTo(threads - 1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM review_vote WHERE document_id = ?", Integer.class, docId)).isEqualTo(1);
    }

    @Test
    @DisplayName("并发改票：同一审核人两个改票请求仅一个成功，票版本只前进一次")
    void concurrentChangeVote() throws Exception {
        long docId = setupDocumentWithPolicy();
        assertThat(vote(docId, "r1", "LANGUAGE", "REJECT", "vk-first", null).status()).isEqualTo(201);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<ApiResult>> futures = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            futures.add(pool.submit(() -> {
                gate.await();
                return vote(docId, "r1", "LANGUAGE", "APPROVE", "vk-change-" + newRequestId(), 1);
            }));
        }
        gate.countDown();
        int created = 0;
        int conflict = 0;
        for (Future<ApiResult> future : futures) {
            int status = future.get(30, TimeUnit.SECONDS).status();
            if (status == 201) {
                created++;
            } else if (status == 409) {
                conflict++;
            }
        }
        pool.shutdown();

        assertThat(created).isEqualTo(1);
        assertThat(conflict).isEqualTo(1);
        // 共两版票：v1 REJECT + v2 APPROVE，仅一个 v2
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM review_vote WHERE document_id = ?", Integer.class, docId)).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM review_vote WHERE document_id = ? AND vote_version = 2",
                Integer.class, docId)).isEqualTo(1);
    }

    @Test
    @DisplayName("发布与投票并发：对应一个一致状态，成功发布冻结完整票集合，失败无部分冻结")
    void concurrentPublishAndVote() throws Exception {
        long docId = setupDocumentWithPolicy();
        assertThat(vote(docId, "r1", "LANGUAGE", "APPROVE", "vk-a", null).status()).isEqualTo(201);
        assertThat(vote(docId, "r2", "LANGUAGE", "APPROVE", "vk-b", null).status()).isEqualTo(201);
        // 当前草稿版本 3、发布版本 0；COMPLIANCE 尚缺一票

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        Future<ApiResult> publishFuture = pool.submit(() -> {
            gate.await();
            return publish(docId, 3, 0, newRequestId());
        });
        Future<ApiResult> voteFuture = pool.submit(() -> {
            gate.await();
            return vote(docId, "r4", "COMPLIANCE", "APPROVE", "vk-c", null);
        });
        gate.countDown();
        ApiResult publishResult = publishFuture.get(30, TimeUnit.SECONDS);
        ApiResult voteResult = voteFuture.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(voteResult.status()).isEqualTo(201);
        assertThat(publishResult.status()).isIn(201, 422);

        Integer publishedVersion = jdbc.queryForObject(
                "SELECT published_version FROM document WHERE document_id = ?", Integer.class, docId);
        Integer snapshots = jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_snapshot WHERE document_id = ?", Integer.class, docId);
        Integer frozen = jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_vote_freeze WHERE document_id = ?", Integer.class, docId);
        assertThat(snapshots).isEqualTo(publishedVersion);
        if (publishResult.status() == 201) {
            // 票先于发布：发布成功且冻结完整的 3 票
            assertThat(publishedVersion).isEqualTo(1);
            assertThat(frozen).isEqualTo(3);
        } else {
            // 发布先于票：COMPLIANCE 未达法定人数，422 且无任何冻结
            assertThat(publishedVersion).isZero();
            assertThat(frozen).isZero();
        }
    }

    @Test
    @DisplayName("发布与策略切换并发：只发布切换前完整状态或因草稿版本变化 409，不产生混合快照")
    void concurrentPublishAndPolicySwitch() throws Exception {
        long docId = setupDocumentWithPolicy();
        assertThat(vote(docId, "r1", "LANGUAGE", "APPROVE", "vk-a", null).status()).isEqualTo(201);
        assertThat(vote(docId, "r2", "LANGUAGE", "APPROVE", "vk-b", null).status()).isEqualTo(201);
        assertThat(vote(docId, "r4", "COMPLIANCE", "APPROVE", "vk-c", null).status()).isEqualTo(201);
        // 当前草稿版本 3、发布版本 0、策略版本 1

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        Future<ApiResult> publishFuture = pool.submit(() -> {
            gate.await();
            return publish(docId, 3, 0, newRequestId());
        });
        Future<ApiResult> switchFuture = pool.submit(() -> {
            gate.await();
            return configurePolicy(docId, "en", 1, 1, "[\"r5\"]", 1, "[\"r6\"]", newRequestId());
        });
        gate.countDown();
        ApiResult publishResult = publishFuture.get(30, TimeUnit.SECONDS);
        ApiResult switchResult = switchFuture.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        // 策略切换必然成功（草稿版本变 4）；发布要么先于切换成功（快照固化策略版本 1），要么 409
        assertThat(switchResult.status()).isEqualTo(201);
        assertThat(publishResult.status()).isIn(201, 409);

        Integer draftVersion = jdbc.queryForObject(
                "SELECT draft_version FROM document WHERE document_id = ?", Integer.class, docId);
        Integer publishedVersion = jdbc.queryForObject(
                "SELECT published_version FROM document WHERE document_id = ?", Integer.class, docId);
        Integer snapshots = jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_snapshot WHERE document_id = ?", Integer.class, docId);
        assertThat(draftVersion).isEqualTo(4);
        assertThat(snapshots).isEqualTo(publishedVersion);
        if (publishResult.status() == 201) {
            assertThat(publishedVersion).isEqualTo(1);
            ApiResult release = getJson("/api/documents/" + docId + "/releases/1");
            assertThat(release.body().get("segments").get(0).get("translations").get(0)
                    .get("policyVersion").asInt()).isEqualTo(1);
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM release_vote_freeze WHERE document_id = ? AND published_version = 1",
                    Integer.class, docId)).isEqualTo(3);
        } else {
            assertThat(publishedVersion).isZero();
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM release_vote_freeze WHERE document_id = ?",
                    Integer.class, docId)).isZero();
        }
    }
}
