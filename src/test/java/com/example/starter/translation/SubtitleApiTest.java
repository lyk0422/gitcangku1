package com.example.starter.translation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 字幕文档模式 API 测试：建文档时段校验、阅读速率边界、码点计数与操作者要求。
 */
class SubtitleApiTest extends AbstractIntegrationTest {

    private static final long ZERO = 1700000000000L;

    private static String cue(String segmentId, String sourceText, long startMs, long endMs) {
        return "{\"segmentId\":\"" + segmentId + "\",\"sourceText\":\"" + sourceText
                + "\",\"zeroPointMs\":" + ZERO + ",\"startMs\":" + startMs + ",\"endMs\":" + endMs + "}";
    }

    private ApiResult createSubtitle(String segmentsJson, String actor) throws Exception {
        String body = "{\"requestId\":\"" + newRequestId() + "\",\"targetLanguages\":[\"en\"],"
                + "\"segments\":" + segmentsJson + ",\"mode\":\"subtitle\"}";
        return postJson("/api/documents", body, actor);
    }

    @Test
    @DisplayName("字幕建文档：201，左闭右开首尾相接合法，时段落库")
    void createSubtitleDocumentSuccess() throws Exception {
        ApiResult result = createSubtitle("[" + cue("s1", "第一句", 0, 1500) + ","
                + cue("s2", "第二句", 1500, 3000) + "]", "alice");
        assertThat(result.status()).isEqualTo(201);
        assertThat(result.body().get("mode").asText()).isEqualTo("subtitle");
        long docId = result.body().get("documentId").asLong();
        Integer timed = jdbc.queryForObject(
                "SELECT COUNT(*) FROM segment WHERE document_id = ? AND zero_point_ms = ? "
                        + "AND start_ms IS NOT NULL AND end_ms IS NOT NULL",
                Integer.class, docId, ZERO);
        assertThat(timed).isEqualTo(2);
    }

    @Test
    @DisplayName("字幕建文档：时长不足 500 毫秒、负起始、重叠、缺时段参数均 422；失败不占键")
    void createSubtitleDocumentTimingValidation() throws Exception {
        assertThat(createSubtitle("[" + cue("s1", "短", 0, 499) + "]", "alice").status()).isEqualTo(422);
        assertThat(createSubtitle("[" + cue("s1", "负", -1, 1000) + "]", "alice").status()).isEqualTo(422);
        assertThat(createSubtitle("[" + cue("s1", "甲", 0, 1000) + ","
                + cue("s2", "乙", 999, 2000) + "]", "alice").status()).isEqualTo(422);
        assertThat(createSubtitle("[{\"segmentId\":\"s1\",\"sourceText\":\"缺\","
                + "\"zeroPointMs\":" + ZERO + ",\"startMs\":0}]", "alice").status()).isEqualTo(422);
        // 失败不占键：同一 requestId 修正参数后可成功
        String requestId = newRequestId();
        String badBody = "{\"requestId\":\"" + requestId + "\",\"targetLanguages\":[\"en\"],"
                + "\"segments\":[" + cue("s1", "短", 0, 100) + "],\"mode\":\"subtitle\"}";
        assertThat(postJson("/api/documents", badBody, "alice").status()).isEqualTo(422);
        String goodBody = "{\"requestId\":\"" + requestId + "\",\"targetLanguages\":[\"en\"],"
                + "\"segments\":[" + cue("s1", "好", 0, 1000) + "],\"mode\":\"subtitle\"}";
        assertThat(postJson("/api/documents", goodBody, "alice").status()).isEqualTo(201);
    }

    @Test
    @DisplayName("字幕建文档：缺操作者 400；未知模式 422；普通文档带时段 422；零点加法溢出 422")
    void createSubtitleDocumentModeValidation() throws Exception {
        assertThat(createSubtitle("[" + cue("s1", "甲", 0, 1000) + "]", null).status()).isEqualTo(400);

        String badMode = "{\"requestId\":\"" + newRequestId() + "\",\"targetLanguages\":[\"en\"],"
                + "\"segments\":[],\"mode\":\"video\"}";
        assertThat(postJson("/api/documents", badMode, "alice").status()).isEqualTo(422);

        String standardWithTiming = "{\"requestId\":\"" + newRequestId() + "\",\"targetLanguages\":[\"en\"],"
                + "\"segments\":[" + cue("s1", "甲", 0, 1000) + "]}";
        assertThat(postJson("/api/documents", standardWithTiming).status()).isEqualTo(422);

        String overflow = "{\"requestId\":\"" + newRequestId() + "\",\"targetLanguages\":[\"en\"],"
                + "\"segments\":[{\"segmentId\":\"s1\",\"sourceText\":\"甲\",\"zeroPointMs\":"
                + Long.MAX_VALUE + ",\"startMs\":1000,\"endMs\":2000}],\"mode\":\"subtitle\"}";
        assertThat(postJson("/api/documents", overflow, "alice").status()).isEqualTo(422);
    }

    @Test
    @DisplayName("阅读速率：1 秒时段 15 码点允许，16 码点 422 且不写稿")
    void readingSpeedBoundary() throws Exception {
        long docId = createSubtitle("[" + cue("s1", "甲", 0, 1000) + ","
                + cue("s2", "乙", 1000, 2000) + "]", "alice").body().get("documentId").asLong();

        ApiResult ok = submitTranslation(docId, "s1", "en", "alice", "a".repeat(15), 1, newRequestId());
        assertThat(ok.status()).isEqualTo(200);

        ApiResult tooFast = submitTranslation(docId, "s2", "en", "alice", "a".repeat(16), 1, newRequestId());
        assertThat(tooFast.status()).isEqualTo(422);
        Integer written = jdbc.queryForObject(
                "SELECT COUNT(*) FROM translation WHERE document_id = ? AND segment_id = 's2'",
                Integer.class, docId);
        assertThat(written).isZero();
    }

    @Test
    @DisplayName("码点计数：补充平面表情计 1 码点；插空白不能规避计数")
    void emojiAndWhitespaceCounting() throws Exception {
        long docId = createSubtitle("[" + cue("s1", "甲", 0, 1000) + ","
                + cue("s2", "乙", 1000, 2000) + ","
                + cue("s3", "丙", 2000, 3000) + ","
                + cue("s4", "丁", 3000, 4000) + "]", "alice").body().get("documentId").asLong();

        // 15 个补充平面表情 = 15 码点，允许
        assertThat(submitTranslation(docId, "s1", "en", "alice", "😀".repeat(15), 1, newRequestId()).status())
                .isEqualTo(200);
        // 16 个表情 = 16 码点，拒绝
        assertThat(submitTranslation(docId, "s2", "en", "alice", "😀".repeat(16), 1, newRequestId()).status())
                .isEqualTo(422);
        // 15 个非空白码点加大量空白（空格、JSON 换行、不间断空格），仍允许
        String spaced = "a b\\nc d e f g h i j k l m n o";
        assertThat(submitTranslation(docId, "s3", "en", "alice", spaced, 1, newRequestId()).status())
                .isEqualTo(200);
        // 16 个非空白码点，即使穿插空白也拒绝
        String spacedFast = "a b c d e f g h i j k l m n o p";
        assertThat(submitTranslation(docId, "s4", "en", "alice", spacedFast, 1, newRequestId()).status())
                .isEqualTo(422);
    }
}
