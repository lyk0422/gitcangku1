package com.example.starter.translation;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 结构修订事务测试：拆分/合并主流程、整体回滚、幂等、跨语言血缘、
 * 发布资格失效与历史快照不变。基于真实 H2（MODE=MySQL）验证约束与事务边界。
 */
class StructureChangeApiTest extends AbstractIntegrationTest {

    private static final List<String> LANGS = List.of("en", "ja");

    /** 构造包含 3 个段落、2 种目标语言且全部译文已批准的文档，返回文档 ID，当前草稿版本 7。 */
    private long preparedDocument() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\",\"ja\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"原文一\"},"
                        + "{\"segmentId\":\"s2\",\"sourceText\":\"原文二\"},"
                        + "{\"segmentId\":\"s3\",\"sourceText\":\"原文三\"}]");
        for (String seg : List.of("s1", "s2", "s3")) {
            submitTranslation(docId, seg, "en", "alice", "en-" + seg, 1, newRequestId());
            submitTranslation(docId, seg, "ja", "carol", "ja-" + seg, 1, newRequestId());
            approve(docId, seg, "en", "bob", 1, newRequestId());
            approve(docId, seg, "ja", "dave", 1, newRequestId());
        }
        return docId;
    }

    private ApiResult splitRequest(long docId, String requestId, String changeKey, int expectedVersion,
                                   List<String> newIds, String oldId, int oldVersion) throws Exception {
        List<Map<String, ?>> mappings = new ArrayList<>();
        for (String language : LANGS) {
            List<Map<String, ?>> segmentMappings = new ArrayList<>();
            for (String newId : newIds) {
                segmentMappings.add(Map.of("newSegmentId", newId, "oldSegmentIds", List.of(oldId)));
            }
            mappings.add(Map.of("language", language, "mappings", segmentMappings));
        }
        List<Map<String, ?>> newSegments = new ArrayList<>();
        for (String id : newIds) {
            newSegments.add(Map.of("newSegmentId", id, "sourceText", "新源-" + id));
        }
        return structureChange(docId, body(requestId, changeKey, expectedVersion,
                newSegments,
                List.of(Map.of("segmentId", oldId, "sourceVersion", oldVersion)),
                termVersions(),
                mappings));
    }

    private ApiResult mergeRequest(long docId, String requestId, String changeKey, int expectedVersion,
                                   String newId, List<String> oldIds, List<Integer> oldVersions) throws Exception {
        List<Map<String, ?>> newSegments = List.of(Map.of("newSegmentId", newId, "sourceText", "合并源文"));
        List<Map<String, ?>> oldSegments = new ArrayList<>();
        for (int i = 0; i < oldIds.size(); i++) {
            oldSegments.add(Map.of("segmentId", oldIds.get(i), "sourceVersion", oldVersions.get(i)));
        }
        List<Map<String, ?>> mappings = new ArrayList<>();
        for (String language : LANGS) {
            mappings.add(Map.of("language", language, "mappings",
                    List.of(Map.of("newSegmentId", newId, "oldSegmentIds", oldIds))));
        }
        return structureChange(docId, body(requestId, changeKey, expectedVersion, newSegments, oldSegments,
                termVersions(), mappings));
    }

    private ApiResult structureChange(long docId, String jsonBody) throws Exception {
        return postJson("/api/documents/" + docId + "/structure-changes", jsonBody);
    }

    private String body(String requestId, String changeKey, int expectedVersion,
                        List<? extends Map<String, ?>> newSegments, List<? extends Map<String, ?>> oldSegments,
                        List<? extends Map<String, ?>> languageTermVersions,
                        List<? extends Map<String, ?>> mappings) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("changeKey", changeKey);
        body.put("expectedDocumentVersion", expectedVersion);
        body.put("newSegments", newSegments);
        body.put("oldSegments", oldSegments);
        body.put("languageTermVersions", languageTermVersions);
        body.put("mappings", mappings);
        return objectMapper.writeValueAsString(body);
    }

    @Test
    @DisplayName("拆分主流程：旧段 SUPERSEDED、新段按序就位、草稿版本原子加一，源段与跨语言血缘完整")
    void splitSuccess() throws Exception {
        long docId = preparedDocument();
        ApiResult result = splitRequest(docId, newRequestId(), "change-split-1", 7,
                List.of("n1", "n2"), "s1", 1);
        assertThat(result.status()).isEqualTo(201);
        assertThat(result.body().get("changeType").asText()).isEqualTo("SPLIT");
        assertThat(result.body().get("draftVersion").asInt()).isEqualTo(8);
        assertThat(result.body().get("newSegmentIds").toString()).contains("n1", "n2");

        // 当前结构顺序：n1,n2,s2,s3；旧段 s1 已淘汰
        ApiResult structure = getJson("/api/documents/" + docId + "/structure");
        assertThat(structure.status()).isEqualTo(200);
        assertThat(structure.body().get("draftVersion").asInt()).isEqualTo(8);
        List<String> order = new ArrayList<>();
        structure.body().get("segments").forEach(s -> order.add(s.get("segmentId").asText()));
        assertThat(order).containsExactly("n1", "n2", "s2", "s3");
        assertThat(jdbc.queryForObject(
                "SELECT status FROM segment WHERE document_id = ? AND segment_id = 's1'",
                String.class, docId)).isEqualTo("SUPERSEDED");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM segment WHERE document_id = ? AND status = 'CURRENT'",
                Integer.class, docId)).isEqualTo(4);

        // 源段双向血缘：两个新段均按序来源 s1
        ApiResult lineage = getJson("/api/documents/" + docId + "/lineage");
        assertThat(lineage.status()).isEqualTo(200);
        assertThat(lineage.body().get("sourceLineage")).hasSize(2);
        JsonNode enCandidates = findLanguage(lineage.body(), "en").get("candidates");
        JsonNode jaCandidates = findLanguage(lineage.body(), "ja").get("candidates");
        assertThat(enCandidates).hasSize(2);
        assertThat(jaCandidates).hasSize(2);
        JsonNode n1En = findCandidate(enCandidates, "n1");
        assertThat(n1En.get("content").asText()).isEqualTo("en-s1");
        assertThat(n1En.get("fragmentBoundaries").toString()).isEqualTo("[5]");
        assertThat(n1En.get("sources")).hasSize(1);
        assertThat(n1En.get("sources").get(0).get("oldSegmentId").asText()).isEqualTo("s1");
        assertThat(findCandidate(jaCandidates, "n2").get("content").asText()).isEqualTo("ja-s1");
    }

    @Test
    @DisplayName("合并主流程：连续旧段合为一段，REFERENCE 候选按序拼接并记录片段边界，位置重排")
    void mergeSuccess() throws Exception {
        long docId = preparedDocument();
        ApiResult result = mergeRequest(docId, newRequestId(), "change-merge-1", 7,
                "m1", List.of("s1", "s2"), List.of(1, 1));
        assertThat(result.status()).isEqualTo(201);
        assertThat(result.body().get("changeType").asText()).isEqualTo("MERGE");
        assertThat(result.body().get("draftVersion").asInt()).isEqualTo(8);

        ApiResult structure = getJson("/api/documents/" + docId + "/structure");
        List<String> order = new ArrayList<>();
        structure.body().get("segments").forEach(s -> order.add(s.get("segmentId").asText()));
        assertThat(order).containsExactly("m1", "s3");

        ApiResult m1Lineage = getJson("/api/documents/" + docId + "/lineage?segmentId=m1");
        assertThat(m1Lineage.status()).isEqualTo(200);
        assertThat(m1Lineage.body().get("sourceLineage")).hasSize(2);
        assertThat(m1Lineage.body().get("sourceLineage").get(0).get("oldSegmentId").asText()).isEqualTo("s1");
        assertThat(m1Lineage.body().get("sourceLineage").get(1).get("oldSegmentId").asText()).isEqualTo("s2");
        JsonNode enCandidate = findCandidate(findLanguage(m1Lineage.body(), "en").get("candidates"), "m1");
        assertThat(enCandidate.get("content").asText()).isEqualTo("en-s1en-s2");
        assertThat(enCandidate.get("fragmentBoundaries").toString()).isEqualTo("[5,10]");
        JsonNode jaCandidate = findCandidate(findLanguage(m1Lineage.body(), "ja").get("candidates"), "m1");
        assertThat(jaCandidate.get("content").asText()).isEqualTo("ja-s1ja-s2");
        assertThat(jaCandidate.get("fragmentBoundaries").toString()).isEqualTo("[5,10]");
    }

    @Test
    @DisplayName("失败分支：版本冲突 409；非连续合并/混合操作/映射缺漏重复/语言不完整/旧译文缺失 422")
    void validationFailures() throws Exception {
        long docId = preparedDocument();

        // 期望文档版本不符：409
        assertThat(splitRequest(docId, newRequestId(), "change-bad-doc", 99,
                List.of("n1", "n2"), "s1", 1).status()).isEqualTo(409);

        // 旧源段版本不符：409
        assertThat(splitRequest(docId, newRequestId(), "change-bad-src", 7,
                List.of("n1", "n2"), "s1", 9).status()).isEqualTo(409);

        // 合并非连续段：422
        assertThat(mergeRequest(docId, newRequestId(), "change-gap", 7,
                "m1", List.of("s1", "s3"), List.of(1, 1)).status()).isEqualTo(422);

        // 混合操作（2→2）：422
        ApiResult mixed = structureChange(docId, body(newRequestId(), "change-mixed", 7,
                List.of(Map.of("newSegmentId", "n1", "sourceText", "a"),
                        Map.of("newSegmentId", "n2", "sourceText", "b")),
                List.of(Map.of("segmentId", "s1", "sourceVersion", 1),
                        Map.of("segmentId", "s2", "sourceVersion", 1)),
                termVersions(), validSplitMappings(List.of("n1", "n2"), List.of("s1", "s2"))));
        assertThat(mixed.status()).isEqualTo(422);

        // 映射语言集合不完整（缺 ja）：422
        ApiResult missingLanguage = structureChange(docId, body(newRequestId(), "change-lang", 7,
                List.of(Map.of("newSegmentId", "n1", "sourceText", "a"),
                        Map.of("newSegmentId", "n2", "sourceText", "b")),
                List.of(Map.of("segmentId", "s1", "sourceVersion", 1)),
                termVersions(),
                List.of(Map.of("language", "en", "mappings", List.of(
                        Map.of("newSegmentId", "n1", "oldSegmentIds", List.of("s1")),
                        Map.of("newSegmentId", "n2", "oldSegmentIds", List.of("s1")))))));
        assertThat(missingLanguage.status()).isEqualTo(422);

        // 映射缺漏新段：422
        ApiResult missingMapping = structureChange(docId, body(newRequestId(), "change-miss", 7,
                List.of(Map.of("newSegmentId", "n1", "sourceText", "a"),
                        Map.of("newSegmentId", "n2", "sourceText", "b")),
                List.of(Map.of("segmentId", "s1", "sourceVersion", 1)),
                termVersions(),
                List.of(Map.of("language", "en", "mappings", List.of(
                                Map.of("newSegmentId", "n1", "oldSegmentIds", List.of("s1")))),
                        Map.of("language", "ja", "mappings", List.of(
                                Map.of("newSegmentId", "n1", "oldSegmentIds", List.of("s1")))))));
        assertThat(missingMapping.status()).isEqualTo(422);

        // 映射重复提交同一新段：422
        ApiResult duplicateMapping = structureChange(docId, body(newRequestId(), "change-dup", 7,
                List.of(Map.of("newSegmentId", "n1", "sourceText", "a"),
                        Map.of("newSegmentId", "n2", "sourceText", "b")),
                List.of(Map.of("segmentId", "s1", "sourceVersion", 1)),
                termVersions(),
                LANGS.stream().map(language -> Map.of("language", language, "mappings", List.of(
                        Map.of("newSegmentId", "n1", "oldSegmentIds", List.of("s1")),
                        Map.of("newSegmentId", "n1", "oldSegmentIds", List.of("s1"))))).toList()));
        assertThat(duplicateMapping.status()).isEqualTo(422);

        // 合并映射旧段顺序与源段不一致：422
        ApiResult wrongOrder = structureChange(docId, body(newRequestId(), "change-order", 7,
                List.of(Map.of("newSegmentId", "m1", "sourceText", "合并")),
                List.of(Map.of("segmentId", "s1", "sourceVersion", 1),
                        Map.of("segmentId", "s2", "sourceVersion", 1)),
                termVersions(),
                LANGS.stream().map(language -> Map.of("language", language, "mappings", List.of(
                        Map.of("newSegmentId", "m1", "oldSegmentIds", List.of("s2", "s1"))))).toList()));
        assertThat(wrongOrder.status()).isEqualTo(422);

        // 旧译文缺失：ja 在 s1 无译文 → 422
        jdbc.update("DELETE FROM translation WHERE document_id = ? AND segment_id = 's1' AND language = 'ja'",
                docId);
        assertThat(splitRequest(docId, newRequestId(), "change-no-translation", 7,
                List.of("n1", "n2"), "s1", 1).status()).isEqualTo(422);
    }

    @Test
    @DisplayName("术语版本变化：涉及语言术语版本不匹配返回 409，失败后无任何部分新段")
    void termVersionConflictRollsBack() throws Exception {
        long docId = preparedDocument();
        List<Map<String, Object>> badTermVersions = List.of(
                Map.of("language", "en", "termVersion", 0),
                Map.of("language", "ja", "termVersion", 3));
        ApiResult result = structureChange(docId, body(newRequestId(), "change-term", 7,
                List.of(Map.of("newSegmentId", "n1", "sourceText", "a"),
                        Map.of("newSegmentId", "n2", "sourceText", "b")),
                List.of(Map.of("segmentId", "s1", "sourceVersion", 1)),
                badTermVersions,
                validSplitMappings(List.of("n1", "n2"), List.of("s1"))));
        assertThat(result.status()).isEqualTo(409);
        assertNoStructureChangeSideEffects(docId);
    }

    @Test
    @DisplayName("整体回滚：任一失败后旧段仍 CURRENT、无新段/血缘/候选/事务记录，草稿版本不变，键不被占用")
    void failureRollsBackEverythingAndReleasesKeys() throws Exception {
        long docId = preparedDocument();
        // 非连续合并 422
        assertThat(mergeRequest(docId, newRequestId(), "change-retry", 7,
                "m1", List.of("s1", "s3"), List.of(1, 1)).status()).isEqualTo(422);
        assertNoStructureChangeSideEffects(docId);

        // 失败不占键：同一 changeKey 用正确参数重试成功
        ApiResult retry = mergeRequest(docId, newRequestId(), "change-retry", 7,
                "m1", List.of("s1", "s2"), List.of(1, 1));
        assertThat(retry.status()).isEqualTo(201);
        assertThat(retry.body().get("newSegmentIds").get(0).asText()).isEqualTo("m1");

        // 已淘汰旧段不能再次参与结构修订：422
        ApiResult reuseOld = splitRequest(docId, newRequestId(), "change-old-again", 8,
                List.of("x1", "x2"), "s1", 1);
        assertThat(reuseOld.status()).isEqualTo(422);

        // 新段键与历史段键冲突：409
        ApiResult dupKey = splitRequest(docId, newRequestId(), "change-dup-key", 8,
                List.of("m1", "x2"), "s3", 1);
        assertThat(dupKey.status()).isEqualTo(409);
    }

    @Test
    @DisplayName("幂等：同 requestId 同参重放原结果且不重复落库；同键异参 409；不同 requestId 复用 changeKey 409")
    void idempotency() throws Exception {
        long docId = preparedDocument();
        String requestId = newRequestId();
        String changeKey = "change-idem";
        ApiResult first = splitRequest(docId, requestId, changeKey, 7, List.of("n1", "n2"), "s1", 1);
        assertThat(first.status()).isEqualTo(201);

        ApiResult replay = splitRequest(docId, requestId, changeKey, 7, List.of("n1", "n2"), "s1", 1);
        assertThat(replay.status()).isEqualTo(201);
        assertThat(replay.body().get("draftVersion").asInt()).isEqualTo(8);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM structure_change WHERE document_id = ?", Integer.class, docId)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM source_lineage WHERE document_id = ?", Integer.class, docId)).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM reference_candidate WHERE document_id = ?", Integer.class, docId)).isEqualTo(4);

        // 同 requestId 异参：409
        ApiResult differentParams = splitRequest(docId, requestId, changeKey, 7,
                List.of("n1", "n9"), "s1", 1);
        assertThat(differentParams.status()).isEqualTo(409);

        // 不同 requestId 复用 changeKey：409
        ApiResult reusedKey = splitRequest(docId, newRequestId(), changeKey, 8,
                List.of("y1", "y2"), "s2", 1);
        assertThat(reusedKey.status()).isEqualTo(409);
    }

    @Test
    @DisplayName("失效语义：结构修订后批准/发布资格失效，须重新编辑批准；历史发布快照不变")
    void approvalsAndPublishInvalidatedButSnapshotFrozen() throws Exception {
        long docId = preparedDocument();
        // 先发布版本 1（当前草稿版本 7）
        ApiResult firstRelease = publish(docId, 7, 0, newRequestId());
        assertThat(firstRelease.status()).isEqualTo(201);

        assertThat(splitRequest(docId, newRequestId(), "change-publish", 7,
                List.of("n1", "n2"), "s1", 1).status()).isEqualTo(201);

        // 新段无译文无批准：发布 422
        ApiResult publishAfter = publish(docId, 8, 1, newRequestId());
        assertThat(publishAfter.status()).isEqualTo(422);
        assertThat(publishAfter.body().get("message").asText()).contains("n1");

        // 血缘候选不是译文：translation 表中没有新段记录，approval 表中也没有
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM translation WHERE document_id = ? AND segment_id IN ('n1','n2')",
                Integer.class, docId)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM approval WHERE document_id = ? AND segment_id IN ('n1','n2')",
                Integer.class, docId)).isZero();

        // 新段重新编辑与批准（未受影响的 s2/s3 批准保留）
        for (String seg : List.of("n1", "n2")) {
            submitTranslation(docId, seg, "en", "alice", "new-en-" + seg, 1, newRequestId());
            submitTranslation(docId, seg, "ja", "carol", "new-ja-" + seg, 1, newRequestId());
            approve(docId, seg, "en", "bob", 1, newRequestId());
            approve(docId, seg, "ja", "dave", 1, newRequestId());
        }
        // 4 次译文提交：草稿版本 8 → 12
        ApiResult secondRelease = publish(docId, 12, 1, newRequestId());
        assertThat(secondRelease.status()).isEqualTo(201);

        // 历史发布快照不变：版本 1 仍是旧三旧段与旧译文
        ApiResult release1 = getJson("/api/documents/" + docId + "/releases/1");
        assertThat(release1.body().get("segments")).hasSize(3);
        assertThat(release1.body().get("segments").get(0).get("segmentId").asText()).isEqualTo("s1");
        ApiResult release2 = getJson("/api/documents/" + docId + "/releases/2");
        List<String> order = new ArrayList<>();
        release2.body().get("segments").forEach(s -> order.add(s.get("segmentId").asText()));
        assertThat(order).containsExactly("n1", "n2", "s2", "s3");
    }

    @Test
    @DisplayName("只读查询：文档不存在 404；血缘查询段不存在 404；结构与血缘均为只读 GET")
    void readOnlyQueries() throws Exception {
        long docId = preparedDocument();
        assertThat(splitRequest(docId, newRequestId(), "change-query", 7,
                List.of("n1", "n2"), "s1", 1).status()).isEqualTo(201);

        assertThat(getJson("/api/documents/999999/structure").status()).isEqualTo(404);
        assertThat(getJson("/api/documents/999999/lineage").status()).isEqualTo(404);
        assertThat(getJson("/api/documents/" + docId + "/lineage?segmentId=ghost").status()).isEqualTo(404);

        ApiResult lineage = getJson("/api/documents/" + docId + "/lineage");
        assertThat(lineage.body().get("sourceLineage")).hasSize(2);
        assertThat(findLanguage(lineage.body(), "en").get("candidates")).hasSize(2);
    }

    private void assertNoStructureChangeSideEffects(long docId) {
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM structure_change WHERE document_id = ?", Integer.class, docId)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM source_lineage WHERE document_id = ?", Integer.class, docId)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM translation_lineage WHERE document_id = ?", Integer.class, docId)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM reference_candidate WHERE document_id = ?", Integer.class, docId)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM segment WHERE document_id = ? AND status = 'SUPERSEDED'",
                Integer.class, docId)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM segment WHERE document_id = ?", Integer.class, docId)).isEqualTo(3);
        assertThat(jdbc.queryForObject(
                "SELECT draft_version FROM document WHERE document_id = ?", Integer.class, docId)).isEqualTo(7);
    }

    private List<Map<String, Object>> termVersions() {
        return LANGS.stream().map(language -> Map.<String, Object>of("language", language, "termVersion", 0))
                .toList();
    }

    private List<Map<String, Object>> validSplitMappings(List<String> newIds, List<String> oldIds) {
        return LANGS.stream().map(language -> {
            List<Map<String, ?>> segmentMappings = new ArrayList<>();
            for (String id : newIds) {
                segmentMappings.add(Map.of("newSegmentId", id, "oldSegmentIds", oldIds));
            }
            return Map.<String, Object>of("language", language, "mappings", segmentMappings);
        }).toList();
    }

    private static JsonNode findLanguage(JsonNode lineage, String language) {
        for (JsonNode node : lineage.get("languages")) {
            if (node.get("language").asText().equals(language)) {
                return node;
            }
        }
        throw new AssertionError("语言不存在: " + language);
    }

    private static JsonNode findCandidate(JsonNode candidates, String segmentId) {
        for (JsonNode node : candidates) {
            if (node.get("segmentId").asText().equals(segmentId)) {
                return node;
            }
        }
        throw new AssertionError("候选不存在: " + segmentId);
    }
}
