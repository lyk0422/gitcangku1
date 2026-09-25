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
 * 并发与幂等边界测试：真实并发调用，通过门闩协调同时发起，设置超时并断言最终数据状态。
 */
class ConcurrencyTest extends AbstractIntegrationTest {

    @Test
    @DisplayName("并发提交译文：同一文档多个段落并发提交全部成功，草稿版本无丢失更新")
    void concurrentTranslationSubmits() throws Exception {
        StringBuilder segments = new StringBuilder("[");
        int segmentCount = 6;
        for (int i = 1; i <= segmentCount; i++) {
            if (i > 1) {
                segments.append(',');
            }
            segments.append("{\"segmentId\":\"s").append(i).append("\",\"sourceText\":\"原文").append(i)
                    .append("\"}");
        }
        segments.append(']');
        long docId = createDocument(newRequestId(), "[\"en\"]", segments.toString());

        ExecutorService pool = Executors.newFixedThreadPool(segmentCount);
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<ApiResult>> futures = new ArrayList<>();
        for (int i = 1; i <= segmentCount; i++) {
            final String segmentId = "s" + i;
            futures.add(pool.submit(() -> {
                gate.await();
                return submitTranslation(docId, segmentId, "en", "alice", "译文-" + segmentId, 1,
                        newRequestId());
            }));
        }
        gate.countDown();
        for (Future<ApiResult> future : futures) {
            assertThat(future.get(30, TimeUnit.SECONDS).status()).isEqualTo(200);
        }
        pool.shutdown();

        Integer draftVersion = jdbc.queryForObject(
                "SELECT draft_version FROM document WHERE document_id = ?", Integer.class, docId);
        assertThat(draftVersion).isEqualTo(1 + segmentCount);
        Integer translations = jdbc.queryForObject(
                "SELECT COUNT(*) FROM translation WHERE document_id = ?", Integer.class, docId);
        assertThat(translations).isEqualTo(segmentCount);
    }

    @Test
    @DisplayName("并发同 requestId 同参：仅执行一次，全部重放同一成功结果")
    void concurrentSameRequestId() throws Exception {
        String requestId = newRequestId();
        String body = "{\"requestId\":\"" + requestId + "\",\"targetLanguages\":[\"en\"],"
                + "\"segments\":[{\"segmentId\":\"s1\",\"sourceText\":\"原文\"}]}";
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<ApiResult>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                gate.await();
                return postJson("/api/documents", body);
            }));
        }
        gate.countDown();
        List<ApiResult> results = new ArrayList<>();
        for (Future<ApiResult> future : futures) {
            results.add(future.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();

        long documentId = results.get(0).body().get("documentId").asLong();
        for (ApiResult result : results) {
            assertThat(result.status()).isEqualTo(201);
            assertThat(result.body().get("documentId").asLong()).isEqualTo(documentId);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM document", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM request_log", Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("发布与源文修订并发：对应一个一致状态，失败不产生部分快照")
    void concurrentPublishAndRevise() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"原文\"}]");
        submitTranslation(docId, "s1", "en", "alice", "hello", 1, newRequestId());
        approve(docId, "s1", "en", "bob", 1, newRequestId());
        // 当前草稿版本 2、发布版本 0

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        Future<ApiResult> publishFuture = pool.submit(() -> {
            gate.await();
            return publish(docId, 2, 0, newRequestId());
        });
        Future<ApiResult> reviseFuture = pool.submit(() -> {
            gate.await();
            return putJson("/api/documents/" + docId + "/segments/s1/source",
                    "{\"requestId\":\"" + newRequestId() + "\",\"sourceText\":\"修订后原文\"}");
        });
        gate.countDown();
        ApiResult publishResult = publishFuture.get(30, TimeUnit.SECONDS);
        ApiResult reviseResult = reviseFuture.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(reviseResult.status()).isEqualTo(200);
        assertThat(publishResult.status()).isIn(201, 409);

        Integer draftVersion = jdbc.queryForObject(
                "SELECT draft_version FROM document WHERE document_id = ?", Integer.class, docId);
        Integer publishedVersion = jdbc.queryForObject(
                "SELECT published_version FROM document WHERE document_id = ?", Integer.class, docId);
        Integer snapshots = jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_snapshot WHERE document_id = ?", Integer.class, docId);

        // 修订必然成功并使草稿版本加一；发布版本与快照数量必须一致（无部分快照）
        assertThat(draftVersion).isEqualTo(3);
        assertThat(snapshots).isEqualTo(publishedVersion);
        if (publishResult.status() == 201) {
            // 发布先于修订：发布版本 1，快照中为修订前源文
            assertThat(publishedVersion).isEqualTo(1);
            ApiResult release = getJson("/api/documents/" + docId + "/releases/1");
            assertThat(release.status()).isEqualTo(200);
            assertThat(release.body().get("segments").get(0).get("sourceText").asText()).isEqualTo("原文");
        } else {
            // 修订先于发布：期望草稿版本不符，发布 409 且无快照
            assertThat(publishedVersion).isZero();
        }
    }

    @Test
    @DisplayName("并发同 batchKey 同参批量审核：仅批准一次，全部重放同一成功快照")
    void concurrentSameBatchKey() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"原文一\"},{\"segmentId\":\"s2\",\"sourceText\":\"原文二\"}]");
        submitTranslation(docId, "s1", "en", "alice", "one", 1, newRequestId());
        submitTranslation(docId, "s2", "en", "alice", "two", 1, newRequestId());

        String batchKey = newRequestId();
        String bodyA = "{\"batchKey\":\"" + batchKey + "\",\"expectedDraftVersion\":3,\"items\":["
                + "{\"segmentId\":\"s1\",\"language\":\"en\",\"expectedTranslationVersion\":1},"
                + "{\"segmentId\":\"s2\",\"language\":\"en\",\"expectedTranslationVersion\":1}]}";
        // 换序同参
        String bodyB = "{\"batchKey\":\"" + batchKey + "\",\"expectedDraftVersion\":3,\"items\":["
                + "{\"segmentId\":\"s2\",\"language\":\"EN\",\"expectedTranslationVersion\":1},"
                + "{\"segmentId\":\"s1\",\"language\":\"en\",\"expectedTranslationVersion\":1}]}";

        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<ApiResult>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final String body = i % 2 == 0 ? bodyA : bodyB;
            futures.add(pool.submit(() -> {
                gate.await();
                return postJson("/api/documents/" + docId + "/batch-approvals", body, "bob");
            }));
        }
        gate.countDown();
        List<ApiResult> results = new ArrayList<>();
        for (Future<ApiResult> future : futures) {
            results.add(future.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();

        for (ApiResult result : results) {
            assertThat(result.status()).isEqualTo(200);
            assertThat(result.body().get("batchKey").asText()).isEqualTo(batchKey);
            assertThat(result.body().get("items")).hasSize(2);
        }
        // 只批准一次：approval 两条、批次记录一条、明细两条、幂等记录一条
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM approval WHERE document_id = ?", Integer.class, docId)).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_approval WHERE batch_key = ?", Integer.class, batchKey))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_approval_item WHERE batch_key = ?", Integer.class, batchKey))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = ?", Integer.class, batchKey))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("批量审核与译文重新提交并发：按事务提交顺序裁决，无部分批准，最终状态一致")
    void concurrentBatchAndResubmit() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"原文\"}]");
        submitTranslation(docId, "s1", "en", "alice", "v1", 1, newRequestId());

        String batchKey = newRequestId();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        Future<ApiResult> batchFuture = pool.submit(() -> {
            gate.await();
            return postJson("/api/documents/" + docId + "/batch-approvals",
                    "{\"batchKey\":\"" + batchKey + "\",\"expectedDraftVersion\":2,\"items\":["
                            + "{\"segmentId\":\"s1\",\"language\":\"en\","
                            + "\"expectedTranslationVersion\":1}]}", "bob");
        });
        Future<ApiResult> resubmitFuture = pool.submit(() -> {
            gate.await();
            return submitTranslation(docId, "s1", "en", "alice", "v2", 1, newRequestId());
        });
        gate.countDown();
        ApiResult batchResult = batchFuture.get(30, TimeUnit.SECONDS);
        ApiResult resubmitResult = resubmitFuture.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(resubmitResult.status()).isEqualTo(200);
        int currentVersion = jdbc.queryForObject(
                "SELECT translation_version FROM translation WHERE document_id = ? AND segment_id = 's1'",
                Integer.class, docId);
        assertThat(currentVersion).isEqualTo(2);

        Integer batchCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_approval WHERE batch_key = ?", Integer.class, batchKey);
        if (batchResult.status() == 200) {
            // 批量先提交：版本 1 被批准；随后重新提交使译文版本变为 2，旧批准失效
            assertThat(batchCount).isEqualTo(1);
            Integer approvedVersion = jdbc.queryForObject(
                    "SELECT translation_version FROM approval WHERE document_id = ? AND segment_id = 's1'",
                    Integer.class, docId);
            assertThat(approvedVersion).isEqualTo(1);
        } else {
            // 重新提交先提交：译文版本已变为 2，期望版本 1 的整批 422，无任何批次记录
            assertThat(batchResult.status()).isEqualTo(422);
            assertThat(batchCount).isZero();
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM request_log WHERE request_id = ?", Integer.class, batchKey))
                    .isZero();
        }
        // 无论顺序如何，批准数只能是 0 或 1，不存在部分批准之外的脏状态
        Integer approvalCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM approval WHERE document_id = ? AND segment_id = 's1'",
                Integer.class, docId);
        assertThat(approvalCount).isIn(0, 1);
    }
}
