package com.example.starter.translation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 字幕文档工作流测试：时段修订失效批准、发布冻结快照、缺译与幂等。
 */
class SubtitleWorkflowTest extends AbstractIntegrationTest {

    private static final long ZERO = 1700000000000L;

    private static String cue(String segmentId, String sourceText, long startMs, long endMs) {
        return "{\"segmentId\":\"" + segmentId + "\",\"sourceText\":\"" + sourceText
                + "\",\"zeroPointMs\":" + ZERO + ",\"startMs\":" + startMs + ",\"endMs\":" + endMs + "}";
    }

    private long createSubtitle(String segmentsJson) throws Exception {
        String body = "{\"requestId\":\"" + newRequestId() + "\",\"targetLanguages\":[\"en\"],"
                + "\"segments\":" + segmentsJson + ",\"mode\":\"subtitle\"}";
        ApiResult result = postJson("/api/documents", body, "alice");
        if (result.status() != 201) {
            throw new IllegalStateException("建字幕文档失败: " + result.status() + " " + result.body());
        }
        return result.body().get("documentId").asLong();
    }

    private ApiResult reviseSource(long docId, String segmentId, String sourceText,
                                   long startMs, long endMs, String actor) throws Exception {
        String body = "{\"requestId\":\"" + newRequestId() + "\",\"sourceText\":\"" + sourceText
                + "\",\"zeroPointMs\":" + ZERO + ",\"startMs\":" + startMs + ",\"endMs\":" + endMs + "}";
        return putJson("/api/documents/" + docId + "/segments/" + segmentId + "/source", body, actor);
    }

    @Test
    @DisplayName("缩短已批准段到 500 毫秒：文本未变也须重审，重新提交批准后发布并冻结快照")
    void shortenApprovedSegmentRequiresReapproval() throws Exception {
        long docId = createSubtitle("[" + cue("s1", "第一句", 0, 2000) + "]");
        // 11 码点 / 2000 毫秒，速率合法
        assertThat(submitTranslation(docId, "s1", "en", "alice", "hello world", 1, newRequestId()).status())
                .isEqualTo(200);
        assertThat(approve(docId, "s1", "en", "bob", 1, newRequestId()).status()).isEqualTo(200);

        // 文本未变，仅缩短时段到 500 毫秒：源版本推进到 2，原批准失效
        ApiResult revised = reviseSource(docId, "s1", "第一句", 0, 500, "alice");
        assertThat(revised.status()).isEqualTo(200);
        assertThat(revised.body().get("sourceVersion").asInt()).isEqualTo(2);

        // 原批准已失效，发布 422；旧译文基于源版本 1，批准也 422
        assertThat(publish(docId, 3, 0, newRequestId()).status()).isEqualTo(422);
        assertThat(approve(docId, "s1", "en", "bob", 1, newRequestId()).status()).isEqualTo(422);

        // 500 毫秒时段最多 7 码点：8 码点提交 422，5 码点提交成功
        assertThat(submitTranslation(docId, "s1", "en", "alice", "12345678", 2, newRequestId()).status())
                .isEqualTo(422);
        assertThat(submitTranslation(docId, "s1", "en", "alice", "short", 2, newRequestId()).status())
                .isEqualTo(200);
        assertThat(approve(docId, "s1", "en", "bob", 2, newRequestId()).status()).isEqualTo(200);

        ApiResult published = publish(docId, 4, 0, newRequestId());
        assertThat(published.status()).isEqualTo(201);

        // 快照冻结时段、文字、码点数与批准依据
        ApiResult release = getJson("/api/documents/" + docId + "/releases/1");
        assertThat(release.status()).isEqualTo(200);
        assertThat(release.body().get("mode").asText()).isEqualTo("subtitle");
        var segment = release.body().get("segments").get(0);
        assertThat(segment.get("zeroPointMs").asLong()).isEqualTo(ZERO);
        assertThat(segment.get("startMs").asLong()).isZero();
        assertThat(segment.get("endMs").asLong()).isEqualTo(500);
        assertThat(segment.get("durationMs").asLong()).isEqualTo(500);
        var translation = segment.get("translations").get(0);
        assertThat(translation.get("content").asText()).isEqualTo("short");
        assertThat(translation.get("codePointCount").asLong()).isEqualTo(5);
        assertThat(translation.get("reviewer").asText()).isEqualTo("bob");
        assertThat(translation.get("sourceVersion").asInt()).isEqualTo(2);
        assertThat(translation.get("translationVersion").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("时段修订：重叠 422、缺时段参数 422、缺操作者 400；普通文档修订带时段 422")
    void reviseTimingValidation() throws Exception {
        long docId = createSubtitle("[" + cue("s1", "甲", 0, 1000) + "," + cue("s2", "乙", 1000, 2000) + "]");
        assertThat(reviseSource(docId, "s2", "乙", 500, 1500, "alice").status()).isEqualTo(422);

        String noTiming = "{\"requestId\":\"" + newRequestId() + "\",\"sourceText\":\"乙\"}";
        assertThat(putJson("/api/documents/" + docId + "/segments/s2/source", noTiming, "alice").status())
                .isEqualTo(422);
        assertThat(reviseSource(docId, "s2", "乙", 1000, 2000, null).status()).isEqualTo(400);

        long standardDoc = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"原文\"}]");
        String withTiming = "{\"requestId\":\"" + newRequestId() + "\",\"sourceText\":\"改\","
                + "\"zeroPointMs\":" + ZERO + ",\"startMs\":0,\"endMs\":1000}";
        assertThat(putJson("/api/documents/" + standardDoc + "/segments/s1/source", withTiming).status())
                .isEqualTo(422);
    }

    @Test
    @DisplayName("字幕增段：合法 201，重叠 422，缺操作者 400")
    void addSubtitleSegment() throws Exception {
        long docId = createSubtitle("[" + cue("s1", "甲", 0, 1000) + "]");
        String addBody = "{\"requestId\":\"" + newRequestId() + "\",\"segmentId\":\"s2\","
                + "\"sourceText\":\"乙\",\"zeroPointMs\":" + ZERO + ",\"startMs\":1000,\"endMs\":2000}";
        assertThat(postJson("/api/documents/" + docId + "/segments", addBody, "alice").status()).isEqualTo(201);

        String overlap = "{\"requestId\":\"" + newRequestId() + "\",\"segmentId\":\"s3\","
                + "\"sourceText\":\"丙\",\"zeroPointMs\":" + ZERO + ",\"startMs\":500,\"endMs\":1500}";
        assertThat(postJson("/api/documents/" + docId + "/segments", overlap, "alice").status()).isEqualTo(422);

        String noActor = "{\"requestId\":\"" + newRequestId() + "\",\"segmentId\":\"s4\","
                + "\"sourceText\":\"丁\",\"zeroPointMs\":" + ZERO + ",\"startMs\":2000,\"endMs\":3000}";
        assertThat(postJson("/api/documents/" + docId + "/segments", noActor).status()).isEqualTo(400);
    }

    @Test
    @DisplayName("字幕发布：缺译整次 422 且无部分快照")
    void publishMissingTranslation() throws Exception {
        long docId = createSubtitle("[" + cue("s1", "甲", 0, 1000) + "," + cue("s2", "乙", 1000, 2000) + "]");
        submitTranslation(docId, "s1", "en", "alice", "hello", 1, newRequestId());
        approve(docId, "s1", "en", "bob", 1, newRequestId());
        // s2 缺译（当前草稿版本 2）
        assertThat(publish(docId, 2, 0, newRequestId()).status()).isEqualTo(422);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_snapshot WHERE document_id = ?", Integer.class, docId)).isZero();
    }

    @Test
    @DisplayName("字幕幂等：同键同参重放首次完整响应；同键改时段参数 409")
    void subtitleIdempotency() throws Exception {
        String requestId = newRequestId();
        String body = "{\"requestId\":\"" + requestId + "\",\"targetLanguages\":[\"en\"],"
                + "\"segments\":[" + cue("s1", "甲", 0, 1000) + "],\"mode\":\"subtitle\"}";
        ApiResult first = postJson("/api/documents", body, "alice");
        assertThat(first.status()).isEqualTo(201);

        ApiResult replay = postJson("/api/documents", body, "alice");
        assertThat(replay.status()).isEqualTo(201);
        assertThat(replay.body().toString()).isEqualTo(first.body().toString());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM document", Integer.class)).isEqualTo(1);

        String changed = "{\"requestId\":\"" + requestId + "\",\"targetLanguages\":[\"en\"],"
                + "\"segments\":[" + cue("s1", "甲", 0, 1500) + "],\"mode\":\"subtitle\"}";
        assertThat(postJson("/api/documents", changed, "alice").status()).isEqualTo(409);
    }
}
