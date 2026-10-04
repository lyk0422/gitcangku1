package com.example.starter.translation;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 批注锚点与定点编辑集成测试：真实 H2（MODE=MySQL）上验证公开业务链、失败分支与状态迁移。
 */
class AnnotationApiTest extends AbstractIntegrationTest {

    private int draftVersion(long documentId) {
        Integer version = jdbc.queryForObject(
                "SELECT draft_version FROM document WHERE document_id = ?", Integer.class, documentId);
        return version == null ? -1 : version;
    }

    private int publishedVersion(long documentId) {
        Integer version = jdbc.queryForObject(
                "SELECT published_version FROM document WHERE document_id = ?", Integer.class, documentId);
        return version == null ? -1 : version;
    }

    private ApiResult createOneAnnotation(long documentId, String segmentId, String language, String actorId,
                                          int expectedTranslationVersion, int start, int end, String bodyText,
                                          String requestId) throws Exception {
        String annotations = "[{\"start\":" + start + ",\"end\":" + end + ",\"body\":\"" + bodyText + "\"}]";
        return createAnnotations(documentId, segmentId, language, actorId, expectedTranslationVersion,
                annotations, requestId);
    }

    @Test
    @DisplayName("合成样例：A😀BC 批注 [2,4)，位置 1 插入 X 平移为 [3,5)，再删除 B 孤立且不能批准")
    void syntheticAnchorMigrationScenario() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"源文\"}]");
        ApiResult submit = submitTranslation(docId, "s1", "en", "alice", "A😀BC", 1, newRequestId());
        assertThat(submit.status()).isEqualTo(200);
        assertThat(submit.body().get("translationVersion").asInt()).isEqualTo(1);

        // 批注 BC 为 [2,4)（A、😀、B、C 共 4 个码点）
        ApiResult created = createOneAnnotation(docId, "s1", "en", "bob", 1, 2, 4, "检查BC", newRequestId());
        assertThat(created.status()).isEqualTo(201);
        JsonNode annotation = created.body().get("annotations").get(0);
        long annotationId = annotation.get("annotationId").asLong();
        assertThat(annotation.get("status").asText()).isEqualTo("OPEN");
        assertThat(annotation.get("start").asInt()).isEqualTo(2);
        assertThat(annotation.get("end").asInt()).isEqualTo(4);
        assertThat(annotation.get("quote").asText()).isEqualTo("BC");
        assertThat(annotation.get("translationVersion").asInt()).isEqualTo(1);

        // 在位置 1 插入 X：批注平移为 [3,5)
        ApiResult insert = editTranslation(docId, "s1", "en", 1, 1, 1, "\"X\"", newRequestId());
        assertThat(insert.status()).isEqualTo(200);
        assertThat(insert.body().get("translationVersion").asInt()).isEqualTo(2);
        assertThat(insert.body().get("shiftedAnnotations").asInt()).isEqualTo(1);
        assertThat(insert.body().get("orphanedAnnotations").asInt()).isEqualTo(0);
        JsonNode listed = listAnnotations(docId, "s1", "en").body().get("annotations").get(0);
        assertThat(listed.get("start").asInt()).isEqualTo(3);
        assertThat(listed.get("end").asInt()).isEqualTo(5);
        assertThat(listed.get("status").asText()).isEqualTo("OPEN");
        String content = jdbc.queryForObject(
                "SELECT content FROM translation WHERE document_id = ? AND segment_id = 's1' AND language = 'en'",
                String.class, docId);
        assertThat(content).isEqualTo("AX😀BC");

        // 删除 B（码点位置 [3,4)）：与批注相交，批注孤立
        ApiResult delete = editTranslation(docId, "s1", "en", 2, 3, 4, "\"\"", newRequestId());
        assertThat(delete.status()).isEqualTo(200);
        assertThat(delete.body().get("translationVersion").asInt()).isEqualTo(3);
        assertThat(delete.body().get("orphanedAnnotations").asInt()).isEqualTo(1);
        JsonNode orphaned = listAnnotations(docId, "s1", "en").body().get("annotations").get(0);
        assertThat(orphaned.get("status").asText()).isEqualTo("ORPHAN");
        assertThat(orphaned.has("start")).isFalse();
        assertThat(orphaned.has("end")).isFalse();
        assertThat(orphaned.get("quote").asText()).isEqualTo("BC");

        // 存在未解决批注不能批准
        ApiResult approveBlocked = approve(docId, "s1", "en", "carol", 3, newRequestId());
        assertThat(approveBlocked.status()).isEqualTo(422);

        // 仅创建人可确认解决；解决后可批准
        ApiResult resolveByOther = resolveAnnotation(docId, "s1", "en", annotationId, "carol", 3, newRequestId());
        assertThat(resolveByOther.status()).isEqualTo(422);
        ApiResult resolved = resolveAnnotation(docId, "s1", "en", annotationId, "bob", 3, newRequestId());
        assertThat(resolved.status()).isEqualTo(200);
        assertThat(resolved.body().get("status").asText()).isEqualTo("RESOLVED");
        ApiResult approve = approve(docId, "s1", "en", "carol", 3, newRequestId());
        assertThat(approve.status()).isEqualTo(200);
    }

    @Test
    @DisplayName("新增批注与定点编辑均使当前批准失效；未解决批注阻止发布；旧发布快照不改写")
    void approvalInvalidationAndPublishBlocking() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"源文\"}]");
        submitTranslation(docId, "s1", "en", "alice", "ABCD", 1, newRequestId());
        assertThat(approve(docId, "s1", "en", "bob", 1, newRequestId()).status()).isEqualTo(200);

        // 新增批注使当前批准失效，且未解决批注阻止发布
        ApiResult created = createOneAnnotation(docId, "s1", "en", "carol", 1, 1, 3, "确认BC", newRequestId());
        long annotationId = created.body().get("annotations").get(0).get("annotationId").asLong();
        ApiResult publishBlocked = publish(docId, draftVersion(docId), publishedVersion(docId), newRequestId());
        assertThat(publishBlocked.status()).isEqualTo(422);

        // 解决批注后仍需重新批准（原批准已失效）
        assertThat(resolveAnnotation(docId, "s1", "en", annotationId, "carol", 1, newRequestId()).status())
                .isEqualTo(200);
        ApiResult publishMissingApproval = publish(docId, draftVersion(docId), publishedVersion(docId),
                newRequestId());
        assertThat(publishMissingApproval.status()).isEqualTo(422);
        assertThat(approve(docId, "s1", "en", "bob", 1, newRequestId()).status()).isEqualTo(200);

        // 发布后定点编辑使批准失效，旧发布快照不改写
        assertThat(publish(docId, draftVersion(docId), publishedVersion(docId), newRequestId()).status())
                .isEqualTo(201);
        assertThat(editTranslation(docId, "s1", "en", 1, 0, 1, "\"Z\"", newRequestId()).status()).isEqualTo(200);
        ApiResult publishAfterEdit = publish(docId, draftVersion(docId), publishedVersion(docId), newRequestId());
        assertThat(publishAfterEdit.status()).isEqualTo(422);
        ApiResult release = getJson("/api/documents/" + docId + "/releases/1");
        assertThat(release.status()).isEqualTo(200);
        assertThat(release.body().at("/segments/0/translations/0/content").asText()).isEqualTo("ABCD");

        // 重新批准新版本后可再次发布
        assertThat(approve(docId, "s1", "en", "bob", 2, newRequestId()).status()).isEqualTo(200);
        ApiResult republish = publish(docId, draftVersion(docId), publishedVersion(docId), newRequestId());
        assertThat(republish.status()).isEqualTo(201);
        assertThat(republish.body().get("publishedVersion").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("区间越界与空区间返回 400；一次超过 20 条批注返回 400")
    void rangeValidationReturns400() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"源文\"}]");
        submitTranslation(docId, "s1", "en", "alice", "ABCD", 1, newRequestId());

        // 批注空区间与越界
        assertThat(createOneAnnotation(docId, "s1", "en", "bob", 1, 2, 2, "空区间", newRequestId()).status())
                .isEqualTo(400);
        assertThat(createOneAnnotation(docId, "s1", "en", "bob", 1, 3, 5, "越界", newRequestId()).status())
                .isEqualTo(400);
        assertThat(createOneAnnotation(docId, "s1", "en", "bob", 1, 3, 2, "倒置", newRequestId()).status())
                .isEqualTo(400);
        // 编辑区间倒置与越界
        assertThat(editTranslation(docId, "s1", "en", 1, 3, 2, "\"X\"", newRequestId()).status()).isEqualTo(400);
        assertThat(editTranslation(docId, "s1", "en", 1, 0, 5, "\"X\"", newRequestId()).status()).isEqualTo(400);
        // 一次 21 条批注超出上限
        StringBuilder annotations = new StringBuilder("[");
        for (int i = 0; i < 21; i++) {
            if (i > 0) {
                annotations.append(',');
            }
            annotations.append("{\"start\":0,\"end\":1,\"body\":\"批注").append(i).append("\"}");
        }
        annotations.append(']');
        assertThat(createAnnotations(docId, "s1", "en", "bob", 1, annotations.toString(), newRequestId()).status())
                .isEqualTo(400);
        // 全部失败不占 requestId 键，也不产生批注（request_log 仅有建文档与提交译文两条成功记录）
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM annotation", Integer.class)).isEqualTo(0);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM request_log", Integer.class)).isEqualTo(2);
    }

    @Test
    @DisplayName("编辑、批注创建与解决携带错误期望版本返回 409")
    void versionConflictsReturn409() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"源文\"}]");
        submitTranslation(docId, "s1", "en", "alice", "ABCD", 1, newRequestId());

        assertThat(createOneAnnotation(docId, "s1", "en", "bob", 2, 0, 1, "版本错", newRequestId()).status())
                .isEqualTo(409);
        assertThat(editTranslation(docId, "s1", "en", 2, 0, 1, "\"X\"", newRequestId()).status()).isEqualTo(409);

        ApiResult created = createOneAnnotation(docId, "s1", "en", "bob", 1, 0, 1, "检查A", newRequestId());
        long annotationId = created.body().get("annotations").get(0).get("annotationId").asLong();
        assertThat(resolveAnnotation(docId, "s1", "en", annotationId, "bob", 2, newRequestId()).status())
                .isEqualTo(409);
        // 失败后状态未变
        JsonNode annotation = listAnnotations(docId, "s1", "en").body().get("annotations").get(0);
        assertThat(annotation.get("status").asText()).isEqualTo("OPEN");
    }

    @Test
    @DisplayName("存在未解决批注时普通全文替换返回 409，解决后可提交")
    void fullReplaceBlockedByUnresolvedAnnotation() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"源文\"}]");
        submitTranslation(docId, "s1", "en", "alice", "ABCD", 1, newRequestId());
        ApiResult created = createOneAnnotation(docId, "s1", "en", "bob", 1, 0, 2, "检查AB", newRequestId());
        long annotationId = created.body().get("annotations").get(0).get("annotationId").asLong();

        ApiResult replace = submitTranslation(docId, "s1", "en", "alice", "WXYZ", 1, newRequestId());
        assertThat(replace.status()).isEqualTo(409);
        // 全文替换被拒绝后译文与版本不变
        String content = jdbc.queryForObject(
                "SELECT content FROM translation WHERE document_id = ? AND segment_id = 's1' AND language = 'en'",
                String.class, docId);
        assertThat(content).isEqualTo("ABCD");

        assertThat(resolveAnnotation(docId, "s1", "en", annotationId, "bob", 1, newRequestId()).status())
                .isEqualTo(200);
        ApiResult replaced = submitTranslation(docId, "s1", "en", "alice", "WXYZ", 1, newRequestId());
        assertThat(replaced.status()).isEqualTo(200);
        assertThat(replaced.body().get("translationVersion").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("解决规则：批注不存在 404，重复解决 422；孤立批注不随后续编辑复活")
    void resolveRulesAndOrphanPersistence() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"源文\"}]");
        submitTranslation(docId, "s1", "en", "alice", "ABCD", 1, newRequestId());
        assertThat(resolveAnnotation(docId, "s1", "en", 999L, "bob", 1, newRequestId()).status()).isEqualTo(404);

        ApiResult created = createOneAnnotation(docId, "s1", "en", "bob", 1, 1, 3, "检查BC", newRequestId());
        long annotationId = created.body().get("annotations").get(0).get("annotationId").asLong();
        // 替换 [1,3) 使批注孤立
        assertThat(editTranslation(docId, "s1", "en", 1, 1, 3, "\"ZZZ\"", newRequestId()).status()).isEqualTo(200);
        // 后续编辑不复活孤立批注
        assertThat(editTranslation(docId, "s1", "en", 2, 0, 1, "\"Q\"", newRequestId()).status()).isEqualTo(200);
        JsonNode annotation = listAnnotations(docId, "s1", "en").body().get("annotations").get(0);
        assertThat(annotation.get("status").asText()).isEqualTo("ORPHAN");
        assertThat(annotation.get("quote").asText()).isEqualTo("BC");
        assertThat(annotation.get("translationVersion").asInt()).isEqualTo(1);

        // 解决孤立批注后不能重复解决
        assertThat(resolveAnnotation(docId, "s1", "en", annotationId, "bob", 3, newRequestId()).status())
                .isEqualTo(200);
        assertThat(resolveAnnotation(docId, "s1", "en", annotationId, "bob", 3, newRequestId()).status())
                .isEqualTo(422);
    }

    @Test
    @DisplayName("纯插入边界：严格落在批注内部孤立，落在终点不变")
    void pureInsertionBoundary() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"源文\"}]");
        submitTranslation(docId, "s1", "en", "alice", "ABCD", 1, newRequestId());
        ApiResult created = createAnnotations(docId, "s1", "en", "bob", 1,
                "[{\"start\":1,\"end\":3,\"body\":\"内部\"},{\"start\":1,\"end\":3,\"body\":\"终点\"}]",
                newRequestId());
        assertThat(created.status()).isEqualTo(201);

        // 位置 2 插入：严格在 [1,3) 内部，两条批注均孤立
        ApiResult insertInside = editTranslation(docId, "s1", "en", 1, 2, 2, "\"X\"", newRequestId());
        assertThat(insertInside.body().get("orphanedAnnotations").asInt()).isEqualTo(2);
        // 位置 4（新内容 ABXCD 中 D 之后）插入：已孤立批注不受影响
        assertThat(editTranslation(docId, "s1", "en", 2, 5, 5, "\"Y\"", newRequestId()).status()).isEqualTo(200);
        JsonNode annotations = listAnnotations(docId, "s1", "en").body().get("annotations");
        assertThat(annotations.get(0).get("status").asText()).isEqualTo("ORPHAN");
        assertThat(annotations.get(1).get("status").asText()).isEqualTo("ORPHAN");
    }

    @Test
    @DisplayName("幂等：同键同参重放首次完整响应，同键异参 409，失败不占键")
    void idempotencyRules() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"源文\"}]");
        submitTranslation(docId, "s1", "en", "alice", "ABCD", 1, newRequestId());

        // 失败不占键：越界 400 后同键换合法参数可成功
        String failedRequestId = newRequestId();
        assertThat(createOneAnnotation(docId, "s1", "en", "bob", 1, 0, 9, "越界", failedRequestId).status())
                .isEqualTo(400);
        assertThat(createOneAnnotation(docId, "s1", "en", "bob", 1, 0, 2, "检查AB", failedRequestId).status())
                .isEqualTo(201);

        // 同键同参重放首次完整响应（含同一 annotationId），不重复落库
        String requestId = newRequestId();
        ApiResult first = createOneAnnotation(docId, "s1", "en", "bob", 1, 2, 4, "检查CD", requestId);
        ApiResult replay = createOneAnnotation(docId, "s1", "en", "bob", 1, 2, 4, "检查CD", requestId);
        assertThat(first.status()).isEqualTo(201);
        assertThat(replay.status()).isEqualTo(201);
        assertThat(replay.body().toString()).isEqualTo(first.body().toString());
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM annotation WHERE document_id = ?", Integer.class, docId)).isEqualTo(2);

        // 同键异参 409
        assertThat(createOneAnnotation(docId, "s1", "en", "bob", 1, 0, 1, "不同参数", requestId).status())
                .isEqualTo(409);

        // 定点编辑同键重放：版本只递增一次
        String editRequestId = newRequestId();
        ApiResult editFirst = editTranslation(docId, "s1", "en", 1, 0, 0, "\"X\"", editRequestId);
        ApiResult editReplay = editTranslation(docId, "s1", "en", 1, 0, 0, "\"X\"", editRequestId);
        assertThat(editFirst.status()).isEqualTo(200);
        assertThat(editReplay.status()).isEqualTo(200);
        assertThat(editReplay.body().toString()).isEqualTo(editFirst.body().toString());
        Integer version = jdbc.queryForObject(
                "SELECT translation_version FROM translation WHERE document_id = ? AND segment_id = 's1' "
                        + "AND language = 'en'", Integer.class, docId);
        assertThat(version).isEqualTo(2);
    }

    @Test
    @DisplayName("并发同版本定点编辑：仅一个成功，其余 409，最终译文版本与内容一致")
    void concurrentEditsSameVersion() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"源文\"}]");
        submitTranslation(docId, "s1", "en", "alice", "ABCD", 1, newRequestId());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        Future<ApiResult> editA = pool.submit(() -> {
            gate.await();
            return editTranslation(docId, "s1", "en", 1, 0, 0, "\"X\"", newRequestId());
        });
        Future<ApiResult> editB = pool.submit(() -> {
            gate.await();
            return editTranslation(docId, "s1", "en", 1, 4, 4, "\"Y\"", newRequestId());
        });
        gate.countDown();
        ApiResult resultA = editA.get(30, TimeUnit.SECONDS);
        ApiResult resultB = editB.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        List<Integer> statuses = List.of(resultA.status(), resultB.status());
        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        Integer version = jdbc.queryForObject(
                "SELECT translation_version FROM translation WHERE document_id = ? AND segment_id = 's1' "
                        + "AND language = 'en'", Integer.class, docId);
        assertThat(version).isEqualTo(2);
        String content = jdbc.queryForObject(
                "SELECT content FROM translation WHERE document_id = ? AND segment_id = 's1' AND language = 'en'",
                String.class, docId);
        assertThat(content).isIn("XABCD", "ABCDY");
    }

    @Test
    @DisplayName("编辑与解决并发：同版本原子裁决，对应一个一致的批注状态")
    void concurrentEditAndResolve() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"源文\"}]");
        submitTranslation(docId, "s1", "en", "alice", "ABCD", 1, newRequestId());
        ApiResult created = createOneAnnotation(docId, "s1", "en", "bob", 1, 1, 3, "检查BC", newRequestId());
        long annotationId = created.body().get("annotations").get(0).get("annotationId").asLong();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        // 删除 [0,1)：若批注仍 OPEN 则平移为 [0,2)
        Future<ApiResult> edit = pool.submit(() -> {
            gate.await();
            return editTranslation(docId, "s1", "en", 1, 0, 1, "\"\"", newRequestId());
        });
        Future<ApiResult> resolve = pool.submit(() -> {
            gate.await();
            return resolveAnnotation(docId, "s1", "en", annotationId, "bob", 1, newRequestId());
        });
        gate.countDown();
        ApiResult editResult = edit.get(30, TimeUnit.SECONDS);
        ApiResult resolveResult = resolve.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(editResult.status()).isEqualTo(200);
        assertThat(resolveResult.status()).isIn(200, 409);
        JsonNode annotation = listAnnotations(docId, "s1", "en").body().get("annotations").get(0);
        if (resolveResult.status() == 200) {
            // 解决先于编辑：批注保持 RESOLVED，编辑不再迁移
            assertThat(annotation.get("status").asText()).isEqualTo("RESOLVED");
        } else {
            // 编辑先于解决：批注平移为 [0,2)，解决因版本过期 409
            assertThat(annotation.get("status").asText()).isEqualTo("OPEN");
            assertThat(annotation.get("start").asInt()).isEqualTo(0);
            assertThat(annotation.get("end").asInt()).isEqualTo(2);
        }
    }
}