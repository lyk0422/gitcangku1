package com.example.starter.translation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 双向隔离符校验集成测试（真实 H2 事务）：合法隔离对精确保存并以转义 JSON 展示；
 * 非法文本在提交、修订、批准、发布各入口拦截为 422 且不改变原稿；失败不占幂等键。
 */
class BidiIsolateApiTest extends AbstractIntegrationTest {

    private static final String LRI = Character.toString(0x2066);
    private static final String RLI = Character.toString(0x2067);
    private static final String PDI = Character.toString(0x2069);
    private static final String LRE = Character.toString(0x202A);
    private static final String EMOJI = new String(Character.toChars(0x1F600));

    @Test
    @DisplayName("合法隔离对：提交 200，库存精确码点，快照 JSON 转义展示且解析后文本不变")
    void validIsolatePairStoredExactlyAndEscapedInSnapshot() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"原文\"}]");
        String content = "A" + RLI + "B" + PDI;
        ApiResult submitted = submitTranslation(docId, "s1", "en", "alice", content, 1, newRequestId());
        assertThat(submitted.status()).isEqualTo(200);

        // 数据库保存精确码点串，不删除、不修复任何字符
        String stored = jdbc.queryForObject(
                "SELECT content FROM translation WHERE document_id = ? AND segment_id = 's1'",
                String.class, docId);
        assertThat(stored).isEqualTo(content);

        approve(docId, "s1", "en", "bob", 1, newRequestId());
        assertThat(publish(docId, 2, 0, newRequestId()).status()).isEqualTo(201);

        // 接口以转义 JSON 展示控制符；解析后文本与原文一致
        String raw = mockMvc.perform(MockMvcRequestBuilders.get("/api/documents/" + docId + "/releases/1"))
                .andReturn().getResponse().getContentAsString();
        assertThat(raw).contains("A\\u2067B\\u2069");
        String parsed = objectMapper.readTree(raw)
                .get("segments").get(0).get("translations").get(0).get("content").asText();
        assertThat(parsed).isEqualTo(content);
    }

    @Test
    @DisplayName("非法提交：各变体 422 且报告从 0 起的码点索引，原稿与版本不变")
    void illegalSubmitRejectedAndOriginalUntouched() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"原文\"}]");
        submitTranslation(docId, "s1", "en", "alice", "hello", 1, newRequestId());

        // 孤立结束符：索引 2
        ApiResult orphan = submitTranslation(docId, "s1", "en", "alice", "AB" + PDI, 1, newRequestId());
        assertThat(orphan.status()).isEqualTo(422);
        assertThat(orphan.body().get("error").asText()).isEqualTo("BIDI_ISOLATE_VIOLATION");
        assertThat(orphan.body().get("codePointIndex").asInt()).isEqualTo(2);

        // 禁用嵌入控制符 U+202A：索引 1
        ApiResult embedding = submitTranslation(docId, "s1", "en", "alice", "A" + LRE + "B", 1, newRequestId());
        assertThat(embedding.status()).isEqualTo(422);
        assertThat(embedding.body().get("codePointIndex").asInt()).isEqualTo(1);

        // 第 5 层嵌套：索引 4
        ApiResult nesting = submitTranslation(docId, "s1", "en", "alice",
                LRI + LRI + LRI + LRI + LRI, 1, newRequestId());
        assertThat(nesting.status()).isEqualTo(422);
        assertThat(nesting.body().get("codePointIndex").asInt()).isEqualTo(4);

        // 未闭合：报告最早未闭合起始符索引 1
        ApiResult unclosed = submitTranslation(docId, "s1", "en", "alice", "A" + RLI + "B", 1, newRequestId());
        assertThat(unclosed.status()).isEqualTo(422);
        assertThat(unclosed.body().get("codePointIndex").asInt()).isEqualTo(1);

        // 题干样例：首字符为补充平面表情、第二码点为 U+2069，报告索引 1（非 UTF-16 位置 2）
        ApiResult emoji = submitTranslation(docId, "s1", "en", "alice", EMOJI + PDI, 1, newRequestId());
        assertThat(emoji.status()).isEqualTo(422);
        assertThat(emoji.body().get("codePointIndex").asInt()).isEqualTo(1);

        // 原稿不变：内容、译文版本、草稿版本均未被非法文本改变
        String stored = jdbc.queryForObject(
                "SELECT content FROM translation WHERE document_id = ? AND segment_id = 's1'",
                String.class, docId);
        assertThat(stored).isEqualTo("hello");
        Integer translationVersion = jdbc.queryForObject(
                "SELECT translation_version FROM translation WHERE document_id = ? AND segment_id = 's1'",
                Integer.class, docId);
        assertThat(translationVersion).isEqualTo(1);
        Integer draftVersion = jdbc.queryForObject(
                "SELECT draft_version FROM document WHERE document_id = ?", Integer.class, docId);
        assertThat(draftVersion).isEqualTo(2);
    }

    @Test
    @DisplayName("源文入口：建文档初始段落、加段落、源文修订的非法文本均 422 且不落库")
    void sourceTextEntriesRejected() throws Exception {
        ApiResult created = postJson("/api/documents",
                "{\"requestId\":\"" + newRequestId() + "\",\"targetLanguages\":[\"en\"],"
                        + "\"segments\":[{\"segmentId\":\"s1\",\"sourceText\":\"AB" + PDI + "\"}]}");
        assertThat(created.status()).isEqualTo(422);
        assertThat(created.body().get("codePointIndex").asInt()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM document", Integer.class)).isZero();

        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"原文\"}]");
        ApiResult added = postJson("/api/documents/" + docId + "/segments",
                "{\"requestId\":\"" + newRequestId() + "\",\"segmentId\":\"s2\",\"sourceText\":\""
                        + LRI + LRI + LRI + LRI + LRI + "\"}");
        assertThat(added.status()).isEqualTo(422);
        assertThat(added.body().get("codePointIndex").asInt()).isEqualTo(4);

        ApiResult revised = putJson("/api/documents/" + docId + "/segments/s1/source",
                "{\"requestId\":\"" + newRequestId() + "\",\"sourceText\":\"新" + LRE + "文\"}");
        assertThat(revised.status()).isEqualTo(422);
        assertThat(revised.body().get("codePointIndex").asInt()).isEqualTo(1);
        // 源文与版本不变
        String source = jdbc.queryForObject(
                "SELECT source_text FROM segment WHERE document_id = ? AND segment_id = 's1'",
                String.class, docId);
        assertThat(source).isEqualTo("原文");
        Integer sourceVersion = jdbc.queryForObject(
                "SELECT source_version FROM segment WHERE document_id = ? AND segment_id = 's1'",
                Integer.class, docId);
        assertThat(sourceVersion).isEqualTo(1);
    }

    @Test
    @DisplayName("历史不合法旧稿：保留可查，但不能重新批准，也不能进入新发布（整次 422 无部分快照）")
    void historicalIllegalDraftCannotBeApprovedOrPublished() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"原文\"}]");
        submitTranslation(docId, "s1", "en", "alice", "hello", 1, newRequestId());
        // 模拟历史遗留：直接改库为不合法旧稿
        jdbc.update("UPDATE translation SET content = ? WHERE document_id = ? AND segment_id = 's1'",
                "AB" + PDI, docId);

        // 旧稿保留可查：术语状态接口仍能看到该译文
        ApiResult status = getJson("/api/documents/" + docId + "/terms/status");
        assertThat(status.status()).isEqualTo(200);
        assertThat(status.body().get("translations")).hasSize(1);

        // 不能重新批准
        ApiResult approve = approve(docId, "s1", "en", "bob", 1, newRequestId());
        assertThat(approve.status()).isEqualTo(422);
        assertThat(approve.body().get("error").asText()).isEqualTo("BIDI_ISOLATE_VIOLATION");
        assertThat(approve.body().get("codePointIndex").asInt()).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM approval WHERE document_id = ?", Integer.class, docId)).isZero();

        // 直接补齐批准后发布：整次 422，不产生部分快照
        jdbc.update("INSERT INTO approval (document_id, segment_id, language, reviewer, "
                + "source_version, translation_version) VALUES (?, 's1', 'en', 'bob', 1, 1)", docId);
        ApiResult published = publish(docId, 2, 0, newRequestId());
        assertThat(published.status()).isEqualTo(422);
        assertThat(published.body().get("error").asText()).isEqualTo("BIDI_ISOLATE_VIOLATION");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_snapshot WHERE document_id = ?", Integer.class, docId))
                .isZero();
        Integer publishedVersion = jdbc.queryForObject(
                "SELECT published_version FROM document WHERE document_id = ?", Integer.class, docId);
        assertThat(publishedVersion).isZero();
    }

    @Test
    @DisplayName("幂等边界：双向校验 422 不占 requestId，同键修正参数后成功")
    void bidiFailureDoesNotOccupyRequestId() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"原文\"}]");
        String requestId = newRequestId();
        ApiResult failed = submitTranslation(docId, "s1", "en", "alice", "AB" + PDI, 1, requestId);
        assertThat(failed.status()).isEqualTo(422);

        ApiResult retried = submitTranslation(docId, "s1", "en", "alice", "A" + RLI + "B" + PDI, 1, requestId);
        assertThat(retried.status()).isEqualTo(200);
        assertThat(retried.body().get("translationVersion").asInt()).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = ?", Integer.class, requestId))
                .isEqualTo(1);
    }
}
