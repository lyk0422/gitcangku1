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
 * 回退链并发边界测试：回退配置、撤回与发布按文档行锁提交顺序裁决，
 * 发布只使用提交时刻一致的链与译文版本；同期望版本并发配置仅一个成功。
 */
class FallbackConcurrencyTest extends AbstractIntegrationTest {

    @Test
    @DisplayName("并发回退配置：同一期望版本仅一个成功，其余 409，链配置无丢失更新")
    void concurrentFallbackConfigures() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\",\"ja\",\"fr\"]", "[]");
        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<ApiResult>> futures = new ArrayList<>();
        String[] languages = {"ja", "ja", "fr", "fr"};
        for (int i = 0; i < threads; i++) {
            final String language = languages[i];
            futures.add(pool.submit(() -> {
                gate.await();
                return configureFallback(docId, language, 1, "en", newRequestId());
            }));
        }
        gate.countDown();
        int success = 0;
        int conflict = 0;
        for (Future<ApiResult> future : futures) {
            int status = future.get(30, TimeUnit.SECONDS).status();
            if (status == 200) {
                success++;
            } else if (status == 409) {
                conflict++;
            }
        }
        pool.shutdown();

        assertThat(success).isEqualTo(1);
        assertThat(conflict).isEqualTo(threads - 1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM locale_fallback WHERE document_id = ?", Integer.class, docId))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT draft_version FROM document WHERE document_id = ?", Integer.class, docId))
                .isEqualTo(2);
    }

    @Test
    @DisplayName("回退配置与发布并发：对应一个一致状态，发布成功时快照固化提交时刻的链")
    void concurrentFallbackConfigAndPublish() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\",\"ja\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"原文\"}]");
        submitTranslation(docId, "s1", "en", "alice", "en-v1", 1, newRequestId());
        approve(docId, "s1", "en", "bob", 1, newRequestId());
        // 当前草稿版本 2、发布版本 0；ja 无译文且无回退配置

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        Future<ApiResult> publishFuture = pool.submit(() -> {
            gate.await();
            return publish(docId, 2, 0, newRequestId());
        });
        Future<ApiResult> configFuture = pool.submit(() -> {
            gate.await();
            return configureFallback(docId, "ja", 2, "en", newRequestId());
        });
        gate.countDown();
        ApiResult publishResult = publishFuture.get(30, TimeUnit.SECONDS);
        ApiResult configResult = configFuture.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        // 配置必然成功（草稿版本 2→3）；发布要么先于配置（ja 缺失 422），要么在其后（期望版本不符 409）
        assertThat(configResult.status()).isEqualTo(200);
        assertThat(publishResult.status()).isIn(409, 422);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_snapshot WHERE document_id = ?", Integer.class, docId)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT published_version FROM document WHERE document_id = ?", Integer.class, docId)).isZero();

        // 配置生效后用新版本发布：ja 经 en 回退成功，快照固化提交时刻的链
        ApiResult published = publish(docId, 3, 0, newRequestId());
        assertThat(published.status()).isEqualTo(201);
        ApiResult release = getJson("/api/documents/" + docId + "/releases/1");
        assertThat(release.body().get("fallbackChains").get(1).get("chain").toString())
                .isEqualTo("[\"ja\",\"en\"]");
        assertThat(release.body().get("segments").get(0).get("translations").get(1)
                .get("usedLanguage").asText()).isEqualTo("en");
    }

    @Test
    @DisplayName("撤回与发布并发：发布要么先于撤回（快照含直接译文），要么判缺失 422，无中间状态")
    void concurrentWithdrawAndPublish() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"原文\"}]");
        submitTranslation(docId, "s1", "en", "alice", "en-v1", 1, newRequestId());
        approve(docId, "s1", "en", "bob", 1, newRequestId());
        // 当前草稿版本 2、发布版本 0

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        Future<ApiResult> publishFuture = pool.submit(() -> {
            gate.await();
            return publish(docId, 2, 0, newRequestId());
        });
        Future<ApiResult> withdrawFuture = pool.submit(() -> {
            gate.await();
            return withdraw(docId, "s1", "en", "bob", newRequestId());
        });
        gate.countDown();
        ApiResult publishResult = publishFuture.get(30, TimeUnit.SECONDS);
        ApiResult withdrawResult = withdrawFuture.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(withdrawResult.status()).isEqualTo(200);
        assertThat(publishResult.status()).isIn(201, 422);
        Integer publishedVersion = jdbc.queryForObject(
                "SELECT published_version FROM document WHERE document_id = ?", Integer.class, docId);
        Integer snapshots = jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_snapshot WHERE document_id = ?", Integer.class, docId);
        assertThat(snapshots).isEqualTo(publishedVersion);
        if (publishResult.status() == 201) {
            // 发布先于撤回：快照固化撤回前已批准译文
            assertThat(publishedVersion).isEqualTo(1);
            ApiResult release = getJson("/api/documents/" + docId + "/releases/1");
            assertThat(release.body().get("segments").get(0).get("translations").get(0)
                    .get("content").asText()).isEqualTo("en-v1");
        } else {
            // 撤回先于发布：无已批准译文，整次 422 且无快照
            assertThat(publishedVersion).isZero();
            assertThat(publishResult.body().get("error").asText()).isEqualTo("MISSING_SEGMENTS");
        }
    }
}
