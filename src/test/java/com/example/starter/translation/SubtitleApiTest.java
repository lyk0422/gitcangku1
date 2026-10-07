package com.example.starter.translation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 字幕文档模式 API 测试：建字幕文档校验、阅读速率提交约束、
 * 源时段修订失效批准、模式入口互斥与幂等重放。真实 H2 内存库验证。
 */
class SubtitleApiTest extends AbstractIntegrationTest {

    private static final long ZERO = 1_700_000_000_000L;

    private static String cue(String segmentId, String sourceText, long zero, long start, long end) {
        return "{\"segmentId\":\"" + segmentId + "\",\"sourceText\":\"" + sourceText
                + "\",\"zeroPointMs\":" + zero + ",\"startMs\":" + start + ",\"endMs\":" + end + "}";
    }

    @Test
    @DisplayName("建字幕文档：201 并持久化时段；相邻区间不重叠合法，不同零点按绝对时间判定")
    void createSubtitleDocumentSuccess() throws Exception {
        ApiResult result = postJson("/api/documents/subtitle",
                "{\"requestId\":\"" + newRequestId() + "\",\"operator\":\"op1\",\"targetLanguages\":[\"en\"],"
                        + "\"segments\":[" + cue("s1", "第一段", ZERO, 0, 1000) + ","
                        + cue("s2", "第二段", ZERO, 1000, 2000) + ","
                        + cue("s3", "第三段", ZERO + 100_000, 0, 500) + "]}");
        assertThat(result.status()).isEqualTo(201);
        assertThat(result.body().get("mode").asText()).isEqualTo("subtitle");
        assertThat(result.body().get("draftVersion").asInt()).isEqualTo(1);
        long documentId = result.body().get("documentId").asLong();
        Long zeroPoint = jdbc.queryForObject(
                "SELECT zero_point_ms FROM segment WHERE document_id = ? AND segment_id = 's1'",
                Long.class, documentId);
        assertThat(zeroPoint).isEqualTo(ZERO);
    }

    @Test
    @DisplayName("建字幕文档：重叠、时长不足、空区间 422；负起始、缺操作者、缺零点 400")
    void createSubtitleDocumentInvalid() throws Exception {
        String overlap = "{\"requestId\":\"" + newRequestId() + "\",\"operator\":\"op1\","
                + "\"targetLanguages\":[\"en\"],\"segments\":[" + cue("s1", "a", ZERO, 0, 1000) + ","
                + cue("s2", "b", ZERO, 500, 1500) + "]}";
        assertThat(postJson("/api/documents/subtitle", overlap).status()).isEqualTo(422);

        String overlapAcrossZeros = "{\"requestId\":\"" + newRequestId() + "\",\"operator\":\"op1\","
                + "\"targetLanguages\":[\"en\"],\"segments\":[" + cue("s1", "a", ZERO, 0, 1000) + ","
                + cue("s2", "b", ZERO + 500, 0, 500) + "]}";
        assertThat(postJson("/api/documents/subtitle", overlapAcrossZeros).status()).isEqualTo(422);

        String tooShort = "{\"requestId\":\"" + newRequestId() + "\",\"operator\":\"op1\","
                + "\"targetLanguages\":[\"en\"],\"segments\":[" + cue("s1", "a", ZERO, 0, 499) + "]}";
        assertThat(postJson("/api/documents/subtitle", tooShort).status()).isEqualTo(422);

        String empty = "{\"requestId\":\"" + newRequestId() + "\",\"operator\":\"op1\","
                + "\"targetLanguages\":[\"en\"],\"segments\":[" + cue("s1", "a", ZERO, 1000, 1000) + "]}";
        assertThat(postJson("/api/documents/subtitle", empty).status()).isEqualTo(422);

        String negative = "{\"requestId\":\"" + newRequestId() + "\",\"operator\":\"op1\","
                + "\"targetLanguages\":[\"en\"],\"segments\":["
                + "{\"segmentId\":\"s1\",\"sourceText\":\"a\",\"zeroPointMs\":" + ZERO
                + ",\"startMs\":-1,\"endMs\":1000}]}";
        assertThat(postJson("/api/documents/subtitle", negative).status()).isEqualTo(400);

        String noOperator = "{\"requestId\":\"" + newRequestId() + "\",\"operator\":\" \","
                + "\"targetLanguages\":[\"en\"],\"segments\":[" + cue("s1", "a", ZERO, 0, 1000) + "]}";
        assertThat(postJson("/api/documents/subtitle", noOperator).status()).isEqualTo(400);

        String noZero = "{\"requestId\":\"" + newRequestId() + "\",\"operator\":\"op1\","
                + "\"targetLanguages\":[\"en\"],\"segments\":["
                + "{\"segmentId\":\"s1\",\"sourceText\":\"a\",\"startMs\":0,\"endMs\":1000}]}";
        assertThat(postJson("/api/documents/subtitle", noZero).status()).isEqualTo(400);
    }

    @Test
    @DisplayName("译文提交：1 秒 15 码点允许、16 码点 422 不写稿；表情计 1 码点；插空白不计数")
    void submitReadingSpeed() throws Exception {
        long docId = createSubtitleDocument(newRequestId(), "op1", "[\"en\"]",
                "[" + cue("s1", "原文", ZERO, 0, 1000) + "]");
        ApiResult tooFast = submitTranslation(docId, "s1", "en", "alice", "1234567890123456", 1,
                newRequestId());
        assertThat(tooFast.status()).isEqualTo(422);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM translation WHERE document_id = ?",
                Integer.class, docId)).isZero();

        ApiResult exact = submitTranslation(docId, "s1", "en", "alice", "123456789012345", 1,
                newRequestId());
        assertThat(exact.status()).isEqualTo(200);

        // 补充平面表情计 1 码点：表情 + 14 字符 = 15 码点允许，两个表情 + 14 字符 = 16 拒绝
        long doc2 = createSubtitleDocument(newRequestId(), "op1", "[\"en\"]",
                "[" + cue("s1", "原文", ZERO, 0, 1000) + "]");
        assertThat(submitTranslation(doc2, "s1", "en", "alice", "😀12345678901234", 1, newRequestId())
                .status()).isEqualTo(200);
        assertThat(submitTranslation(doc2, "s1", "en", "alice", "😀😀12345678901234", 1, newRequestId())
                .status()).isEqualTo(422);

        // 插空白不能规避计数：16 个非空白码点夹空白仍拒绝
        long doc3 = createSubtitleDocument(newRequestId(), "op1", "[\"en\"]",
                "[" + cue("s1", "原文", ZERO, 0, 1000) + "]");
        assertThat(submitTranslation(doc3, "s1", "en", "alice", "1 2 3 4 5 6 7 8 9 0 a b c d e f", 1,
                newRequestId()).status()).isEqualTo(422);
        assertThat(submitTranslation(doc3, "s1", "en", "alice", "1 2 3 4 5 6 7 8 9 0 a b c d e", 1,
                newRequestId()).status()).isEqualTo(200);
    }

    @Test
    @DisplayName("字幕源时段修订：文本未变也推进源版本并失效原批准，重交重审后发布并冻结时段与码点数")
    void reviseSubtitleSourceInvalidatesApproval() throws Exception {
        long docId = createSubtitleDocument(newRequestId(), "op1", "[\"en\"]",
                "[" + cue("s1", "原文", ZERO, 0, 1000) + "]");
        submitTranslation(docId, "s1", "en", "alice", "hello", 1, newRequestId());
        approve(docId, "s1", "en", "bob", 1, newRequestId());

        ApiResult revised = reviseSubtitleSource(docId, "s1", "op1", "原文", ZERO, 0, 1000, newRequestId());
        assertThat(revised.status()).isEqualTo(200);
        assertThat(revised.body().get("sourceVersion").asInt()).isEqualTo(2);

        assertThat(approve(docId, "s1", "en", "bob", 1, newRequestId()).status()).isEqualTo(422);
        assertThat(publish(docId, 3, 0, newRequestId()).status()).isEqualTo(422);

        submitTranslation(docId, "s1", "en", "alice", "hello", 2, newRequestId());
        approve(docId, "s1", "en", "bob", 2, newRequestId());
        ApiResult published = publish(docId, 4, 0, newRequestId());
        assertThat(published.status()).isEqualTo(201);

        ApiResult release = getJson("/api/documents/" + docId + "/releases/1");
        assertThat(release.status()).isEqualTo(200);
        assertThat(release.body().get("mode").asText()).isEqualTo("subtitle");
        var segment = release.body().get("segments").get(0);
        assertThat(segment.get("zeroPointMs").asLong()).isEqualTo(ZERO);
        assertThat(segment.get("startMs").asLong()).isEqualTo(0);
        assertThat(segment.get("endMs").asLong()).isEqualTo(1000);
        var translation = segment.get("translations").get(0);
        assertThat(translation.get("codePointCount").asInt()).isEqualTo(5);
        assertThat(translation.get("reviewer").asText()).isEqualTo("bob");
        assertThat(translation.get("translationVersion").asInt()).isEqualTo(2);
        assertThat(translation.get("sourceVersion").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("模式互斥：普通文档拒绝字幕修订、字幕文档拒绝普通源文修订，均 422")
    void modeGuards() throws Exception {
        long standardId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"原文\"}]");
        assertThat(reviseSubtitleSource(standardId, "s1", "op1", "原文", ZERO, 0, 1000, newRequestId())
                .status()).isEqualTo(422);

        long subtitleId = createSubtitleDocument(newRequestId(), "op1", "[\"en\"]",
                "[" + cue("s1", "原文", ZERO, 0, 1000) + "]");
        ApiResult standardRevise = putJson("/api/documents/" + subtitleId + "/segments/s1/source",
                "{\"requestId\":\"" + newRequestId() + "\",\"sourceText\":\"修订\"}");
        assertThat(standardRevise.status()).isEqualTo(422);
    }

    @Test
    @DisplayName("幂等：同 requestId 同参（含十进制同值）重放首次响应，异参 409，失败不占键")
    void idempotency() throws Exception {
        String requestId = newRequestId();
        String body = "{\"requestId\":\"" + requestId + "\",\"operator\":\"op1\","
                + "\"targetLanguages\":[\"en\"],\"segments\":[" + cue("s1", "原文", ZERO, 0, 1000) + "]}";
        ApiResult first = postJson("/api/documents/subtitle", body);
        assertThat(first.status()).isEqualTo(201);

        // 十进制同值同参：startMs 0 与 0.0 反序列化后一致，重放首次完整响应
        String decimalBody = body.replace("\"startMs\":0,", "\"startMs\":0.0,");
        ApiResult replay = postJson("/api/documents/subtitle", decimalBody);
        assertThat(replay.status()).isEqualTo(201);
        assertThat(replay.body().get("documentId").asLong())
                .isEqualTo(first.body().get("documentId").asLong());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM document", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM request_log", Integer.class)).isEqualTo(1);

        String changed = "{\"requestId\":\"" + requestId + "\",\"operator\":\"op1\","
                + "\"targetLanguages\":[\"en\"],\"segments\":[" + cue("s1", "原文", ZERO, 0, 2000) + "]}";
        assertThat(postJson("/api/documents/subtitle", changed).status()).isEqualTo(409);

        // 失败不占键：先以重叠参数 422，再用同 requestId 合法参数可成功
        String failId = newRequestId();
        String bad = "{\"requestId\":\"" + failId + "\",\"operator\":\"op1\","
                + "\"targetLanguages\":[\"en\"],\"segments\":[" + cue("s1", "a", ZERO, 0, 1000) + ","
                + cue("s2", "b", ZERO, 500, 1500) + "]}";
        assertThat(postJson("/api/documents/subtitle", bad).status()).isEqualTo(422);
        String good = "{\"requestId\":\"" + failId + "\",\"operator\":\"op1\","
                + "\"targetLanguages\":[\"en\"],\"segments\":[" + cue("s1", "a", ZERO, 0, 1000) + "]}";
        assertThat(postJson("/api/documents/subtitle", good).status()).isEqualTo(201);
    }
}
