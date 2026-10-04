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
 * 定点编辑并发边界测试：真实并发调用，门闩协调同时发起，设置超时并断言最终数据状态。
 */
class AnnotationConcurrencyTest extends AbstractIntegrationTest {

    @Test
    @DisplayName("并发定点编辑：同一期望译文版本仅一个成功，另一个 409，版本无丢失更新")
    void concurrentAnchoredEdits() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"原文\"}]");
        submitTranslation(docId, "s1", "en", "alice", "abc", 1, newRequestId());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        Future<ApiResult> editA = pool.submit(() -> {
            gate.await();
            return anchoredEdit(docId, "s1", "en", "alice", 1, 0, 0, "X", newRequestId());
        });
        Future<ApiResult> editB = pool.submit(() -> {
            gate.await();
            return anchoredEdit(docId, "s1", "en", "alice", 1, 3, 3, "Y", newRequestId());
        });
        gate.countDown();
        ApiResult resultA = editA.get(30, TimeUnit.SECONDS);
        ApiResult resultB = editB.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(List.of(resultA.status(), resultB.status())).containsExactlyInAnyOrder(200, 409);
        Integer version = jdbc.queryForObject(
                "SELECT translation_version FROM translation WHERE document_id = ? AND segment_id = 's1'",
                Integer.class, docId);
        assertThat(version).isEqualTo(2);
        String content = jdbc.queryForObject(
                "SELECT content FROM translation WHERE document_id = ? AND segment_id = 's1'",
                String.class, docId);
        assertThat(content).isIn("Xabc", "abcY");
    }

    @Test
    @DisplayName("并发同 requestId 定点编辑：仅执行一次，全部重放同一成功结果")
    void concurrentSameRequestIdEdit() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"原文\"}]");
        submitTranslation(docId, "s1", "en", "alice", "abc", 1, newRequestId());

        String requestId = newRequestId();
        String body = "{\"requestId\":\"" + requestId + "\",\"expectedTranslationVersion\":1,"
                + "\"start\":0,\"end\":0,\"newText\":\"X\"}";
        String url = "/api/documents/" + docId + "/segments/s1/translations/en/edit";
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
            assertThat(result.body().get("translationVersion").asInt()).isEqualTo(2);
        }
        Integer version = jdbc.queryForObject(
                "SELECT translation_version FROM translation WHERE document_id = ? AND segment_id = 's1'",
                Integer.class, docId);
        assertThat(version).isEqualTo(2);
        String content = jdbc.queryForObject(
                "SELECT content FROM translation WHERE document_id = ? AND segment_id = 's1'",
                String.class, docId);
        assertThat(content).isEqualTo("Xabc");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = ?", Integer.class, requestId))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("编辑与批注解决并发：同版本原子裁决，对应一个一致状态")
    void concurrentEditAndResolve() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"原文\"}]");
        submitTranslation(docId, "s1", "en", "alice", "abcdef", 1, newRequestId());
        ApiResult annotation = createAnnotation(docId, "s1", "en", "carol", 2, 4, "意见", newRequestId());
        long annotationId = annotation.body().get("annotationId").asLong();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        // 编辑：替换 [0,1) a→WXY（+2 码点），译文版本变为 2，批注平移
        Future<ApiResult> editFuture = pool.submit(() -> {
            gate.await();
            return anchoredEdit(docId, "s1", "en", "alice", 1, 0, 1, "WXY", newRequestId());
        });
        // 解决：期望译文版本 1
        Future<ApiResult> resolveFuture = pool.submit(() -> {
            gate.await();
            return resolveAnnotation(docId, "s1", "en", annotationId, "carol", 1, newRequestId());
        });
        gate.countDown();
        ApiResult editResult = editFuture.get(30, TimeUnit.SECONDS);
        ApiResult resolveResult = resolveFuture.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(editResult.status()).isEqualTo(200);
        // 解决要么先于编辑成功（版本 1 时尚未编辑），要么因版本已变为 2 而 409
        assertThat(resolveResult.status()).isIn(200, 409);

        Integer version = jdbc.queryForObject(
                "SELECT translation_version FROM translation WHERE document_id = ? AND segment_id = 's1'",
                Integer.class, docId);
        assertThat(version).isEqualTo(2);
        String status = jdbc.queryForObject(
                "SELECT status FROM annotation WHERE annotation_id = ?", String.class, annotationId);
        if (resolveResult.status() == 200) {
            // 解决先于编辑：批注已 RESOLVED，编辑不再迁移已解决批注
            assertThat(status).isEqualTo("RESOLVED");
        } else {
            // 编辑先于解决：批注被平移为 [4,6) 且仍 OPEN，解决因版本不符 409
            assertThat(status).isEqualTo("OPEN");
            Integer start = jdbc.queryForObject(
                    "SELECT start_offset FROM annotation WHERE annotation_id = ?", Integer.class,
                    annotationId);
            assertThat(start).isEqualTo(4);
        }
    }
}