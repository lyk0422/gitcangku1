package com.example.starter.translation;

import com.example.starter.translation.archive.UtcClock;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 译文归档包真实 H2 数据库测试：主流程、失败分支、事务原子性、并发裁决、幂等与 UTC 时间边界。
 */
class ArchiveApiTest extends AbstractIntegrationTest {

    @Autowired
    private UtcClock clock;

    @AfterEach
    void resetClock() {
        clock.setClock(Clock.systemUTC());
    }

    /** 建文档（多段多语种）、逐语种提交并批准、发布，返回文档 ID；最终发布版本为 1。 */
    private long seedPublishedDoc(List<String> languages, int segmentCount) throws Exception {
        StringBuilder segments = new StringBuilder("[");
        for (int i = 1; i <= segmentCount; i++) {
            if (i > 1) {
                segments.append(',');
            }
            segments.append("{\"segmentId\":\"s").append(i).append("\",\"sourceText\":\"原文").append(i)
                    .append("\"}");
        }
        segments.append(']');
        long docId = createDocument(newRequestId(), objectMapper.writeValueAsString(languages), segments.toString());
        for (int i = 1; i <= segmentCount; i++) {
            String segmentId = "s" + i;
            for (String language : languages) {
                submitTranslation(docId, segmentId, language, "author-" + language,
                        "译文-" + language + "-" + segmentId, 1, newRequestId());
                approve(docId, segmentId, language, "reviewer-" + language, 1, newRequestId());
            }
        }
        int draftVersion = 1 + languages.size() * segmentCount;
        assertThat(publish(docId, draftVersion, 0, newRequestId()).status()).isEqualTo(201);
        return docId;
    }

    private int packageCount(long docId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM archive_package WHERE document_id = ?", Integer.class, docId);
    }

    private int eventCount(long docId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM archive_event WHERE document_id = ?", Integer.class, docId);
    }

    @Test
    @DisplayName("主流程：为已发布文档生成归档包，固化规范排序语种、逐段译文版本、审批与发布摘要")
    void createArchiveSuccess() throws Exception {
        long docId = seedPublishedDoc(List.of("ja", "en"), 2);
        ApiResult result = createArchive(docId, 1, "[]", "alice", newRequestId());
        assertThat(result.status()).isEqualTo(201);
        JsonNode body = result.body();
        assertThat(body.get("archiveId").asLong()).isPositive();
        assertThat(body.get("archiveVersion").asInt()).isEqualTo(1);
        assertThat(body.get("publishedVersion").asInt()).isEqualTo(1);
        assertThat(body.get("targetLanguages").toString()).contains("en", "ja");
        // 规范排序：en 在 ja 前
        assertThat(body.get("targetLanguages").get(0).asText()).isEqualTo("en");
        assertThat(body.get("termVersion").asInt()).isZero();
        assertThat(body.get("segmentCount").asInt()).isEqualTo(2);
        assertThat(body.get("languageCount").asInt()).isEqualTo(2);
        assertThat(body.get("approvalCount").asInt()).isEqualTo(4);
        assertThat(body.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(body.get("createdAt").asText()).endsWith("Z");

        JsonNode release = body.get("release");
        assertThat(release.get("publishedVersion").asInt()).isEqualTo(1);
        assertThat(release.get("segmentCount").asInt()).isEqualTo(2);
        assertThat(release.get("languageCount").asInt()).isEqualTo(2);
        assertThat(release.get("approvalCount").asInt()).isEqualTo(4);
        assertThat(release.get("status").asText()).isEqualTo("PUBLISHED");

        // 段落按 segmentId、译文按语言码稳定排序
        JsonNode segments = body.get("segments");
        assertThat(segments).hasSize(2);
        assertThat(segments.get(0).get("segmentId").asText()).isEqualTo("s1");
        assertThat(segments.get(0).get("sourceVersion").asInt()).isEqualTo(1);
        JsonNode translations = segments.get(0).get("translations");
        assertThat(translations).hasSize(2);
        assertThat(translations.get(0).get("language").asText()).isEqualTo("en");
        assertThat(translations.get(0).get("content").asText()).isEqualTo("译文-en-s1");
        assertThat(translations.get(0).get("author").asText()).isEqualTo("author-en");
        assertThat(translations.get(0).get("reviewer").asText()).isEqualTo("reviewer-en");
        assertThat(translations.get(0).get("translationVersion").asInt()).isEqualTo(1);
        assertThat(translations.get(0).get("sourceVersion").asInt()).isEqualTo(1);
        assertThat(translations.get(0).get("termVersion").asInt()).isZero();
        assertThat(translations.get(1).get("language").asText()).isEqualTo("ja");

        // 包主记录、逐段证据与事件原子落库
        assertThat(packageCount(docId)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM archive_segment", Integer.class)).isEqualTo(4);
        ApiResult history = getJson("/api/documents/" + docId + "/archive-history");
        assertThat(history.status()).isEqualTo(200);
        JsonNode events = history.body().get("events");
        assertThat(events).hasSize(1);
        assertThat(events.get(0).get("eventType").asText()).isEqualTo("ARCHIVE_CREATED");
        assertThat(events.get(0).get("archiveVersion").asInt()).isEqualTo(1);
        assertThat(events.get(0).get("actor").asText()).isEqualTo("alice");
        assertThat(events.get(0).get("reason").isNull()).isTrue();
    }

    @Test
    @DisplayName("部分语种归档：只固化所选语种及其段落，数量为实际值")
    void partialLanguageArchive() throws Exception {
        long docId = seedPublishedDoc(List.of("en", "ja"), 2);
        ApiResult result = createArchive(docId, 1, "[\"JA\"]", "alice", newRequestId());
        assertThat(result.status()).isEqualTo(201);
        assertThat(result.body().get("targetLanguages")).hasSize(1);
        assertThat(result.body().get("targetLanguages").get(0).asText()).isEqualTo("ja");
        assertThat(result.body().get("languageCount").asInt()).isEqualTo(1);
        assertThat(result.body().get("approvalCount").asInt()).isEqualTo(2);
        assertThat(result.body().get("segments").get(0).get("translations")).hasSize(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM archive_segment", Integer.class)).isEqualTo(2);
    }

    @Test
    @DisplayName("归档失败：引用不存在发布 404；语种不在发布版本中 422；失败不产生半成品且不占 requestId")
    void archiveFailures() throws Exception {
        long docId = seedPublishedDoc(List.of("en"), 1);

        assertThat(createArchive(docId, 99, "[]", "alice", newRequestId()).status()).isEqualTo(404);
        assertThat(getJson("/api/documents/999999/archive-history").status()).isEqualTo(404);

        String failKey = newRequestId();
        ApiResult badLanguage = createArchive(docId, 1, "[\"fr\"]", "alice", failKey);
        assertThat(badLanguage.status()).isEqualTo(422);
        assertThat(badLanguage.body().get("message").asText()).contains("fr");

        // 失败不留半成品
        assertThat(packageCount(docId)).isZero();
        assertThat(eventCount(docId)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM archive_segment", Integer.class)).isZero();

        // 失败不占键：同 requestId 改合法参数后成功
        ApiResult retried = createArchive(docId, 1, "[\"en\"]", "alice", failKey);
        assertThat(retried.status()).isEqualTo(201);
        assertThat(retried.body().get("archiveVersion").asInt()).isEqualTo(1);
        assertThat(packageCount(docId)).isEqualTo(1);
    }

    @Test
    @DisplayName("归档校验直接构造的问题快照：缺译/缺审批/版本不一致整次 422 且无半成品")
    void archiveValidatesMalformedSnapshots() throws Exception {
        long docId = seedPublishedDoc(List.of("en", "ja"), 1);
        String original = jdbc.queryForObject(
                "SELECT snapshot_json FROM release_snapshot WHERE document_id = ? AND published_version = 1",
                String.class, docId);

        // 缺译：移除 s1 的 ja 译文
        JsonNode missingTranslation = objectMapper.readTree(original);
        ((ObjectNode) missingTranslation.get("segments").get(0)).set("translations",
                objectMapper.createArrayNode()
                        .add(missingTranslation.get("segments").get(0).get("translations").get(0)));
        insertSnapshot(docId, 91, missingTranslation.toString());
        assertThat(createArchive(docId, 91, "[]", "alice", newRequestId()).status()).isEqualTo(422);

        // 缺审批：reviewer 置空
        JsonNode missingReviewer = objectMapper.readTree(original);
        ((ObjectNode) missingReviewer.get("segments").get(0).get("translations").get(0)).putNull("reviewer");
        insertSnapshot(docId, 92, missingReviewer.toString());
        assertThat(createArchive(docId, 92, "[]", "alice", newRequestId()).status()).isEqualTo(422);

        // 版本不一致：译文术语版本与快照术语版本不一致
        JsonNode termMismatch = objectMapper.readTree(original);
        ((ObjectNode) termMismatch.get("segments").get(0).get("translations").get(0)).put("termVersion", 7);
        insertSnapshot(docId, 93, termMismatch.toString());
        assertThat(createArchive(docId, 93, "[]", "alice", newRequestId()).status()).isEqualTo(422);

        // 源文版本不一致
        JsonNode sourceMismatch = objectMapper.readTree(original);
        ((ObjectNode) sourceMismatch.get("segments").get(0)).put("sourceVersion", 5);
        insertSnapshot(docId, 94, sourceMismatch.toString());
        assertThat(createArchive(docId, 94, "[]", "alice", newRequestId()).status()).isEqualTo(422);

        // 全部失败后无任何包与逐段证据
        assertThat(packageCount(docId)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM archive_segment", Integer.class)).isZero();
        assertThat(eventCount(docId)).isZero();
    }

    @Test
    @DisplayName("撤回发布：撤回后不能新建归档包；已有归档包仍可查明细；重复撤回 409")
    void withdrawReleaseAndRestrictedArchiveQuery() throws Exception {
        long docId = seedPublishedDoc(List.of("en"), 1);
        ApiResult archived = createArchive(docId, 1, "[]", "alice", newRequestId());
        assertThat(archived.status()).isEqualTo(201);
        int archiveId = archived.body().get("archiveId").asInt();

        ApiResult withdrawn = withdrawRelease(docId, 1, "发现译文错误", "bob", newRequestId());
        assertThat(withdrawn.status()).isEqualTo(200);
        assertThat(withdrawn.body().get("status").asText()).isEqualTo("WITHDRAWN");
        assertThat(withdrawn.body().get("publishedVersion").asInt()).isEqualTo(1);

        // 撤回后不能新建归档包
        ApiResult newArchive = createArchive(docId, 1, "[]", "carol", newRequestId());
        assertThat(newArchive.status()).isEqualTo(422);
        assertThat(newArchive.body().get("message").asText()).contains("已撤回");
        assertThat(packageCount(docId)).isEqualTo(1);

        // 已有归档包仍可受限查询，证据不受撤回影响
        ApiResult detail = getJson("/api/documents/" + docId + "/archives/1");
        assertThat(detail.status()).isEqualTo(200);
        assertThat(detail.body().get("archiveId").asInt()).isEqualTo(archiveId);

        // 历史含撤回事件，字段与 null 语义稳定
        JsonNode events = getJson("/api/documents/" + docId + "/archive-history").body().get("events");
        assertThat(events).hasSize(2);
        assertThat(events.get(0).get("eventType").asText()).isEqualTo("ARCHIVE_CREATED");
        assertThat(events.get(1).get("eventType").asText()).isEqualTo("RELEASE_WITHDRAWN");
        assertThat(events.get(1).get("actor").asText()).isEqualTo("bob");
        assertThat(events.get(1).get("reason").asText()).isEqualTo("发现译文错误");
        assertThat(events.get(1).get("archiveId").isNull()).isTrue();
        assertThat(events.get(1).get("archiveVersion").isNull()).isTrue();

        // 发布快照证据 JSON 未被撤回改写
        assertThat(jdbc.queryForObject(
                "SELECT status FROM release_snapshot WHERE document_id = ? AND published_version = 1",
                String.class, docId)).isEqualTo("WITHDRAWN");

        assertThat(withdrawRelease(docId, 1, "再次撤回", "bob", newRequestId()).status()).isEqualTo(409);
        assertThat(withdrawRelease(docId, 99, "x", "bob", newRequestId()).status()).isEqualTo(404);
    }

    @Test
    @DisplayName("撤销归档：明细 410、证据与原因保留、历史与诊断可查、重复撤销 409")
    void revokeArchiveKeepsEvidence() throws Exception {
        long docId = seedPublishedDoc(List.of("en"), 1);
        ApiResult archived = createArchive(docId, 1, "[]", "alice", newRequestId());
        long archiveId = archived.body().get("archiveId").asLong();
        String evidenceBefore = jdbc.queryForObject(
                "SELECT evidence_json FROM archive_package WHERE archive_id = ?", String.class, archiveId);

        ApiResult revoked = revokeArchive(docId, 1, "合规要求下架", "carol", newRequestId());
        assertThat(revoked.status()).isEqualTo(200);
        assertThat(revoked.body().get("status").asText()).isEqualTo("REVOKED");
        assertThat(revoked.body().get("revokeReason").asText()).isEqualTo("合规要求下架");
        assertThat(revoked.body().get("revokedBy").asText()).isEqualTo("carol");
        assertThat(revoked.body().get("revokedAt").asText()).endsWith("Z");

        // 明细访问失效
        ApiResult detail = getJson("/api/documents/" + docId + "/archives/1");
        assertThat(detail.status()).isEqualTo(410);
        assertThat(detail.body().get("error").asText()).isEqualTo("ARCHIVE_REVOKED");
        assertThat(detail.body().get("message").asText()).contains("合规要求下架");

        // 证据 JSON 完全保留
        String evidenceAfter = jdbc.queryForObject(
                "SELECT evidence_json FROM archive_package WHERE archive_id = ?", String.class, archiveId);
        assertThat(evidenceAfter).isEqualTo(evidenceBefore);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM archive_segment WHERE archive_id = ?", Integer.class, archiveId))
                .isEqualTo(1);

        // 历史与诊断仍可受限查询
        JsonNode events = getJson("/api/documents/" + docId + "/archive-history").body().get("events");
        assertThat(events).hasSize(2);
        assertThat(events.get(1).get("eventType").asText()).isEqualTo("ARCHIVE_REVOKED");
        assertThat(events.get(1).get("archiveVersion").asInt()).isEqualTo(1);

        ApiResult diagnostics = getJson("/api/documents/" + docId + "/archive-diagnostics");
        assertThat(diagnostics.status()).isEqualTo(200);
        JsonNode row = diagnostics.body().get("archives").get(0);
        assertThat(row.get("status").asText()).isEqualTo("REVOKED");
        assertThat(row.get("archivedRowCount").asInt()).isEqualTo(1);
        assertThat(row.get("missingRowCount").asInt()).isZero();
        assertThat(row.get("detail").asText()).contains("差额 0");

        assertThat(revokeArchive(docId, 1, "再次撤销", "carol", newRequestId()).status()).isEqualTo(409);
        assertThat(revokeArchive(docId, 99, "x", "carol", newRequestId()).status()).isEqualTo(404);
    }

    @Test
    @DisplayName("证据不可变：归档后源文修订、译文重提、术语更新与再发布均不改写归档证据")
    void archiveEvidenceIsImmutable() throws Exception {
        long docId = seedPublishedDoc(List.of("en"), 1);
        ApiResult archived = createArchive(docId, 1, "[]", "alice", newRequestId());
        long archiveId = archived.body().get("archiveId").asLong();
        String evidenceBefore = jdbc.queryForObject(
                "SELECT evidence_json FROM archive_package WHERE archive_id = ?", String.class, archiveId);
        String releaseBefore = jdbc.queryForObject(
                "SELECT snapshot_json FROM release_snapshot WHERE document_id = ? AND published_version = 1",
                String.class, docId);

        // 归档后：术语“退役”（清空规则集）、源文修订、译文重提再批准、二次发布
        updateTerms(docId, 0, "[]", newRequestId());
        putJson("/api/documents/" + docId + "/segments/s1/source",
                "{\"requestId\":\"" + newRequestId() + "\",\"sourceText\":\"修订后原文\"}");
        submitTranslation(docId, "s1", "en", "author-en", "全新译文", 2, newRequestId());
        approve(docId, "s1", "en", "reviewer-en", 2, newRequestId());
        // 发布 v1 后草稿 2；术语更新 3、源文修订 4、译文重提 5
        ApiResult second = publish(docId, 5, 1, newRequestId());
        assertThat(second.status()).isEqualTo(201);
        assertThat(second.body().get("publishedVersion").asInt()).isEqualTo(2);

        // 归档证据与发布 v1 快照均原样保留
        assertThat(jdbc.queryForObject(
                "SELECT evidence_json FROM archive_package WHERE archive_id = ?", String.class, archiveId))
                .isEqualTo(evidenceBefore);
        assertThat(jdbc.queryForObject(
                "SELECT snapshot_json FROM release_snapshot WHERE document_id = ? AND published_version = 1",
                String.class, docId)).isEqualTo(releaseBefore);

        ApiResult detail = getJson("/api/documents/" + docId + "/archives/1");
        JsonNode t = detail.body().get("segments").get(0).get("translations").get(0);
        assertThat(t.get("content").asText()).isEqualTo("译文-en-s1");
        assertThat(t.get("translationVersion").asInt()).isEqualTo(1);
        assertThat(t.get("sourceVersion").asInt()).isEqualTo(1);
        assertThat(detail.body().get("termVersion").asInt()).isZero();
        assertThat(detail.body().get("segments").get(0).get("sourceText").asText()).isEqualTo("原文1");
    }

    @Test
    @DisplayName("批量归档：多版本全部成功；任一语言不完整整批 422 且不产生部分包；重复项 422")
    void batchArchiveAtomicity() throws Exception {
        // 文档 1 段 1 语：先发 v1，再修订重提发 v2
        long docId = seedPublishedDoc(List.of("en"), 1);
        putJson("/api/documents/" + docId + "/segments/s1/source",
                "{\"requestId\":\"" + newRequestId() + "\",\"sourceText\":\"原文v2\"}");
        submitTranslation(docId, "s1", "en", "author-en", "译文v2", 2, newRequestId());
        approve(docId, "s1", "en", "reviewer-en", 2, newRequestId());
        // v1 后草稿 2，修订 3，重提 4
        assertThat(publish(docId, 4, 1, newRequestId()).status()).isEqualTo(201);

        ApiResult ok = batchArchive(docId,
                "[{\"publishedVersion\":2,\"targetLanguages\":[]},"
                        + "{\"publishedVersion\":1,\"targetLanguages\":[\"en\"]}]",
                "alice", newRequestId());
        assertThat(ok.status()).isEqualTo(201);
        assertThat(ok.body().get("packageCount").asInt()).isEqualTo(2);
        // 结果按归档版本顺序：v1->归档版本1，v2->归档版本2
        assertThat(ok.body().get("packages").get(0).get("publishedVersion").asInt()).isEqualTo(1);
        assertThat(ok.body().get("packages").get(0).get("archiveVersion").asInt()).isEqualTo(1);
        assertThat(ok.body().get("packages").get(1).get("publishedVersion").asInt()).isEqualTo(2);
        assertThat(ok.body().get("packages").get(1).get("archiveVersion").asInt()).isEqualTo(2);
        assertThat(packageCount(docId)).isEqualTo(2);
        assertThat(eventCount(docId)).isEqualTo(2);

        // 任一项语种不在发布版本中：整批失败，不新增任何包（版本号也不被占用）
        ApiResult partial = batchArchive(docId,
                "[{\"publishedVersion\":1,\"targetLanguages\":[]},"
                        + "{\"publishedVersion\":2,\"targetLanguages\":[\"fr\"]}]",
                "alice", newRequestId());
        assertThat(partial.status()).isEqualTo(422);
        assertThat(packageCount(docId)).isEqualTo(2);
        assertThat(eventCount(docId)).isEqualTo(2);

        // 下一次成功归档的版本号紧接 2（失败未占用归档版本）
        ApiResult next = createArchive(docId, 1, "[]", "alice", newRequestId());
        assertThat(next.status()).isEqualTo(201);
        assertThat(next.body().get("archiveVersion").asInt()).isEqualTo(3);

        // 批量重复项 422
        ApiResult duplicate = batchArchive(docId,
                "[{\"publishedVersion\":1,\"targetLanguages\":[]},"
                        + "{\"publishedVersion\":1,\"targetLanguages\":[\"EN\"]}]",
                "alice", newRequestId());
        assertThat(duplicate.status()).isEqualTo(422);
    }

    @Test
    @DisplayName("幂等：同 requestId 同参重放首次完整包；异参 409；读取不改状态")
    void idempotency() throws Exception {
        long docId = seedPublishedDoc(List.of("en", "ja"), 1);
        String requestId = newRequestId();
        ApiResult first = createArchive(docId, 1, "[\"EN\"]", "alice", requestId);
        assertThat(first.status()).isEqualTo(201);
        long archiveId = first.body().get("archiveId").asLong();

        // 同参（大小写/空白规范化后等价）重放：返回首次完整响应快照
        ApiResult replay = createArchive(docId, 1, "[\" en \"]", "alice", requestId);
        assertThat(replay.status()).isEqualTo(201);
        assertThat(replay.body().get("archiveId").asLong()).isEqualTo(archiveId);
        assertThat(replay.body().get("archiveVersion").asInt()).isEqualTo(1);
        assertThat(replay.body().get("targetLanguages").get(0).asText()).isEqualTo("en");
        assertThat(packageCount(docId)).isEqualTo(1);
        assertThat(eventCount(docId)).isEqualTo(1);

        // 读取明细不改变状态
        getJson("/api/documents/" + docId + "/archives/1");
        getJson("/api/documents/" + docId + "/archive-diagnostics");
        assertThat(packageCount(docId)).isEqualTo(1);

        // 异参 409：不同语种集合
        assertThat(createArchive(docId, 1, "[\"ja\"]", "alice", requestId).status()).isEqualTo(409);
        // 异参 409：不同操作者
        assertThat(createArchive(docId, 1, "[\"en\"]", "bob", requestId).status()).isEqualTo(409);
        // 异参 409：不同发布版本
        assertThat(createArchive(docId, 2, "[\"en\"]", "alice", requestId).status()).isEqualTo(409);
        assertThat(packageCount(docId)).isEqualTo(1);
    }

    @Test
    @DisplayName("批量幂等：项顺序不同但语义相同视为同参，重放首次完整响应")
    void batchIdempotencyOrderInsensitive() throws Exception {
        long docId = seedPublishedDoc(List.of("en"), 1);
        putJson("/api/documents/" + docId + "/segments/s1/source",
                "{\"requestId\":\"" + newRequestId() + "\",\"sourceText\":\"原文v2\"}");
        submitTranslation(docId, "s1", "en", "author-en", "译文v2", 2, newRequestId());
        approve(docId, "s1", "en", "reviewer-en", 2, newRequestId());
        assertThat(publish(docId, 4, 1, newRequestId()).status()).isEqualTo(201);

        String requestId = newRequestId();
        ApiResult first = batchArchive(docId,
                "[{\"publishedVersion\":1,\"targetLanguages\":[]},"
                        + "{\"publishedVersion\":2,\"targetLanguages\":[]}]", "alice", requestId);
        assertThat(first.status()).isEqualTo(201);
        ApiResult replay = batchArchive(docId,
                "[{\"publishedVersion\":2,\"targetLanguages\":[]},"
                        + "{\"publishedVersion\":1,\"targetLanguages\":[]}]", "alice", requestId);
        assertThat(replay.status()).isEqualTo(201);
        assertThat(replay.body().get("packages").toString())
                .isEqualTo(first.body().get("packages").toString());
        assertThat(packageCount(docId)).isEqualTo(2);
    }

    @Test
    @DisplayName("并发归档：同文档不同 requestId 串行裁决，归档版本无丢失更新，事件按序")
    void concurrentArchives() throws Exception {
        long docId = seedPublishedDoc(List.of("en"), 1);
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<ApiResult>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                gate.await();
                return createArchive(docId, 1, "[]", "alice", newRequestId());
            }));
        }
        gate.countDown();
        List<Integer> versions = new ArrayList<>();
        for (Future<ApiResult> future : futures) {
            ApiResult result = future.get(30, TimeUnit.SECONDS);
            assertThat(result.status()).isEqualTo(201);
            versions.add(result.body().get("archiveVersion").asInt());
        }
        pool.shutdown();

        assertThat(versions).containsExactlyInAnyOrder(1, 2, 3, 4, 5, 6);
        assertThat(packageCount(docId)).isEqualTo(threads);
        assertThat(eventCount(docId)).isEqualTo(threads);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM archive_segment", Integer.class)).isEqualTo(threads);
    }

    @Test
    @DisplayName("并发同 requestId 归档：只生成一次，其余重放同一完整包")
    void concurrentSameRequestIdArchive() throws Exception {
        long docId = seedPublishedDoc(List.of("en"), 1);
        String requestId = newRequestId();
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<ApiResult>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                gate.await();
                return createArchive(docId, 1, "[]", "alice", requestId);
            }));
        }
        gate.countDown();
        long archiveId = 0;
        for (Future<ApiResult> future : futures) {
            ApiResult result = future.get(30, TimeUnit.SECONDS);
            assertThat(result.status()).isEqualTo(201);
            if (archiveId == 0) {
                archiveId = result.body().get("archiveId").asLong();
            }
            assertThat(result.body().get("archiveId").asLong()).isEqualTo(archiveId);
            assertThat(result.body().get("archiveVersion").asInt()).isEqualTo(1);
        }
        pool.shutdown();
        assertThat(packageCount(docId)).isEqualTo(1);
        assertThat(eventCount(docId)).isEqualTo(1);
    }

    @Test
    @DisplayName("并发撤回与归档：按提交顺序裁决，归档成功则撤回在后，撤回成功则归档 422，无半成品")
    void concurrentWithdrawAndArchive() throws Exception {
        long docId = seedPublishedDoc(List.of("en"), 1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        Future<ApiResult> archiveFuture = pool.submit(() -> {
            gate.await();
            return createArchive(docId, 1, "[]", "alice", newRequestId());
        });
        Future<ApiResult> withdrawFuture = pool.submit(() -> {
            gate.await();
            return withdrawRelease(docId, 1, "并发撤回", "bob", newRequestId());
        });
        gate.countDown();
        ApiResult archiveResult = archiveFuture.get(30, TimeUnit.SECONDS);
        ApiResult withdrawResult = withdrawFuture.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(withdrawResult.status()).isEqualTo(200);
        assertThat(archiveResult.status()).isIn(201, 422);
        if (archiveResult.status() == 201) {
            // 归档先提交：1 个 ACTIVE 包，随后撤回，明细仍可查
            assertThat(packageCount(docId)).isEqualTo(1);
            assertThat(getJson("/api/documents/" + docId + "/archives/1").status()).isEqualTo(200);
            JsonNode events = getJson("/api/documents/" + docId + "/archive-history").body().get("events");
            assertThat(events.get(0).get("eventType").asText()).isEqualTo("ARCHIVE_CREATED");
            assertThat(events.get(1).get("eventType").asText()).isEqualTo("RELEASE_WITHDRAWN");
        } else {
            // 撤回先提交：无包、无逐段证据
            assertThat(packageCount(docId)).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM archive_segment", Integer.class)).isZero();
            assertThat(eventCount(docId)).isEqualTo(1);
        }
        // 无论顺序，撤回最终生效
        assertThat(jdbc.queryForObject(
                "SELECT status FROM release_snapshot WHERE document_id = ? AND published_version = 1",
                String.class, docId)).isEqualTo("WITHDRAWN");
    }

    @Test
    @DisplayName("UTC 时间与左闭右开区间：可控时钟下归档时刻精确到毫秒，诊断区间端点语义正确")
    void utcTimestampsAndHalfOpenInterval() throws Exception {
        Instant t1 = Instant.parse("2026-03-01T10:15:30.500Z");
        Instant t2 = Instant.parse("2026-03-01T10:15:45.750Z");
        clock.setClock(Clock.fixed(t1, ZoneOffset.UTC));
        long docId = seedPublishedDoc(List.of("en"), 1);
        ApiResult first = createArchive(docId, 1, "[]", "alice", newRequestId());
        assertThat(first.body().get("createdAt").asText()).isEqualTo("2026-03-01T10:15:30.500Z");
        assertThat(first.body().get("release").get("createdAt").asText())
                .isEqualTo("2026-03-01T10:15:30.500Z");

        // 发 v2 并在 t2 归档
        putJson("/api/documents/" + docId + "/segments/s1/source",
                "{\"requestId\":\"" + newRequestId() + "\",\"sourceText\":\"原文v2\"}");
        submitTranslation(docId, "s1", "en", "author-en", "译文v2", 2, newRequestId());
        approve(docId, "s1", "en", "reviewer-en", 2, newRequestId());
        assertThat(publish(docId, 4, 1, newRequestId()).status()).isEqualTo(201);
        clock.setClock(Clock.fixed(t2, ZoneOffset.UTC));
        ApiResult second = createArchive(docId, 2, "[]", "alice", newRequestId());
        assertThat(second.body().get("createdAt").asText()).isEqualTo("2026-03-01T10:15:45.750Z");

        // [t1, t2)：左闭包含 t1 的包，右开排除 t2 的包
        ApiResult window = getJson("/api/documents/" + docId
                + "/archive-diagnostics?from=2026-03-01T10:15:30.500Z&to=2026-03-01T10:15:45.750Z");
        assertThat(window.body().get("archives")).hasSize(1);
        assertThat(window.body().get("archives").get(0).get("archiveVersion").asInt()).isEqualTo(1);

        // [t2, ...)：仅 t2 的包
        ApiResult fromT2 = getJson("/api/documents/" + docId
                + "/archive-diagnostics?from=2026-03-01T10:15:45.750Z");
        assertThat(fromT2.body().get("archives")).hasSize(1);
        assertThat(fromT2.body().get("archives").get(0).get("archiveVersion").asInt()).isEqualTo(2);

        // [..., t1)：空
        ApiResult beforeT1 = getJson("/api/documents/" + docId
                + "/archive-diagnostics?to=2026-03-01T10:15:30.500Z");
        assertThat(beforeT1.body().get("archives")).isEmpty();

        // from >= to 非法区间 422
        ApiResult badRange = getJson("/api/documents/" + docId
                + "/archive-diagnostics?from=2026-03-01T10:15:45.750Z&to=2026-03-01T10:15:30.500Z");
        assertThat(badRange.status()).isEqualTo(422);

        // 按发布版本过滤 + 诊断实际值
        ApiResult filtered = getJson("/api/documents/" + docId + "/archive-diagnostics?publishedVersion=2");
        assertThat(filtered.body().get("requestedPublishedVersion").asInt()).isEqualTo(2);
        assertThat(filtered.body().get("archives")).hasSize(1);
        JsonNode row = filtered.body().get("archives").get(0);
        assertThat(row.get("publishedVersion").asInt()).isEqualTo(2);
        assertThat(row.get("archivedRowCount").asInt()).isEqualTo(1);
        assertThat(row.get("approvalCount").asInt()).isEqualTo(1);
        assertThat(row.get("missingRowCount").asInt()).isZero();
    }

    private void insertSnapshot(long docId, int publishedVersion, String snapshotJson) {
        jdbc.update("INSERT INTO release_snapshot (document_id, published_version, snapshot_json) "
                + "VALUES (?, ?, ?)", docId, publishedVersion, snapshotJson);
    }
}
