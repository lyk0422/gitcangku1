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
 * 法律效力相关并发边界：真实并发 + 门闩协调 + 超时，最终核对数据库状态而非打印判断。
 */
class LegalStatusConcurrencyTest extends AbstractIntegrationTest {

    @Test
    @DisplayName("两名不同签署人并发签同一译文版本：均成功，固化为两条不同签署人记录")
    void concurrentDistinctSignersBothSucceed() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"原文\"}]");
        submitTranslation(docId, "s1", "en", "alice", "v3", 1, newRequestId());
        // 草稿版本 2

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        Future<ApiResult> jia = pool.submit(() -> {
            gate.await();
            return sign(docId, "s1", "en", "jia", 1, newRequestId());
        });
        Future<ApiResult> yi = pool.submit(() -> {
            gate.await();
            return sign(docId, "s1", "en", "yi", 1, newRequestId());
        });
        gate.countDown();
        assertThat(jia.get(30, TimeUnit.SECONDS).status()).isEqualTo(201);
        assertThat(yi.get(30, TimeUnit.SECONDS).status()).isEqualTo(201);
        pool.shutdown();

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM binding_signature WHERE document_id = ?", Integer.class, docId);
        assertThat(count).isEqualTo(2);
        List<String> signers = jdbc.queryForList(
                "SELECT DISTINCT signer FROM binding_signature WHERE document_id = ?", String.class, docId);
        assertThat(signers).containsExactlyInAnyOrder("jia", "yi");
        // 两份签署满足 BINDING 发布
        assertThat(publishLegal(docId, 2, 0, "BINDING", "en", newRequestId()).status()).isEqualTo(201);
    }

    @Test
    @DisplayName("同一签署人并发重复签同一版本：仅一条成功，另一条 422，库里只有一行")
    void concurrentSameSignerDuplicateRejected() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"原文\"}]");
        submitTranslation(docId, "s1", "en", "alice", "v3", 1, newRequestId());

        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<ApiResult>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                gate.await();
                return sign(docId, "s1", "en", "bob", 1, newRequestId());
            }));
        }
        gate.countDown();
        int success = 0;
        int rejected = 0;
        for (Future<ApiResult> future : futures) {
            int status = future.get(30, TimeUnit.SECONDS).status();
            if (status == 201) {
                success++;
            } else if (status == 422) {
                rejected++;
            }
        }
        pool.shutdown();
        assertThat(success).isEqualTo(1);
        assertThat(rejected).isEqualTo(threads - 1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM binding_signature WHERE document_id = ? AND signer = 'bob'",
                Integer.class, docId)).isEqualTo(1);
    }

    @Test
    @DisplayName("BINDING 发布与源文修订并发：只能提交一个一致的译文及签署版本，失败不留部分快照")
    void concurrentBindingPublishAndRevise() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"原文\"}]");
        submitTranslation(docId, "s1", "en", "alice", "v3", 1, newRequestId());
        sign(docId, "s1", "en", "jia", 1, newRequestId());
        sign(docId, "s1", "en", "yi", 1, newRequestId());
        // 当前草稿版本 2、发布版本 0；两份签署确认 V3/源文 V1

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        Future<ApiResult> publishFuture = pool.submit(() -> {
            gate.await();
            return publishLegal(docId, 2, 0, "BINDING", "en", newRequestId());
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
        assertThat(draftVersion).isEqualTo(3);
        assertThat(snapshots).isEqualTo(publishedVersion);

        if (publishResult.status() == 201) {
            // 发布先于修订：快照固化修订前正文与 V3 签署
            assertThat(publishedVersion).isEqualTo(1);
            ApiResult release = getJson("/api/documents/" + docId + "/releases/1");
            assertThat(release.body().get("legalStatus").asText()).isEqualTo("BINDING");
            assertThat(release.body().get("segments").get(0).get("sourceText").asText()).isEqualTo("原文");
            var translation = release.body().get("segments").get(0).get("translation");
            assertThat(translation.get("content").asText()).isEqualTo("v3");
            assertThat(translation.get("signers")).hasSize(2);
        } else {
            // 修订先于发布：期望草稿版本不符，409 且无快照、发布版本仍为 0
            assertThat(publishedVersion).isZero();
        }
    }

    @Test
    @DisplayName("BINDING 发布与译文修订并发：发布先成功则快照为旧版本；修订先成功则旧签署失效，发布 422 或 409")
    void concurrentBindingPublishAndTranslationRevision() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"原文\"}]");
        submitTranslation(docId, "s1", "en", "alice", "v3", 1, newRequestId());
        sign(docId, "s1", "en", "jia", 1, newRequestId());
        sign(docId, "s1", "en", "yi", 1, newRequestId());
        // 草稿版本 2

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        Future<ApiResult> publishFuture = pool.submit(() -> {
            gate.await();
            return publishLegal(docId, 2, 0, "BINDING", "en", newRequestId());
        });
        Future<ApiResult> resubmitFuture = pool.submit(() -> {
            gate.await();
            return submitTranslation(docId, "s1", "en", "alice", "v4", 1, newRequestId());
        });
        gate.countDown();
        ApiResult publishResult = publishFuture.get(30, TimeUnit.SECONDS);
        ApiResult resubmitResult = resubmitFuture.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        // 行锁串行化：译文修订必然成功并使草稿版本加一；发布要么先成功（201），要么读到草稿版本变化（409）
        assertThat(resubmitResult.status()).isEqualTo(200);
        assertThat(resubmitResult.body().get("translationVersion").asInt()).isEqualTo(2);
        assertThat(publishResult.status()).isIn(201, 409);

        Integer publishedVersion = jdbc.queryForObject(
                "SELECT published_version FROM document WHERE document_id = ?", Integer.class, docId);
        Integer snapshots = jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_snapshot WHERE document_id = ?", Integer.class, docId);
        assertThat(snapshots).isEqualTo(publishedVersion);
        if (publishResult.status() == 201) {
            assertThat(publishedVersion).isEqualTo(1);
            ApiResult release = getJson("/api/documents/" + docId + "/releases/1");
            assertThat(release.body().get("segments").get(0).get("translation")
                    .get("content").asText()).isEqualTo("v3");
        } else {
            // 修订先提交：期望草稿版本不符，409 且无快照、发布版本仍为 0
            assertThat(publishedVersion).isZero();
        }
    }
}
