package com.example.starter.translation;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 结构修订（拆分/合并）主流程、失败整体回滚、幂等、并发边界及真实 H2 约束测试。
 */
class StructureChangeApiTest extends AbstractIntegrationTest {

    /** 构造拆分请求：旧段 oldId 拆为 newIds，每种目标语言映射到旧段当前译文版本 1。 */
    private static String splitBody(String requestId, String changeKey, String oldId, int expectedDocVersion,
                                    String[][] newIdAndText, String[] languages) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"requestId\":\"").append(requestId).append("\",");
        sb.append("\"changeKey\":\"").append(changeKey).append("\",");
        sb.append("\"operation\":\"SPLIT\",");
        sb.append("\"expectedDocumentVersion\":").append(expectedDocVersion).append(",");
        sb.append("\"expectedTermVersion\":0,");
        sb.append("\"split\":{\"segmentId\":\"").append(oldId).append("\",");
        sb.append("\"expectedSourceVersion\":1,\"newSegments\":[");
        for (int i = 0; i < newIdAndText.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"segmentId\":\"").append(newIdAndText[i][0]).append("\",");
            sb.append("\"sourceText\":\"").append(newIdAndText[i][1]).append("\"}");
        }
        sb.append("]},\"mappings\":[");
        boolean first = true;
        for (String[] newIdAndText1 : newIdAndText) {
            for (String language : languages) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append("{\"newSegmentId\":\"").append(newIdAndText1[0]).append("\",");
                sb.append("\"language\":\"").append(language).append("\",");
                sb.append("\"fragments\":[{\"segmentId\":\"").append(oldId)
                        .append("\",\"translationVersion\":1}]}");
            }
        }
        sb.append("]}");
        return sb.toString();
    }

    /** 构造合并请求：oldIds（按结构顺序）合为 newId，映射按旧段顺序引用各旧段译文版本 1。 */
    private static String mergeBody(String requestId, String changeKey, List<String> oldIds,
                                    String newId, String newText, int expectedDocVersion,
                                    String[] languages) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"requestId\":\"").append(requestId).append("\",");
        sb.append("\"changeKey\":\"").append(changeKey).append("\",");
        sb.append("\"operation\":\"MERGE\",");
        sb.append("\"expectedDocumentVersion\":").append(expectedDocVersion).append(",");
        sb.append("\"expectedTermVersion\":0,");
        sb.append("\"merge\":{\"segmentIds\":[");
        for (int i = 0; i < oldIds.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(oldIds.get(i)).append('"');
        }
        sb.append("],\"expectedSourceVersions\":[");
        for (int i = 0; i < oldIds.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(1);
        }
        sb.append("],\"newSegment\":{\"segmentId\":\"").append(newId)
                .append("\",\"sourceText\":\"").append(newText).append("\"}},");
        sb.append("\"mappings\":[");
        for (int li = 0; li < languages.length; li++) {
            if (li > 0) {
                sb.append(',');
            }
            sb.append("{\"newSegmentId\":\"").append(newId).append("\",");
            sb.append("\"language\":\"").append(languages[li]).append("\",");
            sb.append("\"fragments\":[");
            for (int i = 0; i < oldIds.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append("{\"segmentId\":\"").append(oldIds.get(i))
                        .append("\",\"translationVersion\":1}");
            }
            sb.append("]}");
        }
        sb.append("]}");
        return sb.toString();
    }

    private long prepareSplitDocument(String[] languages) throws Exception {
        long docId = createDocument(newRequestId(),
                asJsonArray(languages),
                "[{\"segmentId\":\"s1\",\"sourceText\":\"你好世界\"}]");
        submitTranslation(docId, "s1", "en", "alice", "hello-en", 1, newRequestId());
        submitTranslation(docId, "s1", "ja", "carol", "こんにちは", 1, newRequestId());
        return docId;
    }

    private static String asJsonArray(String[] values) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(values[i]).append('"');
        }
        return sb.append(']').toString();
    }

    @Test
    @DisplayName("拆分主流程：原子生成新文档版本，旧段 SUPERSEDED，源段与各语言双向血缘及 REFERENCE 候选落库")
    void splitSuccess() throws Exception {
        long docId = prepareSplitDocument(new String[]{"en", "ja"});
        // 建文档草稿 1，两次译文提交后草稿 3
        String body = splitBody(newRequestId(), "ck-1", "s1", 3,
                new String[][]{{"n1", "你好"}, {"n2", "世界"}}, new String[]{"en", "ja"});

        ApiResult result = structureChange(docId, body);
        assertThat(result.status()).isEqualTo(201);
        assertThat(result.body().get("documentVersion").asInt()).isEqualTo(4);
        assertThat(result.body().get("operation").asText()).isEqualTo("SPLIT");
        assertThat(result.body().get("segments")).hasSize(2);

        // 旧段废止、新段当前且位置连续
        assertThat(jdbc.queryForObject(
                "SELECT status FROM segment WHERE document_id=? AND segment_id='s1'",
                String.class, docId)).isEqualTo("SUPERSEDED");
        List<String> current = jdbc.queryForList(
                "SELECT segment_id FROM segment WHERE document_id=? AND status='CURRENT' ORDER BY position",
                String.class, docId);
        assertThat(current).containsExactly("n1", "n2");

        // 源段血缘：一旧对两新，序号为新段顺序
        Integer lineageCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM segment_lineage WHERE document_id=? AND change_key='ck-1'",
                Integer.class, docId);
        assertThat(lineageCount).isEqualTo(2);

        // REFERENCE 候选：按映射直接拼接（此处每新段仅一片），片段边界正确
        JsonNode references = result.body().get("references");
        assertThat(references).hasSize(4);
        JsonNode n1En = references.get(0);
        assertThat(n1En.get("segmentId").asText()).isEqualTo("n1");
        assertThat(n1En.get("language").asText()).isEqualTo("en");
        assertThat(n1En.get("content").asText()).isEqualTo("hello-en");
        assertThat(n1En.get("fragments")).hasSize(1);
        assertThat(n1En.get("fragments").get(0).get("startOffset").asInt()).isZero();
        assertThat(n1En.get("fragments").get(0).get("endOffset").asInt())
                .isEqualTo("hello-en".length());
        Integer referenceRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM translation_reference WHERE document_id=?", Integer.class, docId);
        assertThat(referenceRows).isEqualTo(4);
        Integer fragmentRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM lineage_fragment WHERE document_id=?", Integer.class, docId);
        assertThat(fragmentRows).isEqualTo(4);

        // 当前结构只读查询：仅当前段，按位置有序
        ApiResult structure = getStructure(docId);
        assertThat(structure.status()).isEqualTo(200);
        assertThat(structure.body().get("documentVersion").asInt()).isEqualTo(4);
        assertThat(structure.body().get("segments")).hasSize(2);
        assertThat(structure.body().get("segments").get(0).get("segmentId").asText()).isEqualTo("n1");
        assertThat(structure.body().get("segments").get(0).get("position").asInt()).isZero();

        // 新→旧跨语言血缘查询
        ApiResult newLineage = getLineage(docId, "n1");
        assertThat(newLineage.status()).isEqualTo(200);
        assertThat(newLineage.body().get("current").asBoolean()).isTrue();
        assertThat(newLineage.body().get("sources")).hasSize(1);
        assertThat(newLineage.body().get("sources").get(0).get("segmentId").asText()).isEqualTo("s1");
        assertThat(newLineage.body().get("references")).hasSize(2);
        assertThat(newLineage.body().get("references").toString()).contains("hello-en", "こんにちは");

        // 旧→新跨语言血缘查询：旧段标记非当前，派生出两个新段
        ApiResult oldLineage = getLineage(docId, "s1");
        assertThat(oldLineage.body().get("current").asBoolean()).isFalse();
        assertThat(oldLineage.body().get("derived")).hasSize(2);
        assertThat(oldLineage.body().get("derivedTranslations")).hasSize(4);

        // 历史不存在段 404
        assertThat(getLineage(docId, "ghost").status()).isEqualTo(404);
    }

    @Test
    @DisplayName("拆分后批准/术语校验/发布资格全部失效：新段须重新翻译与批准后才能发布")
    void splitInvalidatesApprovalsAndRequiresReedit() throws Exception {
        long docId = prepareSplitDocument(new String[]{"en"});
        approve(docId, "s1", "en", "bob", 1, newRequestId());
        // 先发布一次（草稿版本 2），历史快照随后不得改变
        ApiResult firstPublish = publish(docId, 2, 0, newRequestId());
        assertThat(firstPublish.status()).isEqualTo(201);
        String snapshotBefore = jdbc.queryForObject(
                "SELECT snapshot_json FROM release_snapshot WHERE document_id=? AND published_version=1",
                String.class, docId);

        ApiResult split = structureChange(docId, splitBody(newRequestId(), "ck-split", "s1", 2,
                new String[][]{{"n1", "你好"}, {"n2", "世界"}}, new String[]{"en"}));
        assertThat(split.status()).isEqualTo(201);

        // 新段缺译：发布 422；旧批准行保留但不再有效
        assertThat(publish(docId, 3, 1, newRequestId()).status()).isEqualTo(422);
        Integer oldApproval = jdbc.queryForObject(
                "SELECT COUNT(*) FROM approval WHERE document_id=? AND segment_id='s1'",
                Integer.class, docId);
        assertThat(oldApproval).isEqualTo(1);

        // 废止段不能再修订源文/提交译文/批准
        assertThat(putJson("/api/documents/" + docId + "/segments/s1/source",
                "{\"requestId\":\"" + newRequestId() + "\",\"sourceText\":\"x\"}").status())
                .isEqualTo(422);
        assertThat(submitTranslation(docId, "s1", "en", "alice", "x", 1, newRequestId()).status())
                .isEqualTo(422);
        assertThat(approve(docId, "s1", "en", "bob", 1, newRequestId()).status()).isEqualTo(422);

        // REFERENCE 候选不构成译文：直接为新段批准 404（无译文）
        assertThat(approve(docId, "n1", "en", "bob", 1, newRequestId()).status()).isEqualTo(404);

        // 新段重新翻译、批准后发布成功
        submitTranslation(docId, "n1", "en", "alice", "hi", 1, newRequestId());
        submitTranslation(docId, "n2", "en", "alice", "world", 1, newRequestId());
        approve(docId, "n1", "en", "bob", 1, newRequestId());
        approve(docId, "n2", "en", "bob", 1, newRequestId());
        ApiResult secondPublish = publish(docId, 5, 1, newRequestId());
        assertThat(secondPublish.status()).isEqualTo(201);
        assertThat(secondPublish.body().get("publishedVersion").asInt()).isEqualTo(2);

        // 历史发布快照不变
        ApiResult release1 = getJson("/api/documents/" + docId + "/releases/1");
        assertThat(release1.body().get("segments")).hasSize(1);
        assertThat(release1.body().get("segments").get(0).get("segmentId").asText()).isEqualTo("s1");
        String snapshotAfter = jdbc.queryForObject(
                "SELECT snapshot_json FROM release_snapshot WHERE document_id=? AND published_version=1",
                String.class, docId);
        assertThat(snapshotAfter).isEqualTo(snapshotBefore);
    }

    @Test
    @DisplayName("合并主流程：连续多段合为一段，旧译文按序拼接并记录片段字符边界，位置重排")
    void mergeSuccess() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"AAA\"},"
                        + "{\"segmentId\":\"s2\",\"sourceText\":\"BBB\"},"
                        + "{\"segmentId\":\"s3\",\"sourceText\":\"CCC\"}]");
        submitTranslation(docId, "s1", "en", "alice", "aaa", 1, newRequestId());
        submitTranslation(docId, "s2", "en", "alice", "bbb", 1, newRequestId());
        submitTranslation(docId, "s3", "en", "alice", "ccc", 1, newRequestId());
        // 草稿版本 4
        ApiResult result = structureChange(docId, mergeBody(newRequestId(), "ck-merge",
                List.of("s2", "s3"), "m1", "BBBCCC", 4, new String[]{"en"}));
        assertThat(result.status()).isEqualTo(201);
        assertThat(result.body().get("documentVersion").asInt()).isEqualTo(5);
        assertThat(result.body().get("segments")).hasSize(1);

        List<String> current = jdbc.queryForList(
                "SELECT segment_id FROM segment WHERE document_id=? AND status='CURRENT' ORDER BY position",
                String.class, docId);
        assertThat(current).containsExactly("s1", "m1");

        JsonNode reference = result.body().get("references").get(0);
        assertThat(reference.get("content").asText()).isEqualTo("bbbccc");
        assertThat(reference.get("fragments")).hasSize(2);
        assertThat(reference.get("fragments").get(0).get("segmentId").asText()).isEqualTo("s2");
        assertThat(reference.get("fragments").get(0).get("startOffset").asInt()).isZero();
        assertThat(reference.get("fragments").get(0).get("endOffset").asInt()).isEqualTo(3);
        assertThat(reference.get("fragments").get(1).get("segmentId").asText()).isEqualTo("s3");
        assertThat(reference.get("fragments").get(1).get("startOffset").asInt()).isEqualTo(3);
        assertThat(reference.get("fragments").get(1).get("endOffset").asInt()).isEqualTo(6);

        // 源段血缘：两旧对一新，序号为旧段连续顺序
        assertThat(getLineage(docId, "m1").body().get("sources")).hasSize(2);
        assertThat(getLineage(docId, "s2").body().get("derived")).hasSize(1);
        assertThat(getLineage(docId, "s3").body().get("derived").get(0).get("segmentId").asText())
                .isEqualTo("m1");
    }

    @Test
    @DisplayName("失败分支 409：期望文档版本/旧源段版本/旧译文版本/术语版本不符、changeKey 重复、新段键复用历史段")
    void conflictFailures() throws Exception {
        long docId = prepareSplitDocument(new String[]{"en"});
        // 建文档草稿 1，一次译文提交后草稿 2

        // 期望文档版本不符
        ApiResult badDocVersion = structureChange(docId, splitBody(newRequestId(), "ck-a", "s1", 99,
                new String[][]{{"n1", "a"}, {"n2", "b"}}, new String[]{"en"}));
        assertThat(badDocVersion.status()).isEqualTo(409);

        // 旧源段版本不符：先修订源文到版本 2（草稿 3）
        putJson("/api/documents/" + docId + "/segments/s1/source",
                "{\"requestId\":\"" + newRequestId() + "\",\"sourceText\":\"新原文\"}");
        ApiResult badSourceVersion = structureChange(docId, splitBody(newRequestId(), "ck-b", "s1", 3,
                new String[][]{{"n1", "a"}, {"n2", "b"}}, new String[]{"en"}));
        assertThat(badSourceVersion.status()).isEqualTo(409);

        // 旧译文版本不符：重新提交译文到版本 2（基于当前源文版本 2，草稿 4）
        submitTranslation(docId, "s1", "en", "alice", "hello-en-v2", 2, newRequestId());
        String bodyWithStaleTranslation = splitBody(newRequestId(), "ck-c", "s1", 4,
                new String[][]{{"n1", "a"}, {"n2", "b"}}, new String[]{"en"})
                .replace("\"expectedSourceVersion\":1", "\"expectedSourceVersion\":2");
        assertThat(bodyWithStaleTranslation).contains("\"translationVersion\":1");
        ApiResult staleTranslation = structureChange(docId, bodyWithStaleTranslation);
        assertThat(staleTranslation.status()).isEqualTo(409);

        // 期望术语版本不符（文档版本需正确才能到达术语校验）
        String badTermVersion = splitBody(newRequestId(), "ck-d", "s1", 4,
                new String[][]{{"n1", "a"}, {"n2", "b"}}, new String[]{"en"})
                .replace("\"expectedSourceVersion\":1", "\"expectedSourceVersion\":2")
                .replace("\"expectedTermVersion\":0", "\"expectedTermVersion\":7");
        assertThat(structureChange(docId, badTermVersion).status()).isEqualTo(409);

        // changeKey 重复：先成功一次（草稿 5，基于源文版本 2、译文版本 2）
        ApiResult ok = structureChange(docId,
                splitBody(newRequestId(), "ck-dup", "s1", 4,
                        new String[][]{{"n1", "a"}, {"n2", "b"}}, new String[]{"en"})
                        .replace("\"expectedSourceVersion\":1", "\"expectedSourceVersion\":2")
                        .replace("\"translationVersion\":1", "\"translationVersion\":2"));
        assertThat(ok.status()).isEqualTo(201);
        ApiResult duplicateKey = structureChange(docId, splitBody(newRequestId(), "ck-dup", "s1", 5,
                new String[][]{{"x1", "a"}, {"x2", "b"}}, new String[]{"en"}));
        assertThat(duplicateKey.status()).isEqualTo(409);

        // 新段键复用历史（已废止）段：键冲突先于映射校验
        ApiResult reuseHistory = structureChange(docId, mergeBody(newRequestId(), "ck-reuse",
                List.of("n1", "n2"), "s1", "ab", 5, new String[]{"en"}));
        assertThat(reuseHistory.status()).isEqualTo(409);

        // 全部 409 均未产生额外修订
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM structure_change WHERE document_id=?", Integer.class, docId))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("失败分支 422：合并段不连续/乱序、映射语言不完整、片段缺漏重复、引用越界、操作类型非法")
    void unprocessableFailures() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\",\"ja\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"AAA\"},"
                        + "{\"segmentId\":\"s2\",\"sourceText\":\"BBB\"},"
                        + "{\"segmentId\":\"s3\",\"sourceText\":\"CCC\"}]");
        for (String seg : new String[]{"s1", "s2", "s3"}) {
            submitTranslation(docId, seg, "en", "alice", seg + "-en", 1, newRequestId());
            submitTranslation(docId, seg, "ja", "carol", seg + "-ja", 1, newRequestId());
        }
        // 草稿版本 7

        // 不连续：s1 与 s3 之间隔了 s2
        ApiResult nonContiguous = structureChange(docId, mergeBody(newRequestId(), "ck-1",
                List.of("s1", "s3"), "m1", "AC", 7, new String[]{"en", "ja"}));
        assertThat(nonContiguous.status()).isEqualTo(422);

        // 乱序：[s3, s1] 不按结构顺序
        ApiResult wrongOrder = structureChange(docId, mergeBody(newRequestId(), "ck-2",
                List.of("s3", "s1"), "m1", "AC", 7, new String[]{"en", "ja"}));
        assertThat(wrongOrder.status()).isEqualTo(422);

        // 语言集合不完整：拆分只给 en，缺 ja
        String missingLanguage = splitBody(newRequestId(), "ck-3", "s1", 7,
                new String[][]{{"n1", "A"}, {"n2", "A"}}, new String[]{"en"});
        assertThat(structureChange(docId, missingLanguage).status()).isEqualTo(422);

        // 片段重复：手工构造 en 映射引用 s1 两次
        String duplicateFragment = splitBody(newRequestId(), "ck-4", "s1", 7,
                new String[][]{{"n1", "A"}, {"n2", "A"}}, new String[]{"en", "ja"})
                .replace("\"fragments\":[{\"segmentId\":\"s1\",\"translationVersion\":1}]",
                        "\"fragments\":[{\"segmentId\":\"s1\",\"translationVersion\":1},"
                                + "{\"segmentId\":\"s1\",\"translationVersion\":1}]");
        assertThat(structureChange(docId, duplicateFragment).status()).isEqualTo(422);

        // 引用越界：片段引用非本次旧段
        String foreignFragment = splitBody(newRequestId(), "ck-5", "s1", 7,
                new String[][]{{"n1", "A"}, {"n2", "A"}}, new String[]{"en", "ja"})
                .replace("\"segmentId\":\"s1\",\"translationVersion\":1",
                        "\"segmentId\":\"s2\",\"translationVersion\":1");
        assertThat(structureChange(docId, foreignFragment).status()).isEqualTo(422);

        // 操作类型非法：operation=REPLACE 且 split/merge 均为空
        String invalidOp = "{\"requestId\":\"" + newRequestId() + "\",\"changeKey\":\"ck-6\","
                + "\"operation\":\"REPLACE\",\"expectedDocumentVersion\":7,\"expectedTermVersion\":0,"
                + "\"mappings\":[]}";
        assertThat(structureChange(docId, invalidOp).status()).isEqualTo(422);

        // 旧段不存在/已废止
        String ghost = splitBody(newRequestId(), "ck-7", "ghost", 7,
                new String[][]{{"n1", "A"}, {"n2", "A"}}, new String[]{"en", "ja"});
        assertThat(structureChange(docId, ghost).status()).isEqualTo(422);

        // 全部失败后无任何部分写入
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM structure_change WHERE document_id=?", Integer.class, docId)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM segment_lineage WHERE document_id=?", Integer.class, docId)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM lineage_fragment WHERE document_id=?", Integer.class, docId)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM segment WHERE document_id=? AND status='SUPERSEDED'",
                Integer.class, docId)).isZero();
        Integer draftVersion = jdbc.queryForObject(
                "SELECT draft_version FROM document WHERE document_id=?", Integer.class, docId);
        assertThat(draftVersion).isEqualTo(7);
    }

    @Test
    @DisplayName("参数边界 400：拆分 1/6 段、合并 1/6 段、源文本为空、缺少 requestId")
    void requestShapeValidation() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"A\"},{\"segmentId\":\"s2\",\"sourceText\":\"B\"}]");
        submitTranslation(docId, "s1", "en", "alice", "a", 1, newRequestId());
        submitTranslation(docId, "s2", "en", "alice", "b", 1, newRequestId());

        // 拆分仅 1 个新段 → 400
        String oneNew = splitBody(newRequestId(), "ck-1", "s1", 3,
                new String[][]{{"n1", "A"}}, new String[]{"en"});
        assertThat(structureChange(docId, oneNew).status()).isEqualTo(400);

        // 拆分 6 个新段 → 400
        String sixNews = splitBody(newRequestId(), "ck-2", "s1", 3,
                new String[][]{{"n1", "A"}, {"n2", "A"}, {"n3", "A"}, {"n4", "A"}, {"n5", "A"}, {"n6", "A"}},
                new String[]{"en"});
        assertThat(structureChange(docId, sixNews).status()).isEqualTo(400);

        // 新段源文本为空 → 400
        String blankSource = splitBody(newRequestId(), "ck-3", "s1", 3,
                new String[][]{{"n1", ""}, {"n2", "A"}}, new String[]{"en"});
        assertThat(structureChange(docId, blankSource).status()).isEqualTo(400);

        // 合并仅 1 个旧段 → 400（手工构造）
        String oneOld = "{\"requestId\":\"" + newRequestId() + "\",\"changeKey\":\"ck-4\","
                + "\"operation\":\"MERGE\",\"expectedDocumentVersion\":3,\"expectedTermVersion\":0,"
                + "\"merge\":{\"segmentIds\":[\"s1\"],\"expectedSourceVersions\":[1],"
                + "\"newSegment\":{\"segmentId\":\"m1\",\"sourceText\":\"A\"}},\"mappings\":[]}";
        assertThat(structureChange(docId, oneOld).status()).isEqualTo(400);

        // 缺少 requestId → 400
        String noRequestId = "{\"changeKey\":\"ck-5\",\"operation\":\"SPLIT\","
                + "\"expectedDocumentVersion\":3,\"expectedTermVersion\":0,"
                + "\"split\":{\"segmentId\":\"s1\",\"expectedSourceVersion\":1,"
                + "\"newSegments\":[{\"segmentId\":\"n1\",\"sourceText\":\"A\"},"
                + "{\"segmentId\":\"n2\",\"sourceText\":\"B\"}]},\"mappings\":[]}";
        assertThat(structureChange(docId, noRequestId).status()).isEqualTo(400);
    }

    @Test
    @DisplayName("整体回滚：映射校验失败后不残留任何新段/血缘/候选，草稿版本不变，失败不占 changeKey 与 requestId")
    void atomicRollback() throws Exception {
        long docId = prepareSplitDocument(new String[]{"en"});
        // 建文档草稿 1，一次译文提交后草稿 2
        String body = "{\"requestId\":\"" + newRequestId() + "\",\"changeKey\":\"ck-rollback\","
                + "\"operation\":\"SPLIT\",\"expectedDocumentVersion\":2,\"expectedTermVersion\":0,"
                + "\"split\":{\"segmentId\":\"s1\",\"expectedSourceVersion\":1,"
                + "\"newSegments\":[{\"segmentId\":\"n1\",\"sourceText\":\"A\"},"
                + "{\"segmentId\":\"n2\",\"sourceText\":\"B\"}]},\"mappings\":[]}";
        assertThat(structureChange(docId, body).status()).isEqualTo(422);

        // 无部分新段
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM segment WHERE document_id=? AND segment_id IN ('n1','n2')",
                Integer.class, docId)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM structure_change WHERE document_id=?", Integer.class, docId)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM segment_lineage WHERE document_id=?", Integer.class, docId)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM translation_reference WHERE document_id=?", Integer.class, docId)).isZero();
        // 旧段仍当前，草稿版本不变
        assertThat(jdbc.queryForObject(
                "SELECT status FROM segment WHERE document_id=? AND segment_id='s1'",
                String.class, docId)).isEqualTo("CURRENT");
        assertThat(jdbc.queryForObject(
                "SELECT draft_version FROM document WHERE document_id=?", Integer.class, docId)).isEqualTo(2);

        // 失败不占键：同 changeKey、同 requestId 修正后成功（文档版本仍为 2）
        String fixed = body.replace("\"mappings\":[]",
                "\"mappings\":["
                        + "{\"newSegmentId\":\"n1\",\"language\":\"en\","
                        + "\"fragments\":[{\"segmentId\":\"s1\",\"translationVersion\":1}]},"
                        + "{\"newSegmentId\":\"n2\",\"language\":\"en\","
                        + "\"fragments\":[{\"segmentId\":\"s1\",\"translationVersion\":1}]}]");
        ApiResult retry = structureChange(docId, fixed);
        assertThat(retry.status()).isEqualTo(201);
    }

    @Test
    @DisplayName("幂等：同 requestId 同参（含全部有序映射）重放同一结果；异参 409；不同 requestId 同 changeKey 409")
    void idempotency() throws Exception {
        long docId = prepareSplitDocument(new String[]{"en"});
        String requestId = newRequestId();
        String body = splitBody(requestId, "ck-idem", "s1", 2,
                new String[][]{{"n1", "A"}, {"n2", "B"}}, new String[]{"en"});

        ApiResult first = structureChange(docId, body);
        assertThat(first.status()).isEqualTo(201);
        ApiResult replay = structureChange(docId, body);
        assertThat(replay.status()).isEqualTo(201);
        assertThat(replay.body().get("documentVersion").asInt()).isEqualTo(3);

        // 不产生第二次修订
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM structure_change WHERE document_id=?", Integer.class, docId)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM segment WHERE document_id=? AND status='CURRENT'",
                Integer.class, docId)).isEqualTo(2);
        // 建文档、译文提交、结构修订各一条幂等记录，重放不新增
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM request_log", Integer.class)).isEqualTo(3);

        // 同键异参 409（改期望版本）
        String different = body.replace("\"expectedDocumentVersion\":2", "\"expectedDocumentVersion\":99");
        assertThat(structureChange(docId, different).status()).isEqualTo(409);

        // 不同 requestId、同 changeKey：409 且不占新 requestId
        String otherRequest = splitBody(newRequestId(), "ck-idem", "s1", 3,
                new String[][]{{"x1", "A"}, {"x2", "B"}}, new String[]{"en"});
        assertThat(structureChange(docId, otherRequest).status()).isEqualTo(409);
    }

    @Test
    @DisplayName("并发：结构修订与源文修订同时发起，恰好一个成功，无混合文档版本与部分血缘")
    void concurrentStructureAndSourceRevise() throws Exception {
        long docId = prepareSplitDocument(new String[]{"en"});
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        // 单语言文档：建文档草稿 1，一次译文提交后草稿 2
        String structureBody = splitBody(newRequestId(), "ck-concurrent", "s1", 2,
                new String[][]{{"n1", "A"}, {"n2", "B"}}, new String[]{"en"});
        String reviseBody = "{\"requestId\":\"" + newRequestId() + "\",\"sourceText\":\"并发修订原文\"}";
        Future<ApiResult> structureFuture = pool.submit(() -> {
            gate.await();
            return structureChange(docId, structureBody);
        });
        Future<ApiResult> reviseFuture = pool.submit(() -> {
            gate.await();
            return putJson("/api/documents/" + docId + "/segments/s1/source", reviseBody);
        });
        gate.countDown();
        ApiResult structureResult = structureFuture.get(30, TimeUnit.SECONDS);
        ApiResult reviseResult = reviseFuture.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        int success = (structureResult.status() == 201 ? 1 : 0) + (reviseResult.status() == 200 ? 1 : 0);
        assertThat(success).isEqualTo(1);
        assertThat(structureResult.status()).isIn(201, 409);
        assertThat(reviseResult.status()).isIn(200, 422);

        Integer changes = jdbc.queryForObject(
                "SELECT COUNT(*) FROM structure_change WHERE document_id=?", Integer.class, docId);
        Integer draftVersion = jdbc.queryForObject(
                "SELECT draft_version FROM document WHERE document_id=?", Integer.class, docId);
        assertThat(draftVersion).isEqualTo(3);
        if (structureResult.status() == 201) {
            // 结构修订先成功：旧段废止，源文修订被拒，无混合版本
            assertThat(changes).isEqualTo(1);
            assertThat(jdbc.queryForObject(
                    "SELECT status FROM segment WHERE document_id=? AND segment_id='s1'",
                    String.class, docId)).isEqualTo("SUPERSEDED");
            assertThat(jdbc.queryForList(
                    "SELECT segment_id FROM segment WHERE document_id=? AND status='CURRENT' ORDER BY position",
                    String.class, docId)).containsExactly("n1", "n2");
        } else {
            // 源文修订先成功：结构修订 409，无任何结构血缘残留
            assertThat(changes).isZero();
            assertThat(jdbc.queryForObject(
                    "SELECT source_version FROM segment WHERE document_id=? AND segment_id='s1'",
                    Integer.class, docId)).isEqualTo(2);
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM segment_lineage WHERE document_id=?", Integer.class, docId)).isZero();
        }
    }

    @Test
    @DisplayName("并发：两个结构修订同期望版本不同 changeKey，仅一个成功且无部分新段")
    void concurrentTwoStructureChanges() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"A\"},{\"segmentId\":\"s2\",\"sourceText\":\"B\"}]");
        submitTranslation(docId, "s1", "en", "alice", "a", 1, newRequestId());
        submitTranslation(docId, "s2", "en", "alice", "b", 1, newRequestId());
        String body1 = splitBody(newRequestId(), "ck-c1", "s1", 3,
                new String[][]{{"n1", "A1"}, {"n2", "A2"}}, new String[]{"en"});
        String body2 = splitBody(newRequestId(), "ck-c2", "s2", 3,
                new String[][]{{"m1", "B1"}, {"m2", "B2"}}, new String[]{"en"});

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<ApiResult>> futures = new ArrayList<>();
        for (String body : new String[]{body1, body2}) {
            futures.add(pool.submit(() -> {
                gate.await();
                return structureChange(docId, body);
            }));
        }
        gate.countDown();
        int success = 0;
        int conflict = 0;
        for (Future<ApiResult> future : futures) {
            int status = future.get(30, TimeUnit.SECONDS).status();
            if (status == 201) {
                success++;
            } else if (status == 409) {
                conflict++;
            }
        }
        pool.shutdown();
        assertThat(success).isEqualTo(1);
        assertThat(conflict).isEqualTo(1);

        // 输家不留任何片段/血缘
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM structure_change WHERE document_id=?", Integer.class, docId)).isEqualTo(1);
        Integer currentCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM segment WHERE document_id=? AND status='CURRENT'",
                Integer.class, docId);
        assertThat(currentCount).isEqualTo(3);
    }

    @Test
    @DisplayName("并发同 requestId 同参：结构修订只执行一次，全部重放同一新文档版本")
    void concurrentSameRequestIdReplay() throws Exception {
        long docId = prepareSplitDocument(new String[]{"en"});
        String requestId = newRequestId();
        String body = splitBody(requestId, "ck-same", "s1", 2,
                new String[][]{{"n1", "A"}, {"n2", "B"}}, new String[]{"en"});
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<ApiResult>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                gate.await();
                return structureChange(docId, body);
            }));
        }
        gate.countDown();
        for (Future<ApiResult> future : futures) {
            ApiResult result = future.get(30, TimeUnit.SECONDS);
            assertThat(result.status()).isEqualTo(201);
            assertThat(result.body().get("documentVersion").asInt()).isEqualTo(3);
        }
        pool.shutdown();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM structure_change WHERE document_id=?", Integer.class, docId)).isEqualTo(1);
        // 建文档、译文提交、结构修订各一条幂等记录，并发重放不新增
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM request_log", Integer.class)).isEqualTo(3);
    }

    @Test
    @DisplayName("真实 H2 约束：血缘/候选表唯一约束在数据库层生效（不靠应用 Map 模拟）")
    void h2UniqueConstraintsEnforced() {
        jdbc.update("INSERT INTO document (target_languages, draft_version, published_version, term_version) "
                + "VALUES ('en', 1, 0, 0)");
        Long docId = jdbc.queryForObject("SELECT document_id FROM document", Long.class);
        jdbc.update("INSERT INTO structure_change (document_id, change_key, operation, document_version, "
                + "expected_document_version) VALUES (?, 'ck', 'SPLIT', 1, 1)", docId);
        jdbc.update("INSERT INTO segment_lineage (document_id, change_key, old_segment_id, old_source_version, "
                + "new_segment_id, ordinal) VALUES (?, 'ck', 's1', 1, 'n1', 0)", docId);
        jdbc.update("INSERT INTO translation_reference (document_id, change_key, new_segment_id, language, content) "
                + "VALUES (?, 'ck', 'n1', 'en', 'a')", docId);
        jdbc.update("INSERT INTO lineage_fragment (document_id, change_key, new_segment_id, language, "
                + "old_segment_id, old_translation_version, ordinal, start_offset, end_offset) "
                + "VALUES (?, 'ck', 'n1', 'en', 's1', 1, 0, 0, 1)", docId);

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO segment_lineage (document_id, change_key, old_segment_id, old_source_version, "
                        + "new_segment_id, ordinal) VALUES (?, 'ck', 's1', 1, 'n1', 0)", docId))
                .isInstanceOf(DuplicateKeyException.class);
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO translation_reference (document_id, change_key, new_segment_id, language, content) "
                        + "VALUES (?, 'ck', 'n1', 'en', 'b')", docId))
                .isInstanceOf(DuplicateKeyException.class);
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO lineage_fragment (document_id, change_key, new_segment_id, language, "
                        + "old_segment_id, old_translation_version, ordinal, start_offset, end_offset) "
                        + "VALUES (?, 'ck', 'n1', 'en', 's1', 1, 0, 1, 2)", docId))
                .isInstanceOf(DuplicateKeyException.class);
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO structure_change (document_id, change_key, operation, document_version, "
                        + "expected_document_version) VALUES (?, 'ck', 'MERGE', 1, 1)", docId))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    @DisplayName("changeKey 全局唯一：跨文档复用已提交 changeKey 返回 409；一次事务同时携带两种操作返回 422")
    void changeKeyGlobalUniqueAndNoMixedOperations() throws Exception {
        long doc1 = prepareSplitDocument(new String[]{"en"});
        long doc2 = prepareSplitDocument(new String[]{"en"});
        ApiResult first = structureChange(doc1, splitBody(newRequestId(), "ck-global", "s1", 2,
                new String[][]{{"n1", "A"}, {"n2", "B"}}, new String[]{"en"}));
        assertThat(first.status()).isEqualTo(201);

        ApiResult crossDoc = structureChange(doc2, splitBody(newRequestId(), "ck-global", "s1", 2,
                new String[][]{{"x1", "A"}, {"x2", "B"}}, new String[]{"en"}));
        assertThat(crossDoc.status()).isEqualTo(409);
        // 第二文档不受影响
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM structure_change WHERE document_id=?", Integer.class, doc2)).isZero();

        // 同时携带 split 与 merge：422，且 operation 与二者不一致时优先 422
        String mixed = "{\"requestId\":\"" + newRequestId() + "\",\"changeKey\":\"ck-mix\","
                + "\"operation\":\"SPLIT\",\"expectedDocumentVersion\":2,\"expectedTermVersion\":0,"
                + "\"split\":{\"segmentId\":\"s1\",\"expectedSourceVersion\":1,"
                + "\"newSegments\":[{\"segmentId\":\"n1\",\"sourceText\":\"A\"},"
                + "{\"segmentId\":\"n2\",\"sourceText\":\"B\"}]},"
                + "\"merge\":{\"segmentIds\":[\"s1\",\"s2\"],\"expectedSourceVersions\":[1,1],"
                + "\"newSegment\":{\"segmentId\":\"m1\",\"sourceText\":\"A\"}},\"mappings\":[]}";
        assertThat(structureChange(doc2, mixed).status()).isEqualTo(422);
    }
}
