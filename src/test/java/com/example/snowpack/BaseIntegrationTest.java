package com.example.snowpack;

import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** 集成测试基类：真实 H2 库，逐用例清理场景数据并重置可控时钟。 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ClockTestConfig.class)
public abstract class BaseIntegrationTest {

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected MutableClock clock;

    @BeforeEach
    void cleanScenarioData() {
        jdbc.update("DELETE FROM release_decision");
        jdbc.update("DELETE FROM release_review");
        jdbc.update("DELETE FROM reading_errata");
        jdbc.update("DELETE FROM snow_reading");
        jdbc.update("DELETE FROM closure_command");
        jdbc.update("DELETE FROM zone_state");
        clock.setInstant(ClockTestConfig.TEST_START);
    }

    protected ResultActions postReading(String zoneId, String requestId, String hourUtc,
                                        int snowDepthCm, int newSnowCm) throws Exception {
        String body = """
                {"requestId":"%s","hourUtc":"%s","snowDepthCm":%d,"newSnowCm":%d}
                """.formatted(requestId, hourUtc, snowDepthCm, newSnowCm);
        return mockMvc.perform(post("/api/zones/{zoneId}/readings", zoneId)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    protected ResultActions postClosure(String zoneId, String requestId, String reason) throws Exception {
        String body = """
                {"requestId":"%s","reason":"%s"}
                """.formatted(requestId, reason);
        return mockMvc.perform(post("/api/zones/{zoneId}/closures", zoneId)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    protected ResultActions confirm(String zoneId, String requestId, String reviewer) throws Exception {
        String body = """
                {"requestId":"%s","reviewer":"%s"}
                """.formatted(requestId, reviewer);
        return mockMvc.perform(post("/api/zones/{zoneId}/release-reviews", zoneId)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    protected ResultActions postErrata(String zoneId, long readingId, String requestId, String note,
                                       String reviewResult) throws Exception {
        String body = """
                {"requestId":"%s","note":"%s","reviewResult":%s}
                """.formatted(requestId, note,
                reviewResult == null ? "null" : "\"" + reviewResult + "\"");
        return mockMvc.perform(post("/api/zones/{zoneId}/readings/{readingId}/errata", zoneId, readingId)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    /** 准备封闭区域并上报三条连续合格整点读数（08、09、10 点，新增积雪 4/5/5 厘米）。 */
    protected void closedZoneWithGoodWindow(String zoneId) throws Exception {
        postClosure(zoneId, "c-1", "patrol closure").andExpect(
                org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        postReading(zoneId, "r-08", "2026-10-04T08:00:00Z", 110, 4).andExpect(
                org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        postReading(zoneId, "r-09", "2026-10-04T09:00:00Z", 115, 5).andExpect(
                org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        postReading(zoneId, "r-10", "2026-10-04T10:00:00Z", 120, 5).andExpect(
                org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
    }

    protected int countRows(String table, String zoneId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE zone_id = ?", Integer.class, zoneId);
        return count == null ? 0 : count;
    }

    protected Instant fixedNow() {
        return clock.instant();
    }
}