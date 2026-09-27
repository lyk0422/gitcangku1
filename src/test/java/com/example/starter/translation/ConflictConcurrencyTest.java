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
 * 利益冲突相关并发与幂等边界：真实并发，门闩同时发起，设置超时并断言最终一致状态。
 * 同一文档写操作由文档行 FOR UPDATE 锁串行裁决，不允许出现部分快照或资格中间态。
 */
class ConflictConcurrencyTest extends AbstractIntegrationTest {

    private static final String EFFECTIVE_FROM = "2000-01-01T00:00:00Z";
    private static final String EFFECTIVE_TO = "2100-01-01T00:00:00Z";

    private long readyDocument() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"原文\"}]");
        submitTranslation(docId, "s1", "en", "alice", "hello", 1, newRequestId());
        approve(docId, "s1", "en", "bob", 1, newRequestId());
        return docId;
        // 当前草稿版本 2、发布版本 0
    }

    @Test
    @DisplayName("并发同评审人同语种重叠登记：恰好一条成功，其余 409，声明数为 1")
    void concurrentOverlappingDeclarations() throws Exception {
        long docId = readyDocument();
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<ApiResult>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                gate.await();
                return declareConflict(docId, "bob", "en", EFFECTIVE_FROM, EFFECTIVE_TO,
                        "并发冲突", newRequestId());
            }));
        }
        gate.countDown();
        int created = 0;
        int conflict = 0;
        for (Future<ApiResult> future : futures) {
            int status = future.get(30, TimeUnit.SECONDS).status();
            assertThat(status).isIn(201, 409);
            if (status == 201) {
                created++;
            } else {
                conflict++;
            }
        }
        pool.shutdown();

        assertThat(created).isEqualTo(1);
        assertThat(conflict).isEqualTo(threads - 1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM conflict_declaration WHERE document_id = ?", Integer.class, docId))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM conflict_event WHERE document_id = ? AND event_type = 'DECLARED'",
                Integer.class, docId)).isEqualTo(1);
        // 唯一成功的登记必然把评审标记为 CONFLICTED
        assertThat(jdbc.queryForObject(
                "SELECT review_status FROM approval WHERE document_id = ? AND segment_id = 's1' AND language = 'en'",
                String.class, docId)).isEqualTo("CONFLICTED");
    }

    @Test
    @DisplayName("并发登记冲突与发布：按提交顺序裁决——先发布则 201，先登记则发布 422，无部分快照")
    void concurrentDeclareAndPublish() throws Exception {
        long docId = readyDocument();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        Future<ApiResult> publishFuture = pool.submit(() -> {
            gate.await();
            return publish(docId, 2, 0, newRequestId());
        });
        Future<ApiResult> declareFuture = pool.submit(() -> {
            gate.await();
            return declareConflict(docId, "bob", "en", EFFECTIVE_FROM, EFFECTIVE_TO, "利益冲突",
                    newRequestId());
        });
        gate.countDown();
        ApiResult publishResult = publishFuture.get(30, TimeUnit.SECONDS);
        ApiResult declareResult = declareFuture.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(declareResult.status()).isEqualTo(201);
        assertThat(publishResult.status()).isIn(201, 422);

        Integer publishedVersion = jdbc.queryForObject(
                "SELECT published_version FROM document WHERE document_id = ?", Integer.class, docId);
        Integer snapshots = jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_snapshot WHERE document_id = ?", Integer.class, docId);
        assertThat(snapshots).isEqualTo(publishedVersion);
        if (publishResult.status() == 201) {
            // 发布先于登记：发布版本 1，快照中无冲突、评审批次 VALID
            assertThat(publishedVersion).isEqualTo(1);
            ApiResult release = getJson("/api/documents/" + docId + "/releases/1");
            assertThat(release.body().get("conflicts")).isEmpty();
            assertThat(jdbc.queryForObject(
                    "SELECT review_status FROM approval WHERE document_id = ?", String.class, docId))
                    .isEqualTo("CONFLICTED");
        } else {
            // 登记先于发布：法定人数不足，错误码稳定，无快照
            assertThat(publishResult.status()).isEqualTo(422);
            assertThat(publishResult.body().get("error").asText()).isEqualTo("QUORUM_NOT_MET");
            assertThat(publishedVersion).isZero();
        }
    }

    @Test
    @DisplayName("并发解除冲突与发布：解除先于发布则 201，发布先于解除则 422；解除恰好一次，无部分快照")
    void concurrentReleaseAndPublish() throws Exception {
        long docId = readyDocument();
        long declarationId = declareConflict(docId, "bob", "en", EFFECTIVE_FROM, EFFECTIVE_TO,
                "利益冲突", newRequestId()).body().get("declarationId").asLong();
        // 当前草稿版本 2、发布版本 0，bob 评审 CONFLICTED

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        Future<ApiResult> publishFuture = pool.submit(() -> {
            gate.await();
            return publish(docId, 2, 0, newRequestId());
        });
        Future<ApiResult> releaseFuture = pool.submit(() -> {
            gate.await();
            return releaseConflict(docId, declarationId, "eve", "COMPLIANCE", newRequestId());
        });
        gate.countDown();
        ApiResult publishResult = publishFuture.get(30, TimeUnit.SECONDS);
        ApiResult releaseResult = releaseFuture.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(releaseResult.status()).isEqualTo(200);
        assertThat(publishResult.status()).isIn(201, 422);

        String reviewStatus = jdbc.queryForObject(
                "SELECT review_status FROM approval WHERE document_id = ?", String.class, docId);
        Integer publishedVersion = jdbc.queryForObject(
                "SELECT published_version FROM document WHERE document_id = ?", Integer.class, docId);
        Integer snapshots = jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_snapshot WHERE document_id = ?", Integer.class, docId);
        assertThat(reviewStatus).isEqualTo("VALID"); // 解除最终生效
        assertThat(snapshots).isEqualTo(publishedVersion);
        if (publishResult.status() == 201) {
            // 解除先于发布：法定人数恢复，发布成功
            assertThat(publishedVersion).isEqualTo(1);
        } else {
            // 发布先于解除：422 法定人数不足，无快照
            assertThat(publishResult.body().get("error").asText()).isEqualTo("QUORUM_NOT_MET");
            assertThat(publishedVersion).isZero();
        }

        // 解除恰好一次：再次解除 409，RELEASED 事件只有一条
        assertThat(releaseConflict(docId, declarationId, "eve", "COMPLIANCE", newRequestId()).status())
                .isEqualTo(409);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM conflict_event WHERE document_id = ? AND event_type = 'RELEASED'",
                Integer.class, docId)).isEqualTo(1);
    }
}
