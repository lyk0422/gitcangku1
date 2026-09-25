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
 * 区域变体并发与幂等边界：真实并发 + 门闩协调 + 超时，断言裁决结果与最终数据，
 * 覆盖发布与变体操作并发的一致性、同 requestId 重放与同键异参冲突。
 */
class RegionConcurrencyTest extends AbstractIntegrationTest {

    private long preparedDoc() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"原文\"}]");
        submitTranslation(docId, "s1", "en", "alice", "default-text", 1, newRequestId());
        approve(docId, "s1", "en", "bob", 1, newRequestId());
        return docId;
    }

    @Test
    @DisplayName("并发登记同区域同译文版本变体：主键约束兜底，仅一条成功，其余 409")
    void concurrentCreateVariant() throws Exception {
        long docId = preparedDoc();
        int threads = 5;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<ApiResult>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                gate.await();
                return createVariant(docId, "s1", "en", "US", 1, newRequestId());
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
                "SELECT COUNT(*) FROM regional_variant WHERE document_id = ?", Integer.class, docId))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("发布与变体批准并发：只按事务提交顺序裁决为一致状态，不混合新旧区域选择，无部分快照")
    void concurrentPublishAndApproveVariant() throws Exception {
        long docId = preparedDoc();
        createVariant(docId, "s1", "en", "US", 1, newRequestId());
        // 当前草稿版本 3；若批准先提交，发布读取到 ACTIVE 变体（批准不递增草稿版本）
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        Future<ApiResult> publishFuture = pool.submit(() -> {
            gate.await();
            return publish(docId, 3, 0, "US", newRequestId());
        });
        Future<ApiResult> approveFuture = pool.submit(() -> {
            gate.await();
            return approveVariant(docId, "s1", "en", "US", "carol", 1, newRequestId());
        });
        gate.countDown();
        ApiResult publishResult = publishFuture.get(30, TimeUnit.SECONDS);
        ApiResult approveResult = approveFuture.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(approveResult.status()).isEqualTo(200);
        assertThat(publishResult.status()).isEqualTo(201);

        // 发布必然成功：批准先提交则快照选用 US 变体；发布先提交则 s1 回退 DEFAULT。两种状态均一致。
        ApiResult release = getJson("/api/documents/" + docId + "/releases/1");
        assertThat(release.status()).isEqualTo(200);
        String chosenRegion = release.body().get("segments").get(0).get("translations").get(0)
                .get("region").asText();
        assertThat(chosenRegion).isIn("US", "DEFAULT");
        Integer fallbacks = jdbc.queryForObject(
                "SELECT COUNT(*) FROM fallback_record WHERE document_id = ?", Integer.class, docId);
        if ("US".equals(chosenRegion)) {
            assertThat(fallbacks).isZero();
        } else {
            assertThat(fallbacks).isEqualTo(1);
        }
        Integer snapshots = jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_snapshot WHERE document_id = ?", Integer.class, docId);
        Integer publishedVersion = jdbc.queryForObject(
                "SELECT published_version FROM document WHERE document_id = ?", Integer.class, docId);
        assertThat(snapshots).isEqualTo(publishedVersion).isEqualTo(1);
    }

    @Test
    @DisplayName("发布与变体撤销并发：快照要么含 US 变体要么回退 DEFAULT，撤销后发布版本一致")
    void concurrentPublishAndRevokeVariant() throws Exception {
        long docId = preparedDoc();
        createVariant(docId, "s1", "en", "US", 1, newRequestId());
        approveVariant(docId, "s1", "en", "US", "carol", 1, newRequestId());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        Future<ApiResult> publishFuture = pool.submit(() -> {
            gate.await();
            return publish(docId, 3, 0, "US", newRequestId());
        });
        Future<ApiResult> revokeFuture = pool.submit(() -> {
            gate.await();
            return revokeVariant(docId, "s1", "en", "US", 1, newRequestId());
        });
        gate.countDown();
        ApiResult publishResult = publishFuture.get(30, TimeUnit.SECONDS);
        ApiResult revokeResult = revokeFuture.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(revokeResult.status()).isEqualTo(200);
        assertThat(publishResult.status()).isEqualTo(201);
        ApiResult release = getJson("/api/documents/" + docId + "/releases/1");
        String region = release.body().get("segments").get(0).get("translations").get(0)
                .get("region").asText();
        assertThat(region).isIn("US", "DEFAULT");
        // 撤销后的后续查询必然回退 DEFAULT
        ApiResult resolution = getJson("/api/documents/" + docId + "/regions/US/resolution");
        assertThat(resolution.body().get("items").get(0).get("selectedRegion").asText())
                .isEqualTo("DEFAULT");
    }

    @Test
    @DisplayName("变体写操作幂等：同键同参重放首次完整响应、不重复变更；同键异参 409；失败不占键")
    void variantIdempotency() throws Exception {
        long docId = preparedDoc();
        // preparedDoc 已产生 3 条成功写日志（建文档、提交、批准）
        int logsBefore = jdbc.queryForObject("SELECT COUNT(*) FROM request_log", Integer.class);
        String requestId = newRequestId();

        ApiResult first = createVariant(docId, "s1", "en", "US", 1, requestId);
        assertThat(first.status()).isEqualTo(201);
        ApiResult replay = createVariant(docId, "s1", "en", "US", 1, requestId);
        assertThat(replay.status()).isEqualTo(201);
        assertThat(replay.body().get("status").asText()).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM regional_variant WHERE document_id = ?", Integer.class, docId))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM request_log", Integer.class))
                .isEqualTo(logsBefore + 1);

        // 同键异参：409
        ApiResult different = createVariant(docId, "s1", "en", "UK", 1, requestId);
        assertThat(different.status()).isEqualTo(409);

        // 失败不占键：先用某键以错误版本触发 409，再以同键正确参数成功
        String failKey = newRequestId();
        ApiResult failed = createVariant(docId, "s1", "en", "DE", 99, failKey);
        assertThat(failed.status()).isEqualTo(409);
        ApiResult retried = createVariant(docId, "s1", "en", "DE", 1, failKey);
        assertThat(retried.status()).isEqualTo(201);

        // 批准接口同键重放：首次 200 ACTIVE，重放同一响应
        String approveKey = newRequestId();
        ApiResult approved = approveVariant(docId, "s1", "en", "US", "carol", 1, approveKey);
        assertThat(approved.status()).isEqualTo(200);
        ApiResult approveReplay = approveVariant(docId, "s1", "en", "US", "carol", 1, approveKey);
        assertThat(approveReplay.status()).isEqualTo(200);
        assertThat(approveReplay.body().get("status").asText()).isEqualTo("ACTIVE");
    }
}
