package com.example.starter.translation;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 使用可控时钟验证操作历史的 UTC 时间语义：不同操作落在不同毫秒，
 * 区间查询严格左闭右开，重放与读取不产生新历史。
 */
class ControlledClockHistoryTest extends AbstractIntegrationTest {

    /** 可由测试推进的固定时钟：默认停在固定基准时间，tick 后整体平移。 */
    static final class MutableClock extends Clock {
        private volatile Instant instant = Instant.parse("2026-09-28T00:00:00Z");

        void setInstant(Instant value) {
            this.instant = value;
        }

        void tickMillis(long millis) {
            this.instant = instant.plusMillis(millis);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    @TestConfiguration
    static class ClockConfig {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock();
        }
    }

    @Autowired
    private MutableClock clock;

    @BeforeEach
    void resetClock() {
        clock.setInstant(Instant.parse("2026-09-28T00:00:00Z"));
        jdbc.update("DELETE FROM operation_log");
        jdbc.update("DELETE FROM approval");
        jdbc.update("DELETE FROM translation");
        jdbc.update("DELETE FROM segment");
        jdbc.update("DELETE FROM release_snapshot");
        jdbc.update("DELETE FROM term_rule");
        jdbc.update("DELETE FROM term_version");
        jdbc.update("DELETE FROM request_log");
        jdbc.update("DELETE FROM document");
    }

    @Test
    @DisplayName("可控时钟：历史区间左闭右开精确到毫秒，排序稳定，重放不追加历史")
    void historyWindowWithControlledClock() throws Exception {
        long t0 = clock.instant().toEpochMilli();

        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"Hi {name}\",\"placeholders\":[\"{name}\"]}]");
        clock.tickMillis(20);
        long t1 = clock.instant().toEpochMilli();

        assertThat(submitTranslation(docId, "s1", "en", "alice", "Hallo {name}", 1, newRequestId()).status())
                .isEqualTo(200);
        clock.tickMillis(20);
        long t2 = clock.instant().toEpochMilli();

        String approveRequestId = newRequestId();
        assertThat(approve(docId, "s1", "en", "bob", 1, approveRequestId).status()).isEqualTo(200);
        clock.tickMillis(20);
        long t3 = clock.instant().toEpochMilli();

        // [t0, t1) 仅建文档；右端点开区间不包含提交
        JsonNode firstWindow = getJson(
                "/api/documents/" + docId + "/history?from=" + t0 + "&to=" + t1).body();
        assertThat(firstWindow.get("operations")).hasSize(1);
        assertThat(firstWindow.get("operations").get(0).get("operationType").asText())
                .isEqualTo("DOCUMENT_CREATED");

        // [t1, t2) 仅提交；左端闭区间包含提交
        JsonNode secondWindow = getJson(
                "/api/documents/" + docId + "/history?from=" + t1 + "&to=" + t2).body();
        assertThat(secondWindow.get("operations")).hasSize(1);
        assertThat(secondWindow.get("operations").get(0).get("operationType").asText())
                .isEqualTo("TRANSLATION_SUBMITTED");
        assertThat(secondWindow.get("operations").get(0).get("occurredAtUtcMillis").asLong()).isEqualTo(t1);

        // [t2, t3) 仅批准
        JsonNode thirdWindow = getJson(
                "/api/documents/" + docId + "/history?from=" + t2 + "&to=" + t3).body();
        assertThat(thirdWindow.get("operations")).hasSize(1);
        assertThat(thirdWindow.get("operations").get(0).get("operationType").asText())
                .isEqualTo("TRANSLATION_APPROVED");

        // [t0, t2) 含建文档与提交（右端点开区间排除发生在 t2 的批准），共 2 条
        assertThat(getJson("/api/documents/" + docId + "/history?from=" + t0 + "&to=" + t2)
                .body().get("operations")).hasSize(2);
        // [t0, t3) 含前三次操作但排除 t3 之后的事件（本用例最后一次操作恰在 t2，故仍为 3 条）
        JsonNode all = getJson("/api/documents/" + docId + "/history?from=" + t0 + "&to=" + (t3 + 1)).body();
        assertThat(all.get("operations")).hasSize(3);

        // 时间为 UTC 固定口径：首条 occurredAt 等于基准 UTC 毫秒
        assertThat(all.get("operations").get(0).get("occurredAtUtcMillis").asLong()).isEqualTo(t0);

        // 批准重放（同 requestId 同参）：不追加历史，返回首次响应快照
        int countBefore = historyCount(docId);
        JsonNode replay = approve(docId, "s1", "en", "bob", 1, approveRequestId).body();
        assertThat(replay.get("reviewer").asText()).isEqualTo("bob");
        assertThat(historyCount(docId)).isEqualTo(countBefore);

        // 读取不改状态
        getJson("/api/documents/" + docId);
        getJson("/api/documents/" + docId + "/diagnosis");
        assertThat(historyCount(docId)).isEqualTo(3);
    }

    private int historyCount(long docId) throws Exception {
        return getJson("/api/documents/" + docId + "/history").body().get("count").asInt();
    }
}
