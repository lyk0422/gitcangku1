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
 * 批量审核并发边界测试：与译文重新提交并发按事务提交顺序裁决；同 batchKey 并发仅执行一次。
 */
class BatchApprovalConcurrencyTest extends AbstractIntegrationTest {

    @Test
    @DisplayName("批量审核与译文重新提交并发：重新提交先提交则整批 422 且不批准任何一条，否则批量批准成功")
    void concurrentBatchApproveAndResubmit() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"原文\"}]");
        submitTranslation(docId, "s1", "en", "alice", "hello", 1, newRequestId());
        // 当前译文版本 1、草稿版本 2

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        Future<ApiResult> batchFuture = pool.submit(() -> {
            gate.await();
            return approveBatch(docId, "bob", "batch-race", 2,
                    "[{\"segmentId\":\"s1\",\"language\":\"en\",\"expectedTranslationVersion\":1}]");
        });
        Future<ApiResult> resubmitFuture = pool.submit(() -> {
            gate.await();
            return submitTranslation(docId, "s1", "en", "alice", "hello v2", 1, newRequestId());
        });
        gate.countDown();
        ApiResult batch = batchFuture.get(30, TimeUnit.SECONDS);
        ApiResult resubmit = resubmitFuture.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        // 重新提交必然成功（源文版本未变），译文版本升为 2
        assertThat(resubmit.status()).isEqualTo(200);
        Integer translationVersion = jdbc.queryForObject(
                "SELECT translation_version FROM translation WHERE document_id = ? AND segment_id = 's1'",
                Integer.class, docId);
        assertThat(translationVersion).isEqualTo(2);

        if (batch.status() == 200) {
            // 批量先提交：批准译文版本 1，批量记录与明细齐全
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM approval WHERE document_id = ?",
                    Integer.class, docId)).isEqualTo(1);
            assertThat(jdbc.queryForObject(
                    "SELECT translation_version FROM approval WHERE document_id = ?", Integer.class, docId))
                    .isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM approval_batch", Integer.class))
                    .isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM approval_batch_item", Integer.class))
                    .isEqualTo(1);
        } else {
            // 重新提交先提交：批内译文版本已变，整批 422 且不批准任何一条、不留批量记录
            assertThat(batch.status()).isEqualTo(422);
            assertThat(batch.body().get("failures")).hasSize(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM approval WHERE document_id = ?",
                    Integer.class, docId)).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM approval_batch", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM approval_batch_item", Integer.class))
                    .isZero();
        }
    }

    @Test
    @DisplayName("并发同 batchKey 同参：仅执行一次，全部重放同一成功快照，不重复批准")
    void concurrentSameBatchKey() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"你好\"},{\"segmentId\":\"s2\",\"sourceText\":\"世界\"}]");
        submitTranslation(docId, "s1", "en", "alice", "hello", 1, newRequestId());
        submitTranslation(docId, "s2", "en", "alice", "world", 1, newRequestId());

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<ApiResult>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                gate.await();
                return approveBatch(docId, "bob", "batch-concurrent", 3,
                        "[{\"segmentId\":\"s1\",\"language\":\"en\",\"expectedTranslationVersion\":1},"
                                + "{\"segmentId\":\"s2\",\"language\":\"en\","
                                + "\"expectedTranslationVersion\":1}]");
            }));
        }
        gate.countDown();
        List<ApiResult> results = new ArrayList<>();
        for (Future<ApiResult> future : futures) {
            results.add(future.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();

        String firstBody = results.get(0).body().toString();
        for (ApiResult result : results) {
            assertThat(result.status()).isEqualTo(200);
            assertThat(result.body().toString()).isEqualTo(firstBody);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM approval WHERE document_id = ?",
                Integer.class, docId)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM approval_batch", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM approval_batch_item", Integer.class))
                .isEqualTo(2);
    }
}
