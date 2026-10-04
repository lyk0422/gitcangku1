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
 * 复数类别流程的并发与幂等边界测试：真实并发调用，门闩协调同时发起，设置超时并断言最终数据状态。
 */
class PluralConcurrencyTest extends AbstractIntegrationTest {

    private ApiResult submitCategory(long docId, String category, String actorId, String content,
                                     String requestId) throws Exception {
        String body = "{\"requestId\":\"" + requestId + "\",\"content\":\"" + content
                + "\",\"sourceVersion\":1}";
        return putJson("/api/documents/" + docId + "/segments/s1/translations/en/categories/" + category,
                body, actorId);
    }

    private ApiResult approveCategory(long docId, String category, String actorId, int translationVersion,
                                      String requestId) throws Exception {
        String body = "{\"requestId\":\"" + requestId + "\",\"translationVersion\":" + translationVersion + "}";
        return postJson("/api/documents/" + docId + "/segments/s1/translations/en/categories/" + category
                + "/approve", body, actorId);
    }

    private long newPluralDocument() throws Exception {
        return createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"{count} files\",\"plural\":true}]");
    }

    @Test
    @DisplayName("并发提交不同类别：one 与 other 同时提交全部成功，草稿版本无丢失更新")
    void concurrentCategorySubmits() throws Exception {
        long docId = newPluralDocument();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        Future<ApiResult> oneFuture = pool.submit(() -> {
            gate.await();
            return submitCategory(docId, "one", "alice", "one file", newRequestId());
        });
        Future<ApiResult> otherFuture = pool.submit(() -> {
            gate.await();
            return submitCategory(docId, "other", "bob", "{count} files", newRequestId());
        });
        gate.countDown();
        assertThat(oneFuture.get(30, TimeUnit.SECONDS).status()).isEqualTo(200);
        assertThat(otherFuture.get(30, TimeUnit.SECONDS).status()).isEqualTo(200);
        pool.shutdown();

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM plural_translation WHERE document_id = ?",
                Integer.class, docId)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT draft_version FROM document WHERE document_id = ?",
                Integer.class, docId)).isEqualTo(3);
    }

    @Test
    @DisplayName("并发同 requestId 同参类别提交：仅执行一次，全部重放同一成功结果")
    void concurrentSameRequestIdCategorySubmit() throws Exception {
        long docId = newPluralDocument();
        String requestId = newRequestId();
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<ApiResult>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                gate.await();
                return submitCategory(docId, "one", "alice", "one file", requestId);
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
            assertThat(result.body().get("translationVersion").asInt()).isEqualTo(1);
            assertThat(result.body().toString()).isEqualTo(results.get(0).body().toString());
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM plural_translation WHERE document_id = ?",
                Integer.class, docId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM request_log WHERE request_id = ?",
                Integer.class, requestId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT draft_version FROM document WHERE document_id = ?",
                Integer.class, docId)).isEqualTo(2);
    }

    @Test
    @DisplayName("发布与类别修订并发：对应一个一致状态，失败不产生部分快照")
    void concurrentPublishAndCategoryRevise() throws Exception {
        long docId = newPluralDocument();
        submitCategory(docId, "one", "alice", "one file", newRequestId());
        submitCategory(docId, "other", "alice", "{count} files", newRequestId());
        approveCategory(docId, "one", "bob", 1, newRequestId());
        approveCategory(docId, "other", "bob", 1, newRequestId());
        // 当前草稿版本 3、发布版本 0

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        Future<ApiResult> publishFuture = pool.submit(() -> {
            gate.await();
            return publish(docId, 3, 0, newRequestId());
        });
        Future<ApiResult> reviseFuture = pool.submit(() -> {
            gate.await();
            return submitCategory(docId, "one", "alice", "exactly one file", newRequestId());
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

        // 类别修订必然成功并使草稿版本加一；发布版本与快照数量必须一致（无部分快照）
        assertThat(draftVersion).isEqualTo(4);
        assertThat(snapshots).isEqualTo(publishedVersion);
        if (publishResult.status() == 201) {
            // 发布先于修订：快照中 one 为修订前文本
            assertThat(publishedVersion).isEqualTo(1);
            ApiResult release = getJson("/api/documents/" + docId + "/releases/1");
            assertThat(release.status()).isEqualTo(200);
            assertThat(release.body().get("segments").get(0).get("translations").get(0)
                    .get("categories").get(0).get("content").asText()).isEqualTo("one file");
        } else {
            // 修订先于发布：期望草稿版本不符，发布 409 且无快照
            assertThat(publishedVersion).isZero();
        }
    }
}