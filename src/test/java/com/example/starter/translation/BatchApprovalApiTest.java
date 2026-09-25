package com.example.starter.translation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 译文批量审核主流程、失败分支、原子回滚、幂等与记录查询测试（真实 H2 数据库）。
 */
class BatchApprovalApiTest extends AbstractIntegrationTest {

    /** 准备一个含两个段落、英文译文（alice 提交，版本 1）的文档，当前草稿版本 3。 */
    private long prepareDocumentWithTranslations() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"原文一\"},"
                        + "{\"segmentId\":\"s2\",\"sourceText\":\"原文二\"}]");
        submitTranslation(docId, "s1", "en", "alice", "one", 1, newRequestId());
        submitTranslation(docId, "s2", "en", "alice", "two", 1, newRequestId());
        return docId;
    }

    private String twoItems(int v1, int v2) {
        return "[{\"segmentId\":\"s1\",\"language\":\"en\",\"expectedTranslationVersion\":" + v1 + "},"
                + "{\"segmentId\":\"s2\",\"language\":\"en\",\"expectedTranslationVersion\":" + v2 + "}]";
    }

    @Test
    @DisplayName("批量审核成功：全部原子批准，写入批次记录与明细，不改变文档 draftVersion")
    void batchApproveSuccess() throws Exception {
        long docId = prepareDocumentWithTranslations();
        Integer draftBefore = jdbc.queryForObject(
                "SELECT draft_version FROM document WHERE document_id = ?", Integer.class, docId);

        ApiResult result = batchApprove(docId, "batch-1", 99, twoItems(1, 1), "bob");
        assertThat(result.status()).isEqualTo(200);
        assertThat(result.body().get("batchKey").asText()).isEqualTo("batch-1");
        assertThat(result.body().get("documentId").asLong()).isEqualTo(docId);
        assertThat(result.body().get("expectedDraftVersion").asInt()).isEqualTo(99);
        assertThat(result.body().get("reviewer").asText()).isEqualTo("bob");
        assertThat(result.body().get("approvedAt").asText()).isNotBlank();
        assertThat(result.body().get("items")).hasSize(2);
        assertThat(result.body().get("items").get(0).get("translationVersion").asInt()).isEqualTo(1);
        assertThat(result.body().get("items").get(1).get("reviewer").asText()).isEqualTo("bob");

        // 不改变文档 draftVersion
        Integer draftAfter = jdbc.queryForObject(
                "SELECT draft_version FROM document WHERE document_id = ?", Integer.class, docId);
        assertThat(draftAfter).isEqualTo(draftBefore);

        // approval 表两条，与单条批准共享同一状态机
        Integer approvals = jdbc.queryForObject(
                "SELECT COUNT(*) FROM approval WHERE document_id = ? AND reviewer = 'bob'", Integer.class, docId);
        assertThat(approvals).isEqualTo(2);
        Integer batchRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_approval WHERE batch_key = 'batch-1'", Integer.class);
        assertThat(batchRows).isEqualTo(1);
        Integer itemRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_approval_item WHERE batch_key = 'batch-1'", Integer.class);
        assertThat(itemRows).isEqualTo(2);

        // 记录固化的 expectedDraftVersion 是客户端提交值，与当前文档草稿版本无关
        Integer recorded = jdbc.queryForObject(
                "SELECT expected_draft_version FROM batch_approval WHERE batch_key = 'batch-1'", Integer.class);
        assertThat(recorded).isEqualTo(99);
    }

    @Test
    @DisplayName("批量批准与单条批准等价：批量批准后可直接发布，历史查询不区分入口")
    void batchApprovalEquivalentForPublish() throws Exception {
        long docId = prepareDocumentWithTranslations();
        assertThat(batchApprove(docId, "batch-eq", 3, twoItems(1, 1), "bob").status()).isEqualTo(200);

        ApiResult published = publish(docId, 3, 0, newRequestId());
        assertThat(published.status()).isEqualTo(201);
        assertThat(published.body().get("publishedVersion").asInt()).isEqualTo(1);

        ApiResult release = getJson("/api/documents/" + docId + "/releases/1");
        assertThat(release.status()).isEqualTo(200);
        assertThat(release.body().get("segments")).hasSize(2);
        assertThat(release.body().get("segments").get(0).get("translations").get(0)
                .get("reviewer").asText()).isEqualTo("bob");
    }

    @Test
    @DisplayName("部分失败原子回滚：一条版本不匹配整批 422 并返回逐条原因，不批准任何一条")
    void partialFailureRollsBackEverything() throws Exception {
        long docId = prepareDocumentWithTranslations();

        ApiResult result = batchApprove(docId, "batch-fail", 3, twoItems(1, 7), "bob");
        assertThat(result.status()).isEqualTo(422);
        assertThat(result.body().get("error").asText()).isEqualTo("BATCH_APPROVAL_REJECTED");
        assertThat(result.body().get("items")).hasSize(1);
        var error = result.body().get("items").get(0);
        assertThat(error.get("index").asInt()).isEqualTo(2);
        assertThat(error.get("segmentId").asText()).isEqualTo("s2");
        assertThat(error.get("language").asText()).isEqualTo("en");
        assertThat(error.get("reason").asText()).contains("版本");

        // 原子回滚：无任何批准、批次记录与明细
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM approval WHERE document_id = ?", Integer.class, docId)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_approval", Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_approval_item", Integer.class)).isZero();
    }

    @Test
    @DisplayName("逐条校验失败原因：作者自审、译文不存在、译文待更新、重复批准均 422")
    void perItemValidationReasons() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"原文一\"},"
                        + "{\"segmentId\":\"s2\",\"sourceText\":\"原文二\"},"
                        + "{\"segmentId\":\"s3\",\"sourceText\":\"原文三\"}]");
        submitTranslation(docId, "s1", "en", "alice", "one", 1, newRequestId());
        submitTranslation(docId, "s2", "en", "alice", "two", 1, newRequestId());
        // s1 先经单条批准成为已批准
        assertThat(approve(docId, "s1", "en", "bob", 1, newRequestId()).status()).isEqualTo(200);
        // s2 源文修订后译文待更新
        assertThat(putJson("/api/documents/" + docId + "/segments/s2/source",
                "{\"requestId\":\"" + newRequestId() + "\",\"sourceText\":\"原文二修订\"}").status())
                .isEqualTo(200);

        String items = "["
                + "{\"segmentId\":\"s1\",\"language\":\"en\",\"expectedTranslationVersion\":1},"
                + "{\"segmentId\":\"s2\",\"language\":\"en\",\"expectedTranslationVersion\":1},"
                + "{\"segmentId\":\"s3\",\"language\":\"en\",\"expectedTranslationVersion\":1},"
                + "{\"segmentId\":\"s4\",\"language\":\"en\",\"expectedTranslationVersion\":1}"
                + "]";
        ApiResult result = batchApprove(docId, "batch-reasons", 5, items, "bob");
        assertThat(result.status()).isEqualTo(422);
        assertThat(result.body().get("items")).hasSize(4);
        assertThat(result.body().get("items").get(0).get("reason").asText()).contains("已批准");
        assertThat(result.body().get("items").get(1).get("reason").asText()).contains("待更新");
        assertThat(result.body().get("items").get(2).get("reason").asText()).contains("译文不存在");
        assertThat(result.body().get("items").get(3).get("reason").asText()).contains("段落不存在");

        // 作者自审：carol 提交 s3 译文后自行发起批次审核，422
        submitTranslation(docId, "s3", "en", "carol", "three", 1, newRequestId());
        ApiResult selfReview = batchApprove(docId, "batch-self", 6,
                "[{\"segmentId\":\"s3\",\"language\":\"en\",\"expectedTranslationVersion\":1}]", "carol");
        assertThat(selfReview.status()).isEqualTo(422);
        assertThat(selfReview.body().get("items").get(0).get("reason").asText()).contains("作者");
    }

    @Test
    @DisplayName("旧批准失效后译文重新进入待审核：批量批准新版本成功并覆盖旧批准")
    void resubmittedTranslationIsPendingAgain() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"原文\"}]");
        submitTranslation(docId, "s1", "en", "alice", "v1", 1, newRequestId());
        approve(docId, "s1", "en", "bob", 1, newRequestId());
        // 源文修订并重新提交译文：旧批准失效，译文版本 2 待审核
        putJson("/api/documents/" + docId + "/segments/s1/source",
                "{\"requestId\":\"" + newRequestId() + "\",\"sourceText\":\"原文v2\"}");
        submitTranslation(docId, "s1", "en", "alice", "v2", 2, newRequestId());

        ApiResult result = batchApprove(docId, "batch-re", 4,
                "[{\"segmentId\":\"s1\",\"language\":\"en\",\"expectedTranslationVersion\":2}]", "bob");
        assertThat(result.status()).isEqualTo(200);
        Integer version = jdbc.queryForObject(
                "SELECT translation_version FROM approval WHERE document_id = ? AND segment_id = 's1'",
                Integer.class, docId);
        assertThat(version).isEqualTo(2);
    }

    @Test
    @DisplayName("同一批次内译文标识重复返回 400；条目数 0 或 51 返回 400")
    void duplicateIdentityAndSizeLimits() throws Exception {
        long docId = prepareDocumentWithTranslations();

        ApiResult duplicate = batchApprove(docId, "batch-dup", 3,
                "[{\"segmentId\":\"s1\",\"language\":\"en\",\"expectedTranslationVersion\":1},"
                        + "{\"segmentId\":\"s1\",\"language\":\"EN\",\"expectedTranslationVersion\":1}]", "bob");
        assertThat(duplicate.status()).isEqualTo(400);
        assertThat(duplicate.body().get("message").asText()).contains("重复");

        ApiResult empty = batchApprove(docId, "batch-empty", 3, "[]", "bob");
        assertThat(empty.status()).isEqualTo(400);

        StringBuilder fiftyOne = new StringBuilder("[");
        for (int i = 0; i < 51; i++) {
            if (i > 0) {
                fiftyOne.append(',');
            }
            fiftyOne.append("{\"segmentId\":\"s").append(i).append("\",\"language\":\"en\",")
                    .append("\"expectedTranslationVersion\":1}");
        }
        fiftyOne.append(']');
        ApiResult tooMany = batchApprove(docId, "batch-too-many", 3, fiftyOne.toString(), "bob");
        assertThat(tooMany.status()).isEqualTo(400);
    }

    @Test
    @DisplayName("幂等：同 batchKey 同参（含换序）重放首次快照；异参 409；失败不占键；重放不重复批准")
    void idempotency() throws Exception {
        long docId = prepareDocumentWithTranslations();

        ApiResult first = batchApprove(docId, "idem-key", 3, twoItems(1, 1), "bob");
        assertThat(first.status()).isEqualTo(200);
        String firstBody = first.body().toString();

        // 同参重放：返回首次响应快照，不新增批准/记录
        ApiResult replay = batchApprove(docId, "idem-key", 3, twoItems(1, 1), "bob");
        assertThat(replay.status()).isEqualTo(200);
        assertThat(replay.body().toString()).isEqualTo(firstBody);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM approval WHERE document_id = ?", Integer.class, docId)).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_approval_item WHERE batch_key = 'idem-key'", Integer.class))
                .isEqualTo(2);

        // 条目集合换序视为同参（语言大小写归一）
        String swapped = "[{\"segmentId\":\"s2\",\"language\":\"EN\",\"expectedTranslationVersion\":1},"
                + "{\"segmentId\":\"s1\",\"language\":\"en\",\"expectedTranslationVersion\":1}]";
        ApiResult swappedReplay = batchApprove(docId, "idem-key", 3, swapped, "bob");
        assertThat(swappedReplay.status()).isEqualTo(200);
        assertThat(swappedReplay.body().toString()).isEqualTo(firstBody);

        // 异参：版本不同 409
        ApiResult conflict = batchApprove(docId, "idem-key", 3,
                "[{\"segmentId\":\"s1\",\"language\":\"en\",\"expectedTranslationVersion\":2}]", "bob");
        assertThat(conflict.status()).isEqualTo(409);
        // 异参：审核人不同 409
        ApiResult actorConflict = batchApprove(docId, "idem-key", 3, twoItems(1, 1), "dave");
        assertThat(actorConflict.status()).isEqualTo(409);

        // 失败不占键：在独立文档上先触发 422（版本不匹配），再用同键修正后成功
        long freshDoc = prepareDocumentWithTranslations();
        ApiResult failed = batchApprove(freshDoc, "fail-then-ok", 3, twoItems(1, 9), "bob");
        assertThat(failed.status()).isEqualTo(422);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = 'fail-then-ok'", Integer.class)).isZero();
        ApiResult retried = batchApprove(freshDoc, "fail-then-ok", 3, twoItems(1, 1), "bob");
        assertThat(retried.status()).isEqualTo(200);
    }

    @Test
    @DisplayName("批次记录查询：返回不可变记录与稳定排序明细；批次不存在或跨文档 404")
    void getBatchApprovalRecord() throws Exception {
        long docId = prepareDocumentWithTranslations();
        // 故意换序提交，明细仍按请求顺序固化且查询稳定
        String swapped = "[{\"segmentId\":\"s2\",\"language\":\"en\",\"expectedTranslationVersion\":1},"
                + "{\"segmentId\":\"s1\",\"language\":\"en\",\"expectedTranslationVersion\":1}]";
        assertThat(batchApprove(docId, "batch-q", 3, swapped, "bob").status()).isEqualTo(200);

        ApiResult record = getBatchApproval(docId, "batch-q");
        assertThat(record.status()).isEqualTo(200);
        assertThat(record.body().get("batchKey").asText()).isEqualTo("batch-q");
        assertThat(record.body().get("documentId").asLong()).isEqualTo(docId);
        assertThat(record.body().get("expectedDraftVersion").asInt()).isEqualTo(3);
        assertThat(record.body().get("reviewer").asText()).isEqualTo("bob");
        assertThat(record.body().get("approvedAt").asText()).isNotBlank();
        assertThat(record.body().get("items")).hasSize(2);
        // 按提交位置稳定排序：s2 在前
        assertThat(record.body().get("items").get(0).get("segmentId").asText()).isEqualTo("s2");
        assertThat(record.body().get("items").get(1).get("segmentId").asText()).isEqualTo("s1");
        assertThat(record.body().get("items").get(0).get("sourceVersion").asInt()).isEqualTo(1);

        assertThat(getBatchApproval(docId, "missing").status()).isEqualTo(404);

        long otherDoc = createDocument(newRequestId(), "[\"en\"]", "[]");
        assertThat(getBatchApproval(otherDoc, "batch-q").status()).isEqualTo(404);
    }

    @Test
    @DisplayName("草稿版本在批量校验期间变化不影响本批：并发新增段落只推进 draft，批次仍成功")
    void draftVersionChangeDoesNotRejectBatch() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"原文一\"}]");
        submitTranslation(docId, "s1", "en", "alice", "one", 1, newRequestId());

        // 新增段落推进 draftVersion 至 3，但不影响 s1/en 译文版本
        ApiResult added = postJson("/api/documents/" + docId + "/segments",
                "{\"requestId\":\"" + newRequestId() + "\",\"segmentId\":\"s2\",\"sourceText\":\"原文二\"}");
        assertThat(added.status()).isEqualTo(201);

        // 用变化前的草稿版本 2 提交批次，仍成功；记录固化 2
        ApiResult result = batchApprove(docId, "batch-draft", 2,
                "[{\"segmentId\":\"s1\",\"language\":\"en\",\"expectedTranslationVersion\":1}]", "bob");
        assertThat(result.status()).isEqualTo(200);
        Integer recorded = jdbc.queryForObject(
                "SELECT expected_draft_version FROM batch_approval WHERE batch_key = 'batch-draft'",
                Integer.class);
        assertThat(recorded).isEqualTo(2);
        Integer draft = jdbc.queryForObject(
                "SELECT draft_version FROM document WHERE document_id = ?", Integer.class, docId);
        assertThat(draft).isEqualTo(3);
    }

    @Test
    @DisplayName("并发编辑导致批内译文版本改变：整批 422 且不批准任何一条，需重新提交批次")
    void concurrentTranslationVersionChangeRejectsBatch() throws Exception {
        long docId = prepareDocumentWithTranslations();

        // 先让 s2 译文重新提交为版本 2（模拟并发编辑在批量校验时已落库），再以期望版本 1 提交批次
        submitTranslation(docId, "s2", "en", "alice", "two-v2", 1, newRequestId());

        ApiResult result = batchApprove(docId, "batch-stale", 4, twoItems(1, 1), "bob");
        assertThat(result.status()).isEqualTo(422);
        assertThat(result.body().get("items")).hasSize(1);
        assertThat(result.body().get("items").get(0).get("segmentId").asText()).isEqualTo("s2");

        // 无任何批准
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM approval WHERE document_id = ?", Integer.class, docId)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_approval WHERE batch_key = 'batch-stale'", Integer.class)).isZero();

        // 用新版本重新提交批次后成功
        ApiResult retried = batchApprove(docId, "batch-stale", 4, twoItems(1, 2), "bob");
        assertThat(retried.status()).isEqualTo(200);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM approval WHERE document_id = ?", Integer.class, docId)).isEqualTo(2);
    }
}
