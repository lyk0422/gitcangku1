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
 * 字幕文档并发测试：真实并发调用，门闩协调同时发起，设超时并断言最终数据状态。
 */
class SubtitleConcurrencyTest extends AbstractIntegrationTest {

    private static final long ZERO = 1700000000000L;

    @Test
    @DisplayName("并发提交字幕译文：多段并发全部成功，草稿版本无丢失更新")
    void concurrentSubtitleSubmits() throws Exception {
        int segmentCount = 6;
        StringBuilder segments = new StringBuilder("[");
        for (int i = 1; i <= segmentCount; i++) {
            if (i > 1) {
                segments.append(',');
            }
            segments.append("{\"segmentId\":\"s").append(i).append("\",\"sourceText\":\"句").append(i)
                    .append("\",\"zeroPointMs\":").append(ZERO)
                    .append(",\"startMs\":").append((i - 1) * 1000L)
                    .append(",\"endMs\":").append(i * 1000L).append('}');
        }
        segments.append(']');
        String body = "{\"requestId\":\"" + newRequestId() + "\",\"targetLanguages\":[\"en\"],"
                + "\"segments\":" + segments + ",\"mode\":\"subtitle\"}";
        ApiResult created = postJson("/api/documents", body, "alice");
        assertThat(created.status()).isEqualTo(201);
        long docId = created.body().get("documentId").asLong();

        ExecutorService pool = Executors.newFixedThreadPool(segmentCount);
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<ApiResult>> futures = new ArrayList<>();
        for (int i = 1; i <= segmentCount; i++) {
            final String segmentId = "s" + i;
            futures.add(pool.submit(() -> {
                gate.await();
                return submitTranslation(docId, segmentId, "en", "alice", "译文" + segmentId, 1, newRequestId());
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
    @DisplayName("并发同 requestId 字幕建文档：仅执行一次，全部重放同一成功结果，不暴露唯一键错误")
    void concurrentSameRequestIdSubtitleCreate() throws Exception {
        String requestId = newRequestId();
        String body = "{\"requestId\":\"" + requestId + "\",\"targetLanguages\":[\"en\"],"
                + "\"segments\":[{\"segmentId\":\"s1\",\"sourceText\":\"句\",\"zeroPointMs\":" + ZERO
                + ",\"startMs\":0,\"endMs\":1000}],\"mode\":\"subtitle\"}";
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<ApiResult>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                gate.await();
                return postJson("/api/documents", body, "alice");
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
}
