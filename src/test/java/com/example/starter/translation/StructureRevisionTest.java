package com.example.starter.translation;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 源段结构修订与跨语言血缘测试：SPLIT/MERGE 主流程、整体回滚、幂等、并发边界，
 * 全部基于真实 H2（MODE=MySQL）数据库与实际行锁/唯一约束。
 */
class StructureRevisionTest extends AbstractIntegrationTest {

    private static final List<String> TWO_LANGS = List.of("en", "ja");

    /** 建一个含 s1~s3、en/ja 双语言译文且全部批准的文档；初始草稿版本 1，6 次提交后草稿版本 7。 */
    private long preparedDocument() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\",\"ja\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"原文一\"},"
                        + "{\"segmentId\":\"s2\",\"sourceText\":\"原文二\"},"
                        + "{\"segmentId\":\"s3\",\"sourceText\":\"原文三\"}]");
        for (String seg : new String[]{"s1", "s2", "s3"}) {
            submitTranslation(docId, seg, "en", "alice", seg + "-en", 1, newRequestId());
            submitTranslation(docId, seg, "ja", "carol", seg + "-ja", 1, newRequestId());
            approve(docId, seg, "en", "bob", 1, newRequestId());
            approve(docId, seg, "ja", "dave", 1, newRequestId());
        }
        return docId;
    }

    private List<String> toStringList(JsonNode arrayNode) {
        List<String> values = new ArrayList<>();
        arrayNode.forEach(node -> values.add(node.asText()));
        return values;
    }

    private Map<String, Object> fragment(String oldSegmentId, Integer start, Integer end) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("oldSegmentId", oldSegmentId);
        if (start != null) {
            f.put("startOffset", start);
        }
        if (end != null) {
            f.put("endOffset", end);
        }
        return f;
    }

    private Map<String, Object> mapping(String newSegmentId, List<Map<String, Object>> fragments) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("newSegmentId", newSegmentId);
        m.put("fragments", fragments);
        return m;
    }

    private Map<String, Object> newSegment(String id, String text) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("segmentId", id);
        s.put("sourceText", text);
        return s;
    }

    /** 组装结构修订请求体 JSON。 */
    private String body(String requestId, String changeKey, String changeType, int docVersion, int termVersion,
                        List<String> oldIds, Map<String, Integer> versions,
                        List<Map<String, Object>> newSegments,
                        Map<String, List<Map<String, Object>>> mappings) throws Exception {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("requestId", requestId);
        root.put("changeKey", changeKey);
        root.put("changeType", changeType);
        root.put("expectedDocumentVersion", docVersion);
        root.put("expectedTermVersion", termVersion);
        root.put("segmentIds", oldIds);
        root.put("expectedSourceVersions", versions);
        root.put("newSegments", newSegments);
        root.put("languageMappings", mappings);
        return objectMapper.writeValueAsString(root);
    }

    /** s1 拆为 n1/n2 的标准映射：s1-en 长度 5（"s1","-en"），s1-ja 长度 5。 */
    private Map<String, List<Map<String, Object>>> splitS1Mappings() {
        Map<String, List<Map<String, Object>>> mappings = new LinkedHashMap<>();
        for (String lang : TWO_LANGS) {
            mappings.put(lang, List.of(
                    mapping("n1", List.of(fragment("s1", 0, 2))),
                    mapping("n2", List.of(fragment("s1", 2, 5)))));
        }
        return mappings;
    }

    @Test
    @DisplayName("SPLIT 主流程：原子生成新文档版本与跨语言血缘，旧段 SUPERSEDED，参考候选带边界，新段无译文无批准")
    void splitSuccess() throws Exception {
        long docId = preparedDocument();

        String body = body(newRequestId(), "ck-split-1", "SPLIT", 7, 0,
                List.of("s1"), Map.of("s1", 1),
                List.of(newSegment("n1", "拆出甲"), newSegment("n2", "拆出乙")),
                splitS1Mappings());
        ApiResult result = structureChange(docId, body);
        assertThat(result.status()).isEqualTo(201);
        assertThat(result.body().get("documentVersion").asInt()).isEqualTo(8);
        assertThat(toStringList(result.body().get("oldSegmentIds"))).containsExactly("s1");
        assertThat(toStringList(result.body().get("newSegmentIds"))).containsExactly("n1", "n2");
        assertThat(toStringList(result.body().get("languages"))).containsExactly("en", "ja");

        // 参考候选：旧译文按映射拼接，片段边界可查
        JsonNode references = result.body().get("references");
        assertThat(references).hasSize(4);
        JsonNode n1En = references.get(0);
        assertThat(n1En.get("newSegmentId").asText()).isEqualTo("n1");
        assertThat(n1En.get("language").asText()).isEqualTo("en");
        assertThat(n1En.get("content").asText()).isEqualTo("s1");
        assertThat(n1En.get("boundaries")).hasSize(1);
        assertThat(n1En.get("boundaries").get(0).get("startOffset").asInt()).isZero();
        assertThat(n1En.get("boundaries").get(0).get("endOffset").asInt()).isEqualTo(2);
        JsonNode n2Ja = references.get(3);
        assertThat(n2Ja.get("language").asText()).isEqualTo("ja");
        assertThat(n2Ja.get("content").asText()).isEqualTo("-ja");

        // 当前结构：n1,n2 替换 s1，后序段顺延，草稿版本已递增
        ApiResult structure = getJson("/api/documents/" + docId + "/structure");
        assertThat(structure.status()).isEqualTo(200);
        assertThat(structure.body().get("draftVersion").asInt()).isEqualTo(8);
        List<String> currentIds = new ArrayList<>();
        structure.body().get("segments").forEach(s -> currentIds.add(s.get("segmentId").asText()));
        assertThat(currentIds).containsExactly("n1", "n2", "s2", "s3");
        assertThat(structure.body().get("segments").get(0).get("position").asInt()).isEqualTo(1);
        assertThat(structure.body().get("segments").get(2).get("position").asInt()).isEqualTo(3);

        // 旧段保留为 SUPERSEDED；旧译文与旧批准行仍在（血缘可溯），新段没有译文和批准
        Map<String, Object> s1Row = jdbc.queryForMap(
                "SELECT status, source_version FROM segment WHERE document_id = ? AND segment_id = 's1'", docId);
        assertThat(s1Row.get("status")).isEqualTo("SUPERSEDED");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM segment WHERE document_id = ? AND status = 'CURRENT'",
                Integer.class, docId)).isEqualTo(4);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM translation WHERE document_id = ? AND segment_id IN ('n1','n2')",
                Integer.class, docId)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM approval WHERE document_id = ? AND segment_id IN ('n1','n2')",
                Integer.class, docId)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM translation WHERE document_id = ? AND segment_id = 's1'",
                Integer.class, docId)).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM translation_reference WHERE document_id = ?",
                Integer.class, docId)).isEqualTo(4);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM translation_lineage WHERE document_id = ?",
                Integer.class, docId)).isEqualTo(4);

        // 结构修订列表与详情只读查询
        ApiResult changes = getJson("/api/documents/" + docId + "/structure-changes");
        assertThat(changes.body()).hasSize(1);
        assertThat(changes.body().get(0).get("changeKey").asText()).isEqualTo("ck-split-1");
        ApiResult detail = getJson("/api/documents/" + docId + "/structure-changes/ck-split-1");
        assertThat(detail.status()).isEqualTo(200);
        assertThat(detail.body().get("changeType").asText()).isEqualTo("SPLIT");
        assertThat(detail.body().get("segmentLineage")).hasSize(2);
        assertThat(detail.body().get("segmentLineage").get(0).get("newSegmentId").asText()).isEqualTo("n1");
        assertThat(detail.body().get("translationLineage")).hasSize(4);
        assertThat(detail.body().get("references")).hasSize(4);
        assertThat(getJson("/api/documents/" + docId + "/structure-changes/missing").status()).isEqualTo(404);

        // 单段跨语言血缘：旧段 FORWARD，新段 BACKWARD，无关段 NONE
        ApiResult forward = getJson("/api/documents/" + docId + "/segments/s1/lineage");
        assertThat(forward.body().get("direction").asText()).isEqualTo("FORWARD");
        assertThat(forward.body().get("status").asText()).isEqualTo("SUPERSEDED");
        assertThat(forward.body().get("segmentLineage")).hasSize(2);
        assertThat(forward.body().get("translationLineage")).hasSize(4);
        ApiResult backward = getJson("/api/documents/" + docId + "/segments/n1/lineage");
        assertThat(backward.body().get("direction").asText()).isEqualTo("BACKWARD");
        assertThat(backward.body().get("changeKey").asText()).isEqualTo("ck-split-1");
        ApiResult none = getJson("/api/documents/" + docId + "/segments/s2/lineage");
        assertThat(none.body().get("direction").asText()).isEqualTo("NONE");
    }

    @Test
    @DisplayName("SPLIT 后发布资格失效：新段缺译缺批准发布 422；重新编辑并批准后可发布，且术语校验生效")
    void splitInvalidatesPublishEligibility() throws Exception {
        long docId = preparedDocument();
        ApiResult split = structureChange(docId, body(newRequestId(), "ck-split-pub", "SPLIT", 7, 0,
                List.of("s1"), Map.of("s1", 1),
                List.of(newSegment("n1", "拆出甲"), newSegment("n2", "拆出乙")),
                splitS1Mappings()));
        assertThat(split.status()).isEqualTo(201);

        // 新段无译文无批准：发布 422，且不产生快照
        assertThat(publish(docId, 8, 0, newRequestId()).status()).isEqualTo(422);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_snapshot WHERE document_id = ?", Integer.class, docId)).isZero();

        // 建立术语版本后，新段译文须满足术语规则，旧段 s2/s3 的译文术语过期
        ApiResult terms = updateTerms(docId, 0,
                "[{\"sourceTerm\":\"甲\",\"language\":\"en\",\"requiredTranslation\":\"JIA\"}]",
                newRequestId());
        assertThat(terms.status()).isEqualTo(201);

        // 新段违规译文 422
        ApiResult violated = submitTranslation(docId, "n1", "en", "alice", "wrong", 1, newRequestId());
        assertThat(violated.status()).isEqualTo(422);
        assertThat(violated.body().get("violations")).hasSize(1);

        // 全部当前段按当前术语版本重新翻译批准后发布成功
        submitTranslation(docId, "n1", "en", "alice", "JIA", 1, newRequestId());
        submitTranslation(docId, "n1", "ja", "carol", "n1-ja", 1, newRequestId());
        submitTranslation(docId, "n2", "en", "alice", "n2-en", 1, newRequestId());
        submitTranslation(docId, "n2", "ja", "carol", "n2-ja", 1, newRequestId());
        approve(docId, "n1", "en", "bob", 1, newRequestId());
        approve(docId, "n1", "ja", "dave", 1, newRequestId());
        approve(docId, "n2", "en", "bob", 1, newRequestId());
        approve(docId, "n2", "ja", "dave", 1, newRequestId());
        // s2/s3 术语过期，需按术语版本 1 重新提交（源文不含“甲”，任意合规译文即可）并重新批准
        for (String seg : new String[]{"s2", "s3"}) {
            submitTranslation(docId, seg, "en", "alice", seg + "-en-v2", 1, newRequestId());
            submitTranslation(docId, seg, "ja", "carol", seg + "-ja-v2", 1, newRequestId());
            approve(docId, seg, "en", "bob", 2, newRequestId());
            approve(docId, seg, "ja", "dave", 2, newRequestId());
        }
        int finalDraft = jdbc.queryForObject(
                "SELECT draft_version FROM document WHERE document_id = ?", Integer.class, docId);
        ApiResult published = publish(docId, finalDraft, 0, newRequestId());
        assertThat(published.status()).isEqualTo(201);
    }

    @Test
    @DisplayName("历史发布快照不受结构修订影响：修订后旧发布查询内容不变")
    void splitKeepsReleasedSnapshot() throws Exception {
        long docId = preparedDocument();
        assertThat(publish(docId, 7, 0, newRequestId()).status()).isEqualTo(201);

        ApiResult split = structureChange(docId, body(newRequestId(), "ck-split-snap", "SPLIT", 7, 0,
                List.of("s1"), Map.of("s1", 1),
                List.of(newSegment("n1", "拆出甲"), newSegment("n2", "拆出乙")),
                splitS1Mappings()));
        assertThat(split.status()).isEqualTo(201);

        ApiResult release = getJson("/api/documents/" + docId + "/releases/1");
        assertThat(release.status()).isEqualTo(200);
        List<String> snapshotIds = new ArrayList<>();
        release.body().get("segments").forEach(s -> snapshotIds.add(s.get("segmentId").asText()));
        assertThat(snapshotIds).containsExactly("s1", "s2", "s3");
        assertThat(release.body().get("segments").get(0).get("sourceText").asText()).isEqualTo("原文一");
    }

    @Test
    @DisplayName("MERGE 主流程：连续旧段合并为一段，参考候选按旧段顺序整段拼接，序号重排连续")
    void mergeSuccess() throws Exception {
        long docId = preparedDocument();
        Map<String, List<Map<String, Object>>> mappings = new LinkedHashMap<>();
        for (String lang : TWO_LANGS) {
            mappings.put(lang, List.of(mapping("m1", List.of(
                    fragment("s2", null, null), fragment("s3", null, null)))));
        }
        ApiResult result = structureChange(docId, body(newRequestId(), "ck-merge-1", "MERGE", 7, 0,
                List.of("s2", "s3"), Map.of("s2", 1, "s3", 1),
                List.of(newSegment("m1", "合并段")), mappings));
        assertThat(result.status()).isEqualTo(201);
        assertThat(result.body().get("documentVersion").asInt()).isEqualTo(8);

        JsonNode enRef = result.body().get("references").get(0);
        assertThat(enRef.get("newSegmentId").asText()).isEqualTo("m1");
        assertThat(enRef.get("language").asText()).isEqualTo("en");
        assertThat(enRef.get("content").asText()).isEqualTo("s2-ens3-en");
        assertThat(enRef.get("boundaries")).hasSize(2);
        assertThat(enRef.get("boundaries").get(0).get("oldSegmentId").asText()).isEqualTo("s2");
        assertThat(enRef.get("boundaries").get(0).get("startOffset").asInt()).isZero();
        assertThat(enRef.get("boundaries").get(0).get("endOffset").asInt()).isEqualTo(5);
        assertThat(enRef.get("boundaries").get(1).get("oldSegmentId").asText()).isEqualTo("s3");
        assertThat(enRef.get("boundaries").get(1).get("startOffset").asInt()).isZero();
        assertThat(enRef.get("boundaries").get(1).get("endOffset").asInt()).isEqualTo(5);
        JsonNode jaRef = result.body().get("references").get(1);
        assertThat(jaRef.get("content").asText()).isEqualTo("s2-jas3-ja");

        ApiResult structure = getJson("/api/documents/" + docId + "/structure");
        List<String> currentIds = new ArrayList<>();
        structure.body().get("segments").forEach(s -> currentIds.add(s.get("segmentId").asText()));
        assertThat(currentIds).containsExactly("s1", "m1");
        List<Integer> positions = new ArrayList<>();
        structure.body().get("segments").forEach(s -> positions.add(s.get("position").asInt()));
        assertThat(positions).containsExactly(1, 2);

        ApiResult detail = getJson("/api/documents/" + docId + "/structure-changes/ck-merge-1");
        assertThat(detail.body().get("segmentLineage")).hasSize(2);
        assertThat(detail.body().get("segmentLineage").get(0).get("ordinal").asInt()).isEqualTo(1);
        assertThat(detail.body().get("segmentLineage").get(1).get("newSegmentId").asText()).isEqualTo("m1");
    }

    @Test
    @DisplayName("失败分支：版本冲突 409；结构/映射规则不满足 422；整体回滚无部分新段且 changeKey 不占键")
    void failuresRollbackAtomically() throws Exception {
        long docId = preparedDocument();

        // 期望文档版本不符 409
        ApiResult badDocVersion = structureChange(docId, body(newRequestId(), "ck-bad-doc", "SPLIT", 99, 0,
                List.of("s1"), Map.of("s1", 1),
                List.of(newSegment("n1", "a"), newSegment("n2", "b")), splitS1Mappings()));
        assertThat(badDocVersion.status()).isEqualTo(409);

        // 期望术语版本不符 409
        ApiResult badTermVersion = structureChange(docId, body(newRequestId(), "ck-bad-term", "SPLIT", 7, 9,
                List.of("s1"), Map.of("s1", 1),
                List.of(newSegment("n1", "a"), newSegment("n2", "b")), splitS1Mappings()));
        assertThat(badTermVersion.status()).isEqualTo(409);

        // 旧源段版本不符 409
        ApiResult badSource = structureChange(docId, body(newRequestId(), "ck-bad-src", "SPLIT", 7, 0,
                List.of("s1"), Map.of("s1", 9),
                List.of(newSegment("n1", "a"), newSegment("n2", "b")), splitS1Mappings()));
        assertThat(badSource.status()).isEqualTo(409);

        // SPLIT 给了两个旧段 422
        ApiResult splitTwo = structureChange(docId, body(newRequestId(), "ck-split-two", "SPLIT", 7, 0,
                List.of("s1", "s2"), Map.of("s1", 1, "s2", 1),
                List.of(newSegment("n1", "a"), newSegment("n2", "b")), splitS1Mappings()));
        assertThat(splitTwo.status()).isEqualTo(422);

        // MERGE 只给一个旧段 422
        ApiResult mergeOne = structureChange(docId, body(newRequestId(), "ck-merge-one", "MERGE", 7, 0,
                List.of("s1"), Map.of("s1", 1),
                List.of(newSegment("m1", "a")),
                Map.of("en", List.of(mapping("m1", List.of(fragment("s1", null, null)))),
                        "ja", List.of(mapping("m1", List.of(fragment("s1", null, null)))))));
        assertThat(mergeOne.status()).isEqualTo(422);

        // MERGE 旧段不连续（s1,s3）422
        ApiResult notConsecutive = structureChange(docId, body(newRequestId(), "ck-gap", "MERGE", 7, 0,
                List.of("s1", "s3"), Map.of("s1", 1, "s3", 1),
                List.of(newSegment("m1", "a")),
                Map.of("en", List.of(mapping("m1", List.of(
                                fragment("s1", null, null), fragment("s3", null, null)))),
                        "ja", List.of(mapping("m1", List.of(
                                fragment("s1", null, null), fragment("s3", null, null)))))));
        assertThat(notConsecutive.status()).isEqualTo(422);

        // MERGE 片段次序与旧段不一致 422
        ApiResult wrongOrder = structureChange(docId, body(newRequestId(), "ck-order", "MERGE", 7, 0,
                List.of("s2", "s3"), Map.of("s2", 1, "s3", 1),
                List.of(newSegment("m1", "a")),
                Map.of("en", List.of(mapping("m1", List.of(
                                fragment("s3", null, null), fragment("s2", null, null)))),
                        "ja", List.of(mapping("m1", List.of(
                                fragment("s2", null, null), fragment("s3", null, null)))))));
        assertThat(wrongOrder.status()).isEqualTo(422);

        // 新段键与历史段重复 409
        ApiResult dupKey = structureChange(docId, body(newRequestId(), "ck-dup", "SPLIT", 7, 0,
                List.of("s1"), Map.of("s1", 1),
                List.of(newSegment("s2", "a"), newSegment("n2", "b")), splitS1Mappings()));
        assertThat(dupKey.status()).isEqualTo(409);

        // 语言集合不完整（缺 ja）422
        ApiResult missingLang = structureChange(docId, body(newRequestId(), "ck-lang", "SPLIT", 7, 0,
                List.of("s1"), Map.of("s1", 1),
                List.of(newSegment("n1", "a"), newSegment("n2", "b")),
                Map.of("en", List.of(
                        mapping("n1", List.of(fragment("s1", 0, 2))),
                        mapping("n2", List.of(fragment("s1", 2, 5)))))));
        assertThat(missingLang.status()).isEqualTo(422);

        // 片段缺漏（n1 只覆盖 [0,2)，n2 从 3 开始）422
        Map<String, List<Map<String, Object>>> gapMappings = new LinkedHashMap<>();
        for (String lang : TWO_LANGS) {
            gapMappings.put(lang, List.of(
                    mapping("n1", List.of(fragment("s1", 0, 2))),
                    mapping("n2", List.of(fragment("s1", 3, 5)))));
        }
        ApiResult fragmentGap = structureChange(docId, body(newRequestId(), "ck-frag-gap", "SPLIT", 7, 0,
                List.of("s1"), Map.of("s1", 1),
                List.of(newSegment("n1", "a"), newSegment("n2", "b")), gapMappings));
        assertThat(fragmentGap.status()).isEqualTo(422);

        // 片段重叠（n1 [0,3)，n2 [2,5)）422
        Map<String, List<Map<String, Object>>> overlapMappings = new LinkedHashMap<>();
        for (String lang : TWO_LANGS) {
            overlapMappings.put(lang, List.of(
                    mapping("n1", List.of(fragment("s1", 0, 3))),
                    mapping("n2", List.of(fragment("s1", 2, 5)))));
        }
        ApiResult overlap = structureChange(docId, body(newRequestId(), "ck-frag-overlap", "SPLIT", 7, 0,
                List.of("s1"), Map.of("s1", 1),
                List.of(newSegment("n1", "a"), newSegment("n2", "b")), overlapMappings));
        assertThat(overlap.status()).isEqualTo(422);

        // 片段边界越界 422
        Map<String, List<Map<String, Object>>> overflowMappings = new LinkedHashMap<>();
        for (String lang : TWO_LANGS) {
            overflowMappings.put(lang, List.of(
                    mapping("n1", List.of(fragment("s1", 0, 2))),
                    mapping("n2", List.of(fragment("s1", 2, 99)))));
        }
        ApiResult overflow = structureChange(docId, body(newRequestId(), "ck-frag-overflow", "SPLIT", 7, 0,
                List.of("s1"), Map.of("s1", 1),
                List.of(newSegment("n1", "a"), newSegment("n2", "b")), overflowMappings));
        assertThat(overflow.status()).isEqualTo(422);

        // 缺少某新段的映射 422
        Map<String, List<Map<String, Object>>> missingNewMapping = new LinkedHashMap<>();
        for (String lang : TWO_LANGS) {
            missingNewMapping.put(lang, List.of(
                    mapping("n1", List.of(fragment("s1", 0, 5)))));
        }
        ApiResult missingNew = structureChange(docId, body(newRequestId(), "ck-miss-new", "SPLIT", 7, 0,
                List.of("s1"), Map.of("s1", 1),
                List.of(newSegment("n1", "a"), newSegment("n2", "b")), missingNewMapping));
        assertThat(missingNew.status()).isEqualTo(422);

        // expectedSourceVersions 缺漏/多余 422
        ApiResult missingVersion = structureChange(docId, body(newRequestId(), "ck-miss-ver", "SPLIT", 7, 0,
                List.of("s1"), Map.of(),
                List.of(newSegment("n1", "a"), newSegment("n2", "b")), splitS1Mappings()));
        assertThat(missingVersion.status()).isEqualTo(422);
        ApiResult extraVersion = structureChange(docId, body(newRequestId(), "ck-extra-ver", "SPLIT", 7, 0,
                List.of("s1"), Map.of("s1", 1, "s2", 1),
                List.of(newSegment("n1", "a"), newSegment("n2", "b")), splitS1Mappings()));
        assertThat(extraVersion.status()).isEqualTo(422);

        // 非法 changeType 422
        ApiResult badType = structureChange(docId, body(newRequestId(), "ck-bad-type", "MOVE", 7, 0,
                List.of("s1"), Map.of("s1", 1),
                List.of(newSegment("n1", "a"), newSegment("n2", "b")), splitS1Mappings()));
        assertThat(badType.status()).isEqualTo(422);

        // 全部失败后无任何部分写入：草稿版本、结构修订与新段均未产生
        assertThat(jdbc.queryForObject(
                "SELECT draft_version FROM document WHERE document_id = ?", Integer.class, docId)).isEqualTo(7);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM structure_change WHERE document_id = ?",
                Integer.class, docId)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM segment WHERE document_id = ? AND status = 'SUPERSEDED'",
                Integer.class, docId)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM segment WHERE document_id = ? AND segment_id IN ('n1','n2','m1')",
                Integer.class, docId)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM segment_lineage", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM translation_lineage", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM translation_reference", Integer.class)).isZero();

        // 失败不占键：用触发过 422 的 changeKey 修正参数后成功
        String retryBody = body(newRequestId(), "ck-bad-doc", "SPLIT", 7, 0,
                List.of("s1"), Map.of("s1", 1),
                List.of(newSegment("n1", "拆出甲"), newSegment("n2", "拆出乙")), splitS1Mappings());
        ApiResult retried = structureChange(docId, retryBody);
        assertThat(retried.status()).isEqualTo(201);
    }

    @Test
    @DisplayName("幂等：同 requestId 同参重放同一结果且不产生第二个文档版本；同键异参 409；changeKey 唯一")
    void idempotency() throws Exception {
        long docId = preparedDocument();
        String requestId = newRequestId();
        String payload = body(requestId, "ck-idem", "SPLIT", 7, 0,
                List.of("s1"), Map.of("s1", 1),
                List.of(newSegment("n1", "拆出甲"), newSegment("n2", "拆出乙")), splitS1Mappings());

        ApiResult first = structureChange(docId, payload);
        assertThat(first.status()).isEqualTo(201);
        assertThat(first.body().get("documentVersion").asInt()).isEqualTo(8);

        // 同 requestId 同参重放：相同响应，文档版本不再递增，血缘不重复
        ApiResult replay = structureChange(docId, payload);
        assertThat(replay.status()).isEqualTo(201);
        assertThat(replay.body().get("documentVersion").asInt()).isEqualTo(8);
        assertThat(jdbc.queryForObject(
                "SELECT draft_version FROM document WHERE document_id = ?", Integer.class, docId)).isEqualTo(8);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM structure_change WHERE document_id = ?", Integer.class, docId)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM segment_lineage WHERE document_id = ?", Integer.class, docId)).isEqualTo(2);

        // 同 requestId 异参 409
        String different = body(requestId, "ck-idem-different", "SPLIT", 8, 0,
                List.of("s2"), Map.of("s2", 1),
                List.of(newSegment("x1", "a"), newSegment("x2", "b")),
                Map.of("en", List.of(
                                mapping("x1", List.of(fragment("s2", 0, 2))),
                                mapping("x2", List.of(fragment("s2", 2, 5)))),
                        "ja", List.of(
                                mapping("x1", List.of(fragment("s2", 0, 2))),
                                mapping("x2", List.of(fragment("s2", 2, 5))))));
        assertThat(structureChange(docId, different).status()).isEqualTo(409);

        // changeKey 唯一：新 requestId 但重复 changeKey 409
        String reusedChangeKey = body(newRequestId(), "ck-idem", "SPLIT", 8, 0,
                List.of("s2"), Map.of("s2", 1),
                List.of(newSegment("x1", "a"), newSegment("x2", "b")),
                Map.of("en", List.of(
                                mapping("x1", List.of(fragment("s2", 0, 2))),
                                mapping("x2", List.of(fragment("s2", 2, 5)))),
                        "ja", List.of(
                                mapping("x1", List.of(fragment("s2", 0, 2))),
                                mapping("x2", List.of(fragment("s2", 2, 5))))));
        assertThat(structureChange(docId, reusedChangeKey).status()).isEqualTo(409);
    }

    @Test
    @DisplayName("并发：两个结构修订竞争同一文档版本，恰好一个成功、另一个 409，无混合文档版本")
    void concurrentStructureChanges() throws Exception {
        long docId = preparedDocument();
        String splitBody = body(newRequestId(), "ck-conc-1", "SPLIT", 7, 0,
                List.of("s1"), Map.of("s1", 1),
                List.of(newSegment("n1", "拆出甲"), newSegment("n2", "拆出乙")), splitS1Mappings());
        Map<String, List<Map<String, Object>>> mergeMappings = new LinkedHashMap<>();
        for (String lang : TWO_LANGS) {
            mergeMappings.put(lang, List.of(mapping("m1", List.of(
                    fragment("s2", null, null), fragment("s3", null, null)))));
        }
        String mergeBody = body(newRequestId(), "ck-conc-2", "MERGE", 7, 0,
                List.of("s2", "s3"), Map.of("s2", 1, "s3", 1),
                List.of(newSegment("m1", "合并段")), mergeMappings);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        Future<ApiResult> f1 = pool.submit(() -> {
            gate.await();
            return structureChange(docId, splitBody);
        });
        Future<ApiResult> f2 = pool.submit(() -> {
            gate.await();
            return structureChange(docId, mergeBody);
        });
        gate.countDown();
        ApiResult r1 = f1.get(30, TimeUnit.SECONDS);
        ApiResult r2 = f2.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        int success = (r1.status() == 201 ? 1 : 0) + (r2.status() == 201 ? 1 : 0);
        int conflict = (r1.status() == 409 ? 1 : 0) + (r2.status() == 409 ? 1 : 0);
        assertThat(success).isEqualTo(1);
        assertThat(conflict).isEqualTo(1);

        // 文档版本只加一；结构修订、当前段数量与唯一胜者一致，无混合状态
        assertThat(jdbc.queryForObject(
                "SELECT draft_version FROM document WHERE document_id = ?", Integer.class, docId)).isEqualTo(8);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM structure_change WHERE document_id = ?", Integer.class, docId)).isEqualTo(1);
        int currentCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM segment WHERE document_id = ? AND status = 'CURRENT'",
                Integer.class, docId);
        if (r1.status() == 201) {
            // SPLIT 胜：3-1+2=4 个当前段，s2/s3 未被合并
            assertThat(currentCount).isEqualTo(4);
            assertThat(jdbc.queryForObject(
                    "SELECT status FROM segment WHERE document_id = ? AND segment_id = 's2'",
                    String.class, docId)).isEqualTo("CURRENT");
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM segment WHERE document_id = ? AND segment_id = 'm1'",
                    Integer.class, docId)).isZero();
        } else {
            // MERGE 胜：3-2+1=2 个当前段，s1 未被拆
            assertThat(currentCount).isEqualTo(2);
            assertThat(jdbc.queryForObject(
                    "SELECT status FROM segment WHERE document_id = ? AND segment_id = 's1'",
                    String.class, docId)).isEqualTo("CURRENT");
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM segment WHERE document_id = ? AND segment_id IN ('n1','n2')",
                    Integer.class, docId)).isZero();
        }
    }

    @Test
    @DisplayName("并发：结构修订与源文修订竞争，恰好一方生效，无混合文档版本或部分段")
    void concurrentStructureAndSourceRevision() throws Exception {
        long docId = preparedDocument();
        String splitBody = body(newRequestId(), "ck-vs-src", "SPLIT", 7, 0,
                List.of("s1"), Map.of("s1", 1),
                List.of(newSegment("n1", "拆出甲"), newSegment("n2", "拆出乙")), splitS1Mappings());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        Future<ApiResult> structureFuture = pool.submit(() -> {
            gate.await();
            return structureChange(docId, splitBody);
        });
        Future<ApiResult> reviseFuture = pool.submit(() -> {
            gate.await();
            return putJson("/api/documents/" + docId + "/segments/s1/source",
                    "{\"requestId\":\"" + newRequestId() + "\",\"sourceText\":\"原文一修订\"}");
        });
        gate.countDown();
        ApiResult structureResult = structureFuture.get(30, TimeUnit.SECONDS);
        ApiResult reviseResult = reviseFuture.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        // 文档版本恰好加一：要么结构修订先生效（源文修订后到，s1 已 SUPERSEDED 返回 404），
        // 要么源文修订先生效（结构修订期望版本不符 409）
        int draftVersion = jdbc.queryForObject(
                "SELECT draft_version FROM document WHERE document_id = ?", Integer.class, docId);
        assertThat(draftVersion).isEqualTo(8);
        if (structureResult.status() == 201) {
            // 结构修订先执行（版本 7→8）：s1 被取代保留源文版本 1，旧段源文修订被拒绝（404）
            assertThat(reviseResult.status()).isEqualTo(404);
            assertThat(jdbc.queryForObject(
                    "SELECT source_version FROM segment WHERE document_id = ? AND segment_id = 's1'",
                    Integer.class, docId)).isEqualTo(1);
            assertThat(jdbc.queryForObject(
                    "SELECT status FROM segment WHERE document_id = ? AND segment_id = 's1'",
                    String.class, docId)).isEqualTo("SUPERSEDED");
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM segment WHERE document_id = ? AND segment_id IN ('n1','n2') "
                            + "AND status = 'CURRENT'", Integer.class, docId)).isEqualTo(2);
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM structure_change WHERE document_id = ?", Integer.class, docId))
                    .isEqualTo(1);
        } else {
            // 源文修订先执行（版本 7→8），结构修订期望版本不符 409，无任何结构变更
            assertThat(structureResult.status()).isEqualTo(409);
            assertThat(reviseResult.status()).isEqualTo(200);
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM structure_change WHERE document_id = ?", Integer.class, docId)).isZero();
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM segment WHERE document_id = ? AND segment_id IN ('n1','n2')",
                    Integer.class, docId)).isZero();
            assertThat(jdbc.queryForObject(
                    "SELECT source_version FROM segment WHERE document_id = ? AND segment_id = 's1'",
                    Integer.class, docId)).isEqualTo(2);
            assertThat(jdbc.queryForObject(
                    "SELECT status FROM segment WHERE document_id = ? AND segment_id = 's1'",
                    String.class, docId)).isEqualTo("CURRENT");
        }
    }

    @Test
    @DisplayName("并发：结构修订与发布竞争，恰好一方生效，快照数与发布版本一致，无混合文档版本")
    void concurrentStructureAndPublish() throws Exception {
        long docId = preparedDocument();
        // 当前草稿版本 7、发布版本 0，全部段已批准可发布
        String splitBody = body(newRequestId(), "ck-vs-pub", "SPLIT", 7, 0,
                List.of("s1"), Map.of("s1", 1),
                List.of(newSegment("n1", "拆出甲"), newSegment("n2", "拆出乙")), splitS1Mappings());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        Future<ApiResult> structureFuture = pool.submit(() -> {
            gate.await();
            return structureChange(docId, splitBody);
        });
        Future<ApiResult> publishFuture = pool.submit(() -> {
            gate.await();
            return publish(docId, 7, 0, newRequestId());
        });
        gate.countDown();
        ApiResult structureResult = structureFuture.get(30, TimeUnit.SECONDS);
        ApiResult publishResult = publishFuture.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        int success = (structureResult.status() == 201 ? 1 : 0) + (publishResult.status() == 201 ? 1 : 0);
        // 发布不推进草稿版本：发布先持锁时结构修订的期望草稿版本仍匹配，两者可能串行各自成功
        assertThat(success).isBetween(1, 2);
        assertThat(structureResult.status()).isIn(201, 409);
        assertThat(publishResult.status()).isIn(201, 409);

        Integer draftVersion = jdbc.queryForObject(
                "SELECT draft_version FROM document WHERE document_id = ?", Integer.class, docId);
        Integer publishedVersion = jdbc.queryForObject(
                "SELECT published_version FROM document WHERE document_id = ?", Integer.class, docId);
        Integer snapshots = jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_snapshot WHERE document_id = ?", Integer.class, docId);
        Integer changes = jdbc.queryForObject(
                "SELECT COUNT(*) FROM structure_change WHERE document_id = ?", Integer.class, docId);
        // 快照数永远等于发布版本号：无部分快照、无混合状态
        assertThat(snapshots).isEqualTo(publishedVersion);
        if (structureResult.status() == 201 && publishResult.status() == 409) {
            // 结构修订先生效（草稿 7→8）：发布期望版本不符 409，无快照，新段仍需重新编辑批准
            assertThat(draftVersion).isEqualTo(8);
            assertThat(publishedVersion).isZero();
            assertThat(changes).isEqualTo(1);
        } else if (structureResult.status() == 409) {
            // 理论分支：结构修订看到更新的草稿版本（本场景发布不推进草稿版本，不会到达，保留严格断言）
            throw new AssertionError("发布不推进草稿版本，结构修订不应因发布而 409");
        } else {
            // 两者均成功（发布先持锁固化旧结构，结构修订随后串行生效）：发布版本 1、草稿 8
            assertThat(structureResult.status()).isEqualTo(201);
            assertThat(publishResult.status()).isEqualTo(201);
            assertThat(draftVersion).isEqualTo(8);
            assertThat(publishedVersion).isEqualTo(1);
            assertThat(changes).isEqualTo(1);
            ApiResult release = getJson("/api/documents/" + docId + "/releases/1");
            List<String> snapshotIds = new ArrayList<>();
            release.body().get("segments").forEach(s -> snapshotIds.add(s.get("segmentId").asText()));
            // 快照固化发布时刻的旧结构；当前结构已拆，二者不混合
            assertThat(snapshotIds).containsExactly("s1", "s2", "s3");
            ApiResult structure = getJson("/api/documents/" + docId + "/structure");
            List<String> currentIds = new ArrayList<>();
            structure.body().get("segments").forEach(s -> currentIds.add(s.get("segmentId").asText()));
            assertThat(currentIds).containsExactly("n1", "n2", "s2", "s3");
        }
    }

    @Test
    @DisplayName("血缘链：SPLIT 后再对新段连续 MERGE，多次修订版本递增、双向血缘可逐级溯源")
    void chainedRevisionsKeepFullLineage() throws Exception {
        long docId = preparedDocument();
        // 第一次：s1 拆为 n1/n2（草稿 7→8）
        ApiResult split = structureChange(docId, body(newRequestId(), "ck-chain-1", "SPLIT", 7, 0,
                List.of("s1"), Map.of("s1", 1),
                List.of(newSegment("n1", "甲"), newSegment("n2", "乙")), splitS1Mappings()));
        assertThat(split.status()).isEqualTo(201);

        // 第二次：合并连续当前段 n1,n2 为 m1（草稿 8→9）
        Map<String, List<Map<String, Object>>> mergeMappings = new LinkedHashMap<>();
        // n1/n2 的参考候选正文为 "s1"/"-en"（en）、"s1"/"-ja"（ja），长度分别为 2 和 3
        mergeMappings.put("en", List.of(mapping("m1", List.of(
                fragment("n1", null, null), fragment("n2", null, null)))));
        mergeMappings.put("ja", List.of(mapping("m1", List.of(
                fragment("n1", null, null), fragment("n2", null, null)))));
        ApiResult merge = structureChange(docId, body(newRequestId(), "ck-chain-2", "MERGE", 8, 0,
                List.of("n1", "n2"), Map.of("n1", 1, "n2", 1),
                List.of(newSegment("m1", "合并")), mergeMappings));
        assertThat(merge.status()).isEqualTo(201);
        assertThat(merge.body().get("documentVersion").asInt()).isEqualTo(9);
        JsonNode enRef = merge.body().get("references").get(0);
        // n1/n2 未被重新编辑，无正式译文（REFERENCE 候选不是正式译文），本次旧译文按空串整段拼接；
        // 跨旧译文的完整溯源链仍可经两次修订的 translation_lineage 逐级还原到 s1 的旧译文 "s1-en"
        assertThat(enRef.get("content").asText()).isEmpty();
        assertThat(enRef.get("boundaries")).hasSize(2);
        assertThat(enRef.get("boundaries").get(0).get("oldSegmentId").asText()).isEqualTo("n1");
        assertThat(enRef.get("boundaries").get(0).get("startOffset").asInt()).isZero();
        assertThat(enRef.get("boundaries").get(0).get("endOffset").asInt()).isZero();

        // 跨语言血缘链：第一次修订 s1->n1/n2，第二次修订 n1/n2->m1，逐级可查
        ApiResult firstChange = getJson("/api/documents/" + docId + "/structure-changes/ck-chain-1");
        assertThat(firstChange.body().get("translationLineage").get(0).get("fragments").get(0)
                .get("oldSegmentId").asText()).isEqualTo("s1");
        ApiResult secondChange = getJson("/api/documents/" + docId + "/structure-changes/ck-chain-2");
        assertThat(secondChange.body().get("segmentLineage")).hasSize(2);

        // 当前结构为 m1,s2,s3；s1,n1,n2 均为 SUPERSEDED
        ApiResult structure = getJson("/api/documents/" + docId + "/structure");
        List<String> currentIds = new ArrayList<>();
        structure.body().get("segments").forEach(s -> currentIds.add(s.get("segmentId").asText()));
        assertThat(currentIds).containsExactly("m1", "s2", "s3");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM segment WHERE document_id = ? AND status = 'SUPERSEDED'",
                Integer.class, docId)).isEqualTo(3);

        // 逐级溯源：s1 FORWARD -> n1/n2 FORWARD -> m1 BACKWARD
        ApiResult s1Lineage = getJson("/api/documents/" + docId + "/segments/s1/lineage");
        assertThat(s1Lineage.body().get("direction").asText()).isEqualTo("FORWARD");
        assertThat(s1Lineage.body().get("changeKey").asText()).isEqualTo("ck-chain-1");
        ApiResult n1Lineage = getJson("/api/documents/" + docId + "/segments/n1/lineage");
        assertThat(n1Lineage.body().get("direction").asText()).isEqualTo("FORWARD");
        assertThat(n1Lineage.body().get("changeKey").asText()).isEqualTo("ck-chain-2");
        ApiResult m1Lineage = getJson("/api/documents/" + docId + "/segments/m1/lineage");
        assertThat(m1Lineage.body().get("direction").asText()).isEqualTo("BACKWARD");
        assertThat(m1Lineage.body().get("changeKey").asText()).isEqualTo("ck-chain-2");
        assertThat(m1Lineage.body().get("segmentLineage")).hasSize(2);

        // 新段键全局唯一：历史新段键 n1 不能再次使用（409）
        ApiResult reuse = structureChange(docId, body(newRequestId(), "ck-chain-3", "SPLIT", 9, 0,
                List.of("s2"), Map.of("s2", 1),
                List.of(newSegment("n1", "重复键"), newSegment("p2", "b")),
                Map.of("en", List.of(
                                mapping("n1", List.of(fragment("s2", 0, 2))),
                                mapping("p2", List.of(fragment("s2", 2, 5)))),
                        "ja", List.of(
                                mapping("n1", List.of(fragment("s2", 0, 2))),
                                mapping("p2", List.of(fragment("s2", 2, 5)))))));
        assertThat(reuse.status()).isEqualTo(409);
        // 失败不推进版本
        assertThat(jdbc.queryForObject(
                "SELECT draft_version FROM document WHERE document_id = ?", Integer.class, docId)).isEqualTo(9);
    }

    @Test
    @DisplayName("旧译文缺失：参考候选为空串且无片段，补齐该语言映射语言集合仍须完整")
    void splitWithoutOldTranslationProducesEmptyReference() throws Exception {
        // 单语言文档，s1 无译文
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"原文\"}]");
        String splitBody = body(newRequestId(), "ck-empty", "SPLIT", 1, 0,
                List.of("s1"), Map.of("s1", 1),
                List.of(newSegment("n1", "甲"), newSegment("n2", "乙")),
                Map.of("en", List.of(mapping("n1", List.of()), mapping("n2", List.of()))));
        ApiResult result = structureChange(docId, splitBody);
        assertThat(result.status()).isEqualTo(201);
        assertThat(result.body().get("references")).hasSize(2);
        assertThat(result.body().get("references").get(0).get("content").asText()).isEmpty();
        assertThat(result.body().get("references").get(0).get("boundaries")).isEmpty();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM translation_lineage WHERE document_id = ?", Integer.class, docId)).isZero();

        // 旧译文为空却携带片段边界 422
        long docId2 = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"原文\"}]");
        String bad = body(newRequestId(), "ck-empty-bad", "SPLIT", 1, 0,
                List.of("s1"), Map.of("s1", 1),
                List.of(newSegment("n1", "甲"), newSegment("n2", "乙")),
                Map.of("en", List.of(
                        mapping("n1", List.of()),
                        mapping("n2", List.of(fragment("s1", 0, 0))))));
        assertThat(structureChange(docId2, bad).status()).isEqualTo(422);
    }

    @Test
    @DisplayName("被取代旧段不可再修订源文或提交译文（按 404/422 处理），新段须按版本 1 重新翻译")
    void supersededSegmentsAreNotEditable() throws Exception {
        long docId = preparedDocument();
        ApiResult split = structureChange(docId, body(newRequestId(), "ck-edit", "SPLIT", 7, 0,
                List.of("s1"), Map.of("s1", 1),
                List.of(newSegment("n1", "拆出甲"), newSegment("n2", "拆出乙")), splitS1Mappings()));
        assertThat(split.status()).isEqualTo(201);

        // 旧段源文修订：段不是当前段，404
        ApiResult reviseOld = putJson("/api/documents/" + docId + "/segments/s1/source",
                "{\"requestId\":\"" + newRequestId() + "\",\"sourceText\":\"x\"}");
        assertThat(reviseOld.status()).isEqualTo(404);

        // 新段初始源文版本 1，可正常提交译文
        ApiResult submitNew = submitTranslation(docId, "n1", "en", "alice", "fresh", 1, newRequestId());
        assertThat(submitNew.status()).isEqualTo(200);
        assertThat(submitNew.body().get("sourceVersion").asInt()).isEqualTo(1);
        assertThat(submitNew.body().get("translationVersion").asInt()).isEqualTo(1);
    }
}
