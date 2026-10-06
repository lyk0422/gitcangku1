package com.example.starter.translation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 译文双向隔离符校验集成测试：真实 H2 库验证提交、批准、发布全链路，
 * 覆盖合法隔离符、非法码点索引、历史旧稿、整次发布回滚、幂等重试与并发。
 * 请求体中的隔离符以 JSON 转义书写，断言用码点常量构造期望串。
 */
class BidiIsolateApiTest extends AbstractIntegrationTest {

    private static final String LRI = String.valueOf((char) 0x2066);
    private static final String RLI = String.valueOf((char) 0x2067);
    private static final String PDI = String.valueOf((char) 0x2069);
    private static final String RLO = String.valueOf((char) 0x202E);
    private static final String EMOJI = new String(Character.toChars(0x1F600));

    @Test
    @DisplayName("合法隔离符：提交/批准/发布全链路成功，快照冻结精确码点串，接口以转义 JSON 展示")
    void validIsolatesFullFlow() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"原文\"}]");
        // 请求体 JSON 转义：A + U+2067 + B + U+2069
        ApiResult submitted = submitTranslation(docId, "s1", "en", "alice", "A\\u2067B\\u2069", 1,
                newRequestId());
        assertThat(submitted.status()).isEqualTo(200);

        String expected = "A" + RLI + "B" + PDI;
        String stored = jdbc.queryForObject(
                "SELECT content FROM translation WHERE document_id = ? AND segment_id = 's1'",
                String.class, docId);
        assertThat(stored).isEqualTo(expected);

        assertThat(approve(docId, "s1", "en", "bob", 1, newRequestId()).status()).isEqualTo(200);
        assertThat(publish(docId, 2, 0, newRequestId()).status()).isEqualTo(201);

        // 接口以转义 JSON 展示控制符；解析后码点串与原文完全一致
        MvcResult release = mockMvc.perform(MockMvcRequestBuilders.get(
                "/api/documents/" + docId + "/releases/1")).andReturn();
        String raw = release.getResponse().getContentAsString();
        assertThat(raw).contains("\\u2067").contains("\\u2069");
        String content = objectMapper.readTree(raw)
                .get("segments").get(0).get("translations").get(0).get("content").asText();
        assertThat(content).isEqualTo(expected);
        assertThat(content.codePointCount(0, content.length())).isEqualTo(4);
    }

    @Test
    @DisplayName("非法隔离符提交：补充平面表情后孤立 U+2069 报索引 1；禁止符/超深/未闭合均 422 且不写库")
    void invalidSubmitsRejected() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"原文\"}]");

        // 首字符为补充平面表情（JSON 代理对转义）、第二码点 U+2069：报告码点索引 1
        ApiResult emoji = submitTranslation(docId, "s1", "en", "alice", "\\uD83D\\uDE00\\u2069", 1,
                newRequestId());
        assertThat(emoji.status()).isEqualTo(422);
        assertThat(emoji.body().get("error").asText()).isEqualTo("BIDI_ISOLATE_VIOLATION");
        assertThat(emoji.body().get("codePointIndex").asInt()).isEqualTo(1);

        // 禁止的 U+202E
        ApiResult rlo = submitTranslation(docId, "s1", "en", "alice", "ab\\u202Ecd", 1, newRequestId());
        assertThat(rlo.status()).isEqualTo(422);
        assertThat(rlo.body().get("codePointIndex").asInt()).isEqualTo(2);

        // 嵌套深度 5
        ApiResult deep = submitTranslation(docId, "s1", "en", "alice",
                "\\u2066\\u2067\\u2068\\u2066\\u2067x\\u2069\\u2069\\u2069\\u2069\\u2069", 1,
                newRequestId());
        assertThat(deep.status()).isEqualTo(422);
        assertThat(deep.body().get("codePointIndex").asInt()).isEqualTo(4);

        // 未闭合
        ApiResult unclosed = submitTranslation(docId, "s1", "en", "alice", "A\\u2066B", 1,
                newRequestId());
        assertThat(unclosed.status()).isEqualTo(422);
        assertThat(unclosed.body().get("codePointIndex").asInt()).isEqualTo(1);

        // 全部失败不写库
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM translation WHERE document_id = ?", Integer.class, docId);
        assertThat(count).isZero();
    }

    @Test
    @DisplayName("非法新文本不改变原稿与批准：422 后旧译文与批准保持，发布仍成功")
    void invalidSubmitKeepsOriginalAndApproval() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"原文\"}]");
        submitTranslation(docId, "s1", "en", "alice", "hello", 1, newRequestId());
        approve(docId, "s1", "en", "bob", 1, newRequestId());

        ApiResult invalid = submitTranslation(docId, "s1", "en", "alice", "bad\\u2069", 1,
                newRequestId());
        assertThat(invalid.status()).isEqualTo(422);

        String stored = jdbc.queryForObject(
                "SELECT content FROM translation WHERE document_id = ? AND segment_id = 's1'",
                String.class, docId);
        assertThat(stored).isEqualTo("hello");
        Integer translationVersion = jdbc.queryForObject(
                "SELECT translation_version FROM translation WHERE document_id = ? AND segment_id = 's1'",
                Integer.class, docId);
        assertThat(translationVersion).isEqualTo(1);
        Integer approvals = jdbc.queryForObject(
                "SELECT COUNT(*) FROM approval WHERE document_id = ?", Integer.class, docId);
        assertThat(approvals).isEqualTo(1);

        // 草稿版本未因失败递增，发布仍按原稿成功
        assertThat(publish(docId, 2, 0, newRequestId()).status()).isEqualTo(201);
    }

    @Test
    @DisplayName("历史不合法旧稿：保留可查，但不能重新批准或进入新发布")
    void legacyInvalidDraftCannotBeApprovedOrPublished() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"原文\"}]");
        // 模拟规则上线前的历史旧稿：直接落库非法译文与对应批准
        String legacy = "old" + PDI;
        jdbc.update("INSERT INTO translation (document_id, segment_id, language, content, author, "
                + "source_version, translation_version, term_version) VALUES (?, 's1', 'en', ?, 'alice', 1, 1, 0)",
                docId, legacy);
        jdbc.update("INSERT INTO approval (document_id, segment_id, language, reviewer, "
                + "source_version, translation_version) VALUES (?, 's1', 'en', 'bob', 1, 1)", docId);

        // 保留可查：术语状态接口仍列出该译文
        ApiResult status = getJson("/api/documents/" + docId + "/terms/status");
        assertThat(status.status()).isEqualTo(200);
        assertThat(status.body().get("translations")).hasSize(1);
        String stored = jdbc.queryForObject(
                "SELECT content FROM translation WHERE document_id = ? AND segment_id = 's1'",
                String.class, docId);
        assertThat(stored).isEqualTo(legacy);

        // 不能重新批准
        ApiResult reapprove = approve(docId, "s1", "en", "carol", 1, newRequestId());
        assertThat(reapprove.status()).isEqualTo(422);
        assertThat(reapprove.body().get("error").asText()).isEqualTo("BIDI_ISOLATE_VIOLATION");
        assertThat(reapprove.body().get("codePointIndex").asInt()).isEqualTo(3);

        // 不能进入新发布：整次 422，不产生部分快照
        ApiResult published = publish(docId, 1, 0, newRequestId());
        assertThat(published.status()).isEqualTo(422);
        assertThat(published.body().get("error").asText()).isEqualTo("BIDI_ISOLATE_VIOLATION");
        Integer snapshots = jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_snapshot WHERE document_id = ?", Integer.class, docId);
        assertThat(snapshots).isZero();
        Integer publishedVersion = jdbc.queryForObject(
                "SELECT published_version FROM document WHERE document_id = ?", Integer.class, docId);
        assertThat(publishedVersion).isZero();
    }

    @Test
    @DisplayName("发布对全部候选稿检查：任一非法整次 422，不产生部分快照")
    void publishRejectsSingleInvalidCandidate() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"一\"},{\"segmentId\":\"s2\",\"sourceText\":\"二\"}]");
        // s1 走正常流程；s2 为历史非法旧稿（直接落库译文与批准）
        submitTranslation(docId, "s1", "en", "alice", "one", 1, newRequestId());
        approve(docId, "s1", "en", "bob", 1, newRequestId());
        jdbc.update("INSERT INTO translation (document_id, segment_id, language, content, author, "
                + "source_version, translation_version, term_version) VALUES (?, 's2', 'en', ?, 'alice', 1, 1, 0)",
                docId, "two" + RLO);
        jdbc.update("INSERT INTO approval (document_id, segment_id, language, reviewer, "
                + "source_version, translation_version) VALUES (?, 's2', 'en', 'bob', 1, 1)", docId);

        ApiResult published = publish(docId, 2, 0, newRequestId());
        assertThat(published.status()).isEqualTo(422);
        assertThat(published.body().get("error").asText()).isEqualTo("BIDI_ISOLATE_VIOLATION");
        assertThat(published.body().get("codePointIndex").asInt()).isEqualTo(3);
        Integer snapshots = jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_snapshot WHERE document_id = ?", Integer.class, docId);
        assertThat(snapshots).isZero();
        Integer publishedVersion = jdbc.queryForObject(
                "SELECT published_version FROM document WHERE document_id = ?", Integer.class, docId);
        assertThat(publishedVersion).isZero();
    }

    @Test
    @DisplayName("幂等：隔离符 422 失败不占 requestId，同键修正参数后重试成功")
    void failedSubmitDoesNotOccupyRequestId() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"原文\"}]");
        String requestId = newRequestId();
        ApiResult failed = submitTranslation(docId, "s1", "en", "alice", "bad\\u2069", 1, requestId);
        assertThat(failed.status()).isEqualTo(422);

        ApiResult retried = submitTranslation(docId, "s1", "en", "alice", "good\\u2067x\\u2069", 1,
                requestId);
        assertThat(retried.status()).isEqualTo(200);
        assertThat(retried.body().get("translationVersion").asInt()).isEqualTo(1);
        String stored = jdbc.queryForObject(
                "SELECT content FROM translation WHERE document_id = ? AND segment_id = 's1'",
                String.class, docId);
        assertThat(stored).isEqualTo("good" + RLI + "x" + PDI);
    }

    @Test
    @DisplayName("并发：同段合法与非法提交并发，合法 200、非法 422，最终库中仅合法精确码点串")
    void concurrentValidAndInvalidSubmit() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"原文\"}]");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        Future<ApiResult> validFuture = pool.submit(() -> {
            gate.await();
            return submitTranslation(docId, "s1", "en", "alice", "A\\u2067B\\u2069", 1, newRequestId());
        });
        Future<ApiResult> invalidFuture = pool.submit(() -> {
            gate.await();
            return submitTranslation(docId, "s1", "en", "alice", "bad\\u2069", 1, newRequestId());
        });
        gate.countDown();
        ApiResult valid = validFuture.get(30, TimeUnit.SECONDS);
        ApiResult invalid = invalidFuture.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(valid.status()).isEqualTo(200);
        assertThat(invalid.status()).isEqualTo(422);
        assertThat(invalid.body().get("error").asText()).isEqualTo("BIDI_ISOLATE_VIOLATION");

        String stored = jdbc.queryForObject(
                "SELECT content FROM translation WHERE document_id = ? AND segment_id = 's1'",
                String.class, docId);
        assertThat(stored).isEqualTo("A" + RLI + "B" + PDI);
        Integer translationVersion = jdbc.queryForObject(
                "SELECT translation_version FROM translation WHERE document_id = ? AND segment_id = 's1'",
                Integer.class, docId);
        assertThat(translationVersion).isEqualTo(1);
        Integer draftVersion = jdbc.queryForObject(
                "SELECT draft_version FROM document WHERE document_id = ?", Integer.class, docId);
        assertThat(draftVersion).isEqualTo(2);
    }
}
