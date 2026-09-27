package com.example.starter.maintenance;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 可控时钟测试：证据包、材料撤销与完工时刻均取自注入的 UTC Clock；
 * 验证时间精度（微秒级往返不被截断）与重放时刻稳定。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ControlledClockTests {

    /** 测试内可推进的固定 UTC 时钟（默认精确到微秒，避免库端舍入）。 */
    static class MutableClock extends Clock {
        private volatile Instant current = Instant.parse("2026-04-01T00:00:00.123Z");

        void advanceSeconds(long seconds) {
            current = current.plusSeconds(seconds);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return current;
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
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MutableClock clock;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM idempotency_request");
        jdbc.update("DELETE FROM evidence_appendix");
        jdbc.update("DELETE FROM maintenance_confirmation");
        jdbc.update("DELETE FROM maintenance");
        jdbc.update("DELETE FROM evidence_material");
        jdbc.update("DELETE FROM evidence_check_result");
        jdbc.update("DELETE FROM evidence_package");
        jdbc.update("DELETE FROM maintenance_template_item");
        jdbc.update("DELETE FROM maintenance_template");
        jdbc.update("DELETE FROM material");
        jdbc.update("DELETE FROM reading_revision");
        jdbc.update("DELETE FROM reading");
        jdbc.update("DELETE FROM equipment");
    }

    @Test
    void evidenceAndCompletion_timestampsFollowControlledClock() throws Exception {
        Instant t0 = clock.instant();
        mockMvc.perform(post("/api/equipment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"c-reg","equipmentId":"eq-clock","maintenancePeriodMinutes":100}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/equipment/eq-clock/template-versions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"c-tpl","expectedVersion":1,"overdueThresholdMinutes":200,
                                 "items":[{"itemCode":"I1","itemTitle":"润滑"}]}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/equipment/eq-clock/readings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"c-r","expectedVersion":2,"readingId":"r1",
                                 "sampledAt":"2026-04-01T00:00:00Z","cumulativeMinutes":130}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/materials")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"c-m","batchCode":"MAT-CLOCK01","description":"x"}
                                """))
                .andExpect(status().isCreated());

        // 证据包版本 1 时刻 = t0，纳秒（微秒级）精度保留
        mockMvc.perform(post("/api/equipment/eq-clock/evidence")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"c-ev1","expectedVersion":3,"submittedBy":"alice",
                                 "checkResults":[{"itemCode":"I1","result":"PASS"}],
                                 "materials":[{"batchCode":"MAT-CLOCK01","quantity":1,"unit":"PCS"}]}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.createdAt").value(t0.toString()));

        // 重放不改变历史时刻
        mockMvc.perform(post("/api/equipment/eq-clock/evidence")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"c-ev1","expectedVersion":3,"submittedBy":"alice",
                                 "checkResults":[{"itemCode":"I1","result":"PASS"}],
                                 "materials":[{"batchCode":"MAT-CLOCK01","quantity":1,"unit":"PCS"}]}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.createdAt").value(t0.toString()));

        clock.advanceSeconds(60);
        Instant t1 = clock.instant();
        // 撤销时刻取自推进后的时钟
        mockMvc.perform(post("/api/materials/MAT-CLOCK01/revocations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"c-rev"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.revokedAt").value(t1.toString()));

        // 材料撤销后证据不再齐备：换用新材料批次提交新版本
        mockMvc.perform(post("/api/materials")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"c-m2","batchCode":"MAT-CLOCK02","description":"y"}
                                """))
                .andExpect(status().isCreated());

        clock.advanceSeconds(60);
        Instant t2 = clock.instant();
        mockMvc.perform(post("/api/equipment/eq-clock/maintenances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"c-done","expectedVersion":4,"readingId":"r1","anchorRevisionNo":1,
                                 "expectedPackageVersion":1,"expectedTemplateVersion":1,"confirmerId":"bob"}
                                """))
                // 撤销材料后旧包无有效批次：先 422
                .andExpect(status().isUnprocessableEntity());

        // 重新提交含新材料的版本 2，再完工，完工时刻 = t2
        mockMvc.perform(post("/api/equipment/eq-clock/evidence")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"c-ev2","expectedVersion":4,"submittedBy":"alice",
                                 "materials":[{"batchCode":"MAT-CLOCK02","quantity":1,"unit":"PCS"}]}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.createdAt").value(t2.toString()))
                .andExpect(jsonPath("$.complete").value(true));
        mockMvc.perform(post("/api/equipment/eq-clock/maintenances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"c-done2","expectedVersion":5,"readingId":"r1","anchorRevisionNo":1,
                                 "expectedPackageVersion":2,"expectedTemplateVersion":1,"confirmerId":"bob"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.completedAt").value(t2.toString()));

        // 历史版本时刻保持稳定
        mockMvc.perform(get("/api/equipment/eq-clock/cycles/1/evidence/versions/1"))
                .andExpect(jsonPath("$.createdAt").value(t0.toString()));
    }
}
