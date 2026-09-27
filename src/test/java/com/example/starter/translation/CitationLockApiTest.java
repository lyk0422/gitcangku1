package com.example.starter.translation;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 译文引文锁定端到端测试（真实 H2 内存库，MODE=MySQL）：
 * 覆盖登记、区间迁移、解除法务确认、批量修订事务边界、发布快照固化与追溯、
 * 幂等、诊断与唯一约束并发，所有用例核对数据库最终状态而非打印。
 */
class CitationLockApiTest extends AbstractIntegrationTest {

    private static final Pattern UTC_MICROS =
            Pattern.compile("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{6}Z");

    /** 建文档、提交并批准 s1/en 译文 "hello world"，返回文档 ID（草稿版本 2）。 */
    private long approvedDoc() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"原文\"}]");
        assertThat(submitTranslation(docId, "s1", "en", "alice", "hello world", 1, newRequestId()).status())
                .isEqualTo(200);
        assertThat(approve(docId, "s1", "en", "bob", 1, newRequestId()).status()).isEqualTo(200);
        return docId;
    }

    @Test
    @DisplayName("登记锚点：未批准/批准失效 422；区间越界、交叉、重复标识 422；成功区间按码点截取引用文本")
    void registerAnchorRules() throws Exception {
        // 未批准不能登记
        long docId0 = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"原文\"}]");
        submitTranslation(docId0, "s1", "en", "alice", "hello world", 1, newRequestId());
        ApiResult noApproval = registerAnchors(docId0, "s1", "en", "alice",
                "[{\"citationId\":\"isbn-1\",\"startOffset\":0,\"endOffset\":5,\"lockReason\":\"判决引用\"}]",
                newRequestId());
        assertThat(noApproval.status()).isEqualTo(422);
        assertThat(noApproval.body().get("error").asText()).isEqualTo("ANCHOR_MAPPING_VIOLATION");

        long docId = approvedDoc();

        // 区间越界：end 超过译文码点长度 11
        ApiResult outOfRange = registerAnchors(docId, "s1", "en", "alice",
                "[{\"citationId\":\"isbn-1\",\"startOffset\":8,\"endOffset\":12,\"lockReason\":\"r\"}]",
                newRequestId());
        assertThat(outOfRange.status()).isEqualTo(422);
        assertThat(outOfRange.body().get("message").asText()).contains("码点长度 11", "差额 1");

        // 空区间（start==end）字段注解不拦截，由服务层区间规则判 422；负偏移由注解判 400
        ApiResult emptyRange = registerAnchors(docId, "s1", "en", "alice",
                "[{\"citationId\":\"isbn-1\",\"startOffset\":5,\"endOffset\":5,\"lockReason\":\"r\"}]",
                newRequestId());
        assertThat(emptyRange.status()).isEqualTo(422);
        ApiResult negativeRange = registerAnchors(docId, "s1", "en", "alice",
                "[{\"citationId\":\"isbn-1\",\"startOffset\":-1,\"endOffset\":5,\"lockReason\":\"r\"}]",
                newRequestId());
        assertThat(negativeRange.status()).isEqualTo(400);

        // 成功登记两个不交叉锚点 [0,5)="hello"、[6,11)="world"
        ApiResult ok = registerAnchors(docId, "s1", "en", "alice",
                "[{\"citationId\":\" ISBN-1 \",\"startOffset\":0,\"endOffset\":5,\"lockReason\":\"判决引用一\"},"
                        + "{\"citationId\":\"isbn-2\",\"startOffset\":6,\"endOffset\":11,\"lockReason\":\"判决引用二\"}]",
                newRequestId());
        assertThat(ok.status()).isEqualTo(201);
        assertThat(ok.body().get("anchors")).hasSize(2);
        JsonNode first = ok.body().get("anchors").get(0);
        assertThat(first.get("quoteText").asText()).isEqualTo("hello");
        assertThat(first.get("normalizedCitationId").asText()).isEqualTo("isbn-1");
        assertThat(first.get("citationId").asText()).isEqualTo(" ISBN-1 ");
        assertThat(first.get("createdBy").asText()).isEqualTo("alice");
        assertThat(first.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(first.get("createdAt").asText()).matches(UTC_MICROS);
        Instant.parse(first.get("createdAt").asText());
        // 登记不改变草稿版本
        assertThat(ok.body().get("draftVersion").asInt()).isEqualTo(2);

        // 与已锁定区间交叉
        ApiResult crossing = registerAnchors(docId, "s1", "en", "alice",
                "[{\"citationId\":\"isbn-3\",\"startOffset\":3,\"endOffset\":7,\"lockReason\":\"r\"}]",
                newRequestId());
        assertThat(crossing.status()).isEqualTo(422);

        // 规范化后重复（含同批重复）
        ApiResult duplicate = registerAnchors(docId, "s1", "en", "alice",
                "[{\"citationId\":\"isbn 1\",\"startOffset\":0,\"endOffset\":1,\"lockReason\":\"r\"}]",
                newRequestId());
        assertThat(duplicate.status()).isEqualTo(422);
        ApiResult duplicateInBatch = registerAnchors(docId, "s1", "en", "alice",
                "[{\"citationId\":\"x\",\"startOffset\":0,\"endOffset\":1,\"lockReason\":\"r\"},"
                        + "{\"citationId\":\"X\",\"startOffset\":1,\"endOffset\":2,\"lockReason\":\"r\"}]",
                newRequestId());
        assertThat(duplicateInBatch.status()).isEqualTo(422);

        // 失败不占键、不留半成品
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM citation_anchor WHERE document_id = ?", Integer.class, docId))
                .isEqualTo(2);
    }

    @Test
    @DisplayName("登记前置状态：源文修订使批准失效后登记 422，重新批准后可登记")
    void registerRequiresLiveApproval() throws Exception {
        long docId = approvedDoc();
        putJson("/api/documents/" + docId + "/segments/s1/source",
                "{\"requestId\":\"" + newRequestId() + "\",\"sourceText\":\"新原文\"}");
        ApiResult stale = registerAnchors(docId, "s1", "en", "alice",
                "[{\"citationId\":\"isbn-1\",\"startOffset\":0,\"endOffset\":5,\"lockReason\":\"r\"}]",
                newRequestId());
        assertThat(stale.status()).isEqualTo(422);

        submitTranslation(docId, "s1", "en", "alice", "hello world", 2, newRequestId());
        approve(docId, "s1", "en", "bob", 2, newRequestId());
        ApiResult ok = registerAnchors(docId, "s1", "en", "alice",
                "[{\"citationId\":\"isbn-1\",\"startOffset\":0,\"endOffset\":5,\"lockReason\":\"r\"}]",
                newRequestId());
        assertThat(ok.status()).isEqualTo(201);
    }

    @Test
    @DisplayName("锁定后修订：丢失引用文本 422 不落库；区间不动保留；区间迁移给一一映射，文本不一致 422")
    void revisionMustPreserveAnchors() throws Exception {
        long docId = approvedDoc();
        ApiResult registered = registerAnchors(docId, "s1", "en", "alice",
                "[{\"citationId\":\"isbn-1\",\"startOffset\":0,\"endOffset\":5,\"lockReason\":\"r\"}]",
                newRequestId());
        long anchorId = registered.body().get("anchors").get(0).get("anchorId").asLong();

        // 未给映射且新文本中旧区间文本变化：422，译文与草稿版本不变
        ApiResult dropped = submitTranslation(docId, "s1", "en", "carol", "HELLO world", 1, newRequestId());
        assertThat(dropped.status()).isEqualTo(422);
        assertThat(dropped.body().get("message").asText()).contains("锚点映射缺失", "差额 1");
        assertThat(jdbc.queryForObject(
                "SELECT content FROM translation WHERE document_id = ? AND segment_id = 's1'",
                String.class, docId)).isEqualTo("hello world");
        assertThat(jdbc.queryForObject(
                "SELECT translation_version FROM translation WHERE document_id = ? AND segment_id = 's1'",
                Integer.class, docId)).isEqualTo(1);

        // 区间与文本都不动：直接通过
        ApiResult sameRange = submitTranslation(docId, "s1", "en", "carol", "hello world!", 1,
                newRequestId());
        assertThat(sameRange.status()).isEqualTo(200);
        assertThat(sameRange.body().get("translationVersion").asInt()).isEqualTo(2);
        assertThat(sameRange.body().get("draftVersion").asInt()).isEqualTo(3);

        // 重新批准后迁移："well hello" 中 hello 位于 [5,10)
        approve(docId, "s1", "en", "bob", 2, newRequestId());
        ApiResult mismatched = submitTranslationWithMappings(docId, "s1", "en", "carol", "well hellX", 1,
                "[{\"anchorId\":" + anchorId + ",\"startOffset\":5,\"endOffset\":10}]", newRequestId());
        assertThat(mismatched.status()).isEqualTo(422);
        assertThat(mismatched.body().get("message").asText()).contains("引用文本不一致", "hellX");

        ApiResult moved = submitTranslationWithMappings(docId, "s1", "en", "carol", "well hello", 1,
                "[{\"anchorId\":" + anchorId + ",\"startOffset\":5,\"endOffset\":10}]", newRequestId());
        assertThat(moved.status()).isEqualTo(200);
        assertThat(moved.body().get("translationVersion").asInt()).isEqualTo(3);

        JsonNode anchor = getJson("/api/documents/" + docId + "/anchors/" + anchorId).body().get("anchor");
        assertThat(anchor.get("startOffset").asInt()).isEqualTo(5);
        assertThat(anchor.get("endOffset").asInt()).isEqualTo(10);
        assertThat(anchor.get("quoteText").asText()).isEqualTo("hello");
        assertThat(anchor.get("translationVersion").asInt()).isEqualTo(3);
        assertThat(anchor.get("migratedBy").asText()).isEqualTo("carol");
        assertThat(anchor.get("createdBy").asText()).isEqualTo("alice");
    }

    @Test
    @DisplayName("解除锚点：非法务 403、创建人 403、重复解除 422；非法务确认写入不可变理由与 RELEASED 事件")
    void releaseAnchorAuthorization() throws Exception {
        long docId = approvedDoc();
        long anchorId = registerAnchors(docId, "s1", "en", "alice",
                "[{\"citationId\":\"isbn-1\",\"startOffset\":0,\"endOffset\":5,\"lockReason\":\"初锁\"}]",
                newRequestId()).body().get("anchors").get(0).get("anchorId").asLong();

        assertThat(releaseAnchor(docId, anchorId, "erin", null, "无效角色", newRequestId()).status())
                .isEqualTo(403);
        assertThat(releaseAnchor(docId, anchorId, "erin", "EDITOR", "无效角色", newRequestId()).status())
                .isEqualTo(403);
        assertThat(releaseAnchor(docId, anchorId, "alice", "LEGAL", "自己解除", newRequestId()).status())
                .isEqualTo(403);

        ApiResult released = releaseAnchor(docId, anchorId, "erin", "legal", "引文出处裁定撤销", newRequestId());
        assertThat(released.status()).isEqualTo(200);
        assertThat(released.body().get("status").asText()).isEqualTo("RELEASED");
        assertThat(released.body().get("releasedBy").asText()).isEqualTo("erin");
        assertThat(released.body().get("releasedReason").asText()).isEqualTo("引文出处裁定撤销");
        assertThat(released.body().get("releasedAt").asText()).matches(UTC_MICROS);

        // 终态不可重复解除，理由不可变
        assertThat(releaseAnchor(docId, anchorId, "frank", "LEGAL", "再次解除", newRequestId()).status())
                .isEqualTo(422);
        assertThat(jdbc.queryForObject(
                "SELECT released_reason FROM citation_anchor WHERE anchor_id = ?", String.class, anchorId))
                .isEqualTo("引文出处裁定撤销");

        // 同一引用标识同一段落语言只能锁定一次：解除后重锁仍 422
        ApiResult relock = registerAnchors(docId, "s1", "en", "alice",
                "[{\"citationId\":\"isbn-1\",\"startOffset\":6,\"endOffset\":7,\"lockReason\":\"r\"}]",
                newRequestId());
        assertThat(relock.status()).isEqualTo(422);
    }

    @Test
    @DisplayName("锚点明细与历史：登记、迁移、解除事件按序追加，时间均为 UTC 微秒")
    void anchorDetailAndHistory() throws Exception {
        long docId = approvedDoc();
        long anchorId = registerAnchors(docId, "s1", "en", "alice",
                "[{\"citationId\":\"isbn-1\",\"startOffset\":0,\"endOffset\":5,\"lockReason\":\"初锁\"}]",
                newRequestId()).body().get("anchors").get(0).get("anchorId").asLong();
        submitTranslationWithMappings(docId, "s1", "en", "carol", "well hello", 1,
                "[{\"anchorId\":" + anchorId + ",\"startOffset\":5,\"endOffset\":10}]", newRequestId());
        releaseAnchor(docId, anchorId, "erin", "LEGAL", "撤销", newRequestId());

        ApiResult detail = getJson("/api/documents/" + docId + "/anchors/" + anchorId);
        assertThat(detail.status()).isEqualTo(200);
        JsonNode history = detail.body().get("history");
        assertThat(history).hasSize(3);
        assertThat(history.get(0).get("eventType").asText()).isEqualTo("REGISTERED");
        assertThat(history.get(0).get("reason").asText()).isEqualTo("初锁");
        assertThat(history.get(1).get("eventType").asText()).isEqualTo("MIGRATED");
        assertThat(history.get(1).get("startOffset").asInt()).isEqualTo(5);
        assertThat(history.get(2).get("eventType").asText()).isEqualTo("RELEASED");
        assertThat(history.get(2).get("reason").asText()).isEqualTo("撤销");
        for (JsonNode event : history) {
            assertThat(event.get("eventAt").asText()).matches(UTC_MICROS);
        }
        assertThat(getJson("/api/documents/" + docId + "/anchors/999999").status()).isEqualTo(404);
    }

    @Test
    @DisplayName("发布快照固化锚点：迁移与解除不改旧快照；再发布固化新集合，旧锚点仍可追溯")
    void snapshotFreezesAnchorSet() throws Exception {
        long docId = approvedDoc();
        long anchorId = registerAnchors(docId, "s1", "en", "alice",
                "[{\"citationId\":\"isbn-1\",\"startOffset\":0,\"endOffset\":5,\"lockReason\":\"初锁\"}]",
                newRequestId()).body().get("anchors").get(0).get("anchorId").asLong();
        // 草稿版本仍为 2：登记不 bump
        assertThat(publish(docId, 2, 0, newRequestId()).status()).isEqualTo(201);

        // 发布后迁移锚点（新译文 v2 [5,10)），并再发布 v2
        submitTranslationWithMappings(docId, "s1", "en", "carol", "well hello", 1,
                "[{\"anchorId\":" + anchorId + ",\"startOffset\":5,\"endOffset\":10}]", newRequestId());
        approve(docId, "s1", "en", "bob", 2, newRequestId());
        assertThat(publish(docId, 3, 1, newRequestId()).status()).isEqualTo(201);

        // 再解除锚点
        assertThat(releaseAnchor(docId, anchorId, "erin", "LEGAL", "撤销", newRequestId()).status())
                .isEqualTo(200);

        ApiResult release1 = getJson("/api/documents/" + docId + "/releases/1");
        JsonNode anchorV1 = release1.body().get("segments").get(0).get("translations").get(0)
                .get("citationAnchors").get(0);
        assertThat(anchorV1.get("anchorId").asLong()).isEqualTo(anchorId);
        assertThat(anchorV1.get("startOffset").asInt()).isZero();
        assertThat(anchorV1.get("quoteText").asText()).isEqualTo("hello");

        ApiResult release2 = getJson("/api/documents/" + docId + "/releases/2");
        JsonNode anchorV2 = release2.body().get("segments").get(0).get("translations").get(0)
                .get("citationAnchors").get(0);
        assertThat(anchorV2.get("startOffset").asInt()).isEqualTo(5);

        // 固化关系表：两个版本各自独立，解除不删除旧记录
        List<Integer> v1Refs = jdbc.queryForList(
                "SELECT start_offset FROM release_anchor_ref WHERE document_id = ? AND published_version = 1",
                Integer.class, docId);
        List<Integer> v2Refs = jdbc.queryForList(
                "SELECT start_offset FROM release_anchor_ref WHERE document_id = ? AND published_version = 2",
                Integer.class, docId);
        assertThat(v1Refs).containsExactly(0);
        assertThat(v2Refs).containsExactly(5);

        // 已发布快照中的锚点解除后仍可追溯到当前状态与完整历史
        JsonNode detail = getJson("/api/documents/" + docId + "/anchors/" + anchorId).body();
        assertThat(detail.get("anchor").get("status").asText()).isEqualTo("RELEASED");
        assertThat(detail.get("history")).hasSize(3);
    }

    @Test
    @DisplayName("锁定段落修订后旧批准失效：未重新批准发布 422，重新批准后发布成功且快照含锚点")
    void lockedRevisionRequiresReapprovalBeforePublish() throws Exception {
        long docId = approvedDoc();
        long anchorId = registerAnchors(docId, "s1", "en", "alice",
                "[{\"citationId\":\"isbn-1\",\"startOffset\":0,\"endOffset\":5,\"lockReason\":\"r\"}]",
                newRequestId()).body().get("anchors").get(0).get("anchorId").asLong();
        submitTranslationWithMappings(docId, "s1", "en", "carol", "well hello", 1,
                "[{\"anchorId\":" + anchorId + ",\"startOffset\":5,\"endOffset\":10}]", newRequestId());
        assertThat(publish(docId, 3, 0, newRequestId()).status()).isEqualTo(422);

        approve(docId, "s1", "en", "bob", 2, newRequestId());
        ApiResult published = publish(docId, 3, 0, newRequestId());
        assertThat(published.status()).isEqualTo(201);
        JsonNode anchor = getJson("/api/documents/" + docId + "/releases/1").body()
                .get("segments").get(0).get("translations").get(0).get("citationAnchors").get(0);
        assertThat(anchor.get("startOffset").asInt()).isEqualTo(5);
    }

    @Test
    @DisplayName("批量修订：一段锚点映射失败整批回滚；全部成功一次提交且草稿版本只加一")
    void batchReviseAtomicity() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"原文一\"},"
                        + "{\"segmentId\":\"s2\",\"sourceText\":\"原文二\"}]");
        submitTranslation(docId, "s1", "en", "alice", "hello world", 1, newRequestId());
        submitTranslation(docId, "s2", "en", "alice", "foo bar", 1, newRequestId());
        approve(docId, "s1", "en", "bob", 1, newRequestId());
        approve(docId, "s2", "en", "bob", 1, newRequestId());
        long a1 = registerAnchors(docId, "s1", "en", "alice",
                "[{\"citationId\":\"k1\",\"startOffset\":0,\"endOffset\":5,\"lockReason\":\"r\"}]",
                newRequestId()).body().get("anchors").get(0).get("anchorId").asLong();
        long a2 = registerAnchors(docId, "s2", "en", "alice",
                "[{\"citationId\":\"k2\",\"startOffset\":0,\"endOffset\":3,\"lockReason\":\"r\"}]",
                newRequestId()).body().get("anchors").get(0).get("anchorId").asLong();
        // 当前草稿版本 3

        // s1 映射合法（hello 移到 [5,10)），s2 丢失 foo 未给映射：整批 422
        String failingBatch = "["
                + "{\"segmentId\":\"s1\",\"language\":\"en\",\"content\":\"well hello\",\"sourceVersion\":1,"
                + "\"anchorMappings\":[{\"anchorId\":" + a1 + ",\"startOffset\":5,\"endOffset\":10}]},"
                + "{\"segmentId\":\"s2\",\"language\":\"en\",\"content\":\"xxx bar\",\"sourceVersion\":1,"
                + "\"anchorMappings\":[]}]";
        assertThat(batchRevise(docId, 0, failingBatch, "carol", newRequestId()).status()).isEqualTo(422);

        // 整批回滚：两段译文内容/版本不变，草稿版本不变，无迁移事件
        assertThat(jdbc.queryForObject(
                "SELECT content FROM translation WHERE document_id = ? AND segment_id = 's1'",
                String.class, docId)).isEqualTo("hello world");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM translation WHERE translation_version <> 1 AND document_id = ?",
                Integer.class, docId)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT draft_version FROM document WHERE document_id = ?", Integer.class, docId)).isEqualTo(3);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM citation_anchor_event WHERE event_type = 'MIGRATED'",
                Integer.class)).isZero();

        // 术语版本不符 409
        assertThat(batchRevise(docId, 9, failingBatch, "carol", newRequestId()).status()).isEqualTo(409);

        // 全部映射合法：一次提交，两段版本各自加一，草稿版本整批只加一为 4
        String okBatch = "["
                + "{\"segmentId\":\"s1\",\"language\":\"en\",\"content\":\"well hello\",\"sourceVersion\":1,"
                + "\"anchorMappings\":[{\"anchorId\":" + a1 + ",\"startOffset\":5,\"endOffset\":10}]},"
                + "{\"segmentId\":\"s2\",\"language\":\"EN\",\"content\":\"foo baz\",\"sourceVersion\":1,"
                + "\"anchorMappings\":[{\"anchorId\":" + a2 + ",\"startOffset\":0,\"endOffset\":3}]}]";
        ApiResult ok = batchRevise(docId, 0, okBatch, "carol", newRequestId());
        assertThat(ok.status()).isEqualTo(200);
        assertThat(ok.body().get("revisions")).hasSize(2);
        assertThat(ok.body().get("draftVersion").asInt()).isEqualTo(4);
        assertThat(jdbc.queryForObject(
                "SELECT start_offset FROM citation_anchor WHERE anchor_id = ?", Integer.class, a1))
                .isEqualTo(5);
        assertThat(jdbc.queryForObject(
                "SELECT start_offset FROM citation_anchor WHERE anchor_id = ?", Integer.class, a2)).isZero();
    }

    @Test
    @DisplayName("锚点写操作幂等：登记/解除/批量修订同键重放、异参 409、失败不占键")
    void anchorIdempotency() throws Exception {
        long docId = approvedDoc();
        String registerKey = newRequestId();
        String anchorsJson = "[{\"citationId\":\"isbn-1\",\"startOffset\":0,\"endOffset\":5,\"lockReason\":\"r\"}]";
        ApiResult first = registerAnchors(docId, "s1", "en", "alice", anchorsJson, registerKey);
        assertThat(first.status()).isEqualTo(201);
        long anchorId = first.body().get("anchors").get(0).get("anchorId").asLong();

        ApiResult replay = registerAnchors(docId, "s1", "en", "alice", anchorsJson, registerKey);
        assertThat(replay.status()).isEqualTo(201);
        assertThat(replay.body().get("anchors").get(0).get("anchorId").asLong()).isEqualTo(anchorId);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM citation_anchor", Integer.class)).isEqualTo(1);

        ApiResult diff = registerAnchors(docId, "s1", "en", "alice",
                "[{\"citationId\":\"isbn-1\",\"startOffset\":6,\"endOffset\":7,\"lockReason\":\"r\"}]",
                registerKey);
        assertThat(diff.status()).isEqualTo(409);

        // 解除幂等重放
        String releaseKey = newRequestId();
        assertThat(releaseAnchor(docId, anchorId, "erin", "LEGAL", "撤销", releaseKey).status()).isEqualTo(200);
        ApiResult releaseReplay = releaseAnchor(docId, anchorId, "erin", "LEGAL", "撤销", releaseKey);
        assertThat(releaseReplay.status()).isEqualTo(200);
        assertThat(releaseReplay.body().get("status").asText()).isEqualTo("RELEASED");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM citation_anchor_event WHERE event_type = 'RELEASED'", Integer.class))
                .isEqualTo(1);

        // 批量修订失败不占键：先触发 422，再用同键修正后成功
        long docId2 = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"原文\"}]");
        submitTranslation(docId2, "s1", "en", "alice", "hello world", 1, newRequestId());
        approve(docId2, "s1", "en", "bob", 1, newRequestId());
        long anchor2 = registerAnchors(docId2, "s1", "en", "alice",
                "[{\"citationId\":\"k\",\"startOffset\":0,\"endOffset\":5,\"lockReason\":\"r\"}]",
                newRequestId()).body().get("anchors").get(0).get("anchorId").asLong();
        String batchKey = newRequestId();
        String bad = "[{\"segmentId\":\"s1\",\"language\":\"en\",\"content\":\"HELLO world\",\"sourceVersion\":1}]";
        assertThat(batchRevise(docId2, 0, bad, "carol", batchKey).status()).isEqualTo(422);
        String good = "[{\"segmentId\":\"s1\",\"language\":\"en\",\"content\":\"well hello\",\"sourceVersion\":1,"
                + "\"anchorMappings\":[{\"anchorId\":" + anchor2 + ",\"startOffset\":5,\"endOffset\":10}]}]";
        assertThat(batchRevise(docId2, 0, good, "carol", batchKey).status()).isEqualTo(200);
    }

    @Test
    @DisplayName("只读查询：锚点列表与诊断给出实际数、要求数、差额；解除后生效数下降")
    void anchorListAndDiagnostics() throws Exception {
        long docId = approvedDoc();
        long anchorId = registerAnchors(docId, "s1", "en", "alice",
                "[{\"citationId\":\"isbn-1\",\"startOffset\":0,\"endOffset\":5,\"lockReason\":\"r\"}]",
                newRequestId()).body().get("anchors").get(0).get("anchorId").asLong();

        ApiResult list = getJson("/api/documents/" + docId + "/anchors");
        assertThat(list.status()).isEqualTo(200);
        assertThat(list.body()).hasSize(1);
        ApiResult scoped = getJson("/api/documents/" + docId
                + "/segments/s1/translations/en/anchors");
        assertThat(scoped.body()).hasSize(1);

        ApiResult diagnostics = getJson("/api/documents/" + docId + "/anchors/diagnostics");
        JsonNode item = diagnostics.body().get("items").get(0);
        assertThat(item.get("segmentId").asText()).isEqualTo("s1");
        assertThat(item.get("actualCount").asInt()).isEqualTo(1);
        assertThat(item.get("requiredCount").asInt()).isEqualTo(1);
        assertThat(item.get("difference").asInt()).isZero();
        assertThat(item.get("outOfRangeAnchorIds")).isEmpty();
        assertThat(item.get("quoteMismatchAnchorIds")).isEmpty();
        assertThat(item.get("crossingPairs")).isEmpty();
        assertThat(item.get("duplicateCitationKeys")).isEmpty();

        releaseAnchor(docId, anchorId, "erin", "LEGAL", "撤销", newRequestId());
        JsonNode afterRelease = getJson("/api/documents/" + docId + "/anchors/diagnostics")
                .body().get("items").get(0);
        assertThat(afterRelease.get("actualCount").asInt()).isZero();
    }

    @Test
    @DisplayName("并发登记同一引用标识：文档行锁串行后仅一个成功，其余业务 422/约束 409，最终仅一条锚点")
    void concurrentRegisterSameCitationKey() throws Exception {
        long docId = approvedDoc();
        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<ApiResult>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                gate.await();
                return registerAnchors(docId, "s1", "en", "alice",
                        "[{\"citationId\":\"isbn-1\",\"startOffset\":0,\"endOffset\":5,\"lockReason\":\"r\"}]",
                        newRequestId());
            }));
        }
        gate.countDown();
        int created = 0;
        int rejected = 0;
        for (Future<ApiResult> future : futures) {
            int status = future.get(30, TimeUnit.SECONDS).status();
            assertThat(status).isIn(201, 409, 422);
            if (status == 201) {
                created++;
            } else {
                rejected++;
            }
        }
        pool.shutdown();
        assertThat(created).isEqualTo(1);
        assertThat(rejected).isEqualTo(threads - 1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM citation_anchor WHERE document_id = ?", Integer.class, docId))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("并发解除与修订：按提交顺序裁决，要么修订先到被映射规则拦截，要么解除先到修订放行，最终状态一致")
    void concurrentReleaseAndRevision() throws Exception {
        long docId = approvedDoc();
        long anchorId = registerAnchors(docId, "s1", "en", "alice",
                "[{\"citationId\":\"isbn-1\",\"startOffset\":0,\"endOffset\":5,\"lockReason\":\"r\"}]",
                newRequestId()).body().get("anchors").get(0).get("anchorId").asLong();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        // 修订不携带映射且引用文本位置移动：锚点仍生效时必然 422；锚点先解除时无生效锚点则放行
        Future<ApiResult> revisionFuture = pool.submit(() -> {
            gate.await();
            return submitTranslation(docId, "s1", "en", "carol", "well hello", 1, newRequestId());
        });
        Future<ApiResult> releaseFuture = pool.submit(() -> {
            gate.await();
            return releaseAnchor(docId, anchorId, "erin", "LEGAL", "撤销", newRequestId());
        });
        gate.countDown();
        ApiResult revisionResult = revisionFuture.get(30, TimeUnit.SECONDS);
        ApiResult releaseResult = releaseFuture.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        // 解除必然成功；修订要么先于解除被锚点规则拦截，要么后于解除放行
        assertThat(releaseResult.status()).isEqualTo(200);
        assertThat(revisionResult.status()).isIn(200, 422);

        String status = jdbc.queryForObject(
                "SELECT status FROM citation_anchor WHERE anchor_id = ?", String.class, anchorId);
        assertThat(status).isEqualTo("RELEASED");
        String content = jdbc.queryForObject(
                "SELECT content FROM translation WHERE document_id = ? AND segment_id = 's1'",
                String.class, docId);
        Integer version = jdbc.queryForObject(
                "SELECT translation_version FROM translation WHERE document_id = ? AND segment_id = 's1'",
                Integer.class, docId);
        Integer draftVersion = jdbc.queryForObject(
                "SELECT draft_version FROM document WHERE document_id = ?", Integer.class, docId);
        if (revisionResult.status() == 200) {
            // 解除先提交：修订放行，译文 v2、草稿版本 3；锚点已解除不产生迁移
            assertThat(content).isEqualTo("well hello");
            assertThat(version).isEqualTo(2);
            assertThat(draftVersion).isEqualTo(3);
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM citation_anchor_event WHERE event_type = 'MIGRATED'",
                    Integer.class)).isZero();
        } else {
            // 修订先提交：锚点映射缺失 422 整体回滚，译文仍 v1、草稿版本 2
            assertThat(content).isEqualTo("hello world");
            assertThat(version).isEqualTo(1);
            assertThat(draftVersion).isEqualTo(2);
        }
    }
}
