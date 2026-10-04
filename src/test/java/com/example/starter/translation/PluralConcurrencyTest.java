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
 * 复数流程的并发与幂等边界测试：真实并发调用，门闩协调同时发起，设超时并断言最终数据。
 */
class PluralConcurrencyTest extends AbstractIntegrationTest {

    private static final String PLURAL_SEGMENT =
            "[{\"segmentId\":\"p1\",\"sourceText\":\"{count} files\",\"plural\":true}]";

    @Test
    @DisplayName("并发提交不同类别：同一复数段落各类别并发提交全部成功，草稿版本无丢失更新")
    void concurrentCategorySubmits() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\",\"zh\"]", PLURAL_SEGMENT);
        ExecutorService pool = Executors.newFixedThreadPool(3);
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<ApiResult>> futures = new ArrayList<>();
        futures.add(pool.submit(() -> {
            gate.await();
            return submitPlural(docId, "p1", "en", "alice", 1,
                    "[{\"category\":\"one\",\"content\":\"one file\"}]", newRequestId());
        }));
        futures.add(pool.submit(() -> {
            gate.await();
            return submitPlural(docId, "p1", "en", "bob", 1,
                    "[{\"category\":\"other\",\"content\":\"many files\"}]", newRequestId());
        }));
        futures.add(pool.submit(() -> {
            gate.await();
            return submitPlural(docId, "p1", "zh", "carol", 1,
                    "[{\"category\":\"other\",\"content\":\"文件\"}]", newRequestId());
        }));
        gate.countDown();
        for (Future<ApiResult> future : futures) {
            assertThat(future.get(30, TimeUnit.SECONDS).status()).isEqualTo(200);
        }
        pool.shutdown();

        Integer draftVersion = jdbc.queryForObject(
                "SELECT draft_version FROM document WHERE document_id = ?", Integer.class, docId);
        assertThat(draftVersion).isEqualTo(4);
        Integer rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM plural_translation WHERE document_id = ?", Integer.class, docId);
        assertThat(rows).isEqualTo(3);
    }

    @Test
    @DisplayName("并发同 requestId 同参复数提交：仅执行一次，全部重放同一结果，不泄露唯一键异常")
    void concurrentSameRequestIdPluralSubmit() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]", PLURAL_SEGMENT);
        String requestId = newRequestId();
        String body = "{\"requestId\":\"" + requestId + "\",\"sourceVersion\":1,"
                + "\"categories\":[{\"category\":\"one\",\"content\":\"one file\"},"
                + "{\"category\":\"other\",\"content\":\"many files\"}]}";
        String url = "/api/documents/" + docId + "/segments/p1/plural-translations/en";
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<ApiResult>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                gate.await();
                return putJson(url, body, "alice");
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
            assertThat(result.body().toString()).isEqualTo(results.get(0).body().toString());
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM request_log", Integer.class)).isEqualTo(2);
        Integer oneVersion = jdbc.queryForObject("SELECT translation_version FROM plural_translation "
                        + "WHERE document_id = ? AND segment_id = 'p1' AND language = 'en' AND category = 'one'",
                Integer.class, docId);
        assertThat(oneVersion).isEqualTo(1);
        Integer draftVersion = jdbc.queryForObject(
                "SELECT draft_version FROM document WHERE document_id = ?", Integer.class, docId);
        assertThat(draftVersion).isEqualTo(2);
    }

    @Test
    @DisplayName("发布与类别修订并发：对应一个一致状态，失败不产生部分快照")
    void concurrentPublishAndCategoryRevision() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]", PLURAL_SEGMENT);
        submitPlural(docId, "p1", "en", "alice", 1,
                "[{\"category\":\"one\",\"content\":\"one file\"},"
                        + "{\"category\":\"other\",\"content\":\"many files\"}]", newRequestId());
        approvePlural(docId, "p1", "en", "bob", "one", 1, newRequestId());
        approvePlural(docId, "p1", "en", "bob", "other", 1, newRequestId());
        // 当前草稿版本 2、发布版本 0

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        Future<ApiResult> publishFuture = pool.submit(() -> {
            gate.await();
            return publish(docId, 2, 0, newRequestId());
        });
        Future<ApiResult> reviseFuture = pool.submit(() -> {
            gate.await();
            return submitPlural(docId, "p1", "en", "alice", 1,
                    "[{\"category\":\"one\",\"content\":\"exactly one file\"}]", newRequestId());
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

        // 类别修订必然成功并使草稿版本加一；发布版本与快照数量一致（无部分快照）
        assertThat(draftVersion).isEqualTo(3);
        assertThat(snapshots).isEqualTo(publishedVersion);
        if (publishResult.status() == 201) {
            // 发布先于修订：快照冻结修订前的类别文本
            assertThat(publishedVersion).isEqualTo(1);
            ApiResult release = getJson("/api/documents/" + docId + "/releases/1");
            var cats = release.body().get("segments").get(0).get("translations").get(0)
                    .get("categories");
            assertThat(cats.get(0).get("category").asText()).isEqualTo("one");
            assertThat(cats.get(0).get("content").asText()).isEqualTo("one file");
        } else {
            assertThat(publishedVersion).isZero();
        }
    }
}