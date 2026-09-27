package com.example.starter.translation;

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
 * 利益冲突相关并发边界：真实线程 + 门闩协调同时发起，带超时，
 * 以提交顺序裁决最终状态，核对业务结果与落库数据。
 */
class ConflictConcurrencyTest extends AbstractIntegrationTest {

    private static final String T0 = "2026-10-01T00:00:00Z";
    private static final String T1 = "2026-10-02T00:00:00Z";

    private String enRange(String reason) {
        return "[{\"language\":\"en\",\"validFrom\":\"" + T0 + "\",\"validTo\":\"" + T1
                + "\",\"reason\":\"" + reason + "\"}]";
    }

    private long readyDoc() throws Exception {
        clock.setFixed(Instant.parse(T0));
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"原文\"}]");
        assertThat(submitTranslation(docId, "s1", "en", "alice", "hello", 1, newRequestId()).status())
                .isEqualTo(200);
        return docId;
    }

    @Test
    @DisplayName("并发：同一评审人重叠区间登记只有一个成功，另一个 409，无重复标记")
    void concurrentOverlappingRegistrations() throws Exception {
        long docId = readyDoc();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<ApiResult>> futures = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            final int n = i;
            futures.add(pool.submit(() -> {
                gate.await();
                return registerConflicts(docId, "bob", enRange("并发原因" + n), newRequestId());
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
            } else {
                throw new AssertionError("意外状态码: " + status);
            }
        }
        pool.shutdown();

        assertThat(success).isEqualTo(1);
        assertThat(conflict).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM conflict_declaration WHERE document_id = ?",
                Integer.class, docId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM conflict_event WHERE document_id = ?",
                Integer.class, docId)).isEqualTo(1);
    }

    @Test
    @DisplayName("并发：冲突登记与批准提交按顺序裁决——先批准则被标记 CONFLICTED，先登记则批准 422")
    void concurrentRegistrationAndApproval() throws Exception {
        long docId = readyDoc();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        Future<ApiResult> registerFuture = pool.submit(() -> {
            gate.await();
            return registerConflicts(docId, "bob", enRange("bob 关联关系"), newRequestId());
        });
        Future<ApiResult> approveFuture = pool.submit(() -> {
            gate.await();
            return approve(docId, "s1", "en", "bob", 1, newRequestId());
        });
        gate.countDown();
        ApiResult registerResult = registerFuture.get(30, TimeUnit.SECONDS);
        ApiResult approveResult = approveFuture.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(registerResult.status()).isEqualTo(201);
        Integer conflictCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM conflict_declaration WHERE document_id = ?", Integer.class, docId);
        assertThat(conflictCount).isEqualTo(1);

        if (approveResult.status() == 200) {
            // 批准先提交：登记后该评审必须被标记 CONFLICTED，且发布重算为法定人数不足
            assertThat(registerResult.body().get("markedConflictReviewCount").asInt()).isEqualTo(1);
            String status = jdbc.queryForObject(
                    "SELECT review_status FROM approval WHERE document_id = ? AND reviewer = 'bob'",
                    String.class, docId);
            assertThat(status).isEqualTo("CONFLICTED");
            assertThat(publish(docId, 2, 0, newRequestId()).status()).isEqualTo(422);
        } else {
            // 登记先提交：批准被有效冲突拦截，没有新评审行
            assertThat(approveResult.status()).isEqualTo(422);
            assertThat(approveResult.body().get("error").asText()).isEqualTo("REVIEWER_CONFLICT");
            assertThat(registerResult.body().get("markedConflictReviewCount").asInt()).isZero();
            Integer reviewCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM approval WHERE document_id = ? AND reviewer = 'bob'",
                    Integer.class, docId);
            assertThat(reviewCount).isZero();
        }
    }

    @Test
    @DisplayName("并发：冲突登记与发布串行裁决——发布先于登记则 201 出快照，后于登记则 422，绝不出现部分发布")
    void concurrentRegistrationAndPublish() throws Exception {
        long docId = readyDoc();
        assertThat(approve(docId, "s1", "en", "bob", 1, newRequestId()).status()).isEqualTo(200);
        // 当前草稿版本 2

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        Future<ApiResult> publishFuture = pool.submit(() -> {
            gate.await();
            return publish(docId, 2, 0, newRequestId());
        });
        Future<ApiResult> registerFuture = pool.submit(() -> {
            gate.await();
            return registerConflicts(docId, "bob", enRange("bob 关联关系"), newRequestId());
        });
        gate.countDown();
        ApiResult publishResult = publishFuture.get(30, TimeUnit.SECONDS);
        ApiResult registerResult = registerFuture.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(registerResult.status()).isEqualTo(201);
        Integer publishedVersion = jdbc.queryForObject(
                "SELECT published_version FROM document WHERE document_id = ?", Integer.class, docId);
        Integer snapshotCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_snapshot WHERE document_id = ?", Integer.class, docId);
        assertThat(snapshotCount).isEqualTo(publishedVersion);

        if (publishResult.status() == 201) {
            assertThat(publishedVersion).isEqualTo(1);
            // 快照中固化批准时的 ACTIVE 状态
            ApiResult snapshot = getJson("/api/documents/" + docId + "/releases/1");
            assertThat(snapshot.body().get("segments").get(0).get("translations").get(0)
                    .get("reviews").get(0).get("reviewStatus").asText()).isEqualTo("ACTIVE");
        } else {
            assertThat(publishResult.status()).isEqualTo(422);
            assertThat(publishResult.body().get("error").asText()).isEqualTo("QUORUM_NOT_MET");
            assertThat(publishedVersion).isZero();
        }
    }
}
