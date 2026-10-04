package com.example.starter.translation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 批注锚点与定点编辑测试：合成链主流程、区间校验、平移/孤立规则、
 * 批准失效、幂等与并发边界。H2 内存库（MODE=MySQL）真实事务验证。
 */
class AnnotationApiTest extends AbstractIntegrationTest {

    @Test
    @DisplayName("合成链：A😀BC 批注 [2,4)，位置 1 插入 X 后 [3,5)，再删除 B 则 ORPHAN 且不能批准，解决后可批准发布")
    void syntheticChain() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"原文\"}]");
        ApiResult submit = submitTranslation(docId, "s1", "en", "alice", "A😀BC", 1, newRequestId());
        assertThat(submit.status()).isEqualTo(200);
        assertThat("A😀BC".codePointCount(0, "A😀BC".length())).isEqualTo(4);

        // 批注 BC 为 [2,4)，绑定译文版本 1
        ApiResult annotation = createAnnotation(docId, "s1", "en", "carol", 2, 4, "BC译法待确认",
                newRequestId());
        assertThat(annotation.status()).isEqualTo(201);
        long annotationId = annotation.body().get("annotationId").asLong();
        assertThat(annotation.body().get("status").asText()).isEqualTo("OPEN");
        assertThat(annotation.body().get("start").asInt()).isEqualTo(2);
        assertThat(annotation.body().get("end").asInt()).isEqualTo(4);
        assertThat(annotation.body().get("quote").asText()).isEqualTo("BC");
        assertThat(annotation.body().get("translationVersion").asInt()).isEqualTo(1);
        assertThat(annotation.body().get("creator").asText()).isEqualTo("carol");

        // 在位置 1 插入 X：批注起点或更早的纯插入，整体平移为 [3,5)
        ApiResult insert = anchoredEdit(docId, "s1", "en", "alice", 1, 1, 1, "X", newRequestId());
        assertThat(insert.status()).isEqualTo(200);
        assertThat(insert.body().get("translationVersion").asInt()).isEqualTo(2);
        String content = jdbc.queryForObject(
                "SELECT content FROM translation WHERE document_id = ? AND segment_id = 's1'",
                String.class, docId);
        assertThat(content).isEqualTo("AX😀BC");
        ApiResult afterInsert = listAnnotations(docId, "s1", "en");
        assertThat(afterInsert.body().get("annotations").get(0).get("start").asInt()).isEqualTo(3);
        assertThat(afterInsert.body().get("annotations").get(0).get("end").asInt()).isEqualTo(5);
        assertThat(afterInsert.body().get("annotations").get(0).get("status").asText()).isEqualTo("OPEN");
        // 旧批注版本不改写
        assertThat(afterInsert.body().get("annotations").get(0).get("translationVersion").asInt())
                .isEqualTo(1);

        // 删除 B（码点位置 3）：与非空替换区间相交，批注 ORPHAN，只保留原引用
        ApiResult delete = anchoredEdit(docId, "s1", "en", "alice", 2, 3, 4, "", newRequestId());
        assertThat(delete.status()).isEqualTo(200);
        assertThat(delete.body().get("translationVersion").asInt()).isEqualTo(3);
        content = jdbc.queryForObject(
                "SELECT content FROM translation WHERE document_id = ? AND segment_id = 's1'",
                String.class, docId);
        assertThat(content).isEqualTo("AX😀C");
        ApiResult afterDelete = listAnnotations(docId, "s1", "en");
        var orphaned = afterDelete.body().get("annotations").get(0);
        assertThat(orphaned.get("status").asText()).isEqualTo("ORPHAN");
        assertThat(orphaned.get("start").isNull()).isTrue();
        assertThat(orphaned.get("end").isNull()).isTrue();
        assertThat(orphaned.get("quote").asText()).isEqualTo("BC");

        // 存在未解决批注：不能批准、不能发布（422），普通全文替换 409
        assertThat(approve(docId, "s1", "en", "bob", 3, newRequestId()).status()).isEqualTo(422);
        assertThat(publish(docId, 4, 0, newRequestId()).status()).isEqualTo(422);
        assertThat(submitTranslation(docId, "s1", "en", "alice", "全新译文", 1, newRequestId()).status())
                .isEqualTo(409);

        // 仅创建人可解决；解决与编辑、批准同版本原子裁决
        assertThat(resolveAnnotation(docId, "s1", "en", annotationId, "bob", 3, newRequestId()).status())
                .isEqualTo(422);
        assertThat(resolveAnnotation(docId, "s1", "en", annotationId, "carol", 99, newRequestId()).status())
                .isEqualTo(409);
        ApiResult resolved = resolveAnnotation(docId, "s1", "en", annotationId, "carol", 3, newRequestId());
        assertThat(resolved.status()).isEqualTo(200);
        assertThat(resolved.body().get("status").asText()).isEqualTo("RESOLVED");

        // 解决后可批准、可发布
        assertThat(approve(docId, "s1", "en", "bob", 3, newRequestId()).status()).isEqualTo(200);
        ApiResult published = publish(docId, 4, 0, newRequestId());
        assertThat(published.status()).isEqualTo(201);
        assertThat(published.body().get("publishedVersion").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("区间校验：批注空区间或越界 400，编辑区间越界 400，失败不占 requestId 键")
    void rangeValidation() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"原文\"}]");
        submitTranslation(docId, "s1", "en", "alice", "hello", 1, newRequestId());

        assertThat(createAnnotation(docId, "s1", "en", "carol", 2, 2, "空区间", newRequestId()).status())
                .isEqualTo(400);
        assertThat(createAnnotation(docId, "s1", "en", "carol", 0, 6, "越界", newRequestId()).status())
                .isEqualTo(400);
        assertThat(createAnnotation(docId, "s1", "en", "carol", 4, 2, "倒置", newRequestId()).status())
                .isEqualTo(400);
        assertThat(createAnnotation(docId, "s1", "en", "carol", -1, 2, "负值", newRequestId()).status())
                .isEqualTo(400);

        // 失败不占键：同 requestId 修正参数后成功
        String retryKey = newRequestId();
        assertThat(createAnnotation(docId, "s1", "en", "carol", 2, 2, "空区间", retryKey).status())
                .isEqualTo(400);
        assertThat(createAnnotation(docId, "s1", "en", "carol", 1, 3, "修正后", retryKey).status())
                .isEqualTo(201);

        assertThat(anchoredEdit(docId, "s1", "en", "alice", 1, 0, 99, "x", newRequestId()).status())
                .isEqualTo(400);
        assertThat(anchoredEdit(docId, "s1", "en", "alice", 1, 3, 1, "x", newRequestId()).status())
                .isEqualTo(400);
        assertThat(anchoredEdit(docId, "s1", "en", "alice", 1, -1, 2, "x", newRequestId()).status())
                .isEqualTo(400);

        String editRetryKey = newRequestId();
        assertThat(anchoredEdit(docId, "s1", "en", "alice", 1, 0, 99, "x", editRetryKey).status())
                .isEqualTo(400);
        assertThat(anchoredEdit(docId, "s1", "en", "alice", 1, 5, 5, "!", editRetryKey).status())
                .isEqualTo(200);
    }

    @Test
    @DisplayName("平移规则：编辑前方保持、后方平移、相交孤立；纯插入起点平移、内部孤立、终点不变")
    void shiftRules() throws Exception {
        // 替换编辑在前方（[0,1) a→XY，+1）：批注 [2,4) 平移为 [3,5)
        assertShift("abcdef", 2, 4, 0, 1, "XY", "OPEN", 3, 5);
        // 替换编辑在后方（[5,6) f→Z）：批注不变
        assertShift("abcdef", 2, 4, 5, 6, "Z", "OPEN", 2, 4);
        // 替换区间与批注相交（[3,5)→Q）：ORPHAN
        assertShift("abcdef", 2, 4, 3, 5, "Q", "ORPHAN", null, null);
        // 替换区间终点恰为批注起点（[0,2)→W，-1）：平移为 [1,3)
        assertShift("abcdef", 2, 4, 0, 2, "W", "OPEN", 1, 3);
        // 替换区间起点恰为批注终点（[4,6)→W）：不变
        assertShift("abcdef", 2, 4, 4, 6, "W", "OPEN", 2, 4);
        // 纯插入在批注起点（位置 2）：平移为 [3,5)
        assertShift("abcdef", 2, 4, 2, 2, "X", "OPEN", 3, 5);
        // 纯插入严格在批注内部（位置 3）：ORPHAN
        assertShift("abcdef", 2, 4, 3, 3, "X", "ORPHAN", null, null);
        // 纯插入在批注终点（位置 4）：不变
        assertShift("abcdef", 2, 4, 4, 4, "X", "OPEN", 2, 4);
    }

    /** 单条平移用例：建文档、提交译文、建批注 [a,b)、执行一次定点编辑后断言批注状态与位置。 */
    private void assertShift(String content, int a, int b, int editStart, int editEnd, String newText,
                             String expectedStatus, Integer expectedStart, Integer expectedEnd)
            throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"原文\"}]");
        submitTranslation(docId, "s1", "en", "alice", content, 1, newRequestId());
        ApiResult annotation = createAnnotation(docId, "s1", "en", "carol", a, b, "意见", newRequestId());
        assertThat(annotation.status()).isEqualTo(201);
        ApiResult edit = anchoredEdit(docId, "s1", "en", "alice", 1, editStart, editEnd, newText,
                newRequestId());
        assertThat(edit.status()).isEqualTo(200);
        ApiResult list = listAnnotations(docId, "s1", "en");
        var shifted = list.body().get("annotations").get(0);
        assertThat(shifted.get("status").asText()).isEqualTo(expectedStatus);
        if (expectedStart == null) {
            assertThat(shifted.get("start").isNull()).isTrue();
            assertThat(shifted.get("end").isNull()).isTrue();
        } else {
            assertThat(shifted.get("start").asInt()).isEqualTo(expectedStart);
            assertThat(shifted.get("end").asInt()).isEqualTo(expectedEnd);
        }
    }
    @Test
    @DisplayName("批注上限：未解决批注最多 20 条，第 21 条 422；解决一条后可再新增")
    void annotationLimit() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"原文\"}]");
        submitTranslation(docId, "s1", "en", "alice", "0123456789", 1, newRequestId());
        long firstAnnotationId = -1;
        for (int i = 0; i < 20; i++) {
            ApiResult created = createAnnotation(docId, "s1", "en", "carol", 0, 1, "意见" + i,
                    newRequestId());
            assertThat(created.status()).isEqualTo(201);
            if (i == 0) {
                firstAnnotationId = created.body().get("annotationId").asLong();
            }
        }
        assertThat(createAnnotation(docId, "s1", "en", "carol", 0, 1, "第21条", newRequestId()).status())
                .isEqualTo(422);
        assertThat(resolveAnnotation(docId, "s1", "en", firstAnnotationId, "carol", 1, newRequestId())
                .status()).isEqualTo(200);
        assertThat(createAnnotation(docId, "s1", "en", "carol", 0, 1, "补充", newRequestId()).status())
                .isEqualTo(201);
    }

    @Test
    @DisplayName("新增批注使当前批准失效：批注后发布 422，解决并重新批准后发布成功")
    void annotationInvalidatesApproval() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"原文\"}]");
        submitTranslation(docId, "s1", "en", "alice", "hello", 1, newRequestId());
        assertThat(approve(docId, "s1", "en", "bob", 1, newRequestId()).status()).isEqualTo(200);

        ApiResult annotation = createAnnotation(docId, "s1", "en", "carol", 0, 2, "待确认", newRequestId());
        assertThat(annotation.status()).isEqualTo(201);
        long annotationId = annotation.body().get("annotationId").asLong();

        // 批准已失效且存在未解决批注：发布 422，重新批准也 422
        assertThat(publish(docId, 2, 0, newRequestId()).status()).isEqualTo(422);
        assertThat(approve(docId, "s1", "en", "bob", 1, newRequestId()).status()).isEqualTo(422);

        assertThat(resolveAnnotation(docId, "s1", "en", annotationId, "carol", 1, newRequestId()).status())
                .isEqualTo(200);
        assertThat(approve(docId, "s1", "en", "bob", 1, newRequestId()).status()).isEqualTo(200);
        assertThat(publish(docId, 2, 0, newRequestId()).status()).isEqualTo(201);
    }

    @Test
    @DisplayName("定点编辑使原批准失效：编辑后发布 422，按新版本重新批准后发布成功")
    void editInvalidatesApproval() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"原文\"}]");
        submitTranslation(docId, "s1", "en", "alice", "hello", 1, newRequestId());
        assertThat(approve(docId, "s1", "en", "bob", 1, newRequestId()).status()).isEqualTo(200);

        ApiResult edit = anchoredEdit(docId, "s1", "en", "alice", 1, 5, 5, "!", newRequestId());
        assertThat(edit.status()).isEqualTo(200);
        assertThat(edit.body().get("translationVersion").asInt()).isEqualTo(2);

        assertThat(publish(docId, 3, 0, newRequestId()).status()).isEqualTo(422);
        assertThat(approve(docId, "s1", "en", "bob", 1, newRequestId()).status()).isEqualTo(422);
        assertThat(approve(docId, "s1", "en", "bob", 2, newRequestId()).status()).isEqualTo(200);
        assertThat(publish(docId, 3, 0, newRequestId()).status()).isEqualTo(201);
    }

    @Test
    @DisplayName("幂等：批注与编辑同键同参重放首次完整响应，同键异参 409")
    void idempotency() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"原文\"}]");
        submitTranslation(docId, "s1", "en", "alice", "hello", 1, newRequestId());

        String annotationKey = newRequestId();
        ApiResult first = postJson("/api/documents/" + docId + "/segments/s1/translations/en/annotations",
                "{\"requestId\":\"" + annotationKey + "\",\"start\":0,\"end\":2,\"body\":\"意见\"}", "carol");
        assertThat(first.status()).isEqualTo(201);
        ApiResult replay = postJson("/api/documents/" + docId + "/segments/s1/translations/en/annotations",
                "{\"requestId\":\"" + annotationKey + "\",\"start\":0,\"end\":2,\"body\":\"意见\"}", "carol");
        assertThat(replay.status()).isEqualTo(201);
        assertThat(replay.body().get("annotationId").asLong())
                .isEqualTo(first.body().get("annotationId").asLong());
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM annotation WHERE document_id = ?", Integer.class, docId))
                .isEqualTo(1);
        ApiResult conflict = postJson("/api/documents/" + docId + "/segments/s1/translations/en/annotations",
                "{\"requestId\":\"" + annotationKey + "\",\"start\":1,\"end\":3,\"body\":\"意见\"}", "carol");
        assertThat(conflict.status()).isEqualTo(409);

        String editKey = newRequestId();
        String editBody = "{\"requestId\":\"" + editKey + "\",\"expectedTranslationVersion\":1,"
                + "\"start\":0,\"end\":0,\"newText\":\"X\"}";
        ApiResult firstEdit = putJson("/api/documents/" + docId + "/segments/s1/translations/en/edit",
                editBody, "alice");
        assertThat(firstEdit.status()).isEqualTo(200);
        ApiResult replayEdit = putJson("/api/documents/" + docId + "/segments/s1/translations/en/edit",
                editBody, "alice");
        assertThat(replayEdit.status()).isEqualTo(200);
        assertThat(replayEdit.body().get("translationVersion").asInt()).isEqualTo(2);
        Integer version = jdbc.queryForObject(
                "SELECT translation_version FROM translation WHERE document_id = ? AND segment_id = 's1'",
                Integer.class, docId);
        assertThat(version).isEqualTo(2);
        String conflictBody = "{\"requestId\":\"" + editKey + "\",\"expectedTranslationVersion\":1,"
                + "\"start\":1,\"end\":1,\"newText\":\"Y\"}";
        assertThat(putJson("/api/documents/" + docId + "/segments/s1/translations/en/edit",
                conflictBody, "alice").status()).isEqualTo(409);
    }

    @Test
    @DisplayName("资源与状态错误：缺译文 404、语言不符 422、批注不存在 404、重复解决 422")
    void notFoundAndStateErrors() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"原文\"}]");
        assertThat(createAnnotation(docId, "s1", "en", "carol", 0, 1, "无译文", newRequestId()).status())
                .isEqualTo(404);
        assertThat(anchoredEdit(docId, "s1", "en", "alice", 1, 0, 0, "x", newRequestId()).status())
                .isEqualTo(404);

        submitTranslation(docId, "s1", "en", "alice", "hello", 1, newRequestId());
        assertThat(createAnnotation(docId, "s1", "fr", "carol", 0, 1, "语言不符", newRequestId()).status())
                .isEqualTo(422);
        assertThat(resolveAnnotation(docId, "s1", "en", 999999, "carol", 1, newRequestId()).status())
                .isEqualTo(404);

        ApiResult annotation = createAnnotation(docId, "s1", "en", "carol", 0, 2, "意见", newRequestId());
        long annotationId = annotation.body().get("annotationId").asLong();
        assertThat(resolveAnnotation(docId, "s1", "en", annotationId, "carol", 1, newRequestId()).status())
                .isEqualTo(200);
        assertThat(resolveAnnotation(docId, "s1", "en", annotationId, "carol", 1, newRequestId()).status())
                .isEqualTo(422);

        assertThat(listAnnotations(999999, "s1", "en").status()).isEqualTo(404);
    }
}