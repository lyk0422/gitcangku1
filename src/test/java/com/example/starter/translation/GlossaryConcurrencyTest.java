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
 * 全局术语库并发边界测试：全局更新、引用升级、发布按提交顺序裁决，
 * 真实并发调用通过门闩协调同时发起，设置超时并断言最终数据状态。
 */
class GlossaryConcurrencyTest extends AbstractIntegrationTest {

    @Test
    @DisplayName("并发全局版本提交：同一期望版本仅一个成功，全局版本无丢失更新")
    void concurrentGlobalTermUpdates() throws Exception {
        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<ApiResult>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final int n = i;
            futures.add(pool.submit(() -> {
                gate.await();
                return updateGlobalTerms(0,
                        "[{\"sourceTerm\":\"术语" + n + "\",\"language\":\"en\","
                                + "\"requiredTranslation\":\"term" + n + "\"}]", newRequestId());
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
            }
        }
        pool.shutdown();

        assertThat(success).isEqualTo(1);
        assertThat(conflict).isEqualTo(threads - 1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM global_term_version", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT current_version FROM global_glossary_state WHERE state_id = 1", Integer.class))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("并发同 requestId 全局提交：仅执行一次，全部重放同一成功结果")
    void concurrentGlobalUpdateSameRequestId() throws Exception {
        String requestId = newRequestId();
        String body = "{\"requestId\":\"" + requestId + "\",\"expectedGlobalTermVersion\":0,"
                + "\"rules\":[{\"sourceTerm\":\"机器学习\",\"language\":\"en\","
                + "\"requiredTranslation\":\"machine learning\"}]}";
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<ApiResult>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                gate.await();
                return putJson("/api/glossary/terms", body);
            }));
        }
        gate.countDown();
        List<ApiResult> results = new ArrayList<>();
        for (Future<ApiResult> future : futures) {
            results.add(future.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();

        for (ApiResult result : results) {
            assertThat(result.status()).isEqualTo(201);
            assertThat(result.body().get("globalTermVersion").asInt()).isEqualTo(1);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM global_term_version", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM request_log", Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("引用升级与全局更新并发：按提交顺序裁决，最终文档引用与全局状态一致")
    void concurrentUpgradeAndGlobalUpdate() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"机器学习\"}]");
        // 当前草稿版本 1、引用全局版本 0、最新全局版本 0

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        Future<ApiResult> upgradeFuture = pool.submit(() -> {
            gate.await();
            return upgradeGlossary(docId, 0, 1, newRequestId());
        });
        Future<ApiResult> globalFuture = pool.submit(() -> {
            gate.await();
            return updateGlobalTerms(0,
                    "[{\"sourceTerm\":\"机器学习\",\"language\":\"en\","
                            + "\"requiredTranslation\":\"machine learning\"}]", newRequestId());
        });
        gate.countDown();
        ApiResult upgradeResult = upgradeFuture.get(30, TimeUnit.SECONDS);
        ApiResult globalResult = globalFuture.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        // 全局更新必然成功
        assertThat(globalResult.status()).isEqualTo(201);
        // 升级要么推进到 v1（全局更新先提交），要么因已是最新而 422（升级先提交时最新版本仍为 0）
        assertThat(upgradeResult.status()).isIn(200, 422);

        Integer docGlobal = jdbc.queryForObject(
                "SELECT global_term_version FROM document WHERE document_id = ?", Integer.class, docId);
        Integer draftVersion = jdbc.queryForObject(
                "SELECT draft_version FROM document WHERE document_id = ?", Integer.class, docId);
        Integer latestGlobal = jdbc.queryForObject(
                "SELECT current_version FROM global_glossary_state WHERE state_id = 1", Integer.class);
        assertThat(latestGlobal).isEqualTo(1);
        if (upgradeResult.status() == 200) {
            // 升级在全局更新后提交：引用推进到 v1，草稿版本加一
            assertThat(docGlobal).isEqualTo(1);
            assertThat(draftVersion).isEqualTo(2);
        } else {
            // 升级先提交：最新全局版本仍为 0，无需升级，文档状态不变
            assertThat(docGlobal).isZero();
            assertThat(draftVersion).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("发布与全局更新并发：快照只含发布时引用的完整规则集，不产生混合规则集快照")
    void concurrentPublishAndGlobalUpdate() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"机器学习\"}]");
        assertThat(updateGlobalTerms(0,
                "[{\"sourceTerm\":\"机器学习\",\"language\":\"en\","
                        + "\"requiredTranslation\":\"machine learning\"}]", newRequestId()).status())
                .isEqualTo(201);
        assertThat(upgradeGlossary(docId, 0, 1, newRequestId()).status()).isEqualTo(200);
        submitTranslation(docId, "s1", "en", "alice", "machine learning", 1, newRequestId());
        approve(docId, "s1", "en", "bob", 1, newRequestId());
        // 当前草稿版本 3、发布版本 0、引用全局版本 1

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        Future<ApiResult> publishFuture = pool.submit(() -> {
            gate.await();
            return publish(docId, 3, 0, newRequestId());
        });
        Future<ApiResult> globalFuture = pool.submit(() -> {
            gate.await();
            return updateGlobalTerms(1,
                    "[{\"sourceTerm\":\"机器学习\",\"language\":\"en\",\"requiredTranslation\":\"ML\"}]",
                    newRequestId());
        });
        gate.countDown();
        ApiResult publishResult = publishFuture.get(30, TimeUnit.SECONDS);
        ApiResult globalResult = globalFuture.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        // 全局更新必然成功；发布要么成功（读取到最新版本仍为 v1），要么 422（引用落后）
        assertThat(globalResult.status()).isEqualTo(201);
        assertThat(publishResult.status()).isIn(201, 422);

        Integer publishedVersion = jdbc.queryForObject(
                "SELECT published_version FROM document WHERE document_id = ?", Integer.class, docId);
        Integer snapshots = jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_snapshot WHERE document_id = ?", Integer.class, docId);
        assertThat(snapshots).isEqualTo(publishedVersion);
        if (publishResult.status() == 201) {
            // 快照固化发布时引用的全局版本 1 与当时的完整规则集，不被后续全局更新改写
            ApiResult release = getJson("/api/documents/" + docId + "/releases/1");
            assertThat(release.body().get("globalTermVersion").asInt()).isEqualTo(1);
            assertThat(release.body().get("terms")).hasSize(1);
            assertThat(release.body().get("terms").get(0).get("requiredTranslation").asText())
                    .isEqualTo("machine learning");
            assertThat(release.body().get("terms").get(0).get("source").asText()).isEqualTo("GLOBAL");
        } else {
            assertThat(publishedVersion).isZero();
        }
        // 全局术语库已推进到 v2，历史版本 1 规则集不变
        assertThat(getJson("/api/glossary/terms").body().get("globalTermVersion").asInt()).isEqualTo(2);
        assertThat(getJson("/api/glossary/terms/1").body().get("rules").get(0)
                .get("requiredTranslation").asText()).isEqualTo("machine learning");
    }
}
