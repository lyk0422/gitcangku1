package com.example.starter.translation;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
 * 结构修订并发边界测试：真实并发 + 门闩协调 + 超时，断言终态无混合文档版本、
 * 同 changeKey 仅一个事务成功、同 requestId 同参重放。基于真实 H2 行锁与事务验证。
 */
class StructureConcurrencyTest extends AbstractIntegrationTest {

    /** 单段、en 单语、译文已批准的文档：草稿版本 2、发布版本 0、术语版本 0。 */
    private long preparedSingleSegment() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"原文\"}]");
        submitTranslation(docId, "s1", "en", "alice", "hello", 1, newRequestId());
        approve(docId, "s1", "en", "bob", 1, newRequestId());
        return docId;
    }

    /** 拆分 s1(v1) → n1,n2 的结构修订请求 JSON。 */
    private String splitBody(String requestId, String changeKey, int expectedDocumentVersion) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("requestId", requestId);
        body.put("changeKey", changeKey);
        body.put("expectedDocumentVersion", expectedDocumentVersion);
        ArrayNode newSegments = body.putArray("newSegments");
        newSegments.addObject().put("newSegmentId", "n1").put("sourceText", "新段一");
        newSegments.addObject().put("newSegmentId", "n2").put("sourceText", "新段二");
        ArrayNode oldSegments = body.putArray("oldSegments");
        oldSegments.addObject().put("segmentId", "s1").put("sourceVersion", 1);
        ArrayNode termVersions = body.putArray("languageTermVersions");
        termVersions.addObject().put("language", "en").put("termVersion", 0);
        ArrayNode mappings = body.putArray("mappings");
        ObjectNode languageMapping = mappings.addObject();
        languageMapping.put("language", "en");
        ArrayNode segmentMappings = languageMapping.putArray("mappings");
        segmentMappings.addObject().put("newSegmentId", "n1")
                .putArray("oldSegmentIds").add("s1");
        segmentMappings.addObject().put("newSegmentId", "n2")
                .putArray("oldSegmentIds").add("s1");
        return body.toString();
    }

    @Test
    @DisplayName("结构修订与源文修订并发：仅一种终态，草稿版本一致，无新旧段混合结构")
    void concurrentStructureAndSourceRevise() throws Exception {
        long docId = preparedSingleSegment();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        Future<ApiResult> structureFuture = pool.submit(() -> {
            gate.await();
            return postJson("/api/documents/" + docId + "/structure-changes",
                    splitBody(newRequestId(), "change-concurrent-1", 2));
        });
        Future<ApiResult> reviseFuture = pool.submit(() -> {
            gate.await();
            return putJson("/api/documents/" + docId + "/segments/s1/source",
                    "{\"requestId\":\"" + newRequestId() + "\",\"sourceText\":\"修订原文\"}");
        });
        gate.countDown();
        ApiResult structureResult = structureFuture.get(30, TimeUnit.SECONDS);
        ApiResult reviseResult = reviseFuture.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        Integer draftVersion = jdbc.queryForObject(
                "SELECT draft_version FROM document WHERE document_id = ?", Integer.class, docId);
        assertThat(draftVersion).isEqualTo(3);
        Integer structureChanges = jdbc.queryForObject(
                "SELECT COUNT(*) FROM structure_change WHERE document_id = ?", Integer.class, docId);

        if (structureResult.status() == 201) {
            // 结构修订先成功：源文修订随后找不到当前段（已 SUPERSEDED）→ 404
            assertThat(reviseResult.status()).isEqualTo(404);
            assertThat(structureChanges).isEqualTo(1);
            assertThat(currentSegmentIds(docId)).containsExactly("n1", "n2");
            assertThat(jdbc.queryForObject(
                    "SELECT source_version FROM segment WHERE document_id = ? AND segment_id = 's1'",
                    Integer.class, docId)).isEqualTo(1);
        } else {
            // 源文修订先成功：结构修订期望版本不符 → 409
            assertThat(structureResult.status()).isEqualTo(409);
            assertThat(reviseResult.status()).isEqualTo(200);
            assertThat(structureChanges).isZero();
            assertThat(currentSegmentIds(docId)).containsExactly("s1");
            assertThat(jdbc.queryForObject(
                    "SELECT source_version FROM segment WHERE document_id = ? AND segment_id = 's1'",
                    Integer.class, docId)).isEqualTo(2);
        }
    }

    @Test
    @DisplayName("结构修订与译文提交并发：不产生新段译文，终态要么拆分成功要么提交成功，草稿版本无丢失更新")
    void concurrentStructureAndTranslationSubmit() throws Exception {
        long docId = preparedSingleSegment();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        Future<ApiResult> structureFuture = pool.submit(() -> {
            gate.await();
            return postJson("/api/documents/" + docId + "/structure-changes",
                    splitBody(newRequestId(), "change-concurrent-2", 2));
        });
        Future<ApiResult> submitFuture = pool.submit(() -> {
            gate.await();
            return submitTranslation(docId, "s1", "en", "alice", "hello v2", 1, newRequestId());
        });
        gate.countDown();
        ApiResult structureResult = structureFuture.get(30, TimeUnit.SECONDS);
        ApiResult submitResult = submitFuture.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(jdbc.queryForObject(
                "SELECT draft_version FROM document WHERE document_id = ?", Integer.class, docId)).isEqualTo(3);
        if (structureResult.status() == 201) {
            // 结构修订先成功：旧段已淘汰，译文提交 404，新段只有 REFERENCE 候选而无译文
            assertThat(submitResult.status()).isEqualTo(404);
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM translation WHERE document_id = ? AND segment_id IN ('n1','n2')",
                    Integer.class, docId)).isZero();
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM reference_candidate WHERE document_id = ?", Integer.class, docId))
                    .isEqualTo(2);
        } else {
            // 译文提交先成功：结构修订 409
            assertThat(structureResult.status()).isEqualTo(409);
            assertThat(submitResult.status()).isEqualTo(200);
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM structure_change WHERE document_id = ?", Integer.class, docId)).isZero();
        }
    }

    @Test
    @DisplayName("结构修订与发布并发：已发布快照数始终等于发布版本，不存在含未批准新段的混合快照")
    void concurrentStructureAndPublish() throws Exception {
        long docId = preparedSingleSegment();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        Future<ApiResult> structureFuture = pool.submit(() -> {
            gate.await();
            return postJson("/api/documents/" + docId + "/structure-changes",
                    splitBody(newRequestId(), "change-concurrent-3", 2));
        });
        Future<ApiResult> publishFuture = pool.submit(() -> {
            gate.await();
            return publish(docId, 2, 0, newRequestId());
        });
        gate.countDown();
        ApiResult structureResult = structureFuture.get(30, TimeUnit.SECONDS);
        ApiResult publishResult = publishFuture.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        Integer publishedVersion = jdbc.queryForObject(
                "SELECT published_version FROM document WHERE document_id = ?", Integer.class, docId);
        Integer snapshots = jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_snapshot WHERE document_id = ?", Integer.class, docId);
        assertThat(snapshots).isEqualTo(publishedVersion);
        // 发布不推高草稿版本：结构修订必然成功；发布要么先于结构修订成功（快照为旧结构），
        // 要么因草稿版本被推高而 409，两种终态均无混合版本。
        assertThat(structureResult.status()).isEqualTo(201);
        if (publishResult.status() == 201) {
            assertThat(publishedVersion).isEqualTo(1);
            ApiResult release = getJson("/api/documents/" + docId + "/releases/1");
            assertThat(release.body().get("segments")).hasSize(1);
            assertThat(release.body().get("segments").get(0).get("segmentId").asText()).isEqualTo("s1");
        } else {
            assertThat(publishResult.status()).isEqualTo(409);
            assertThat(publishedVersion).isZero();
        }
        assertThat(currentSegmentIds(docId)).containsExactly("n1", "n2");
    }

    @Test
    @DisplayName("结构修订与术语更新并发：术语版本与结构状态各自一致，失败方 409，无部分血缘")
    void concurrentStructureAndTermUpdate() throws Exception {
        long docId = preparedSingleSegment();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        Future<ApiResult> structureFuture = pool.submit(() -> {
            gate.await();
            return postJson("/api/documents/" + docId + "/structure-changes",
                    splitBody(newRequestId(), "change-concurrent-4", 2));
        });
        Future<ApiResult> termsFuture = pool.submit(() -> {
            gate.await();
            return updateTerms(docId, 0,
                    "[{\"sourceTerm\":\"原文\",\"language\":\"en\",\"requiredTranslation\":\"source\"}]",
                    newRequestId());
        });
        gate.countDown();
        ApiResult structureResult = structureFuture.get(30, TimeUnit.SECONDS);
        ApiResult termsResult = termsFuture.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        // 术语更新不校验草稿版本，必然成功；结构修订是否成功决定草稿版本是 4 还是 3
        assertThat(termsResult.status()).isEqualTo(201);
        assertThat(jdbc.queryForObject(
                "SELECT term_version FROM document WHERE document_id = ?", Integer.class, docId)).isEqualTo(1);
        Integer draftVersion = jdbc.queryForObject(
                "SELECT draft_version FROM document WHERE document_id = ?", Integer.class, docId);
        if (structureResult.status() == 201) {
            assertThat(draftVersion).isEqualTo(4);
            assertThat(currentSegmentIds(docId)).containsExactly("n1", "n2");
        } else {
            // 术语更新先推高草稿版本：结构修订 409，无任何部分新段与血缘
            assertThat(structureResult.status()).isEqualTo(409);
            assertThat(draftVersion).isEqualTo(3);
            assertThat(currentSegmentIds(docId)).containsExactly("s1");
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM structure_change WHERE document_id = ?", Integer.class, docId)).isZero();
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM source_lineage WHERE document_id = ?", Integer.class, docId)).isZero();
        }
    }

    @Test
    @DisplayName("并发相同 changeKey（不同 requestId）：仅一个结构修订成功，其余 409，新段与草稿版本唯一")
    void concurrentSameChangeKey() throws Exception {
        long docId = preparedSingleSegment();
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<ApiResult>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                gate.await();
                return postJson("/api/documents/" + docId + "/structure-changes",
                        splitBody(newRequestId(), "change-same-key", 2));
            }));
        }
        gate.countDown();
        int success = 0;
        int conflict = 0;
        for (Future<ApiResult> future : futures) {
            int status = future.get(30, TimeUnit.SECONDS).status();
            assertThat(status).isIn(201, 409);
            if (status == 201) {
                success++;
            } else {
                conflict++;
            }
        }
        pool.shutdown();

        assertThat(success).isEqualTo(1);
        assertThat(conflict).isEqualTo(threads - 1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM structure_change WHERE document_id = ?", Integer.class, docId)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM segment WHERE document_id = ? AND status = 'CURRENT'",
                Integer.class, docId)).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT draft_version FROM document WHERE document_id = ?", Integer.class, docId)).isEqualTo(3);
    }

    @Test
    @DisplayName("并发相同 requestId 与完全一致的有序映射：全部重放同一成功结果，结构修订只执行一次")
    void concurrentSameRequestIdReplay() throws Exception {
        long docId = preparedSingleSegment();
        String requestId = newRequestId();
        String body = splitBody(requestId, "change-same-request", 2);
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<ApiResult>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                gate.await();
                return postJson("/api/documents/" + docId + "/structure-changes", body);
            }));
        }
        gate.countDown();
        for (Future<ApiResult> future : futures) {
            ApiResult result = future.get(30, TimeUnit.SECONDS);
            assertThat(result.status()).isEqualTo(201);
            assertThat(result.body().get("draftVersion").asInt()).isEqualTo(3);
        }
        pool.shutdown();

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = ?", Integer.class, requestId)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM structure_change WHERE document_id = ?", Integer.class, docId)).isEqualTo(1);
        assertThat(currentSegmentIds(docId)).containsExactly("n1", "n2");
    }

    private List<String> currentSegmentIds(long docId) {
        List<String> ids = new ArrayList<>();
        jdbc.query("SELECT segment_id FROM segment WHERE document_id = ? AND status = 'CURRENT' ORDER BY position",
                rs -> {
                    do {
                        ids.add(rs.getString(1));
                    } while (rs.next());
                }, docId);
        return ids;
    }
}
