package com.example.starter.translation;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 译文引文锁定 API：登记、修订映射、整批回滚、法务解除、发布一致性、
 * 快照固化、幂等与只读诊断查询的主流程与失败分支（真实 H2 内存库）。
 */
class CitationApiTest extends AbstractIntegrationTest {

    /** 建文档、提交并批准英文译文 "hello world"，返回文档 ID；此时草稿版本 2。 */
    private long approvedDocument() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"原文一\"}]");
        assertThat(submitTranslation(docId, "s1", "en", "alice", "hello world", 1, newRequestId()).status())
                .isEqualTo(200);
        assertThat(approve(docId, "s1", "en", "bob", 1, newRequestId()).status()).isEqualTo(200);
        return docId;
    }

    private static String twoAnchors() {
        return "[{\"citationKey\":\"cite/one\",\"startOffset\":0,\"endOffset\":5,\"lockReason\":\"出处一\"},"
                + "{\"citationKey\":\"cite/two\",\"startOffset\":6,\"endOffset\":11,\"lockReason\":\"出处二\"}]";
    }

    @Test
    @DisplayName("登记锚点：201，固化引用文本、左闭右开区间与长度，规范化标识，草稿版本加一")
    void registerAnchorsSuccess() throws Exception {
        long docId = approvedDocument();
        ApiResult result = registerAnchors(docId, "s1", "en", "alice", "EDITOR", 1, twoAnchors(), newRequestId());
        assertThat(result.status()).isEqualTo(201);
        assertThat(result.body().get("draftVersion").asInt()).isEqualTo(3);
        assertThat(result.body().get("anchors")).hasSize(2);
        JsonNode first = result.body().get("anchors").get(0);
        assertThat(first.get("anchorId").asLong()).isPositive();
        assertThat(first.get("citationKey").asText()).isEqualTo("cite/one");
        assertThat(first.get("startOffset").asInt()).isZero();
        assertThat(first.get("endOffset").asInt()).isEqualTo(5);
        assertThat(first.get("length").asInt()).isEqualTo(5);
        assertThat(first.get("anchorText").asText()).isEqualTo("hello");
        assertThat(first.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(first.get("createdBy").asText()).isEqualTo("alice");
        assertThat(first.get("createdAt").asText()).endsWith("Z");
        assertThat(first.get("releasedBy").isNull()).isTrue();
        assertThat(first.get("releaseReason").isNull()).isTrue();
        assertThat(first.get("releasedAt").isNull()).isTrue();
        JsonNode second = result.body().get("anchors").get(1);
        assertThat(second.get("anchorText").asText()).isEqualTo("world");

        // 明细查询与登记结果一致
        ApiResult listed = getJson("/api/documents/" + docId + "/segments/s1/anchors");
        assertThat(listed.status()).isEqualTo(200);
        assertThat(listed.body()).hasSize(2);
    }

    @Test
    @DisplayName("登记失败：未批准/版本不符/越界/交叉/标识重复 422，同标识再锁 409，缺 actor 头 400")
    void registerAnchorsFailures() throws Exception {
        long docId = approvedDocument();

        // 译文版本不符：要求值与当前值差额 1
        ApiResult wrongVersion = registerAnchors(docId, "s1", "en", "alice", "EDITOR", 9,
                "[{\"citationKey\":\"cite/x\",\"startOffset\":0,\"endOffset\":5,\"lockReason\":\"r\"}]",
                newRequestId());
        assertThat(wrongVersion.status()).isEqualTo(422);
        assertThat(wrongVersion.body().get("message").asText()).contains("差额");

        // 区间越界（译文长 11，[10,12) 越界）
        ApiResult outOfRange = registerAnchors(docId, "s1", "en", "alice", "EDITOR", 1,
                "[{\"citationKey\":\"cite/x\",\"startOffset\":10,\"endOffset\":12,\"lockReason\":\"r\"}]",
                newRequestId());
        assertThat(outOfRange.status()).isEqualTo(422);
        assertThat(outOfRange.body().get("message").asText()).contains("11");

        // 空区间由业务规则拦截：422；负起点由 Bean Validation 拦截：400
        ApiResult emptyRange = registerAnchors(docId, "s1", "en", "alice", "EDITOR", 1,
                "[{\"citationKey\":\"cite/x\",\"startOffset\":3,\"endOffset\":3,\"lockReason\":\"r\"}]",
                newRequestId());
        assertThat(emptyRange.status()).isEqualTo(422);
        ApiResult negativeStart = registerAnchors(docId, "s1", "en", "alice", "EDITOR", 1,
                "[{\"citationKey\":\"cite/x\",\"startOffset\":-1,\"endOffset\":3,\"lockReason\":\"r\"}]",
                newRequestId());
        assertThat(negativeStart.status()).isEqualTo(400);

        // 区间交叉：[0,6) 与 [5,11)
        ApiResult overlap = registerAnchors(docId, "s1", "en", "alice", "EDITOR", 1,
                "[{\"citationKey\":\"cite/a\",\"startOffset\":0,\"endOffset\":6,\"lockReason\":\"r\"},"
                        + "{\"citationKey\":\"cite/b\",\"startOffset\":5,\"endOffset\":11,\"lockReason\":\"r\"}]",
                newRequestId());
        assertThat(overlap.status()).isEqualTo(422);

        // 同一次请求标识重复（规范化后）
        ApiResult duplicate = registerAnchors(docId, "s1", "en", "alice", "EDITOR", 1,
                "[{\"citationKey\":\"cite/a\",\"startOffset\":0,\"endOffset\":1,\"lockReason\":\"r\"},"
                        + "{\"citationKey\":\" CITE/A \",\"startOffset\":1,\"endOffset\":2,\"lockReason\":\"r\"}]",
                newRequestId());
        assertThat(duplicate.status()).isEqualTo(422);

        // 成功登记一个
        assertThat(registerAnchors(docId, "s1", "en", "alice", "EDITOR", 1,
                "[{\"citationKey\":\"cite/one\",\"startOffset\":0,\"endOffset\":5,\"lockReason\":\"出处一\"}]",
                newRequestId()).status()).isEqualTo(201);
        // 同一引用标识再次锁定（即使大小写不同）→ 409
        ApiResult reused = registerAnchors(docId, "s1", "en", "alice", "EDITOR", 1,
                "[{\"citationKey\":\"CITE/ONE\",\"startOffset\":0,\"endOffset\":5,\"lockReason\":\"r\"}]",
                newRequestId());
        assertThat(reused.status()).isEqualTo(409);
        // 新区间与既有锚点交叉 → 422
        ApiResult crossExisting = registerAnchors(docId, "s1", "en", "alice", "EDITOR", 1,
                "[{\"citationKey\":\"cite/two\",\"startOffset\":3,\"endOffset\":8,\"lockReason\":\"r\"}]",
                newRequestId());
        assertThat(crossExisting.status()).isEqualTo(422);

        // 重新提交译文后旧批准失效：登记 422
        assertThat(submitTranslation(docId, "s1", "en", "alice", "hello world!", 1, newRequestId()).status())
                .isEqualTo(200);
        ApiResult staleApproval = registerAnchors(docId, "s1", "en", "alice", "EDITOR", 2,
                "[{\"citationKey\":\"cite/three\",\"startOffset\":0,\"endOffset\":1,\"lockReason\":\"r\"}]",
                newRequestId());
        assertThat(staleApproval.status()).isEqualTo(422);

        // 全部失败不留锚点：只有 cite/one 一条
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM citation_anchor WHERE document_id = ?", Integer.class, docId))
                .isEqualTo(1);

        // 缺 X-Actor-Id：400
        ApiResult noActor = postJson("/api/documents/" + docId + "/segments/s1/translations/en/anchors",
                "{\"requestId\":\"" + newRequestId() + "\",\"translationVersion\":1,\"anchors\":["
                        + "{\"citationKey\":\"cite/z\",\"startOffset\":0,\"endOffset\":1,\"lockReason\":\"r\"}]}");
        assertThat(noActor.status()).isEqualTo(400);
    }

    @Test
    @DisplayName("锁定后单条修订：区间不动且文本保留则通过；引用文本变化或区间需移动而未走映射 422")
    void singleSubmitPreservesCitations() throws Exception {
        long docId = approvedDocument();
        assertThat(registerAnchors(docId, "s1", "en", "alice", "EDITOR", 1,
                "[{\"citationKey\":\"cite/one\",\"startOffset\":0,\"endOffset\":5,\"lockReason\":\"出处一\"}]",
                newRequestId()).status()).isEqualTo(201);

        // 尾部追加，旧区间 [0,5) 仍切片为 hello：通过，锚点随译文版本重新绑定
        ApiResult appended = submitTranslation(docId, "s1", "en", "alice", "hello world!", 1, newRequestId());
        assertThat(appended.status()).isEqualTo(200);
        assertThat(appended.body().get("translationVersion").asInt()).isEqualTo(2);

        // 引用文本被改动：422
        ApiResult changed = submitTranslation(docId, "s1", "en", "alice", "HELLO world!", 1, newRequestId());
        assertThat(changed.status()).isEqualTo(422);
        assertThat(changed.body().get("message").asText()).contains("cite/one");

        // 引用文本移动位置（前缀插入），未提供映射：422
        ApiResult moved = submitTranslation(docId, "s1", "en", "alice", "xhello world!", 1, newRequestId());
        assertThat(moved.status()).isEqualTo(422);
        assertThat(moved.body().get("message").asText()).contains("引用文本不一致");

        // 失败不覆盖译文：当前译文仍是 v2 的 "hello world!"
        String content = jdbc.queryForObject(
                "SELECT content FROM translation WHERE document_id = ? AND segment_id = 's1' AND language = 'en'",
                String.class, docId);
        assertThat(content).isEqualTo("hello world!");

        // 单条提交携带一一映射：迁移成功到 v3，区间与事件正确
        ApiResult migrated = submitTranslation(docId, "s1", "en", "alice", "xhello world!", 1,
                "[{\"citationKey\":\"cite/one\",\"startOffset\":1,\"endOffset\":6}]", newRequestId());
        assertThat(migrated.status()).isEqualTo(200);
        assertThat(migrated.body().get("translationVersion").asInt()).isEqualTo(3);
        assertThat(jdbc.queryForObject(
                "SELECT start_offset FROM citation_anchor WHERE citation_key = 'cite/one' AND document_id = ?",
                Integer.class, docId)).isEqualTo(1);
        ApiResult history = getJson("/api/documents/" + docId + "/segments/s1/anchors/history");
        assertThat(history.body().get("events").get(1).get("eventType").asText()).isEqualTo("MIGRATED");
    }

    @Test
    @DisplayName("批量修订：整批成功只加一个草稿版本，锚点按一一映射迁移并记录 MIGRATED 事件")
    void batchReviseSuccess() throws Exception {
        long docId = approvedDocument();
        registerAnchors(docId, "s1", "en", "alice", "EDITOR", 1, twoAnchors(), newRequestId());

        // 前缀插入 x：两个锚点整体右移 1，提供映射
        String revisions = "[{\"segmentId\":\"s1\",\"language\":\"en\",\"content\":\"xhello world\","
                + "\"sourceVersion\":1,\"anchorMappings\":["
                + "{\"citationKey\":\"cite/one\",\"startOffset\":1,\"endOffset\":6},"
                + "{\"citationKey\":\"cite/two\",\"startOffset\":7,\"endOffset\":12}]}]";
        ApiResult result = batchRevise(docId, "alice", "EDITOR", revisions, newRequestId());
        assertThat(result.status()).isEqualTo(200);
        assertThat(result.body().get("draftVersion").asInt()).isEqualTo(4);
        JsonNode item = result.body().get("results").get(0);
        assertThat(item.get("translationVersion").asInt()).isEqualTo(2);
        assertThat(item.get("anchors")).hasSize(2);
        assertThat(item.get("anchors").get(0).get("startOffset").asInt()).isEqualTo(1);
        assertThat(item.get("anchors").get(0).get("anchorText").asText()).isEqualTo("hello");

        // 锚点表原地更新、ID 不变；历史含 REGISTERED x2 + MIGRATED x2，顺序与 from/to 正确
        ApiResult history = getJson("/api/documents/" + docId + "/segments/s1/anchors/history");
        assertThat(history.status()).isEqualTo(200);
        assertThat(history.body().get("events")).hasSize(4);
        JsonNode migrated = history.body().get("events").get(3);
        assertThat(migrated.get("eventType").asText()).isEqualTo("MIGRATED");
        assertThat(migrated.get("fromStart").asInt()).isEqualTo(6);
        assertThat(migrated.get("toStart").asInt()).isEqualTo(7);
    }

    @Test
    @DisplayName("批量修订失败：映射缺失/重复/文本不一致 422，整批回滚无半成品")
    void batchReviseMappingFailuresRollback() throws Exception {
        long docId = approvedDocument();
        registerAnchors(docId, "s1", "en", "alice", "EDITOR", 1, twoAnchors(), newRequestId());

        // 仅映射一个锚点且另一个区间实际已移动：缺失映射 → 422
        String missing = "[{\"segmentId\":\"s1\",\"language\":\"en\",\"content\":\"xhello world\","
                + "\"sourceVersion\":1,\"anchorMappings\":["
                + "{\"citationKey\":\"cite/one\",\"startOffset\":1,\"endOffset\":6}]}]";
        assertThat(batchRevise(docId, "alice", "EDITOR", missing, newRequestId()).status()).isEqualTo(422);

        // 重复映射：422
        String duplicated = "[{\"segmentId\":\"s1\",\"language\":\"en\",\"content\":\"xhello world\","
                + "\"sourceVersion\":1,\"anchorMappings\":["
                + "{\"citationKey\":\"cite/one\",\"startOffset\":1,\"endOffset\":6},"
                + "{\"citationKey\":\"CITE/ONE\",\"startOffset\":1,\"endOffset\":6},"
                + "{\"citationKey\":\"cite/two\",\"startOffset\":7,\"endOffset\":12}]}]";
        assertThat(batchRevise(docId, "alice", "EDITOR", duplicated, newRequestId()).status()).isEqualTo(422);

        // 文本不一致：映射新区间切片不是固化文本
        String mismatch = "[{\"segmentId\":\"s1\",\"language\":\"en\",\"content\":\"xhello WORLD\","
                + "\"sourceVersion\":1,\"anchorMappings\":["
                + "{\"citationKey\":\"cite/one\",\"startOffset\":1,\"endOffset\":6},"
                + "{\"citationKey\":\"cite/two\",\"startOffset\":7,\"endOffset\":12}]}]";
        ApiResult mismatchResult = batchRevise(docId, "alice", "EDITOR", mismatch, newRequestId());
        assertThat(mismatchResult.status()).isEqualTo(422);
        assertThat(mismatchResult.body().get("message").asText()).contains("world", "WORLD");

        // 整批回滚：译文仍为 v1 原文，锚点区间未变，草稿版本停在 3，无 MIGRATED 事件
        assertThat(jdbc.queryForObject(
                "SELECT content FROM translation WHERE document_id = ? AND segment_id = 's1'",
                String.class, docId)).isEqualTo("hello world");
        assertThat(jdbc.queryForObject(
                "SELECT translation_version FROM translation WHERE document_id = ?", Integer.class, docId))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT start_offset FROM citation_anchor WHERE citation_key = 'cite/one' AND document_id = ?",
                Integer.class, docId)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT draft_version FROM document WHERE document_id = ?", Integer.class, docId)).isEqualTo(3);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM citation_anchor_event WHERE event_type = 'MIGRATED' AND document_id = ?",
                Integer.class, docId)).isZero();
    }

    @Test
    @DisplayName("批量修订：一批中任一条术语违规整批 422(TERM_VIOLATION) 且先校验后写入；段落+语言重复 422")
    void batchReviseTermViolationRollback() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"机器学习\"},"
                        + "{\"segmentId\":\"s2\",\"sourceText\":\"深度学习\"}]");
        updateTerms(docId, 0,
                "[{\"sourceTerm\":\"机器学习\",\"language\":\"en\",\"requiredTranslation\":\"machine learning\"},"
                        + "{\"sourceTerm\":\"深度学习\",\"language\":\"en\",\"requiredTranslation\":\"deep learning\"}]",
                newRequestId());
        submitTranslation(docId, "s1", "en", "alice", "machine learning v1", 1, newRequestId());
        submitTranslation(docId, "s2", "en", "alice", "deep learning v1", 1, newRequestId());
        approve(docId, "s1", "en", "bob", 1, newRequestId());
        approve(docId, "s2", "en", "bob", 1, newRequestId());
        registerAnchors(docId, "s1", "en", "alice", "EDITOR", 1,
                "[{\"citationKey\":\"cite/one\",\"startOffset\":0,\"endOffset\":7,\"lockReason\":\"r\"}]",
                newRequestId());
        int draftBefore = jdbc.queryForObject(
                "SELECT draft_version FROM document WHERE document_id = ?", Integer.class, docId);

        // s1 合规且映射正确（前缀插入右移 1），s2 违反术语规则：整批回滚
        String revisions = "[{\"segmentId\":\"s1\",\"language\":\"en\",\"content\":\"xmachine learning v1\","
                + "\"sourceVersion\":1,\"anchorMappings\":["
                + "{\"citationKey\":\"cite/one\",\"startOffset\":1,\"endOffset\":8}]},"
                + "{\"segmentId\":\"s2\",\"language\":\"en\",\"content\":\"DL v1\","
                + "\"sourceVersion\":1,\"anchorMappings\":[]}]";
        ApiResult violated = batchRevise(docId, "alice", "EDITOR", revisions, newRequestId());
        assertThat(violated.status()).isEqualTo(422);
        assertThat(violated.body().get("error").asText()).isEqualTo("TERM_VIOLATION");
        assertThat(violated.body().get("violations")).hasSize(1);

        // 两条译文均停留在 v1，锚点未迁移，草稿版本不变，无 MIGRATED 事件
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM translation WHERE translation_version = 2 AND document_id = ?",
                Integer.class, docId)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT start_offset FROM citation_anchor WHERE citation_key = 'cite/one' AND document_id = ?",
                Integer.class, docId)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT draft_version FROM document WHERE document_id = ?", Integer.class, docId))
                .isEqualTo(draftBefore);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM citation_anchor_event WHERE event_type = 'MIGRATED' AND document_id = ?",
                Integer.class, docId)).isZero();

        // 同一段落+语言在一批中重复：422（语言码归一后仍重复）
        String dupBatch = "[{\"segmentId\":\"s1\",\"language\":\"en\",\"content\":\"xmachine learning v1\","
                + "\"sourceVersion\":1,\"anchorMappings\":["
                + "{\"citationKey\":\"cite/one\",\"startOffset\":1,\"endOffset\":8}]},"
                + "{\"segmentId\":\"s1\",\"language\":\"EN\",\"content\":\"xmachine learning v1\","
                + "\"sourceVersion\":1,\"anchorMappings\":["
                + "{\"citationKey\":\"cite/one\",\"startOffset\":1,\"endOffset\":8}]}]";
        assertThat(batchRevise(docId, "alice", "EDITOR", dupBatch, newRequestId()).status()).isEqualTo(422);
    }

    @Test
    @DisplayName("解除锁定：创建人或非法务角色 422；异于创建人的 LEGAL 确认并写不可变理由")
    void releaseAnchorRules() throws Exception {
        long docId = approvedDocument();
        ApiResult registered = registerAnchors(docId, "s1", "en", "alice", "EDITOR", 1,
                "[{\"citationKey\":\"cite/one\",\"startOffset\":0,\"endOffset\":5,\"lockReason\":\"出处一\"}]",
                newRequestId());
        long anchorId = registered.body().get("anchors").get(0).get("anchorId").asLong();

        // 创建人自己即便带 LEGAL 角色也不能解除
        assertThat(releaseAnchor(docId, anchorId, "alice", "LEGAL", "无需保留", newRequestId()).status())
                .isEqualTo(422);
        // 不同人但非 LEGAL 角色
        assertThat(releaseAnchor(docId, anchorId, "carol", "EDITOR", "无需保留", newRequestId()).status())
                .isEqualTo(422);
        // 空理由：400
        ApiResult blankReason = postJson("/api/documents/" + docId + "/anchors/" + anchorId + "/release",
                "{\"requestId\":\"" + newRequestId() + "\",\"reason\":\"\"}", "carol", "LEGAL");
        assertThat(blankReason.status()).isEqualTo(400);

        // 异于创建人的法务确认：200
        ApiResult released = releaseAnchor(docId, anchorId, "carol", "legal", "经法务核版解除", newRequestId());
        assertThat(released.status()).isEqualTo(200);
        JsonNode anchor = released.body().get("anchor");
        assertThat(anchor.get("status").asText()).isEqualTo("RELEASED");
        assertThat(anchor.get("releasedBy").asText()).isEqualTo("carol");
        assertThat(anchor.get("releaseReason").asText()).isEqualTo("经法务核版解除");
        assertThat(anchor.get("releasedAt").asText()).endsWith("Z");

        // 重复解除 422
        assertThat(releaseAnchor(docId, anchorId, "dave", "LEGAL", "再次解除", newRequestId()).status())
                .isEqualTo(422);
        // 引用标识不可复用：409
        assertThat(registerAnchors(docId, "s1", "en", "alice", "EDITOR", 1,
                "[{\"citationKey\":\"cite/one\",\"startOffset\":0,\"endOffset\":5,\"lockReason\":\"重新锁\"}]",
                newRequestId()).status()).isEqualTo(409);

        // 解除后不再约束修订：任意改动引用文本也能提交（但仍受术语等其他规则约束）
        ApiResult free = submitTranslation(docId, "s1", "en", "alice", "HELLO world", 1, newRequestId());
        assertThat(free.status()).isEqualTo(200);

        // 历史含 REGISTERED 与 RELEASED，解除事件 from* 为 null、to* 沿用区间
        ApiResult history = getJson("/api/documents/" + docId + "/segments/s1/anchors/history");
        assertThat(history.body().get("events")).hasSize(2);
        JsonNode releaseEvent = history.body().get("events").get(1);
        assertThat(releaseEvent.get("eventType").asText()).isEqualTo("RELEASED");
        assertThat(releaseEvent.get("fromStart").isNull()).isTrue();
        assertThat(releaseEvent.get("fromEnd").isNull()).isTrue();
        assertThat(releaseEvent.get("toEnd").asInt()).isEqualTo(5);
        assertThat(releaseEvent.get("actorRole").asText()).isEqualTo("LEGAL");
        assertThat(releaseEvent.get("reason").asText()).isEqualTo("经法务核版解除");
    }

    @Test
    @DisplayName("发布一致性：锚点未迁移 422；迁移并重新批准后发布，快照固化锚点集合且后续不改写")
    void publishFreezesCitations() throws Exception {
        long docId = approvedDocument();
        registerAnchors(docId, "s1", "en", "alice", "EDITOR", 1,
                "[{\"citationKey\":\"cite/one\",\"startOffset\":0,\"endOffset\":5,\"lockReason\":\"出处一\"}]",
                newRequestId());

        // 前缀插入迁移到 [1,6)，译文变 v2、旧批准失效
        String revisions = "[{\"segmentId\":\"s1\",\"language\":\"en\",\"content\":\"xhello world\","
                + "\"sourceVersion\":1,\"anchorMappings\":["
                + "{\"citationKey\":\"cite/one\",\"startOffset\":1,\"endOffset\":6}]}]";
        assertThat(batchRevise(docId, "alice", "EDITOR", revisions, newRequestId()).status()).isEqualTo(200);
        // 未重新批准：发布 422（批准失效）
        assertThat(publish(docId, 4, 0, newRequestId()).status()).isEqualTo(422);
        approve(docId, "s1", "en", "bob", 2, newRequestId());

        // 直接篡改锚点绑定版本，模拟“修订未迁移锚点”：发布必须 422 且不产生快照
        jdbc.update("UPDATE citation_anchor SET translation_version = 1 WHERE document_id = ?", docId);
        ApiResult driftPublish = publish(docId, 4, 0, newRequestId());
        assertThat(driftPublish.status()).isEqualTo(422);
        assertThat(driftPublish.body().get("message").asText()).contains("未随译文修订迁移");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_snapshot WHERE document_id = ?", Integer.class, docId)).isZero();
        jdbc.update("UPDATE citation_anchor SET translation_version = 2 WHERE document_id = ?", docId);

        // 一致状态发布成功，快照固化迁移后区间
        assertThat(publish(docId, 4, 0, newRequestId()).status()).isEqualTo(201);
        ApiResult release1 = getJson("/api/documents/" + docId + "/releases/1");
        assertThat(release1.body().get("citations")).hasSize(1);
        JsonNode frozen = release1.body().get("citations").get(0);
        assertThat(frozen.get("startOffset").asInt()).isEqualTo(1);
        assertThat(frozen.get("anchorText").asText()).isEqualTo("hello");
        assertThat(frozen.get("status").asText()).isEqualTo("ACTIVE");

        // 解除锚点后再发布：旧快照不改写，新快照记录 RELEASED 状态与解除信息
        long anchorId = frozen.get("anchorId").asLong();
        assertThat(releaseAnchor(docId, anchorId, "carol", "LEGAL", "引文作废", newRequestId()).status())
                .isEqualTo(200);
        // 解除使草稿版本加一（5），译文仍为已批准 v2，直接发布 v2 快照
        ApiResult second = publish(docId, 5, 1, newRequestId());
        assertThat(second.status()).isEqualTo(201);
        ApiResult oldRelease = getJson("/api/documents/" + docId + "/releases/1");
        assertThat(oldRelease.body().get("citations").get(0).get("status").asText()).isEqualTo("ACTIVE");
        assertThat(oldRelease.body().get("citations").get(0).get("releasedBy").isNull()).isTrue();
        ApiResult newRelease = getJson("/api/documents/" + docId + "/releases/2");
        assertThat(newRelease.body().get("citations").get(0).get("status").asText()).isEqualTo("RELEASED");
        assertThat(newRelease.body().get("citations").get(0).get("releasedBy").asText()).isEqualTo("carol");
    }

    @Test
    @DisplayName("诊断查询：只读反映版本漂移、区间与文本状态；明细/历史 404 分支")
    void diagnosticsAndNotFound() throws Exception {
        long docId = approvedDocument();
        registerAnchors(docId, "s1", "en", "alice", "EDITOR", 1,
                "[{\"citationKey\":\"cite/one\",\"startOffset\":0,\"endOffset\":5,\"lockReason\":\"出处一\"}]",
                newRequestId());

        ApiResult healthy = getJson("/api/documents/" + docId + "/anchors/diagnostics");
        assertThat(healthy.status()).isEqualTo(200);
        JsonNode diagnostic = healthy.body().get("anchors").get(0);
        assertThat(diagnostic.get("rangeInCurrentContent").asBoolean()).isTrue();
        assertThat(diagnostic.get("textIntactInCurrentContent").asBoolean()).isTrue();
        assertThat(diagnostic.get("drift").asBoolean()).isFalse();
        assertThat(diagnostic.get("currentContentLength").asInt()).isEqualTo(11);

        // 直接构造漂移：篡改译文内容使区间切片变化（真实 SQL 更新，验证诊断读取的是最终数据）
        jdbc.update("UPDATE translation SET content = 'HELLO world' WHERE document_id = ?", docId);
        ApiResult drifted = getJson("/api/documents/" + docId + "/anchors/diagnostics");
        JsonNode bad = drifted.body().get("anchors").get(0);
        assertThat(bad.get("textIntactInCurrentContent").asBoolean()).isFalse();
        assertThat(bad.get("drift").asBoolean()).isTrue();
        jdbc.update("UPDATE translation SET content = 'hello world' WHERE document_id = ?", docId);

        assertThat(getJson("/api/documents/999999/segments/s1/anchors").status()).isEqualTo(404);
        assertThat(getJson("/api/documents/" + docId + "/segments/nope/anchors/history").status())
                .isEqualTo(404);
        assertThat(getJson("/api/documents/999999/anchors/diagnostics").status()).isEqualTo(404);
    }

    @Test
    @DisplayName("锚点写操作幂等：同键同参重放同一锚点，异参 409，失败不占键")
    void anchorIdempotency() throws Exception {
        long docId = approvedDocument();
        String requestId = newRequestId();
        String anchors = "[{\"citationKey\":\"cite/one\",\"startOffset\":0,\"endOffset\":5,\"lockReason\":\"出处一\"}]";
        ApiResult first = registerAnchors(docId, "s1", "en", "alice", "EDITOR", 1, anchors, requestId);
        assertThat(first.status()).isEqualTo(201);
        long anchorId = first.body().get("anchors").get(0).get("anchorId").asLong();

        ApiResult replay = registerAnchors(docId, "s1", "en", "alice", "EDITOR", 1, anchors, requestId);
        assertThat(replay.status()).isEqualTo(201);
        assertThat(replay.body().get("anchors").get(0).get("anchorId").asLong()).isEqualTo(anchorId);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM citation_anchor WHERE document_id = ?", Integer.class, docId)).isOne();

        ApiResult conflict = registerAnchors(docId, "s1", "en", "alice", "EDITOR", 1,
                "[{\"citationKey\":\"cite/one\",\"startOffset\":0,\"endOffset\":4,\"lockReason\":\"出处一\"}]",
                requestId);
        assertThat(conflict.status()).isEqualTo(409);

        // 失败不占键：先用该 requestId 触发交叉 422，修正后成功
        String failKey = newRequestId();
        ApiResult failed = registerAnchors(docId, "s1", "en", "alice", "EDITOR", 1,
                "[{\"citationKey\":\"cite/two\",\"startOffset\":3,\"endOffset\":9,\"lockReason\":\"r\"}]",
                failKey);
        assertThat(failed.status()).isEqualTo(422);
        ApiResult retried = registerAnchors(docId, "s1", "en", "alice", "EDITOR", 1,
                "[{\"citationKey\":\"cite/two\",\"startOffset\":6,\"endOffset\":11,\"lockReason\":\"r\"}]",
                failKey);
        assertThat(retried.status()).isEqualTo(201);
    }
}
