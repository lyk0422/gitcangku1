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
 * 术语冻结并发与幂等边界测试：真实并发调用，门闩协调同时发起，断言最终数据状态。
 */
class FreezeConcurrencyTest extends AbstractIntegrationTest {

    private static final String ENTRIES =
            "[{\"term\":\"机器学习\",\"language\":\"en\",\"allowedTranslations\":[\"machine learning\"]}]";

    @Test
    @DisplayName("并发同内容冻结：同一 freezeKey 仅创建一次，全部重放同一冻结")
    void concurrentFreezeSameContent() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"机器学习\"}]");
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<ApiResult>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                gate.await();
                return createFreeze(docId, "carol", ENTRIES, newRequestId());
            }));
        }
        gate.countDown();
        List<ApiResult> results = new ArrayList<>();
        for (Future<ApiResult> future : futures) {
            results.add(future.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();

        String freezeKey = results.get(0).body().get("freezeKey").asText();
        for (ApiResult result : results) {
            assertThat(result.status()).isEqualTo(201);
            assertThat(result.body().get("freezeVersion").asInt()).isEqualTo(1);
            assertThat(result.body().get("freezeKey").asText()).isEqualTo(freezeKey);
        }
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM term_freeze WHERE document_id = ?", Integer.class, docId))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM term_freeze_entry WHERE document_id = ?", Integer.class, docId))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("并发异内容冻结：同一文档版本仅一个成功，其余 409，不产生多份有效冻结")
    void concurrentFreezeDifferentContent() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]", "[]");
        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<ApiResult>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final int n = i;
            futures.add(pool.submit(() -> {
                gate.await();
                return createFreeze(docId, "actor" + n,
                        "[{\"term\":\"术语" + n + "\",\"language\":\"en\","
                                + "\"allowedTranslations\":[\"term" + n + "\"]}]", newRequestId());
            }));
        }
        gate.countDown();
        int success = 0;
        int conflict = 0;
        for (Future<ApiResult> future : futures) {
            ApiResult result = future.get(30, TimeUnit.SECONDS);
            if (result.status() == 201) {
                success++;
            } else {
                assertThat(result.status()).isEqualTo(409);
                assertThat(result.body().get("error").asText()).isEqualTo("FREEZE_CONFLICT");
                conflict++;
            }
        }
        pool.shutdown();

        assertThat(success).isEqualTo(1);
        assertThat(conflict).isEqualTo(threads - 1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM term_freeze WHERE document_id = ?", Integer.class, docId))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM term_freeze WHERE document_id = ? AND status = 'ACTIVE'",
                Integer.class, docId)).isEqualTo(1);
    }

    @Test
    @DisplayName("批量修订与撤销并发：按提交顺序裁决，对应一个一致状态，失败不留半成品")
    void concurrentBatchAndRevoke() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"机器学习\"}]");
        createFreeze(docId, "carol", ENTRIES, newRequestId());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        Future<ApiResult> batchFuture = pool.submit(() -> {
            gate.await();
            return submitBatch(docId, "alice",
                    "[{\"segmentId\":\"s1\",\"language\":\"en\",\"content\":\"hello\","
                            + "\"sourceVersion\":1}]", newRequestId());
        });
        Future<ApiResult> revokeFuture = pool.submit(() -> {
            gate.await();
            return revokeFreeze(docId, 1, newRequestId());
        });
        gate.countDown();
        ApiResult batch = batchFuture.get(30, TimeUnit.SECONDS);
        ApiResult revoke = revokeFuture.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        // 撤销必然成功；批量修订要么先于撤销被冻结拦截（422），要么在撤销后通过（200）
        assertThat(revoke.status()).isEqualTo(200);
        assertThat(batch.status()).isIn(200, 422);
        Integer translations = jdbc.queryForObject(
                "SELECT COUNT(*) FROM translation WHERE document_id = ?", Integer.class, docId);
        Integer draftVersion = jdbc.queryForObject(
                "SELECT draft_version FROM document WHERE document_id = ?", Integer.class, docId);
        String status = jdbc.queryForObject(
                "SELECT status FROM term_freeze WHERE document_id = ? AND freeze_version = 1",
                String.class, docId);
        assertThat(status).isEqualTo("REVOKED");
        if (batch.status() == 422) {
            assertThat(batch.body().get("error").asText()).isEqualTo("FREEZE_VIOLATION");
            assertThat(translations).isZero();
            assertThat(draftVersion).isEqualTo(1);
        } else {
            assertThat(translations).isEqualTo(1);
            assertThat(draftVersion).isEqualTo(2);
        }
    }

    @Test
    @DisplayName("发布与撤销并发：发布要么固化冻结版本，要么在撤销后无冻结发布，快照与发布版本一致")
    void concurrentPublishAndRevoke() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"机器学习\"}]");
        createFreeze(docId, "carol", ENTRIES, newRequestId());
        submitTranslation(docId, "s1", "en", "alice", "machine learning", 1, newRequestId());
        approve(docId, "s1", "en", "bob", 1, newRequestId());
        // 当前草稿版本 2、发布版本 0、冻结版本 1（有效）

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        Future<ApiResult> publishFuture = pool.submit(() -> {
            gate.await();
            return publish(docId, 2, 0, newRequestId());
        });
        Future<ApiResult> revokeFuture = pool.submit(() -> {
            gate.await();
            return revokeFreeze(docId, 1, newRequestId());
        });
        gate.countDown();
        ApiResult publish = publishFuture.get(30, TimeUnit.SECONDS);
        ApiResult revoke = revokeFuture.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(revoke.status()).isEqualTo(200);
        assertThat(publish.status()).isEqualTo(201);
        Integer publishedVersion = jdbc.queryForObject(
                "SELECT published_version FROM document WHERE document_id = ?", Integer.class, docId);
        Integer snapshots = jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_snapshot WHERE document_id = ?", Integer.class, docId);
        assertThat(snapshots).isEqualTo(publishedVersion).isEqualTo(1);

        // 快照固化发布时所见的冻结状态：先于撤销则 freezeVersion=1，后于撤销则为 null
        ApiResult release = getJson("/api/documents/" + docId + "/releases/1");
        assertThat(release.status()).isEqualTo(200);
        if (release.body().get("freezeVersion").isNull()) {
            assertThat(release.body().get("freezeTerms")).isEmpty();
        } else {
            assertThat(release.body().get("freezeVersion").asInt()).isEqualTo(1);
            assertThat(release.body().get("freezeTerms")).hasSize(1);
        }
    }
}
