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
 * 重跑稳定性通过多轮 test 任务验证。
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
    @DisplayName("发布与术语更新并发：只发布更新前完整状态或因版本变化失败，不产生混合快照")
    void concurrentPublishAndTermUpdate() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"机器学习\"}]");
        updateTerms(docId, 0,
                "[{\"sourceTerm\":\"机器学习\",\"language\":\"en\",\"requiredTranslation\":\"machine learning\"}]",
                newRequestId());
        submitTranslation(docId, "s1", "en", "alice", "machine learning", 1, newRequestId());
        approve(docId, "s1", "en", "bob", 1, newRequestId());
        // 当前草稿版本 3、发布版本 0、术语版本 1

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        Future<ApiResult> publishFuture = pool.submit(() -> {
            gate.await();
            return publish(docId, 3, 0, newRequestId());
        });
        Future<ApiResult> termsFuture = pool.submit(() -> {
            gate.await();
            return updateTerms(docId, 1,
                    "[{\"sourceTerm\":\"机器学习\",\"language\":\"en\",\"requiredTranslation\":\"ML\"}]",
                    newRequestId());
        });
        gate.countDown();
        ApiResult publishResult = publishFuture.get(30, TimeUnit.SECONDS);
        ApiResult termsResult = termsFuture.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        // 术语更新必然成功；发布要么成功（先于术语更新，快照固化术语版本 1），要么 409（草稿版本已变）
        assertThat(termsResult.status()).isEqualTo(201);
        assertThat(publishResult.status()).isIn(201, 409);

        Integer draftVersion = jdbc.queryForObject(
                "SELECT draft_version FROM document WHERE document_id = ?", Integer.class, docId);
        Integer termVersion = jdbc.queryForObject(
                "SELECT term_version FROM document WHERE document_id = ?", Integer.class, docId);
        Integer publishedVersion = jdbc.queryForObject(
                "SELECT published_version FROM document WHERE document_id = ?", Integer.class, docId);
        Integer snapshots = jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_snapshot WHERE document_id = ?", Integer.class, docId);

        assertThat(draftVersion).isEqualTo(4);
        assertThat(termVersion).isEqualTo(2);
        assertThat(snapshots).isEqualTo(publishedVersion);
        if (publishResult.status() == 201) {
            // 发布先于术语更新：快照为更新前完整状态（术语版本 1、旧规则集）
            assertThat(publishedVersion).isEqualTo(1);
            ApiResult release = getJson("/api/documents/" + docId + "/releases/1");
            assertThat(release.status()).isEqualTo(200);
            assertThat(release.body().get("termVersion").asInt()).isEqualTo(1);
            assertThat(release.body().get("terms").get(0).get("requiredTranslation").asText())
                    .isEqualTo("machine learning");
        } else {
            // 术语更新先于发布：期望草稿版本不符，发布 409 且无快照
            assertThat(publishedVersion).isZero();
        }
    }

    @Test
    @DisplayName("并发术语更新：同一期望版本仅一个成功，术语版本无丢失更新")
    void concurrentTermUpdates() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]", "[]");
        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<ApiResult>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final int n = i;
            futures.add(pool.submit(() -> {
                gate.await();
                return updateTerms(docId, 0,
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
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM term_version WHERE document_id = ?", Integer.class, docId))
                .isEqualTo(1);
        Integer termVersion = jdbc.queryForObject(
                "SELECT term_version FROM document WHERE document_id = ?", Integer.class, docId);
        assertThat(termVersion).isEqualTo(1);
    }

    @Test
    @DisplayName("并发创建全局术语版本：同一期望版本仅一个成功，版本号连续无丢失，快照各自完整")
    void concurrentGlobalTermCreates() throws Exception {
        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<ApiResult>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final int n = i;
            futures.add(pool.submit(() -> {
                gate.await();
                return createGlobalTerms(0,
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
        // 仅生成全局版本 1，失败事务未产生空快照版本
        Integer versionCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM global_term_version", Integer.class);
        assertThat(versionCount).isEqualTo(1);
        Integer ruleCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM global_term_rule WHERE global_term_version = 1", Integer.class);
        assertThat(ruleCount).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COALESCE(MAX(global_term_version), 0) FROM global_term_version", Integer.class))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("发布与全局术语更新并发：按提交顺序裁决，发布先则固化旧版本快照，更新先则引用落后 422，无混合快照")
    void concurrentPublishAndGlobalTermCreate() throws Exception {
        // 文档引用全局 v1，译文合规且已批准，发布开始时引用为最新（草稿 3、发布 0）
        createGlobalTerms(0,
                "[{\"sourceTerm\":\"机器学习\",\"language\":\"en\",\"requiredTranslation\":\"machine learning\"}]",
                newRequestId());
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"机器学习\"}]");
        upgradeGlobalReference(docId, 0, 1, newRequestId());
        submitTranslation(docId, "s1", "en", "alice", "machine learning", 1, newRequestId());
        approve(docId, "s1", "en", "bob", 1, newRequestId());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        Future<ApiResult> publishFuture = pool.submit(() -> {
            gate.await();
            return publish(docId, 3, 0, newRequestId());
        });
        Future<ApiResult> globalFuture = pool.submit(() -> {
            gate.await();
            return createGlobalTerms(1,
                    "[{\"sourceTerm\":\"机器学习\",\"language\":\"en\",\"requiredTranslation\":\"ML\"}]",
                    newRequestId());
        });
        gate.countDown();
        ApiResult publishResult = publishFuture.get(30, TimeUnit.SECONDS);
        ApiResult globalResult = globalFuture.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        // 全局更新必然成功；发布要么先提交成功（快照固化全局版本 1），要么检测到引用落后 422
        assertThat(globalResult.status()).isEqualTo(201);
        assertThat(globalResult.body().get("globalTermVersion").asInt()).isEqualTo(2);
        assertThat(publishResult.status()).isIn(201, 422);

        Integer publishedVersion = jdbc.queryForObject(
                "SELECT published_version FROM document WHERE document_id = ?", Integer.class, docId);
        Integer snapshots = jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_snapshot WHERE document_id = ?", Integer.class, docId);
        assertThat(snapshots).isEqualTo(publishedVersion);
        if (publishResult.status() == 201) {
            assertThat(publishedVersion).isEqualTo(1);
            ApiResult release = getJson("/api/documents/" + docId + "/releases/1");
            assertThat(release.body().get("globalTermVersion").asInt()).isEqualTo(1);
            assertThat(release.body().get("terms").get(0).get("requiredTranslation").asText())
                    .isEqualTo("machine learning");
        } else {
            assertThat(publishedVersion).isZero();
        }
    }

    @Test
    @DisplayName("发布、全局更新与引用升级三方并发：仅一个一致结局，快照绝不混合两个全局版本")
    void concurrentPublishGlobalCreateAndUpgrade() throws Exception {
        createGlobalTerms(0,
                "[{\"sourceTerm\":\"机器学习\",\"language\":\"en\",\"requiredTranslation\":\"machine learning\"}]",
                newRequestId());
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"机器学习\"}]");
        upgradeGlobalReference(docId, 0, 1, newRequestId());
        submitTranslation(docId, "s1", "en", "alice", "machine learning", 1, newRequestId());
        approve(docId, "s1", "en", "bob", 1, newRequestId());
        // 初始：草稿 3、发布 0、引用 1、最新全局 1

        ExecutorService pool = Executors.newFixedThreadPool(3);
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<ApiResult>> futures = new ArrayList<>();
        futures.add(pool.submit(() -> {
            gate.await();
            return publish(docId, 3, 0, newRequestId());
        }));
        futures.add(pool.submit(() -> {
            gate.await();
            return createGlobalTerms(1,
                    "[{\"sourceTerm\":\"机器学习\",\"language\":\"en\",\"requiredTranslation\":\"ML\"}]",
                    newRequestId());
        }));
        futures.add(pool.submit(() -> {
            gate.await();
            return upgradeGlobalReference(docId, 1, 2, newRequestId());
        }));
        gate.countDown();
        ApiResult publishResult = futures.get(0).get(30, TimeUnit.SECONDS);
        ApiResult globalResult = futures.get(1).get(30, TimeUnit.SECONDS);
        ApiResult upgradeResult = futures.get(2).get(30, TimeUnit.SECONDS);
        pool.shutdown();

        // 全局 v2 必然出现；升级可能 200，或在目标版本尚未提交时 404/与其他变更冲突 409
        assertThat(globalResult.status()).isEqualTo(201);
        assertThat(upgradeResult.status()).isIn(200, 409, 404);
        // 发布：201（最先提交、固化引用 1）/ 409（升级先改草稿）/ 422（全局更新先使引用落后）
        assertThat(publishResult.status()).isIn(201, 409, 422);

        // 若升级未成功，则在并发结束后补偿升级，再断言最终一致状态
        if (jdbc.queryForObject(
                "SELECT global_term_version FROM document WHERE document_id = ?", Integer.class, docId) == 1) {
            assertThat(upgradeGlobalReference(docId, 1, 2, newRequestId()).status()).isEqualTo(200);
        }

        Integer draftVersion = jdbc.queryForObject(
                "SELECT draft_version FROM document WHERE document_id = ?", Integer.class, docId);
        Integer globalRef = jdbc.queryForObject(
                "SELECT global_term_version FROM document WHERE document_id = ?", Integer.class, docId);
        Integer publishedVersion = jdbc.queryForObject(
                "SELECT published_version FROM document WHERE document_id = ?", Integer.class, docId);
        Integer snapshots = jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_snapshot WHERE document_id = ?", Integer.class, docId);
        assertThat(globalRef).isEqualTo(2);
        assertThat(snapshots).isEqualTo(publishedVersion);
        if (publishResult.status() == 201) {
            // 成功发布必然先于升级：草稿只被升级再 +1，快照固化全局版本 1
            assertThat(publishedVersion).isEqualTo(1);
            assertThat(draftVersion).isEqualTo(4);
            ApiResult release = getJson("/api/documents/" + docId + "/releases/1");
            assertThat(release.body().get("globalTermVersion").asInt()).isEqualTo(1);
            assertThat(release.body().get("terms").get(0).get("source").asText()).isEqualTo("GLOBAL");
        } else {
            // 409/422：无快照；升级成功则草稿 +1
            assertThat(publishedVersion).isZero();
            assertThat(draftVersion).isEqualTo(4);
        }
    }
}
