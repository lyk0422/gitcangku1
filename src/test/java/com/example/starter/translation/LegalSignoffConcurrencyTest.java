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
 * 法律审签并发与幂等边界测试：真实并发调用，门闩协调同时发起，
 * 设置超时并断言最终数据状态（审签、发布按事务提交顺序裁决）。
 */
class LegalSignoffConcurrencyTest extends AbstractIntegrationTest {

    /** 建文档（en 单语言、单段落 s1）并提交且批准译文 v1，返回 documentId。 */
    private long approvedDoc() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"原文\"}]");
        submitTranslation(docId, "s1", "en", "alice", "hello", 1, newRequestId());
        approve(docId, "s1", "en", "bob", 1, newRequestId());
        return docId;
    }

    @Test
    @DisplayName("并发审签同一译文版本：不同法务人均成功，同版本仅保留最后一条终态审签")
    void concurrentSignoffsSameVersion() throws Exception {
        long docId = approvedDoc();
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<ApiResult>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final int n = i;
            futures.add(pool.submit(() -> {
                gate.await();
                String status = n % 2 == 0 ? "APPROVED" : "REJECTED";
                String reason = n % 2 == 0 ? null : "拒绝-" + n;
                return legalSignoff(docId, "s1", "en", "legal" + n, 1, status, reason, newRequestId());
            }));
        }
        gate.countDown();
        for (Future<ApiResult> future : futures) {
            assertThat(future.get(30, TimeUnit.SECONDS).status()).isEqualTo(200);
        }
        pool.shutdown();

        // 同一译文版本仅一条终态审签（最后提交者），历史查询仅一条
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM legal_signoff WHERE document_id = ? AND translation_version = 1",
                Integer.class, docId)).isEqualTo(1);
        ApiResult history = getJson("/api/documents/" + docId + "/segments/s1/translations/en/legal-signoffs");
        assertThat(history.body().get("signoffs")).hasSize(1);
    }

    @Test
    @DisplayName("并发同 signKey 同参：仅执行一次，全部重放首次响应")
    void concurrentSameSignKey() throws Exception {
        long docId = approvedDoc();
        String signKey = newRequestId();
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<ApiResult>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                gate.await();
                return legalSignoff(docId, "s1", "en", "legal1", 1, "APPROVED", "通过", signKey);
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
            assertThat(result.body().toString()).isEqualTo(results.get(0).body().toString());
        }
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM legal_signoff WHERE document_id = ?", Integer.class, docId))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = ?", Integer.class, signKey))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("发布与法务拒绝并发：按事务提交顺序裁决，拒绝后该版本不得再入新快照")
    void concurrentPublishAndReject() throws Exception {
        long docId = approvedDoc();
        legalApprove(docId, "s1", "en", "legal1", 1);
        // 当前草稿版本 2、发布版本 0

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        Future<ApiResult> publishFuture = pool.submit(() -> {
            gate.await();
            return publish(docId, 2, 0, newRequestId());
        });
        Future<ApiResult> rejectFuture = pool.submit(() -> {
            gate.await();
            return legalSignoff(docId, "s1", "en", "legal2", 1, "REJECTED", "事后发现违规",
                    newRequestId());
        });
        gate.countDown();
        ApiResult publishResult = publishFuture.get(30, TimeUnit.SECONDS);
        ApiResult rejectResult = rejectFuture.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        // 拒绝必然成功（expectedVersion 匹配当前译文版本）；发布按提交顺序成功或被门禁阻断
        assertThat(rejectResult.status()).isEqualTo(200);
        assertThat(publishResult.status()).isIn(201, 422);

        Integer publishedVersion = jdbc.queryForObject(
                "SELECT published_version FROM document WHERE document_id = ?", Integer.class, docId);
        Integer snapshots = jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_snapshot WHERE document_id = ?", Integer.class, docId);
        assertThat(snapshots).isEqualTo(publishedVersion);
        // 终态审签为 REJECTED
        assertThat(jdbc.queryForObject(
                "SELECT status FROM legal_signoff WHERE document_id = ? AND translation_version = 1",
                String.class, docId)).isEqualTo("REJECTED");

        if (publishResult.status() == 201) {
            // 发布先于拒绝：历史快照固化为 APPROVED，不追溯改变
            assertThat(publishedVersion).isEqualTo(1);
            ApiResult release = getJson("/api/documents/" + docId + "/releases/1");
            assertThat(release.body().get("segments").get(0).get("translations").get(0)
                    .get("legalSignoff").get("status").asText()).isEqualTo("APPROVED");
        } else {
            // 拒绝先于发布：门禁阻断且无快照
            assertThat(publishResult.body().get("error").asText()).isEqualTo("LEGAL_GATE");
            assertThat(publishedVersion).isZero();
        }

        // 无论顺序如何，被拒绝的版本不得再入新快照
        ApiResult republish = publish(docId, 2, publishedVersion, newRequestId());
        assertThat(republish.status()).isEqualTo(422);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_snapshot WHERE document_id = ?", Integer.class, docId))
                .isEqualTo(snapshots);
    }
}
