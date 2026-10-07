package com.example.starter.translation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 字幕文档批准与发布的时间轴重检测试：缩短已批准段须重审、
 * 批准与发布重新检查当前时间轴（重叠/超速/缺译整次 422）。真实 H2 内存库验证。
 */
class SubtitlePublishTest extends AbstractIntegrationTest {

    private static final long ZERO = 1_700_000_000_000L;

    private static String cue(String segmentId, String sourceText, long zero, long start, long end) {
        return "{\"segmentId\":\"" + segmentId + "\",\"sourceText\":\"" + sourceText
                + "\",\"zeroPointMs\":" + zero + ",\"startMs\":" + start + ",\"endMs\":" + end + "}";
    }

    @Test
    @DisplayName("缩短已批准段到 500 毫秒：原批准失效须重审，15 码点超速拒绝，7 码点重交重审后发布")
    void shortenApprovedSegmentTo500msRequiresReapproval() throws Exception {
        long docId = createSubtitleDocument(newRequestId(), "op1", "[\"en\"]",
                "[" + cue("s1", "原文", ZERO, 0, 1000) + "]");
        submitTranslation(docId, "s1", "en", "alice", "123456789012345", 1, newRequestId());
        approve(docId, "s1", "en", "bob", 1, newRequestId());

        ApiResult shortened = reviseSubtitleSource(docId, "s1", "op1", "原文", ZERO, 0, 500, newRequestId());
        assertThat(shortened.status()).isEqualTo(200);
        assertThat(shortened.body().get("sourceVersion").asInt()).isEqualTo(2);

        // 原批准已失效：发布 422；15 码点按 500 毫秒超速（15000 > 7500）提交 422
        assertThat(publish(docId, 3, 0, newRequestId()).status()).isEqualTo(422);
        assertThat(submitTranslation(docId, "s1", "en", "alice", "123456789012345", 2, newRequestId())
                .status()).isEqualTo(422);

        assertThat(submitTranslation(docId, "s1", "en", "alice", "1234567", 2, newRequestId()).status())
                .isEqualTo(200);
        assertThat(approve(docId, "s1", "en", "bob", 2, newRequestId()).status()).isEqualTo(200);
        assertThat(publish(docId, 4, 0, newRequestId()).status()).isEqualTo(201);

        ApiResult release = getJson("/api/documents/" + docId + "/releases/1");
        assertThat(release.status()).isEqualTo(200);
        var segment = release.body().get("segments").get(0);
        assertThat(segment.get("startMs").asLong()).isEqualTo(0);
        assertThat(segment.get("endMs").asLong()).isEqualTo(500);
        assertThat(segment.get("translations").get(0).get("codePointCount").asInt()).isEqualTo(7);
    }

    @Test
    @DisplayName("批准重检时间轴：当前时间轴出现重叠时批准整次 422")
    void approveRechecksTimelineOverlap() throws Exception {
        long docId = createSubtitleDocument(newRequestId(), "op1", "[\"en\"]",
                "[" + cue("s1", "第一段", ZERO, 0, 1000) + "," + cue("s2", "第二段", ZERO, 2000, 3000) + "]");
        submitTranslation(docId, "s1", "en", "alice", "hello", 1, newRequestId());

        // 绕过 API 直接改库制造重叠，验证批准时重新检查当前时间轴
        jdbc.update("UPDATE segment SET start_ms = 500, end_ms = 1500 "
                + "WHERE document_id = ? AND segment_id = 's2'", docId);
        ApiResult blocked = approve(docId, "s1", "en", "bob", 1, newRequestId());
        assertThat(blocked.status()).isEqualTo(422);
        assertThat(blocked.body().get("message").asText()).contains("重叠");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM approval WHERE document_id = ?",
                Integer.class, docId)).isZero();

        jdbc.update("UPDATE segment SET start_ms = 2000, end_ms = 3000 "
                + "WHERE document_id = ? AND segment_id = 's2'", docId);
        assertThat(approve(docId, "s1", "en", "bob", 1, newRequestId()).status()).isEqualTo(200);
    }

    @Test
    @DisplayName("发布重检时间轴：重叠或任一译文按当前时段超速整次 422，恢复后发布成功")
    void publishRechecksTimeline() throws Exception {
        long docId = createSubtitleDocument(newRequestId(), "op1", "[\"en\"]",
                "[" + cue("s1", "第一段", ZERO, 0, 1000) + "," + cue("s2", "第二段", ZERO, 2000, 3000) + "]");
        submitTranslation(docId, "s1", "en", "alice", "123456789012345", 1, newRequestId());
        approve(docId, "s1", "en", "bob", 1, newRequestId());
        submitTranslation(docId, "s2", "en", "alice", "hello", 1, newRequestId());
        approve(docId, "s2", "en", "bob", 1, newRequestId());
        // 当前草稿版本 3、发布版本 0

        // 时间轴重叠：发布整次 422 且无快照
        jdbc.update("UPDATE segment SET start_ms = 500, end_ms = 1500 "
                + "WHERE document_id = ? AND segment_id = 's2'", docId);
        assertThat(publish(docId, 3, 0, newRequestId()).status()).isEqualTo(422);
        jdbc.update("UPDATE segment SET start_ms = 2000, end_ms = 3000 "
                + "WHERE document_id = ? AND segment_id = 's2'", docId);

        // s1 译文 15 码点，时段缩到 600 毫秒后超速（15000 > 9000）：发布整次 422
        jdbc.update("UPDATE segment SET end_ms = 600 WHERE document_id = ? AND segment_id = 's1'", docId);
        assertThat(publish(docId, 3, 0, newRequestId()).status()).isEqualTo(422);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_snapshot WHERE document_id = ?", Integer.class, docId)).isZero();

        jdbc.update("UPDATE segment SET end_ms = 1000 WHERE document_id = ? AND segment_id = 's1'", docId);
        assertThat(publish(docId, 3, 0, newRequestId()).status()).isEqualTo(201);
    }

    @Test
    @DisplayName("发布缺译：字幕文档任一目标语言缺译整次 422")
    void publishMissingTranslation() throws Exception {
        long docId = createSubtitleDocument(newRequestId(), "op1", "[\"en\",\"ja\"]",
                "[" + cue("s1", "原文", ZERO, 0, 1000) + "]");
        submitTranslation(docId, "s1", "en", "alice", "hello", 1, newRequestId());
        approve(docId, "s1", "en", "bob", 1, newRequestId());
        ApiResult result = publish(docId, 2, 0, newRequestId());
        assertThat(result.status()).isEqualTo(422);
        assertThat(result.body().get("message").asText()).contains("缺少译文");
    }
}
