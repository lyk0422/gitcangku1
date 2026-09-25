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
 * 术语冻结并发边界测试：真实并发调用，通过门闩协调同时发起，设置超时并断言最终数据状态。
 */
class FreezeConcurrencyTest extends AbstractIntegrationTest {

    private static final String FREEZE_ENTRIES =
            "[{\"sourceTerm\":\"机器学习\",\"language\":\"en\","
                    + "\"allowedTranslations\":[\"machine learning\"]}]";

    @Test
    @DisplayName("并发同 freezeKey 同参建冻结：仅执行一次，全部重放同一冻结版本")
    void concurrentCreateFreezeSameKey() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"机器学习\"}]");
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<ApiResult>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                gate.await();
                return createFreeze(docId, "fk-shared", FREEZE_ENTRIES, "alice", newRequestId());
            }));
        }
        gate.countDown();
        List<ApiResult> results = new ArrayList<>();
        for (Future<ApiResult> future : futures) {
            results.add(future.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();

        String fingerprint = results.get(0).body().get("fingerprint").asText();
        for (ApiResult result : results) {
            assertThat(result.status()).isEqualTo(201);
            assertThat(result.body().get("freezeVersion").asInt()).isEqualTo(1);
            assertThat(result.body().get("fingerprint").asText()).isEqualTo(fingerprint);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM term_freeze", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM term_freeze_entry", Integer.class))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("并发不同 freezeKey 建冻结：同一术语版本仅一个成功，其余 409 FREEZE_EXISTS")
    void concurrentCreateFreezeDistinctKeys() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"机器学习\"}]");
        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<ApiResult>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final int n = i;
            futures.add(pool.submit(() -> {
                gate.await();
                return createFreeze(docId, "fk-" + n, FREEZE_ENTRIES, "alice", newRequestId());
            }));
        }
        gate.countDown();
        int created = 0;
        int conflict = 0;
        for (Future<ApiResult> future : futures) {
            ApiResult result = future.get(30, TimeUnit.SECONDS);
            if (result.status() == 201) {
                created++;
            } else {
                assertThat(result.status()).isEqualTo(409);
                assertThat(result.body().get("error").asText()).isEqualTo("FREEZE_EXISTS");
                conflict++;
            }
        }
        pool.shutdown();

        assertThat(created).isEqualTo(1);
        assertThat(conflict).isEqualTo(threads - 1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM term_freeze", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM term_freeze WHERE status = 'ACTIVE'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("批量修订与撤销并发：对应一个一致状态，失败不留半成品译文")
    void concurrentBatchAndRevoke() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"机器学习\"}]");
        createFreeze(docId, "fk-1", FREEZE_ENTRIES, "alice", newRequestId());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        Future<ApiResult> batchFuture = pool.submit(() -> {
            gate.await();
            return submitRevisionBatch(docId,
                    "[{\"segmentId\":\"s1\",\"language\":\"en\",\"content\":\"bad\",\"sourceVersion\":1}]",
                    "alice", newRequestId());
        });
        Future<ApiResult> revokeFuture = pool.submit(() -> {
            gate.await();
            return revokeFreeze(docId, 1, "carol", newRequestId());
        });
        gate.countDown();
        ApiResult batchResult = batchFuture.get(30, TimeUnit.SECONDS);
        ApiResult revokeResult = revokeFuture.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(revokeResult.status()).isEqualTo(200);
        assertThat(batchResult.status()).isIn(200, 422);
        Integer translations = jdbc.queryForObject(
                "SELECT COUNT(*) FROM translation WHERE document_id = ?", Integer.class, docId);
        if (batchResult.status() == 422) {
            // 批量修订先于撤销：冻结仍生效，整批回滚无译文
            assertThat(batchResult.body().get("error").asText()).isEqualTo("FREEZE_VIOLATION");
            assertThat(translations).isZero();
        } else {
            // 撤销先于批量修订：冻结已失效，批次完整写入
            assertThat(translations).isEqualTo(1);
        }
        // 无论先后，冻结最终均为已撤销且唯一
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM term_freeze WHERE status = 'REVOKED'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("发布与撤销并发：发布要么被冻结拦截无快照，要么在撤销后成功且快照无冻结")
    void concurrentPublishAndRevoke() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"机器学习\"}]");
        // 冻结前写入违反未来冻结的译文并批准
        submitTranslation(docId, "s1", "en", "alice", "hello", 1, newRequestId());
        approve(docId, "s1", "en", "bob", 1, newRequestId());
        createFreeze(docId, "fk-1", FREEZE_ENTRIES, "alice", newRequestId());
        // 当前草稿版本 2、发布版本 0

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        Future<ApiResult> publishFuture = pool.submit(() -> {
            gate.await();
            return publish(docId, 2, 0, newRequestId());
        });
        Future<ApiResult> revokeFuture = pool.submit(() -> {
            gate.await();
            return revokeFreeze(docId, 1, "carol", newRequestId());
        });
        gate.countDown();
        ApiResult publishResult = publishFuture.get(30, TimeUnit.SECONDS);
        ApiResult revokeResult = revokeFuture.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(revokeResult.status()).isEqualTo(200);
        assertThat(publishResult.status()).isIn(201, 422);

        Integer publishedVersion = jdbc.queryForObject(
                "SELECT published_version FROM document WHERE document_id = ?", Integer.class, docId);
        Integer snapshots = jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_snapshot WHERE document_id = ?", Integer.class, docId);
        // 发布版本与快照数量一致：无部分快照
        assertThat(snapshots).isEqualTo(publishedVersion);
        if (publishResult.status() == 422) {
            // 发布先于撤销：冻结生效拦截，无快照
            assertThat(publishResult.body().get("error").asText()).isEqualTo("FREEZE_VIOLATION");
            assertThat(publishedVersion).isZero();
        } else {
            // 撤销先于发布：快照不含冻结
            assertThat(publishedVersion).isEqualTo(1);
            ApiResult release = getJson("/api/documents/" + docId + "/releases/1");
            assertThat(release.body().get("freezeVersion").isNull()).isTrue();
        }
    }
}
