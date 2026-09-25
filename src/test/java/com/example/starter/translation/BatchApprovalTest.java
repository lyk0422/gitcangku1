package com.example.starter.translation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 译文批量审核 API 测试：主流程、逐条校验失败原子回滚、重复标识、幂等重放与记录查询。
 */
class BatchApprovalTest extends AbstractIntegrationTest {

    /** 建含 s1、s2 两段、en/ja 双语的文档，并提交全部四条译文（草稿版本到 5）。 */
    private long createDocWithTranslations() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\",\"ja\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"你好\"},{\"segmentId\":\"s2\",\"sourceText\":\"世界\"}]");
        submitTranslation(docId, "s1", "en", "alice", "hello", 1, newRequestId());
        submitTranslation(docId, "s1", "ja", "alice", "こんにちは", 1, newRequestId());
        submitTranslation(docId, "s2", "en", "alice", "world", 1, newRequestId());
        submitTranslation(docId, "s2", "ja", "alice", "世界", 1, newRequestId());
        return docId;
    }

    @Test
    @DisplayName("批量审核主流程：原子批准多条译文，不改变草稿版本，与逐条批准状态等价可发布")
    void batchApproveSuccess() throws Exception {
        long docId = createDocWithTranslations();
        ApiResult result = approveBatch(docId, "bob", "batch-1", 5,
                "[{\"segmentId\":\"s2\",\"language\":\"ja\",\"expectedTranslationVersion\":1},"
                        + "{\"segmentId\":\"s1\",\"language\":\"en\",\"expectedTranslationVersion\":1}]");
        assertThat(result.status()).isEqualTo(200);
        assertThat(result.body().get("batchKey").asText()).isEqualTo("batch-1");
        assertThat(result.body().get("documentId").asLong()).isEqualTo(docId);
        assertThat(result.body().get("draftVersion").asInt()).isEqualTo(5);
        assertThat(result.body().get("reviewer").asText()).isEqualTo("bob");
        assertThat(result.body().get("approvedAt").asText()).isNotBlank();
        // 明细按段落与语言稳定排序
        assertThat(result.body().get("items")).hasSize(2);
        assertThat(result.body().get("items").get(0).get("segmentId").asText()).isEqualTo("s1");
        assertThat(result.body().get("items").get(0).get("language").asText()).isEqualTo("en");
        assertThat(result.body().get("items").get(1).get("segmentId").asText()).isEqualTo("s2");

        // 批量审核不改变文档草稿版本
        Integer draftVersion = jdbc.queryForObject(
                "SELECT draft_version FROM document WHERE document_id = ?", Integer.class, docId);
        assertThat(draftVersion).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM approval WHERE document_id = ?",
                Integer.class, docId)).isEqualTo(2);

        // 其余两条走逐条批准入口，两种入口状态等价：发布成功
        approve(docId, "s1", "ja", "carol", 1, newRequestId());
        approve(docId, "s2", "en", "carol", 1, newRequestId());
        ApiResult published = publish(docId, 5, 0, newRequestId());
        assertThat(published.status()).isEqualTo(201);
    }

    @Test
    @DisplayName("部分失败原子回滚：任一条版本不匹配整批 422 并逐条给出原因，不批准任何一条，失败不占键")
    void partialFailureRollsBackWholeBatch() throws Exception {
        long docId = createDocWithTranslations();
        ApiResult result = approveBatch(docId, "bob", "batch-fail", 5,
                "[{\"segmentId\":\"s1\",\"language\":\"en\",\"expectedTranslationVersion\":1},"
                        + "{\"segmentId\":\"s2\",\"language\":\"en\",\"expectedTranslationVersion\":9}]");
        assertThat(result.status()).isEqualTo(422);
        assertThat(result.body().get("error").asText()).isEqualTo("UNPROCESSABLE");
        assertThat(result.body().get("failures")).hasSize(1);
        assertThat(result.body().get("failures").get(0).get("segmentId").asText()).isEqualTo("s2");
        assertThat(result.body().get("failures").get(0).get("reason").asText()).contains("不匹配");

        // 整批回滚：无任何批准、无批量记录
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM approval WHERE document_id = ?",
                Integer.class, docId)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM approval_batch", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM approval_batch_item", Integer.class)).isZero();

        // 失败不占键：同 batchKey 修正参数后成功
        ApiResult retried = approveBatch(docId, "bob", "batch-fail", 5,
                "[{\"segmentId\":\"s1\",\"language\":\"en\",\"expectedTranslationVersion\":1},"
                        + "{\"segmentId\":\"s2\",\"language\":\"en\",\"expectedTranslationVersion\":1}]");
        assertThat(retried.status()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM approval WHERE document_id = ?",
                Integer.class, docId)).isEqualTo(2);
    }

    @Test
    @DisplayName("逐条校验：审核人是作者、译文已批准、译文不存在均整批 422 且逐条给出原因")
    void perItemValidationFailures() throws Exception {
        long docId = createDocWithTranslations();
        // 审核人是作者
        ApiResult byAuthor = approveBatch(docId, "alice", "batch-author", 5,
                "[{\"segmentId\":\"s1\",\"language\":\"en\",\"expectedTranslationVersion\":1}]");
        assertThat(byAuthor.status()).isEqualTo(422);
        assertThat(byAuthor.body().get("failures").get(0).get("reason").asText()).contains("作者");

        // 译文已批准（非待审核）
        approve(docId, "s1", "en", "bob", 1, newRequestId());
        ApiResult already = approveBatch(docId, "carol", "batch-approved", 5,
                "[{\"segmentId\":\"s1\",\"language\":\"en\",\"expectedTranslationVersion\":1},"
                        + "{\"segmentId\":\"s2\",\"language\":\"en\",\"expectedTranslationVersion\":1}]");
        assertThat(already.status()).isEqualTo(422);
        assertThat(already.body().get("failures").get(0).get("reason").asText()).contains("非待审核");
        // 整批回滚：s2/en 未被批准
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM approval WHERE document_id = ?",
                Integer.class, docId)).isEqualTo(1);

        // 译文不存在
        ApiResult missing = approveBatch(docId, "bob", "batch-missing", 5,
                "[{\"segmentId\":\"s9\",\"language\":\"en\",\"expectedTranslationVersion\":1}]");
        assertThat(missing.status()).isEqualTo(422);
        assertThat(missing.body().get("failures").get(0).get("reason").asText()).contains("译文不存在");
    }

    @Test
    @DisplayName("批内译文标识重复返回 400；语言码归一后重复也算重复")
    void duplicateItemReturns400() throws Exception {
        long docId = createDocWithTranslations();
        ApiResult result = approveBatch(docId, "bob", "batch-dup", 5,
                "[{\"segmentId\":\"s1\",\"language\":\"en\",\"expectedTranslationVersion\":1},"
                        + "{\"segmentId\":\"s1\",\"language\":\"EN\",\"expectedTranslationVersion\":1}]");
        assertThat(result.status()).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM approval WHERE document_id = ?",
                Integer.class, docId)).isZero();
    }

    @Test
    @DisplayName("批量条数边界：0 条或 51 条返回 400")
    void batchSizeBounds() throws Exception {
        long docId = createDocWithTranslations();
        ApiResult empty = approveBatch(docId, "bob", "batch-empty", 5, "[]");
        assertThat(empty.status()).isEqualTo(400);

        StringBuilder items = new StringBuilder("[");
        for (int i = 0; i < 51; i++) {
            if (i > 0) {
                items.append(',');
            }
            items.append("{\"segmentId\":\"s1\",\"language\":\"en\",\"expectedTranslationVersion\":1}");
        }
        items.append(']');
        ApiResult tooMany = approveBatch(docId, "bob", "batch-large", 5, items.toString());
        assertThat(tooMany.status()).isEqualTo(400);
    }

    @Test
    @DisplayName("幂等：同键同参（译文标识换序）重放首次响应快照且不重复批准；同键异参 409")
    void idempotentReplay() throws Exception {
        long docId = createDocWithTranslations();
        ApiResult first = approveBatch(docId, "bob", "batch-idem", 5,
                "[{\"segmentId\":\"s1\",\"language\":\"en\",\"expectedTranslationVersion\":1},"
                        + "{\"segmentId\":\"s1\",\"language\":\"ja\",\"expectedTranslationVersion\":1}]");
        assertThat(first.status()).isEqualTo(200);

        // 换序视为同参：重放首次响应快照，不重复批准
        ApiResult replay = approveBatch(docId, "bob", "batch-idem", 5,
                "[{\"segmentId\":\"s1\",\"language\":\"ja\",\"expectedTranslationVersion\":1},"
                        + "{\"segmentId\":\"s1\",\"language\":\"en\",\"expectedTranslationVersion\":1}]");
        assertThat(replay.status()).isEqualTo(200);
        assertThat(replay.body().toString()).isEqualTo(first.body().toString());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM approval WHERE document_id = ?",
                Integer.class, docId)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM approval_batch", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM approval_batch_item", Integer.class))
                .isEqualTo(2);

        // 同键异参：409
        ApiResult conflict = approveBatch(docId, "bob", "batch-idem", 5,
                "[{\"segmentId\":\"s2\",\"language\":\"en\",\"expectedTranslationVersion\":1}]");
        assertThat(conflict.status()).isEqualTo(409);
    }

    @Test
    @DisplayName("草稿版本仅固化记录：批量校验期间草稿版本变化不影响本批，记录保存审核人提交值")
    void draftVersionOnlyRecorded() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"你好\"},{\"segmentId\":\"s2\",\"sourceText\":\"世界\"}]");
        submitTranslation(docId, "s1", "en", "alice", "hello", 1, newRequestId());
        // 审核人基于草稿版本 2 准备批次；期间另一译者提交使草稿版本变为 3
        submitTranslation(docId, "s2", "en", "alice", "world", 1, newRequestId());

        ApiResult result = approveBatch(docId, "bob", "batch-draft", 2,
                "[{\"segmentId\":\"s1\",\"language\":\"en\",\"expectedTranslationVersion\":1}]");
        assertThat(result.status()).isEqualTo(200);
        assertThat(result.body().get("draftVersion").asInt()).isEqualTo(2);

        Integer currentDraft = jdbc.queryForObject(
                "SELECT draft_version FROM document WHERE document_id = ?", Integer.class, docId);
        assertThat(currentDraft).isEqualTo(3);
        Integer recordedDraft = jdbc.queryForObject(
                "SELECT draft_version FROM approval_batch WHERE batch_key = 'batch-draft'", Integer.class);
        assertThat(recordedDraft).isEqualTo(2);
    }

    @Test
    @DisplayName("批量审核记录查询：列表与明细只读稳定排序；未知批次或文档 404")
    void queryBatchRecords() throws Exception {
        long docId = createDocWithTranslations();
        approveBatch(docId, "bob", "batch-q1", 5,
                "[{\"segmentId\":\"s2\",\"language\":\"ja\",\"expectedTranslationVersion\":1},"
                        + "{\"segmentId\":\"s1\",\"language\":\"en\",\"expectedTranslationVersion\":1}]");
        approveBatch(docId, "carol", "batch-q2", 5,
                "[{\"segmentId\":\"s2\",\"language\":\"en\",\"expectedTranslationVersion\":1}]");

        ApiResult list = getJson("/api/documents/" + docId + "/approval-batches");
        assertThat(list.status()).isEqualTo(200);
        assertThat(list.body()).hasSize(2);
        assertThat(list.body().get(0).get("batchKey").asText()).isEqualTo("batch-q1");
        assertThat(list.body().get(0).get("itemCount").asInt()).isEqualTo(2);
        assertThat(list.body().get(1).get("batchKey").asText()).isEqualTo("batch-q2");

        ApiResult detail = getJson("/api/documents/" + docId + "/approval-batches/batch-q1");
        assertThat(detail.status()).isEqualTo(200);
        assertThat(detail.body().get("reviewer").asText()).isEqualTo("bob");
        assertThat(detail.body().get("items")).hasSize(2);
        // 明细稳定排序：s1/en 在 s2/ja 前
        assertThat(detail.body().get("items").get(0).get("segmentId").asText()).isEqualTo("s1");
        assertThat(detail.body().get("items").get(0).get("language").asText()).isEqualTo("en");
        assertThat(detail.body().get("items").get(0).get("translationVersion").asInt()).isEqualTo(1);
        assertThat(detail.body().get("items").get(1).get("segmentId").asText()).isEqualTo("s2");

        ApiResult missingBatch = getJson("/api/documents/" + docId + "/approval-batches/nope");
        assertThat(missingBatch.status()).isEqualTo(404);
        ApiResult missingDoc = getJson("/api/documents/999999/approval-batches");
        assertThat(missingDoc.status()).isEqualTo(404);
    }
}
