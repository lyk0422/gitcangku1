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
    @DisplayName("并发撤回同一版本：仅一个成功，其余 409，目录修订号只推进一次")
    void concurrentWithdraws() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"原文\"}]");
        submitTranslation(docId, "s1", "en", "alice", "hello", 1, newRequestId());
        approve(docId, "s1", "en", "bob", 1, newRequestId());
        assertThat(publish(docId, 2, 0, newRequestId()).status()).isEqualTo(201);
        // 当前目录修订号 1

        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<ApiResult>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final int n = i;
            futures.add(pool.submit(() -> {
                gate.await();
                return withdraw(docId, 1, "并发撤回-" + n, 1, newRequestId());
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
        // 串行一致：修订号只推进一次，仅一条撤回记录，当前可用为 null
        Integer releaseRevision = jdbc.queryForObject(
                "SELECT release_revision FROM document WHERE document_id = ?", Integer.class, docId);
        assertThat(releaseRevision).isEqualTo(2);
        Integer withdrawnCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_snapshot WHERE document_id = ? AND withdrawn = TRUE",
                Integer.class, docId);
        assertThat(withdrawnCount).isEqualTo(1);
        ApiResult current = getJson("/api/documents/" + docId + "/releases/current");
        assertThat(current.status()).isEqualTo(200);
        assertThat(current.body().get("snapshot").isNull()).isTrue();
    }

    @Test
    @DisplayName("撤回与再次发布并发：串行执行对应一个一致状态，当前可用始终指向完整提交的 v2")
    void concurrentWithdrawAndRepublish() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"原文\"}]");
        submitTranslation(docId, "s1", "en", "alice", "hello", 1, newRequestId());
        approve(docId, "s1", "en", "bob", 1, newRequestId());
        assertThat(publish(docId, 2, 0, newRequestId()).status()).isEqualTo(201);
        // 当前草稿版本 2、发布版本 1、目录修订号 1

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        Future<ApiResult> withdrawFuture = pool.submit(() -> {
            gate.await();
            return withdraw(docId, 1, "撤回 v1", 1, newRequestId());
        });
        Future<ApiResult> publishFuture = pool.submit(() -> {
            gate.await();
            return publish(docId, 2, 1, newRequestId());
        });
        gate.countDown();
        ApiResult withdrawResult = withdrawFuture.get(30, TimeUnit.SECONDS);
        ApiResult publishResult = publishFuture.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        // 发布不校验目录修订号，必然成功；撤回先于发布则成功，否则因修订号过期 409
        assertThat(publishResult.status()).isEqualTo(201);
        assertThat(publishResult.body().get("publishedVersion").asInt()).isEqualTo(2);
        assertThat(withdrawResult.status()).isIn(200, 409);

        boolean withdrawn = withdrawResult.status() == 200;
        Integer releaseRevision = jdbc.queryForObject(
                "SELECT release_revision FROM document WHERE document_id = ?", Integer.class, docId);
        // 初始 0 + 两次发布各加一 + 撤回成功再加一
        assertThat(releaseRevision).isEqualTo(withdrawn ? 3 : 2);
        Integer withdrawnCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_snapshot WHERE document_id = ? AND withdrawn = TRUE",
                Integer.class, docId);
        assertThat(withdrawnCount).isEqualTo(withdrawn ? 1 : 0);

        // 当前可用始终是一个完整提交状态：v2 已发布且未撤回，不会指向已撤回的 v1 或为 null
        ApiResult current = getJson("/api/documents/" + docId + "/releases/current");
        assertThat(current.status()).isEqualTo(200);
        assertThat(current.body().get("publishedVersion").asInt()).isEqualTo(2);
        assertThat(current.body().get("snapshot").get("publishedVersion").asInt()).isEqualTo(2);
        assertThat(current.body().get("releaseRevision").asInt()).isEqualTo(releaseRevision);

        // 目录与历史一致：两个发布条目，撤回标记与并发结果一致
        ApiResult catalog = getJson("/api/documents/" + docId + "/releases");
        assertThat(catalog.body().get("releases")).hasSize(2);
        assertThat(catalog.body().get("releases").get(0).get("withdrawn").asBoolean()).isEqualTo(withdrawn);
        assertThat(catalog.body().get("releases").get(1).get("withdrawn").asBoolean()).isFalse();
    }
}
