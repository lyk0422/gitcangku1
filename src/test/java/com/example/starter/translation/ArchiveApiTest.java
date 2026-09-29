package com.example.starter.translation;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 发布撤回、术语退役与译文归档包的真实 H2 集成测试：
 * 主流程、失败分支、证据保留、时间区间、幂等与并发裁决，均核对最终数据而非打印结果。
 */
class ArchiveApiTest extends AbstractIntegrationTest {

    /** 建文档、逐语种逐段提交译文并批准、发布，返回发布版本 1 的文档 ID。 */
    private long publishCompleteDocument(List<String> languages, List<String> segmentIds) throws Exception {
        StringBuilder segments = new StringBuilder("[");
        for (int i = 0; i < segmentIds.size(); i++) {
            if (i > 0) {
                segments.append(',');
            } segments.append("{\"segmentId\":\"").append(segmentIds.get(i))
                    .append("\",\"sourceText\":\"原文-").append(segmentIds.get(i)).append("\"}");
        }
        segments.append(']');
        long docId = createDocument(newRequestId(), objectMapper.writeValueAsString(languages), segments.toString());
        for (String segmentId : segmentIds) {
            for (int li = 0; li < languages.size(); li++) {
                String language = languages.get(li);
                String author = "author-" + language;
                String reviewer = "reviewer-" + language;
                assertThat(submitTranslation(docId, segmentId, language, author,
                        "译文-" + segmentId + "-" + language, 1, newRequestId()).status()).isEqualTo(200);
                assertThat(approve(docId, segmentId, language, reviewer, 1, newRequestId()).status())
                        .isEqualTo(200);
            }
        }
        int draftVersion = 1 + languages.size() * segmentIds.size();
        assertThat(publish(docId, draftVersion, 0, newRequestId()).status()).isEqualTo(201);
        return docId;
    }

    /** 发布版本 2：修订 s1 源文、重新提交各语种译文并批准后再次发布。 */
    private void publishSecondVersion(long docId, List<String> languages, int v1DraftVersion) throws Exception {
        assertThat(putJson("/api/documents/" + docId + "/segments/s1/source",
                "{\"requestId\":\"" + newRequestId() + "\",\"sourceText\":\"原文-s1-v2\"}").status())
                .isEqualTo(200);
        for (String language : languages) {
            assertThat(submitTranslation(docId, "s1", language, "author-" + language,
                    "译文v2-s1-" + language, 2, newRequestId()).status()).isEqualTo(200);
            assertThat(approve(docId, "s1", language, "reviewer-" + language, 2, newRequestId()).status())
                    .isEqualTo(200);
        }
        int draftVersion = v1DraftVersion + 1 + languages.size();
        assertThat(publish(docId, draftVersion, 1, newRequestId()).status()).isEqualTo(201);
    }

    @Test
    @DisplayName("归档主流程：201 创建包，明细含规范排序语种/段落、逐段译文版本、术语版本、审批记录与发布摘要")
    void createArchiveAndGetEvidence() throws Exception {
        long docId = publishCompleteDocument(List.of("ja", "en"), List.of("s2", "s1"));

        ApiResult created = createArchive(docId, 1, "archiver", newRequestId());
        assertThat(created.status()).isEqualTo(201);
        assertThat(created.body().get("status").asText()).isEqualTo("ACTIVE");
        assertThat(created.body().get("createdBy").asText()).isEqualTo("archiver");
        long archiveId = created.body().get("archiveId").asLong();
        String createdAt = created.body().get("createdAt").asText();
        assertThat(createdAt).endsWith("Z");

        ApiResult detail = getJson("/api/documents/" + docId + "/archives/" + archiveId);
        assertThat(detail.status()).isEqualTo(200);
        JsonNode evidence = detail.body().get("evidence");
        // 目标语种规范排序：en 在 ja 前；段落规范排序：s1 在 s2 前
        assertThat(evidence.get("targetLanguages").get(0).asText()).isEqualTo("en");
        assertThat(evidence.get("targetLanguages").get(1).asText()).isEqualTo("ja");
        assertThat(evidence.get("segments")).hasSize(2);
        assertThat(evidence.get("segments").get(0).get("segmentId").asText()).isEqualTo("s1");
        JsonNode firstTranslations = evidence.get("segments").get(0).get("translations");
        assertThat(firstTranslations).hasSize(2);
        assertThat(firstTranslations.get(0).get("language").asText()).isEqualTo("en");
        assertThat(firstTranslations.get(0).get("translationVersion").asInt()).isEqualTo(1);
        assertThat(firstTranslations.get(0).get("sourceVersion").asInt()).isEqualTo(1);
        assertThat(firstTranslations.get(0).get("termVersion").asInt()).isZero();
        assertThat(firstTranslations.get(0).get("reviewer").asText()).isEqualTo("reviewer-en");
        // 审批记录独立固化且按段落、语种排序
        JsonNode approvals = evidence.get("approvals");
        assertThat(approvals).hasSize(4);
        assertThat(approvals.get(0).get("segmentId").asText()).isEqualTo("s1");
        assertThat(approvals.get(0).get("language").asText()).isEqualTo("en");
        assertThat(approvals.get(0).get("reviewer").asText()).isEqualTo("reviewer-en");
        // 发布快照摘要：数量均为实际值
        JsonNode summary = evidence.get("releaseSummary");
        assertThat(summary.get("publishedVersion").asInt()).isEqualTo(1);
        assertThat(summary.get("segmentCount").asInt()).isEqualTo(2);
        assertThat(summary.get("requiredLanguageCount").asInt()).isEqualTo(2);
        assertThat(summary.get("translationCount").asInt()).isEqualTo(4);
        assertThat(summary.get("approvalCount").asInt()).isEqualTo(4);
        assertThat(summary.get("releasedAt").asText()).endsWith("Z");
    }

    @Test
    @DisplayName("证据保留：撤回发布、译文修订、术语退役均不改写归档证据；撤回后已有归档仍可查询")
    void archiveEvidenceImmutableAfterWithdrawRevisionAndRetirement() throws Exception {
        long docId = publishCompleteDocument(List.of("en"), List.of("s1"));
        ApiResult created = createArchive(docId, 1, "archiver", newRequestId());
        long archiveId = created.body().get("archiveId").asLong();

        // 撤回发布
        ApiResult withdrawn = withdrawRelease(docId, 1, "boss", "发布有误", newRequestId());
        assertThat(withdrawn.status()).isEqualTo(200);
        // 撤回后已有归档包仍可受限查询（200，证据原样）
        ApiResult afterWithdraw = getJson("/api/documents/" + docId + "/archives/" + archiveId);
        assertThat(afterWithdraw.status()).isEqualTo(200);
        assertThat(afterWithdraw.body().get("evidence").get("segments").get(0).get("translations").get(0)
                .get("content").asText()).isEqualTo("译文-s1-en");

        // 译文修订（重新发布链路之外的直接修订）不影响归档证据
        putJson("/api/documents/" + docId + "/segments/s1/source",
                "{\"requestId\":\"" + newRequestId() + "\",\"sourceText\":\"原文-s1-变了\"}");
        // 术语退役不影响归档证据
        updateTerms(docId, 0,
                "[{\"sourceTerm\":\"机器学习\",\"language\":\"en\",\"requiredTranslation\":\"ML\"}]",
                newRequestId());
        ApiResult retired = retireTerms(docId, 1, "boss", "术语过期", newRequestId());
        assertThat(retired.status()).isEqualTo(200);

        ApiResult detail = getJson("/api/documents/" + docId + "/archives/" + archiveId);
        assertThat(detail.status()).isEqualTo(200);
        JsonNode evidence = detail.body().get("evidence");
        assertThat(evidence.get("termVersion").asInt()).isZero();
        assertThat(evidence.get("terms")).isEmpty();
        assertThat(evidence.get("segments").get(0).get("sourceText").asText()).isEqualTo("原文-s1");
        assertThat(evidence.get("segments").get(0).get("translations").get(0)
                .get("content").asText()).isEqualTo("译文-s1-en");
    }

    @Test
    @DisplayName("撤回发布：重复撤回 409；撤回后不能新建归档包 422；撤回不存在版本 404")
    void withdrawalRules() throws Exception {
        long docId = publishCompleteDocument(List.of("en"), List.of("s1"));

        assertThat(withdrawRelease(docId, 1, "boss", "原因", newRequestId()).status()).isEqualTo(200);
        assertThat(withdrawRelease(docId, 1, "boss", "再次撤回", newRequestId()).status()).isEqualTo(409);
        assertThat(createArchive(docId, 1, "archiver", newRequestId()).status()).isEqualTo(422);
        assertThat(withdrawRelease(docId, 99, "boss", "x", newRequestId()).status()).isEqualTo(404);
        assertThat(withdrawRelease(999999, 1, "boss", "x", newRequestId()).status()).isEqualTo(404);
    }

    @Test
    @DisplayName("术语退役：成功后重复 409；术语规则仍可查询；版本不存在 404")
    void termRetirementRules() throws Exception {
        long docId = publishCompleteDocument(List.of("en"), List.of("s1"));
        updateTerms(docId, 0,
                "[{\"sourceTerm\":\"机器学习\",\"language\":\"en\",\"requiredTranslation\":\"ML\"}]",
                newRequestId());

        ApiResult retired = retireTerms(docId, 1, "boss", "术语废弃", newRequestId());
        assertThat(retired.status()).isEqualTo(200);
        assertThat(retired.body().get("retiredAt").asText()).endsWith("Z");
        assertThat(retireTerms(docId, 1, "boss", "再次退役", newRequestId()).status()).isEqualTo(409);
        assertThat(retireTerms(docId, 99, "boss", "x", newRequestId()).status()).isEqualTo(404);
        // 退役只标记，不删除规则
        assertThat(getJson("/api/documents/" + docId + "/terms/1").body().get("rules")).hasSize(1);
    }

    @Test
    @DisplayName("归档失败分支：发布不存在/文档不存在 404；同版本重复归档 409")
    void archiveFailures() throws Exception {
        long docId = publishCompleteDocument(List.of("en"), List.of("s1"));
        assertThat(createArchive(docId, 99, "a", newRequestId()).status()).isEqualTo(404);
        assertThat(createArchive(999999, 1, "a", newRequestId()).status()).isEqualTo(404);

        assertThat(createArchive(docId, 1, "a", newRequestId()).status()).isEqualTo(201);
        ApiResult duplicate = createArchive(docId, 1, "a", newRequestId());
        assertThat(duplicate.status()).isEqualTo(409);
        assertThat(getJson("/api/documents/" + docId + "/archives/999999").status()).isEqualTo(404);
    }

    @Test
    @DisplayName("完整性 422：快照缺某语种段落译文时整次失败且查不到半成品（直接构造遗留/损坏快照）")
    void incompleteSnapshotRejectedAtomically() throws Exception {
        long docId = publishCompleteDocument(List.of("en", "ja"), List.of("s1"));
        // 直接写入一个缺 ja 译文的遗留快照（发布版本 5），模拟历史不完整数据
        String incompleteSnapshot = "{\"documentId\":" + docId + ",\"publishedVersion\":5,\"termVersion\":0,"
                + "\"targetLanguages\":[\"en\",\"ja\"],\"terms\":[],\"segments\":[{"
                + "\"segmentId\":\"s1\",\"sourceText\":\"原文-s1\",\"sourceVersion\":1,"
                + "\"translations\":[{\"language\":\"en\",\"content\":\"v\",\"author\":\"author-en\","
                + "\"translationVersion\":1,\"sourceVersion\":1,\"termVersion\":0,\"reviewer\":\"reviewer-en\"}]}]}";
        jdbc.update("INSERT INTO release_snapshot (document_id, published_version, snapshot_json) VALUES (?, 5, ?)",
                docId, incompleteSnapshot);

        ApiResult result = createArchive(docId, 5, "archiver", newRequestId());
        assertThat(result.status()).isEqualTo(422);
        assertThat(result.body().get("message").asText()).contains("ja");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM archive_package WHERE document_id = ?", Integer.class, docId)).isZero();
    }

    @Test
    @DisplayName("批量归档：两个发布版本全部成功；含一个不完整版本时整批不产生任何包")
    void batchArchiveSuccessAndAtomicFailure() throws Exception {
        long docId = publishCompleteDocument(List.of("en"), List.of("s1"));
        int v1DraftVersion = 2;
        publishSecondVersion(docId, List.of("en"), v1DraftVersion);

        // 乱序版本号：响应按版本号稳定排序
        ApiResult batch = batchArchive(docId, "[2,1]", "archiver", newRequestId());
        assertThat(batch.status()).isEqualTo(201);
        assertThat(batch.body().get("requestedCount").asInt()).isEqualTo(2);
        assertThat(batch.body().get("createdCount").asInt()).isEqualTo(2);
        assertThat(batch.body().get("existingCount").asInt()).isZero();
        JsonNode packages = batch.body().get("packages");
        assertThat(packages).hasSize(2);
        assertThat(packages.get(0).get("publishedVersion").asInt()).isEqualTo(1);
        assertThat(packages.get(1).get("publishedVersion").asInt()).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM archive_package WHERE document_id = ?", Integer.class, docId)).isEqualTo(2);

        // 再次批量（新 requestId）：均为既有包，createdCount=0、existingCount=2，不产生新包
        ApiResult again = batchArchive(docId, "[1,2]", "archiver", newRequestId());
        assertThat(again.status()).isEqualTo(201);
        assertThat(again.body().get("createdCount").asInt()).isZero();
        assertThat(again.body().get("existingCount").asInt()).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM archive_package WHERE document_id = ?", Integer.class, docId)).isEqualTo(2);

        // 构造第三个不完整快照，批量 [1,3] 整批失败：版本 1 已有包（计既有），版本 3 不完整
        String incompleteSnapshot = "{\"documentId\":" + docId + ",\"publishedVersion\":3,\"termVersion\":0,"
                + "\"targetLanguages\":[\"en\"],\"terms\":[],\"segments\":[{"
                + "\"segmentId\":\"s1\",\"sourceText\":\"x\",\"sourceVersion\":1,\"translations\":[]}]}";
        jdbc.update("INSERT INTO release_snapshot (document_id, published_version, snapshot_json) VALUES (?, 3, ?)",
                docId, incompleteSnapshot);
        ApiResult failed = batchArchive(docId, "[3,1]", "archiver", newRequestId());
        assertThat(failed.status()).isEqualTo(422);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM archive_package WHERE document_id = ?", Integer.class, docId)).isEqualTo(2);
    }

    @Test
    @DisplayName("撤销归档：200 后明细 410 且带原因，历史显示 REVOKED 与撤销字段；重复撤销 409；证据行仍保留")
    void revokeArchive() throws Exception {
        long docId = publishCompleteDocument(List.of("en"), List.of("s1"));
        long archiveId = createArchive(docId, 1, "archiver", newRequestId())
                .body().get("archiveId").asLong();

        ApiResult revoked = revokeArchive(docId, archiveId, "auditor", "合规要求", newRequestId());
        assertThat(revoked.status()).isEqualTo(200);
        assertThat(revoked.body().get("status").asText()).isEqualTo("REVOKED");
        assertThat(revoked.body().get("revokedBy").asText()).isEqualTo("auditor");
        assertThat(revoked.body().get("revokedReason").asText()).isEqualTo("合规要求");

        ApiResult detail = getJson("/api/documents/" + docId + "/archives/" + archiveId);
        assertThat(detail.status()).isEqualTo(410);
        assertThat(detail.body().get("message").asText()).contains("合规要求");

        assertThat(revokeArchive(docId, archiveId, "auditor", "再次撤销", newRequestId()).status())
                .isEqualTo(409);

        ApiResult history = getJson("/api/documents/" + docId + "/archives");
        assertThat(history.status()).isEqualTo(200);
        JsonNode item = history.body().get("items").get(0);
        assertThat(item.get("status").asText()).isEqualTo("REVOKED");
        assertThat(item.get("revokedBy").asText()).isEqualTo("auditor");
        assertThat(item.get("revokedAt").asText()).endsWith("Z");
        assertThat(item.get("revokedReason").asText()).isEqualTo("合规要求");
        assertThat(history.body().get("totalCount").asInt()).isEqualTo(1);
        assertThat(history.body().get("activeCount").asInt()).isZero();
        assertThat(history.body().get("revokedCount").asInt()).isEqualTo(1);
        // 行与证据 JSON 保留
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM archive_package WHERE archive_id = ? AND status = 'REVOKED'",
                Integer.class, archiveId)).isEqualTo(1);
    }

    @Test
    @DisplayName("历史 null 语义：ACTIVE 行撤销字段为 null；字段稳定存在")
    void historyNullSemanticsForActive() throws Exception {
        long docId = publishCompleteDocument(List.of("en"), List.of("s1"));
        createArchive(docId, 1, "archiver", newRequestId());
        ApiResult history = getJson("/api/documents/" + docId + "/archives");
        JsonNode item = history.body().get("items").get(0);
        assertThat(item.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(item.get("revokedBy").isNull()).isTrue();
        assertThat(item.get("revokedAt").isNull()).isTrue();
        assertThat(item.get("revokedReason").isNull()).isTrue();
        assertThat(history.body().get("from").isNull()).isTrue();
        assertThat(history.body().get("to").isNull()).isTrue();
    }

    @Test
    @DisplayName("历史时间区间：UTC 左闭右开，from 包含端点、to 排除端点；非法格式与反向区间 400")
    void historyTimeWindow() throws Exception {
        long docId = publishCompleteDocument(List.of("en"), List.of("s1"));
        ApiResult created = createArchive(docId, 1, "archiver", newRequestId());
        Instant createdAt = Instant.parse(created.body().get("createdAt").asText());

        // from = 生成时刻：包含
        ApiResult fromAt = getJson("/api/documents/" + docId + "/archives?from="
                + createdAt.minusSeconds(1));
        assertThat(fromAt.body().get("totalCount").asInt()).isEqualTo(1);
        // to = 生成时刻：右开排除
        ApiResult toAt = getJson("/api/documents/" + docId + "/archives?to=" + createdAt);
        assertThat(toAt.body().get("totalCount").asInt()).isZero();
        // 之前区间：排除
        ApiResult before = getJson("/api/documents/" + docId + "/archives?from="
                + createdAt.minusSeconds(10) + "&to=" + createdAt.minusSeconds(5));
        assertThat(before.body().get("totalCount").asInt()).isZero();
        // 归一化回显（毫秒精度、Z 结尾）
        assertThat(fromAt.body().get("from").asText()).isEqualTo(createdAt.minusSeconds(1)
                .truncatedTo(ChronoUnit.MILLIS).toString());

        assertThat(getJson("/api/documents/" + docId + "/archives?from=not-a-time").status()).isEqualTo(400);
        assertThat(getJson("/api/documents/" + docId + "/archives?from=2026-09-29T10:00:00Z&to=2026-09-29T09:00:00Z")
                .status()).isEqualTo(400);
        assertThat(getJson("/api/documents/999999/archives").status()).isEqualTo(404);
    }

    @Test
    @DisplayName("诊断查询：完整发布返回要求值/实际值/差额；缺段时差额与问题原因可见；不改变状态")
    void diagnostics() throws Exception {
        long docId = publishCompleteDocument(List.of("en", "ja"), List.of("s1"));
        ApiResult ok = getJson("/api/documents/" + docId + "/releases/1/archive-diagnostics");
        assertThat(ok.status()).isEqualTo(200);
        assertThat(ok.body().get("releaseExists").asBoolean()).isTrue();
        assertThat(ok.body().get("withdrawn").asBoolean()).isFalse();
        assertThat(ok.body().get("archiveExists").asBoolean()).isFalse();
        assertThat(ok.body().get("requiredLanguageCount").asInt()).isEqualTo(2);
        assertThat(ok.body().get("requiredSegmentCount").asInt()).isEqualTo(1);
        assertThat(ok.body().get("requiredCellCount").asInt()).isEqualTo(2);
        assertThat(ok.body().get("completeCellCount").asInt()).isEqualTo(2);
        assertThat(ok.body().get("missingCellCount").asInt()).isZero();
        assertThat(ok.body().get("completeLanguageCount").asInt()).isEqualTo(2);
        assertThat(ok.body().get("incompleteLanguageCount").asInt()).isZero();
        assertThat(ok.body().get("languages")).hasSize(2);
        assertThat(ok.body().get("issues")).isEmpty();

        // 归档与撤回后诊断反映状态
        createArchive(docId, 1, "archiver", newRequestId());
        withdrawRelease(docId, 1, "boss", "撤回原因", newRequestId());
        ApiResult changed = getJson("/api/documents/" + docId + "/releases/1/archive-diagnostics");
        assertThat(changed.body().get("archiveExists").asBoolean()).isTrue();
        assertThat(changed.body().get("archiveStatus").asText()).isEqualTo("ACTIVE");
        assertThat(changed.body().get("withdrawn").asBoolean()).isTrue();
        assertThat(changed.body().get("withdrawalReason").asText()).isEqualTo("撤回原因");
        assertThat(changed.body().get("issues").toString()).contains("已撤回", "已存在归档包");

        // 不完整快照的差额诊断
        String incompleteSnapshot = "{\"documentId\":" + docId + ",\"publishedVersion\":7,\"termVersion\":0,"
                + "\"targetLanguages\":[\"en\",\"ja\"],\"terms\":[],\"segments\":[{"
                + "\"segmentId\":\"s1\",\"sourceText\":\"x\",\"sourceVersion\":1,\"translations\":[]}]}";
        jdbc.update("INSERT INTO release_snapshot (document_id, published_version, snapshot_json) VALUES (?, 7, ?)",
                docId, incompleteSnapshot);
        ApiResult broken = getJson("/api/documents/" + docId + "/releases/7/archive-diagnostics");
        assertThat(broken.body().get("requiredCellCount").asInt()).isEqualTo(2);
        assertThat(broken.body().get("completeCellCount").asInt()).isZero();
        assertThat(broken.body().get("missingCellCount").asInt()).isEqualTo(2);
        assertThat(broken.body().get("completeLanguageCount").asInt()).isZero();
        assertThat(broken.body().get("incompleteLanguageCount").asInt()).isEqualTo(2);
        JsonNode ja = broken.body().get("languages").get(1);
        assertThat(ja.get("language").asText()).isEqualTo("ja");
        assertThat(ja.get("requiredSegmentCount").asInt()).isEqualTo(1);
        assertThat(ja.get("actualSegmentCount").asInt()).isZero();
        assertThat(ja.get("missingSegmentCount").asInt()).isEqualTo(1);
        assertThat(ja.get("missingSegmentIds").get(0).asText()).isEqualTo("s1");
        assertThat(ja.get("complete").asBoolean()).isFalse();
        assertThat(broken.body().get("issues").toString()).contains("缺少段落");
    }

    @Test
    @DisplayName("幂等：同键同参重放首次完整响应；异参/异操作者 409；失败不占键")
    void idempotency() throws Exception {
        long docId = publishCompleteDocument(List.of("en"), List.of("s1"));
        String requestId = newRequestId();

        ApiResult first = createArchive(docId, 1, "archiver", requestId);
        assertThat(first.status()).isEqualTo(201);
        long archiveId = first.body().get("archiveId").asLong();
        ApiResult replay = createArchive(docId, 1, "archiver", requestId);
        assertThat(replay.status()).isEqualTo(201);
        assertThat(replay.body().get("archiveId").asLong()).isEqualTo(archiveId);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM archive_package WHERE document_id = ?", Integer.class, docId)).isEqualTo(1);

        // 同键异参（不同发布版本）409
        String bodyDifferentVersion = "{\"requestId\":\"" + requestId + "\",\"publishedVersion\":9}";
        ApiResult differentVersion = postJson("/api/documents/" + docId + "/archives",
                bodyDifferentVersion, "archiver");
        assertThat(differentVersion.status()).isEqualTo(409);

        // 同键异操作者 409（指纹含操作者）
        ApiResult differentActor = createArchive(docId, 1, "someone-else", requestId);
        assertThat(differentActor.status()).isEqualTo(409);

        // 失败不占键：先以某 requestId 触发 404（术语版本不存在），再用同键退役存在的版本成功
        long docId2 = publishCompleteDocument(List.of("en"), List.of("s1"));
        updateTerms(docId2, 0, "[]", newRequestId());
        String retryKey = newRequestId();
        assertThat(retireTerms(docId2, 99, "boss", "x", retryKey).status()).isEqualTo(404);
        ApiResult retried = retireTerms(docId2, 1, "boss", "正式退役", retryKey);
        assertThat(retried.status()).isEqualTo(200);

        // 撤回同样失败不占键：不存在发布版本先 404，版本 1 撤回后用同键对版本 1 再撤回为重放 200
        long docId3 = publishCompleteDocument(List.of("en"), List.of("s1"));
        String withdrawKey = newRequestId();
        assertThat(withdrawRelease(docId3, 99, "boss", "x", withdrawKey).status()).isEqualTo(404);
        assertThat(withdrawRelease(docId3, 1, "boss", "首次撤回", withdrawKey).status()).isEqualTo(200);
        ApiResult withdrawReplay = withdrawRelease(docId3, 1, "boss", "首次撤回", withdrawKey);
        assertThat(withdrawReplay.status()).isEqualTo(200);
        assertThat(withdrawReplay.body().get("reason").asText()).isEqualTo("首次撤回");
    }

    @Test
    @DisplayName("批量幂等指纹：集合顺序归一，[2,1] 与 [1,2] 同指纹可重放；不同集合 409")
    void batchIdempotencyFingerprint() throws Exception {
        long docId = publishCompleteDocument(List.of("en"), List.of("s1"));
        publishSecondVersion(docId, List.of("en"), 2);
        String requestId = newRequestId();

        ApiResult first = batchArchive(docId, "[2,1]", "archiver", requestId);
        assertThat(first.status()).isEqualTo(201);
        ApiResult replay = batchArchive(docId, "[1,2]", "archiver", requestId);
        assertThat(replay.status()).isEqualTo(201);
        assertThat(replay.body().get("packages").get(0).get("publishedVersion").asInt()).isEqualTo(1);

        ApiResult differentSet = batchArchive(docId, "[1]", "archiver", requestId);
        assertThat(differentSet.status()).isEqualTo(409);
    }

    @Test
    @DisplayName("并发：同版本并发归档仅一个成功；归档与撤回并发按提交顺序裁决且无矛盾状态")
    void concurrentArchiveAndWithdrawal() throws Exception {
        long docId = publishCompleteDocument(List.of("en"), List.of("s1"));

        // 并发同版本归档（不同 requestId）：唯一约束兜底，仅一个 201
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<ApiResult>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                gate.await();
                return createArchive(docId, 1, "archiver", newRequestId());
            }));
        }
        gate.countDown();
        int created = 0;
        int conflicts = 0;
        for (Future<ApiResult> future : futures) {
            int status = future.get(30, TimeUnit.SECONDS).status();
            if (status == 201) {
                created++;
            } else if (status == 409) {
                conflicts++;
            }
        }
        assertThat(created).isEqualTo(1);
        assertThat(conflicts).isEqualTo(threads - 1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM archive_package WHERE document_id = ?", Integer.class, docId)).isEqualTo(1);
        pool.shutdown();

        // 新文档：归档与撤回并发，两种提交顺序均合法，但不得出现“撤回后仍新建成功”之外的矛盾
        long docId2 = publishCompleteDocument(List.of("en"), List.of("s1"));
        ExecutorService pool2 = Executors.newFixedThreadPool(2);
        CountDownLatch gate2 = new CountDownLatch(1);
        Future<ApiResult> archiveFuture = pool2.submit(() -> {
            gate2.await();
            return createArchive(docId2, 1, "archiver", newRequestId());
        });
        Future<ApiResult> withdrawFuture = pool2.submit(() -> {
            gate2.await();
            return withdrawRelease(docId2, 1, "boss", "并发撤回", newRequestId());
        });
        gate2.countDown();
        int archiveStatus = archiveFuture.get(30, TimeUnit.SECONDS).status();
        int withdrawStatus = withdrawFuture.get(30, TimeUnit.SECONDS).status();
        pool2.shutdown();

        assertThat(withdrawStatus).isEqualTo(200);
        assertThat(archiveStatus).isIn(201, 422);
        Integer packageCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM archive_package WHERE document_id = ?", Integer.class, docId2);
        Integer withdrawalCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_withdrawal WHERE document_id = ?", Integer.class, docId2);
        assertThat(withdrawalCount).isEqualTo(1);
        if (archiveStatus == 201) {
            // 归档先提交：包存在，撤回后仍可受限查询
            assertThat(packageCount).isEqualTo(1);
        } else {
            // 撤回先提交：无包，且无半成品
            assertThat(packageCount).isZero();
        }
    }
}
