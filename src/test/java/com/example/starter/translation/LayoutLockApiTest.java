package com.example.starter.translation;

import com.fasterxml.jackson.databind.JsonNode;
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
 * 译文版式锁定集成测试：真实 H2 内存库（MODE=MySQL）验证占位符集合校验、源修订失效、
 * 批量批准原子性、撤回、历史证据、发布诊断、快照固化、事务回滚、幂等与并发裁决。
 */
class LayoutLockApiTest extends AbstractIntegrationTest {

    private static final String SOURCE = "Hello {name}, you have {count} new messages for {name}";
    private static final String DECLARED = "[\"{count}\",\"{name}\"]";
    private static final String EN_GOOD = "Hallo {name}, Sie haben {count} neue Nachrichten fuer {name}";
    private static final String JA_GOOD = "こんにちは{name}さん、{name}さんへの新着メッセージは{count}件です";

    private long createDocWithPlaceholders(String requestId) throws Exception {
        return createDocument(requestId, "[\"en\",\"ja\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"" + SOURCE + "\",\"placeholders\":" + DECLARED + "}]");
    }

    private ApiResult revise(String sourceText, String declaredJson, String requestId) throws Exception {
        return putJson("/api/documents/" + docId + "/segments/s1/source",
                "{\"requestId\":\"" + requestId + "\",\"sourceText\":\"" + sourceText + "\""
                        + (declaredJson == null ? "" : ",\"placeholders\":" + declaredJson) + "}");
    }

    private long docId;

    private ApiResult batchApprove(String actorId, String itemsJson, String requestId) throws Exception {
        return postJson("/api/documents/" + docId + "/approvals",
                "{\"requestId\":\"" + requestId + "\",\"items\":" + itemsJson + "}", actorId);
    }

    private ApiResult withdraw(String actorId, String requestId) throws Exception {
        return deleteJson("/api/documents/" + docId + "/segments/s1/translations/en/approve",
                "{\"requestId\":\"" + requestId + "\"}", actorId);
    }

    private JsonNode history() throws Exception {
        return getJson("/api/documents/" + docId + "/history").body();
    }

    private int historyCount() throws Exception {
        return history().get("count").asInt();
    }

    private void prepareTwoApprovedTranslations() throws Exception {
        assertThat(submitTranslation(docId, "s1", "en", "alice", EN_GOOD, 1, newRequestId()).status())
                .isEqualTo(200);
        assertThat(submitTranslation(docId, "s1", "ja", "carol", JA_GOOD, 1, newRequestId()).status())
                .isEqualTo(200);
        assertThat(approve(docId, "s1", "en", "bob", 1, newRequestId()).status()).isEqualTo(200);
        assertThat(approve(docId, "s1", "ja", "dave", 1, newRequestId()).status()).isEqualTo(200);
    }

    @Test
    @DisplayName("主流程：声明集合规范化排序；译文占位符一致可批准发布，快照固化占位符")
    void placeholderMainFlowAndSnapshot() throws Exception {
        docId = createDocWithPlaceholders(newRequestId());

        // 声明顺序乱序入库，响应返回字典序规范集合
        JsonNode detail = getJson("/api/documents/" + docId).body();
        assertThat(detail.get("segments").get(0).get("placeholders").toString())
                .isEqualTo("[\"{count}\",\"{name}\"]");

        ApiResult en = submitTranslation(docId, "s1", "en", "alice", EN_GOOD, 1, newRequestId());
        assertThat(en.status()).isEqualTo(200);
        assertThat(en.body().get("status").asText()).isEqualTo("ACTIVE");

        ApiResult ja = submitTranslation(docId, "s1", "ja", "carol", JA_GOOD, 1, newRequestId());
        assertThat(ja.status()).isEqualTo(200);

        assertThat(batchApprove("bob",
                "[{\"segmentId\":\"s1\",\"language\":\"en\",\"translationVersion\":1},"
                        + "{\"segmentId\":\"s1\",\"language\":\"ja\",\"translationVersion\":1}]",
                newRequestId()).status()).isEqualTo(200);

        // 草稿版本：建文档 1，两次提交 +1+1 = 3，批量批准不产生草稿版本变化
        ApiResult published = publish(docId, 3, 0, newRequestId());
        assertThat(published.status()).isEqualTo(201);

        JsonNode release = getJson("/api/documents/" + docId + "/releases/1").body();
        JsonNode segment = release.get("segments").get(0);
        assertThat(segment.get("placeholders").toString()).isEqualTo("[\"{count}\",\"{name}\"]");
        assertThat(segment.get("placeholderCounts").get("{name}").asInt()).isEqualTo(2);
        JsonNode enSnapshot = segment.get("translations").get(0);
        assertThat(enSnapshot.get("language").asText()).isEqualTo("en");
        assertThat(enSnapshot.get("placeholderCounts").get("{name}").asInt()).isEqualTo(2);
        assertThat(enSnapshot.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(enSnapshot.get("reviewer").asText()).isEqualTo("bob");
    }

    @Test
    @DisplayName("失败分支：非法格式、声明不一致、译文缺失/额外/重复计数均 422，且不写半成品")
    void placeholderValidationFailures() throws Exception {
        docId = createDocWithPlaceholders(newRequestId());

        // 声明非法词法 422
        ApiResult badDeclared = postJson("/api/documents/" + docId + "/segments",
                "{\"requestId\":\"" + newRequestId() + "\",\"segmentId\":\"s2\","
                        + "\"sourceText\":\"x {name}\",\"placeholders\":[\"{na-me}\"]}");
        assertThat(badDeclared.status()).isEqualTo(422);
        assertThat(getJson("/api/documents/" + docId).body().get("segments")).hasSize(1);

        // 源文实际占位符与声明集合不一致（声明缺少 {city}）422；该 requestId 失败不占键
        String failedReviseKey = newRequestId();
        ApiResult mismatch = revise("Hi {name} in {city}", "[\"{name}\"]", failedReviseKey);
        assertThat(mismatch.status()).isEqualTo(422);
        assertThat(mismatch.body().get("message").asText()).contains("差额", "1 个");
        Integer sourceVersion = jdbc.queryForObject(
                "SELECT source_version FROM segment WHERE document_id = ? AND segment_id = 's1'",
                Integer.class, docId);
        assertThat(sourceVersion).isEqualTo(1);

        // 源文含未闭合占位符 422
        assertThat(revise("broken {name", "[\"{name}\"]", newRequestId()).status()).isEqualTo(422);

        // 译文缺失占位符 422（少一个 {name}）
        ApiResult missing = submitTranslation(docId, "s1", "en", "alice",
                "Hallo {name}, {count} messages", 1, newRequestId());
        assertThat(missing.status()).isEqualTo(422);
        assertThat(missing.body().get("message").asText()).contains("缺失", "实际 1 次", "要求 2 次", "差额 1 次");

        // 译文额外占位符 422
        ApiResult extra = submitTranslation(docId, "s1", "en", "alice",
                EN_GOOD + " {city}", 1, newRequestId());
        assertThat(extra.status()).isEqualTo(422);
        assertThat(extra.body().get("message").asText()).contains("额外占位符 {city}");

        // 译文重复计数不一致 422（{count} 多一次）；同时含非法花括号 422
        ApiResult duplicated = submitTranslation(docId, "s1", "en", "alice",
                "Hallo {name}, {count}{count} for {name}", 1, newRequestId());
        assertThat(duplicated.status()).isEqualTo(422);
        assertThat(duplicated.body().get("message").asText()).contains("重复计数不一致");
        ApiResult malformed = submitTranslation(docId, "s1", "en", "alice",
                "Hallo {name}, {count} for {name", 1, newRequestId());
        assertThat(malformed.status()).isEqualTo(422);
        assertThat(malformed.body().get("message").asText()).contains("未闭合");

        // 全部失败均未写译文、未产生多余历史；失败不占 requestId：用同一失败 requestId 修正参数后成功
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM translation WHERE document_id = ?", Integer.class, docId)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM operation_log WHERE document_id = ?", Integer.class, docId)).isEqualTo(1);
        assertThat(revise(SOURCE, DECLARED, failedReviseKey).status())
                .as("失败不占键：原失败 requestId 修正为合法修订后成功（源版本 2）")
                .isEqualTo(200);
    }

    @Test
    @DisplayName("源段修订即新版本：全部译文转待修订，旧译文禁止批准与发布；重新提交后恢复")
    void sourceRevisionMarksTranslationsNeedsRevision() throws Exception {
        docId = createDocWithPlaceholders(newRequestId());
        prepareTwoApprovedTranslations();

        // 仅正文变化（占位符集合不变）也创建新源版本，两译文转 NEEDS_REVISION
        String revisedSource = "Hello {name}, you have {count} renewed messages for {name}";
        ApiResult revised = revise(revisedSource, DECLARED, newRequestId());
        assertThat(revised.status()).isEqualTo(200);
        assertThat(revised.body().get("sourceVersion").asInt()).isEqualTo(2);
        assertThat(revised.body().get("draftVersion").asInt()).isEqualTo(4);

        JsonNode detailAfter = getJson("/api/documents/" + docId).body();
        for (JsonNode t : detailAfter.get("segments").get(0).get("translations")) {
            assertThat(t.get("status").asText()).isEqualTo("NEEDS_REVISION");
            assertThat(t.get("approvalValid").asBoolean()).isFalse();
        }

        // 旧译文不能批准（422，译文待更新）
        assertThat(approve(docId, "s1", "en", "bob", 1, newRequestId()).status()).isEqualTo(422);
        // 旧译文不能发布
        assertThat(publish(docId, 4, 0, newRequestId()).status()).isEqualTo(422);

        // 基于旧源版本重新提交 422；按新版本提交（占位符仍一致）恢复 ACTIVE
        assertThat(submitTranslation(docId, "s1", "en", "alice", EN_GOOD, 1, newRequestId()).status())
                .isEqualTo(422);
        String enV2 = "Hallo {name}, Sie haben {count} erneuerte Nachrichten fuer {name}";
        ApiResult resubmit = submitTranslation(docId, "s1", "en", "alice", enV2, 2, newRequestId());
        assertThat(resubmit.status()).isEqualTo(200);
        assertThat(resubmit.body().get("translationVersion").asInt()).isEqualTo(2);
        assertThat(resubmit.body().get("status").asText()).isEqualTo("ACTIVE");

        // ja 仍待修订：发布继续 422
        assertThat(publish(docId, 5, 0, newRequestId()).status()).isEqualTo(422);
        ApiResult jaV2 = submitTranslation(docId, "s1", "ja", "carol", JA_GOOD, 2, newRequestId());
        assertThat(jaV2.status()).isEqualTo(200);
        assertThat(approve(docId, "s1", "en", "bob", 2, newRequestId()).status()).isEqualTo(200);
        assertThat(approve(docId, "s1", "ja", "dave", 2, newRequestId()).status()).isEqualTo(200);
        assertThat(publish(docId, 6, 0, newRequestId()).status()).isEqualTo(201);
    }

    @Test
    @DisplayName("占位符集合修订：旧译文同样失效；占位符不匹配的新译文 422，匹配后才能发布")
    void placeholderSetRevisionInvalidates() throws Exception {
        docId = createDocWithPlaceholders(newRequestId());
        prepareTwoApprovedTranslations();
        // 修订前先发布 v1（草稿版本 3），用于验证旧快照不被后续修订改写
        assertThat(publish(docId, 3, 0, newRequestId()).status()).isEqualTo(201);

        // 集合从 {count},{name} 变为仅 {name}：新版本，旧译文含 {count} 属多余
        String newSource = "Hello {name}, welcome back {name}";
        ApiResult revised = revise(newSource, "[\"{name}\"]", newRequestId());
        assertThat(revised.status()).isEqualTo(200);
        assertThat(revised.body().get("placeholders").toString()).isEqualTo("[\"{name}\"]");
        assertThat(publish(docId, 4, 1, newRequestId()).status()).isEqualTo(422);

        // 旧风格译文（仍带 {count}）提交：额外占位符 422
        ApiResult extra = submitTranslation(docId, "s1", "en", "alice", EN_GOOD, 2, newRequestId());
        assertThat(extra.status()).isEqualTo(422);
        assertThat(extra.body().get("message").asText()).contains("额外占位符 {count}");

        // 按新集合提交两种语言并批准后发布成功
        String enNew = "Hallo {name}, willkommen zurueck {name}";
        String jaNew = "こんにちは{name}さん、お帰りなさい{name}さん";
        assertThat(submitTranslation(docId, "s1", "en", "alice", enNew, 2, newRequestId()).status()).isEqualTo(200);
        assertThat(submitTranslation(docId, "s1", "ja", "carol", jaNew, 2, newRequestId()).status()).isEqualTo(200);
        assertThat(approve(docId, "s1", "en", "bob", 2, newRequestId()).status()).isEqualTo(200);
        assertThat(approve(docId, "s1", "ja", "dave", 2, newRequestId()).status()).isEqualTo(200);
        assertThat(publish(docId, 6, 1, newRequestId()).status()).isEqualTo(201);

        // 旧快照仍固化旧集合，新快照为新集合，历史证据不被覆盖
        JsonNode release1 = getJson("/api/documents/" + docId + "/releases/1").body();
        assertThat(release1.get("segments").get(0).get("placeholders").toString())
                .isEqualTo("[\"{count}\",\"{name}\"]");
        JsonNode release2 = getJson("/api/documents/" + docId + "/releases/2").body();
        assertThat(release2.get("segments").get(0).get("placeholders").toString())
                .isEqualTo("[\"{name}\"]");
    }

    @Test
    @DisplayName("批量批准：任一项不完整时整次不改变批准状态；全部通过时统一生效")
    void batchApproveAtomicity() throws Exception {
        docId = createDocWithPlaceholders(newRequestId());
        assertThat(submitTranslation(docId, "s1", "en", "alice", EN_GOOD, 1, newRequestId()).status())
                .isEqualTo(200);
        assertThat(submitTranslation(docId, "s1", "ja", "carol", JA_GOOD, 1, newRequestId()).status())
                .isEqualTo(200);

        // en 版本号错误：整批 422，ja 也不得被批准
        ApiResult badBatch = batchApprove("bob",
                "[{\"segmentId\":\"s1\",\"language\":\"en\",\"translationVersion\":9},"
                        + "{\"segmentId\":\"s1\",\"language\":\"ja\",\"translationVersion\":1}]",
                newRequestId());
        assertThat(badBatch.status()).isEqualTo(422);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM approval WHERE document_id = ?",
                Integer.class, docId)).isZero();

        // 审核人是 ja 作者：整批 422
        ApiResult authorBatch = batchApprove("carol",
                "[{\"segmentId\":\"s1\",\"language\":\"en\",\"translationVersion\":1},"
                        + "{\"segmentId\":\"s1\",\"language\":\"ja\",\"translationVersion\":1}]",
                newRequestId());
        assertThat(authorBatch.status()).isEqualTo(422);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM approval WHERE document_id = ?",
                Integer.class, docId)).isZero();

        // 重复项目 422
        ApiResult dupBatch = batchApprove("bob",
                "[{\"segmentId\":\"s1\",\"language\":\"en\",\"translationVersion\":1},"
                        + "{\"segmentId\":\"s1\",\"language\":\"EN\",\"translationVersion\":1}]",
                newRequestId());
        assertThat(dupBatch.status()).isEqualTo(422);

        // 全部有效：一次统一批准两项
        ApiResult ok = batchApprove("bob",
                "[{\"segmentId\":\"s1\",\"language\":\"en\",\"translationVersion\":1},"
                        + "{\"segmentId\":\"s1\",\"language\":\"ja\",\"translationVersion\":1}]",
                newRequestId());
        assertThat(ok.status()).isEqualTo(200);
        assertThat(ok.body().get("approvedCount").asInt()).isEqualTo(2);
        assertThat(ok.body().get("results")).hasSize(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM approval WHERE document_id = ?",
                Integer.class, docId)).isEqualTo(2);
        assertThat(publish(docId, 3, 0, newRequestId()).status()).isEqualTo(201);
    }

    @Test
    @DisplayName("撤回：删除批准后发布 422；重复撤回 removed=false；撤回幂等重放")
    void withdrawApproval() throws Exception {
        docId = createDocWithPlaceholders(newRequestId());
        prepareTwoApprovedTranslations();

        ApiResult withdrawn = withdraw("bob", newRequestId());
        assertThat(withdrawn.status()).isEqualTo(200);
        assertThat(withdrawn.body().get("removed").asBoolean()).isTrue();
        assertThat(publish(docId, 3, 0, newRequestId()).status()).isEqualTo(422);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM approval WHERE document_id = ? AND language = 'en'",
                Integer.class, docId)).isZero();

        // 再次撤回：无批准可删，但操作本身成功返回 removed=false
        ApiResult again = withdraw("bob", newRequestId());
        assertThat(again.status()).isEqualTo(200);
        assertThat(again.body().get("removed").asBoolean()).isFalse();

        // 重新批准后撤回：同 requestId 同参重放返回首次完整响应快照（removed=true），不新增历史
        assertThat(approve(docId, "s1", "en", "bob", 1, newRequestId()).status()).isEqualTo(200);
        String replayKey = newRequestId();
        ApiResult first = withdraw("bob", replayKey);
        assertThat(first.body().get("removed").asBoolean()).isTrue();
        int historyAfterFirst = historyCount();
        ApiResult replay = withdraw("bob", replayKey);
        assertThat(replay.status()).isEqualTo(200);
        assertThat(replay.body().get("removed").asBoolean())
                .as("重放首次成功响应，removed 仍为首次的 true").isTrue();
        assertThat(historyCount()).isEqualTo(historyAfterFirst);
    }

    @Test
    @DisplayName("历史证据：UTC 毫秒左闭右开、稳定排序、指纹齐全、读取不改状态、null 语义稳定")
    void historyEvidenceAndTimeWindow() throws Exception {
        docId = createDocWithPlaceholders(newRequestId());
        prepareTwoApprovedTranslations();
        int opCount = historyCount();
        assertThat(opCount).isEqualTo(5); // 建文档 + 2 提交 + 2 批准

        JsonNode all = history();
        long prevSeq = -1;
        long prevTime = Long.MIN_VALUE;
        for (JsonNode op : all.get("operations")) {
            assertThat(op.get("seq").asLong()).isGreaterThan(prevSeq);
            assertThat(op.get("occurredAtUtcMillis").asLong()).isGreaterThanOrEqualTo(prevTime);
            assertThat(op.get("fingerprint").asText()).matches("[0-9a-f]{64}");
            prevSeq = op.get("seq").asLong();
            prevTime = op.get("occurredAtUtcMillis").asLong();
        }
        // 建文档无操作者：actor/segmentId/language 均为 null；提交有操作者
        JsonNode createOp = all.get("operations").get(0);
        assertThat(createOp.get("operationType").asText()).isEqualTo("DOCUMENT_CREATED");
        assertThat(createOp.get("actor").isNull()).isTrue();
        assertThat(createOp.get("segmentId").isNull()).isTrue();
        assertThat(createOp.get("detail").get("segments").get(0).get("placeholders").toString())
                .isEqualTo("[\"{count}\",\"{name}\"]");
        JsonNode submitOp = all.get("operations").get(1);
        assertThat(submitOp.get("operationType").asText()).isEqualTo("TRANSLATION_SUBMITTED");
        assertThat(submitOp.get("actor").asText()).isEqualTo("alice");
        assertThat(submitOp.get("detail").get("placeholderCounts").get("{name}").asInt()).isEqualTo(2);

        long firstTs = createOp.get("occurredAtUtcMillis").asLong();
        long lastTs = all.get("operations").get(opCount - 1).get("occurredAtUtcMillis").asLong();

        // 左闭右开：[0, firstTs) 为空；[firstTs, firstTs+1) 只含首毫秒事件；to=lastTs 排除最后一毫秒事件
        JsonNode before = getJson("/api/documents/" + docId + "/history?from=0&to=" + firstTs).body();
        assertThat(before.get("operations")).isEmpty();
        JsonNode firstWindow = getJson(
                "/api/documents/" + docId + "/history?from=" + firstTs + "&to=" + (firstTs + 1)).body();
        for (JsonNode op : firstWindow.get("operations")) {
            assertThat(op.get("occurredAtUtcMillis").asLong()).isEqualTo(firstTs);
        }
        JsonNode excludingLast = getJson(
                "/api/documents/" + docId + "/history?from=" + firstTs + "&to=" + lastTs).body();
        for (JsonNode op : excludingLast.get("operations")) {
            assertThat(op.get("occurredAtUtcMillis").asLong()).isLessThan(lastTs);
        }
        // 无前界：[0, lastTs+1) 覆盖全部
        JsonNode fullWindow = getJson(
                "/api/documents/" + docId + "/history?from=0&to=" + (lastTs + 1)).body();
        assertThat(fullWindow.get("count").asInt()).isEqualTo(opCount);
        // from > to 返回 422
        assertThat(getJson("/api/documents/" + docId + "/history?from=" + (lastTs + 10) + "&to=" + firstTs)
                .status()).isEqualTo(422);

        // 读取明细/历史/诊断不改变状态：历史条数与指纹不变
        String fingerprintBefore = createOp.get("fingerprint").asText();
        getJson("/api/documents/" + docId);
        getJson("/api/documents/" + docId + "/diagnosis");
        history();
        history();
        JsonNode after = history();
        assertThat(after.get("count").asInt()).isEqualTo(opCount);
        assertThat(after.get("operations").get(0).get("fingerprint").asText()).isEqualTo(fingerprintBefore);
    }

    @Test
    @DisplayName("发布诊断：返回实际值/要求值/差额与阻塞数；修复后 publishable=true；诊断不写状态")
    void diagnosisReportsActualRequiredAndDifference() throws Exception {
        docId = createDocWithPlaceholders(newRequestId());
        // 经 API 提交占位符不符的译文会在提交侧被 422 拦截；为验证发布侧复核与诊断数值，
        // 用 JDBC 直接构造一条占位符少一次的已存译文（绕过提交侧，模拟历史脏数据防御）。
        assertThat(submitTranslation(docId, "s1", "en", "alice", EN_GOOD, 1, newRequestId()).status())
                .isEqualTo(200);
        jdbc.update("UPDATE translation SET content = ? WHERE document_id = ? AND segment_id = 's1' AND language = 'en'",
                "Hallo {name}, Sie haben {count} Nachrichten fuer", docId);

        int historyBefore = historyCount();
        ApiResult diagnosisResult = getJson("/api/documents/" + docId + "/diagnosis");
        JsonNode diagnosis = diagnosisResult.body();
        assertThat(diagnosis.get("publishable").asBoolean()).isFalse();
        assertThat(diagnosis.get("requiredCount").asInt()).isEqualTo(2);
        assertThat(diagnosis.get("readyCount").asInt()).isZero();
        assertThat(diagnosis.get("blockedCount").asInt()).isEqualTo(2);

        // en 的 PLACEHOLDERS_MATCH：源文要求 3 次（{name}x2+{count}），译文实际 2 次，差额 -1
        JsonNode enPlaceholderItem = findItem(diagnosis, "en", "PLACEHOLDERS_MATCH");
        assertThat(enPlaceholderItem.get("actual").asInt()).isEqualTo(2);
        assertThat(enPlaceholderItem.get("required").asInt()).isEqualTo(3);
        assertThat(enPlaceholderItem.get("difference").asInt()).isEqualTo(-1);
        assertThat(enPlaceholderItem.get("passed").asBoolean()).isFalse();
        // en 还缺批准
        assertThat(findItem(diagnosis, "en", "APPROVAL_PRESENT").get("passed").asBoolean()).isFalse();
        // ja 缺译：要求 1 实际 0 差额 -1
        JsonNode missingItem = findItem(diagnosis, "ja", "TRANSLATION_PRESENT");
        assertThat(missingItem.get("actual").asInt()).isZero();
        assertThat(missingItem.get("required").asInt()).isEqualTo(1);
        assertThat(missingItem.get("difference").asInt()).isEqualTo(-1);
        // 诊断是只读：历史与数据不变
        assertThat(historyCount()).isEqualTo(historyBefore);

        // 发布侧同样拦截该脏数据（占位符不一致 422，无部分快照）
        assertThat(publish(docId, 2, 0, newRequestId()).status()).isEqualTo(422);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_snapshot WHERE document_id = ?", Integer.class, docId)).isZero();

        // 补齐后诊断 publishable=true
        prepareRemainingForPublish();
        JsonNode fixed = getJson("/api/documents/" + docId + "/diagnosis").body();
        assertThat(fixed.get("publishable").asBoolean()).isTrue();
        assertThat(fixed.get("readyCount").asInt()).isEqualTo(2);
        assertThat(fixed.get("blockedCount").asInt()).isZero();
        for (JsonNode item : fixed.get("items")) {
            assertThat(item.get("passed").asBoolean()).isTrue();
            assertThat(item.get("difference").asInt()).isZero();
        }
    }

    private void prepareRemainingForPublish() throws Exception {
        // en 译文为脏数据：按正确内容重提（版本 2）并批准；ja 提交并批准
        assertThat(submitTranslation(docId, "s1", "en", "alice", EN_GOOD, 1, newRequestId()).status())
                .isEqualTo(200);
        assertThat(submitTranslation(docId, "s1", "ja", "carol", JA_GOOD, 1, newRequestId()).status())
                .isEqualTo(200);
        assertThat(approve(docId, "s1", "en", "bob", 2, newRequestId()).status()).isEqualTo(200);
        assertThat(approve(docId, "s1", "ja", "dave", 1, newRequestId()).status()).isEqualTo(200);
    }

    private JsonNode findItem(JsonNode diagnosis, String language, String check) {
        for (JsonNode item : diagnosis.get("items")) {
            if (language.equals(item.get("language").asText()) && check.equals(item.get("check").asText())) {
                return item;
            }
        }
        throw new AssertionError("诊断项不存在: " + language + "/" + check);
    }

    @Test
    @DisplayName("并发裁决：源段修订与批量批准竞争，最终不存在半批批准且发布状态自洽")
    void concurrentReviseAndBatchApprove() throws Exception {
        // 两个段落 × 两种语言共 4 条译文，批量批准应整体成功或整体失败
        docId = createDocument(newRequestId(), "[\"en\",\"ja\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"" + SOURCE + "\",\"placeholders\":" + DECLARED + "},"
                        + "{\"segmentId\":\"s2\",\"sourceText\":\"Bye {name}\",\"placeholders\":[\"{name}\"]}]");
        submitTranslation(docId, "s1", "en", "alice", EN_GOOD, 1, newRequestId());
        submitTranslation(docId, "s1", "ja", "carol", JA_GOOD, 1, newRequestId());
        submitTranslation(docId, "s2", "en", "alice", "Tschuess {name}", 1, newRequestId());
        submitTranslation(docId, "s2", "ja", "carol", "さようなら{name}", 1, newRequestId());

        String items = "[{\"segmentId\":\"s1\",\"language\":\"en\",\"translationVersion\":1},"
                + "{\"segmentId\":\"s1\",\"language\":\"ja\",\"translationVersion\":1},"
                + "{\"segmentId\":\"s2\",\"language\":\"en\",\"translationVersion\":1},"
                + "{\"segmentId\":\"s2\",\"language\":\"ja\",\"translationVersion\":1}]";

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        Future<ApiResult> approveFuture = pool.submit(() -> {
            gate.await();
            return batchApprove("bob", items, newRequestId());
        });
        Future<ApiResult> reviseFuture = pool.submit(() -> {
            gate.await();
            return putJson("/api/documents/" + docId + "/segments/s2/source",
                    "{\"requestId\":\"" + newRequestId() + "\",\"sourceText\":\"Bye bye {name}\","
                            + "\"placeholders\":[\"{name}\"]}");
        });
        gate.countDown();
        ApiResult approveResult = approveFuture.get(30, TimeUnit.SECONDS);
        ApiResult reviseResult = reviseFuture.get(30, TimeUnit.SECONDS);
        pool.shutdown();
        assertThat(reviseResult.status()).isEqualTo(200);
        assertThat(approveResult.status()).isIn(200, 422);

        Integer s2Version = jdbc.queryForObject(
                "SELECT source_version FROM segment WHERE document_id = ? AND segment_id = 's2'",
                Integer.class, docId);
        Integer s2Approvals = jdbc.queryForObject(
                "SELECT COUNT(*) FROM approval WHERE document_id = ? AND segment_id = 's2'",
                Integer.class, docId);
        if (approveResult.status() == 200) {
            // 批准先提交：s2 修订前 4 项全部批准；修订后批准失效，译文待修订，发布必失败
            assertThat(s2Approvals).isEqualTo(2);
            assertThat(s2Version).isEqualTo(2);
            assertThat(publish(docId, 6, 0, newRequestId()).status()).isEqualTo(422);
        } else {
            // 修订先提交：整批失败，无任何批准残留（含 s1）
            Integer allApprovals = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM approval WHERE document_id = ?", Integer.class, docId);
            assertThat(allApprovals).isZero();
            assertThat(s2Approvals).isZero();
        }
        // 任何情况下都不存在部分快照
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_snapshot WHERE document_id = ?", Integer.class, docId)).isZero();
    }

    @Test
    @DisplayName("并发：批准与撤回竞争同一译文，最终状态单一，历史证据条数与实际裁决一致")
    void concurrentApproveAndWithdraw() throws Exception {
        docId = createDocWithPlaceholders(newRequestId());
        assertThat(submitTranslation(docId, "s1", "en", "alice", EN_GOOD, 1, newRequestId()).status())
                .isEqualTo(200);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<ApiResult>> futures = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            final boolean approve = i == 0;
            futures.add(pool.submit(() -> {
                gate.await();
                if (approve) {
                    return approve(docId, "s1", "en", "bob", 1, newRequestId());
                }
                return withdraw("bob", newRequestId());
            }));
        }
        gate.countDown();
        for (Future<ApiResult> future : futures) {
            assertThat(future.get(30, TimeUnit.SECONDS).status()).isEqualTo(200);
        }
        pool.shutdown();

        Integer approvals = jdbc.queryForObject(
                "SELECT COUNT(*) FROM approval WHERE document_id = ? AND segment_id = 's1' AND language = 'en'",
                Integer.class, docId);
        assertThat(approvals).isIn(0, 1);
        // 历史记录真实发生顺序的全部写操作：建文档、提交、批准、撤回（两个独立写操作均落证据）
        JsonNode all = history();
        assertThat(all.get("count").asInt()).isEqualTo(4);
        long seq = -1;
        for (JsonNode op : all.get("operations")) {
            assertThat(op.get("seq").asLong()).isGreaterThan(seq);
            seq = op.get("seq").asLong();
        }
    }

    @Test
    @DisplayName("源修订事务原子性：非法占位符时版本、译文状态与历史均不变")
    void reviseRollsBackOnIllegalPlaceholder() throws Exception {
        docId = createDocWithPlaceholders(newRequestId());
        prepareTwoApprovedTranslations();

        ApiResult failed = revise("Hi {name", "[\"{name}\"]", newRequestId());
        assertThat(failed.status()).isEqualTo(422);

        assertThat(jdbc.queryForObject(
                "SELECT source_version FROM segment WHERE document_id = ? AND segment_id = 's1'",
                Integer.class, docId)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM translation WHERE document_id = ? AND status = 'NEEDS_REVISION'",
                Integer.class, docId)).isZero();
        // 建文档 + 2 提交 + 2 批准 = 5 条，失败修订不留历史
        assertThat(historyCount()).isEqualTo(5);
        // 原批准仍有效：可直接发布
        assertThat(publish(docId, 3, 0, newRequestId()).status()).isEqualTo(201);
    }
}
