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
 * 引文锚点并发边界测试：真实并发经门闩同时发起，设置超时并断言最终数据。
 */
class CitationConcurrencyTest extends AbstractIntegrationTest {

    private long prepare() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"原文一\"}]");
        submitTranslation(docId, "s1", "en", "alice", "AAA HELLO world text", 1, newRequestId());
        approve(docId, "s1", "en", "bob", 1, newRequestId());
        return docId;
    }

    @Test
    @DisplayName("并发登记不同引用标识：全部成功，锚点不交叉，草稿版本无丢失更新")
    void concurrentRegisterDistinctKeys() throws Exception {
        long docId = prepare();
        // HELLO [4,9) 与 world [10,15)，互不交叉
        int threads = 2;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<ApiResult>> futures = new ArrayList<>();
        futures.add(pool.submit(() -> {
            gate.await();
            return registerAnchors(docId, "s1", "en", "alice",
                    "[{\"citationKey\":\"K/HELLO\",\"rangeStart\":4,\"rangeEnd\":9,\"lockReason\":\"r1\"}]");
        }));
        futures.add(pool.submit(() -> {
            gate.await();
            return registerAnchors(docId, "s1", "en", "alice",
                    "[{\"citationKey\":\"K/WORLD\",\"rangeStart\":10,\"rangeEnd\":15,\"lockReason\":\"r2\"}]");
        }));
        gate.countDown();
        for (Future<ApiResult> future : futures) {
            assertThat(future.get(30, TimeUnit.SECONDS).status()).isEqualTo(201);
        }
        pool.shutdown();

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM citation_anchor WHERE document_id = ? AND status = 'LOCKED'",
                Integer.class, docId)).isEqualTo(2);
        // 建文档1 + 译文提交2 + 两次登记3,4
        assertThat(jdbc.queryForObject(
                "SELECT draft_version FROM document WHERE document_id = ?", Integer.class, docId))
                .isEqualTo(4);
    }

    @Test
    @DisplayName("并发登记同一引用标识：文档行锁串行裁决，恰好一个成功，其余 422 且不占事件")
    void concurrentRegisterSameKey() throws Exception {
        long docId = prepare();
        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<ApiResult>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                gate.await();
                return registerAnchors(docId, "s1", "en", "alice",
                        "[{\"citationKey\":\"K/SAME\",\"rangeStart\":4,\"rangeEnd\":9,\"lockReason\":\"r\"}]");
            }));
        }
        gate.countDown();
        int success = 0;
        int rejected = 0;
        for (Future<ApiResult> future : futures) {
            ApiResult result = future.get(30, TimeUnit.SECONDS);
            if (result.status() == 201) {
                success++;
            } else if (result.status() == 422) {
                assertThat(result.body().get("issues").get(0).get("code").asText())
                        .isEqualTo("ANCHOR_KEY_ALREADY_EXISTS");
                rejected++;
            } else {
                throw new AssertionError("未预期的状态码: " + result.status());
            }
        }
        pool.shutdown();
        assertThat(success).isEqualTo(1);
        assertThat(rejected).isEqualTo(threads - 1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM citation_anchor WHERE document_id = ?", Integer.class, docId))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM citation_anchor_event WHERE document_id = ?", Integer.class, docId))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("并发解除同一锚点：恰好一个成功，另一个 422，仅一条 RELEASED 事件")
    void concurrentRelease() throws Exception {
        long docId = prepare();
        ApiResult registered = registerAnchors(docId, "s1", "en", "alice",
                "[{\"citationKey\":\"K/1\",\"rangeStart\":4,\"rangeEnd\":9,\"lockReason\":\"r\"}]");
        long anchorId = registered.body().get("anchors").get(0).get("anchorId").asLong();

        int threads = 2;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<ApiResult>> futures = new ArrayList<>();
        futures.add(pool.submit(() -> {
            gate.await();
            return releaseAnchor(docId, anchorId, "carol", "legal", "法务carol解除");
        }));
        futures.add(pool.submit(() -> {
            gate.await();
            return releaseAnchor(docId, anchorId, "dave", "legal", "法务dave解除");
        }));
        gate.countDown();
        int success = 0;
        int rejected = 0;
        for (Future<ApiResult> future : futures) {
            int status = future.get(30, TimeUnit.SECONDS).status();
            if (status == 200) {
                success++;
            } else if (status == 422) {
                rejected++;
            } else {
                throw new AssertionError("未预期的状态码: " + status);
            }
        }
        pool.shutdown();
        assertThat(success).isEqualTo(1);
        assertThat(rejected).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM citation_anchor WHERE anchor_id = ? AND status = 'RELEASED'",
                Integer.class, anchorId)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM citation_anchor_event WHERE anchor_id = ? AND event_type = 'RELEASED'",
                Integer.class, anchorId)).isEqualTo(1);
    }

    @Test
    @DisplayName("并发修订同一段落并迁移同一锚点：串行提交，锚点最终区间一致、引用文本保留")
    void concurrentRevisionMigrateSameAnchor() throws Exception {
        long docId = prepare();
        ApiResult registered = registerAnchors(docId, "s1", "en", "alice",
                "[{\"citationKey\":\"K/1\",\"rangeStart\":4,\"rangeEnd\":9,\"lockReason\":\"r\"}]");
        long anchorId = registered.body().get("anchors").get(0).get("anchorId").asLong();

        // 两个并发修订都把 HELLO 放到 [0,5)，文本均保留
        int threads = 2;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<ApiResult>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                gate.await();
                return submitTranslationWithMappings(docId, "s1", "en", "alice",
                        "HELLO AAA world text", 1,
                        "[{\"anchorId\":" + anchorId + ",\"rangeStart\":0,\"rangeEnd\":5}]");
            }));
        }
        gate.countDown();
        for (Future<ApiResult> future : futures) {
            assertThat(future.get(30, TimeUnit.SECONDS).status()).isEqualTo(200);
        }
        pool.shutdown();

        assertThat(jdbc.queryForObject(
                "SELECT translation_version FROM translation WHERE document_id = ?", Integer.class, docId))
                .isEqualTo(3);
        assertThat(jdbc.queryForObject(
                "SELECT range_start FROM citation_anchor WHERE anchor_id = ?", Integer.class, anchorId))
                .isZero();
        assertThat(jdbc.queryForObject(
                "SELECT range_end FROM citation_anchor WHERE anchor_id = ?", Integer.class, anchorId))
                .isEqualTo(5);
        assertThat(jdbc.queryForObject(
                "SELECT anchor_text FROM citation_anchor WHERE anchor_id = ?", String.class, anchorId))
                .isEqualTo("HELLO");
    }
}
