package com.example.starter.translation;

import com.example.starter.translation.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 并发与幂等边界测试：真实并发请求 + 真实 H2 事务，协调起跑并设置超时，
 * 断言响应与最终数据状态。
 */
class ConcurrencyTest extends IntegrationTestBase {

    @Test
    void 并发_发布与源文修订_对应一个一致状态() throws Exception {
        prepareApprovableDoc("doc-c1", "c1");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            Future<MvcResult> publishFuture = pool.submit(() -> {
                ready.countDown();
                start.await();
                return publish("doc-c1", "c1-p", 2, 0).andReturn();
            });
            Future<MvcResult> reviseFuture = pool.submit(() -> {
                ready.countDown();
                start.await();
                return reviseSource("doc-c1", "s1", "c1-r", "Hello v2", "alice").andReturn();
            });
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            MvcResult publishResult = publishFuture.get(30, TimeUnit.SECONDS);
            MvcResult reviseResult = reviseFuture.get(30, TimeUnit.SECONDS);

            int publishStatus = publishResult.getResponse().getStatus();
            assertThat(reviseResult.getResponse().getStatus()).isEqualTo(200);

            Integer draftVersion = jdbc.queryForObject(
                    "SELECT draft_version FROM document WHERE document_id = 'doc-c1'", Integer.class);
            Integer publishedVersion = jdbc.queryForObject(
                    "SELECT published_version FROM document WHERE document_id = 'doc-c1'", Integer.class);
            Integer sourceVersion = jdbc.queryForObject(
                    "SELECT source_version FROM segment WHERE document_id = 'doc-c1' AND segment_id = 's1'",
                    Integer.class);
            // 两种串行结局之一：发布先于修订（快照保留旧源文），或修订先于发布（版本冲突 409、无快照）。
            assertThat(draftVersion).isEqualTo(3);
            assertThat(sourceVersion).isEqualTo(2);
            if (publishStatus == 200) {
                assertThat(publishedVersion).isEqualTo(1);
                getJson("/api/documents/doc-c1/publications/1")
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.segments[0].sourceText").value("Hello"))
                        .andExpect(jsonPath("$.segments[0].sourceVersion").value(1))
                        .andExpect(jsonPath("$.segments[0].translations.length()").value(1));
            } else {
                assertThat(publishStatus).isEqualTo(409);
                assertThat(publishedVersion).isZero();
                getJson("/api/documents/doc-c1/publications/1").andExpect(status().isNotFound());
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM publication", Integer.class)).isZero();
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void 并发_同requestId同参提交译文_只生效一次且响应一致() throws Exception {
        createDoc("c2-c", "doc-c2", List.of("en"), List.of(seg("s1", "Hello")))
                .andExpect(status().isOk());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            Future<MvcResult> first = pool.submit(() -> {
                ready.countDown();
                start.await();
                return submitTranslation("doc-c2", "s1", "c2-t", "en", "Hello EN", 1, "bob").andReturn();
            });
            Future<MvcResult> second = pool.submit(() -> {
                ready.countDown();
                start.await();
                return submitTranslation("doc-c2", "s1", "c2-t", "en", "Hello EN", 1, "bob").andReturn();
            });
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            MvcResult r1 = first.get(30, TimeUnit.SECONDS);
            MvcResult r2 = second.get(30, TimeUnit.SECONDS);

            assertThat(r1.getResponse().getStatus()).isEqualTo(200);
            assertThat(r2.getResponse().getStatus()).isEqualTo(200);
            assertThat(r2.getResponse().getContentAsString())
                    .isEqualTo(r1.getResponse().getContentAsString());
            // 去重记录仅一条；译文版本为 1；草稿版本只加一（建文档 1 + 提交 1 = 2）。
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM request_log WHERE request_id = 'c2-t'", Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject(
                    "SELECT translation_version FROM translation WHERE document_id = 'doc-c2' AND segment_id = 's1' AND language = 'en'",
                    Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject(
                    "SELECT draft_version FROM document WHERE document_id = 'doc-c2'", Integer.class))
                    .isEqualTo(2);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void 并发_同requestId发布_只生成一份快照() throws Exception {
        prepareApprovableDoc("doc-c3", "c3");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            Future<MvcResult> first = pool.submit(() -> {
                ready.countDown();
                start.await();
                return publish("doc-c3", "c3-p", 2, 0).andReturn();
            });
            Future<MvcResult> second = pool.submit(() -> {
                ready.countDown();
                start.await();
                return publish("doc-c3", "c3-p", 2, 0).andReturn();
            });
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            MvcResult r1 = first.get(30, TimeUnit.SECONDS);
            MvcResult r2 = second.get(30, TimeUnit.SECONDS);

            assertThat(r1.getResponse().getStatus()).isEqualTo(200);
            assertThat(r2.getResponse().getStatus()).isEqualTo(200);
            assertThat(r2.getResponse().getContentAsString())
                    .isEqualTo(r1.getResponse().getContentAsString());
            // 仅一份快照、发布版本只推进一次。
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM publication", Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject(
                    "SELECT published_version FROM document WHERE document_id = 'doc-c3'", Integer.class))
                    .isEqualTo(1);
            getJson("/api/documents/doc-c3/publications/1")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.segments.length()").value(1))
                    .andExpect(jsonPath("$.segments[0].translations.length()").value(1));
        } finally {
            pool.shutdownNow();
        }
    }
}
