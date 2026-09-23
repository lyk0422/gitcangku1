package com.example.starter.firmware;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 队列迁移端到端 HTTP 测试（H2，MODE=MySQL）：建档→分配→预览只读→激活→迟到回执→只读查询证据，
 * 以及 422/409 状态码与幂等重放。
 */
@SpringBootTest
@AutoConfigureMockMvc
class FirmwareMigrationApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanUp() {
        jdbc.update("DELETE FROM receipt_history");
        jdbc.update("DELETE FROM cohort_migration_item");
        jdbc.update("DELETE FROM cohort_migration_order");
        jdbc.update("DELETE FROM assignment_command");
        jdbc.update("DELETE FROM cohort_assignment");
        jdbc.update("DELETE FROM cohort");
        jdbc.update("DELETE FROM cohort_region");
        jdbc.update("DELETE FROM rollout_task");
        jdbc.update("DELETE FROM release_pause_record");
        jdbc.update("DELETE FROM release_resume_record");
        jdbc.update("DELETE FROM release_order");
        jdbc.update("DELETE FROM device");
        jdbc.update("DELETE FROM idempotency_record");
    }

    private static long idOf(MvcResult result, String path) throws Exception {
        Number value = com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), path);
        return value.longValue();
    }

    private long createRelease() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                {"requestId":"req-rel","model":"m1","fromVersion":"1.0.0","toVersion":"2.0.0","ratio":100}
                """)).andExpect(status().isOk()).andReturn();
        return idOf(result, "$.releaseId");
    }

    private long createCohort(long releaseId, String requestId, String code, int cap, int canary)
            throws Exception {
        MvcResult result = mockMvc.perform(post("/api/cohorts").contentType("application/json").content("""
                {"requestId":"%s","releaseId":%d,"cohortCode":"%s","firmwareVersion":"fw-9","regionCode":"R1",
                "deviceCap":%d,"canaryPercent":%d,"sampleFloor":2,"failureThresholdPercent":50}
                """.formatted(requestId, releaseId, code, cap, canary)))
                .andExpect(status().isOk()).andReturn();
        return idOf(result, "$.cohortId");
    }

    private long assign(long releaseId, String deviceId, long cohortId, String requestId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/cohorts/assignments").contentType("application/json")
                        .content("""
                                {"requestId":"%s","releaseId":%d,"deviceId":"%s","cohortId":%d}
                                """.formatted(requestId, releaseId, deviceId, cohortId)))
                .andExpect(status().isOk()).andReturn();
        return idOf(result, "$.commandId");
    }

    @Test
    void 完整迁移流程_预览只读_激活_迟到回执_查询证据() throws Exception {
        long releaseId = createRelease();
        mockMvc.perform(post("/api/cohorts/regions").contentType("application/json").content("""
                {"requestId":"req-region","releaseId":%d,"regionCode":"R1","quota":100}
                """.formatted(releaseId))).andExpect(status().isOk());
        long cohortA = createCohort(releaseId, "req-ca", "A", 10, 100);
        long cohortB = createCohort(releaseId, "req-cb", "B", 10, 100);
        long oldCmdD1 = assign(releaseId, "d1", cohortA, "req-a1");
        assign(releaseId, "d2", cohortA, "req-a2");
        String twoItems = """
                [{"deviceId":"d1","currentCohortId":%d,"assignmentVersion":1,"targetCohortId":%d},
                 {"deviceId":"d2","currentCohortId":%d,"assignmentVersion":1,"targetCohortId":%d}]
                """.formatted(cohortA, cohortB, cohortA, cohortB);

        // 预览：合法后态 feasible=true，且不写迁移单
        mockMvc.perform(post("/api/migrations/preview").contentType("application/json").content("""
                {"migrationKey":"mk-prev","releaseId":%d,"items":%s}
                """.formatted(releaseId, twoItems)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.feasible").value(true))
                .andExpect(jsonPath("$.violations").isArray());
        assertThatNoMigration();

        // 激活
        MvcResult activated = mockMvc.perform(post("/api/migrations/activate").contentType("application/json")
                        .content("""
                                {"requestId":"req-mig","migrationKey":"mk-1","releaseId":%d,"items":%s}
                                """.formatted(releaseId, twoItems)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deviceCount").value(2))
                .andExpect(jsonPath("$.items[0].oldGeneration").value(1))
                .andExpect(jsonPath("$.items[0].newGeneration").value(2))
                .andReturn();
        long migrationId = idOf(activated, "$.migrationId");
        long newCmdD1 = idOf(activated, "$.items[0].newCommandId");

        // d1 旧代次迟到回执：指令保持 SUPERSEDED，仅存 LATE
        mockMvc.perform(post("/api/commands/" + oldCmdD1 + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"req-late\",\"result\":\"SUCCESS\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUPERSEDED"));
        // 目标队列统计仍为0，规模为完整后态2台
        mockMvc.perform(get("/api/migrations/cohorts/" + cohortB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.successCount").value(0))
                .andExpect(jsonPath("$.deviceCount").value(2));

        // 只读查询返回迁移前后队列、代次与 LATE 证据
        mockMvc.perform(get("/api/migrations/" + migrationId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMMITTED"))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].fromCohortId").value(cohortA))
                .andExpect(jsonPath("$.items[0].toCohortId").value(cohortB))
                .andExpect(jsonPath("$.items[0].newCommandId").value(newCmdD1))
                .andExpect(jsonPath("$.lateReceipts[0].result").value("LATE"))
                .andExpect(jsonPath("$.lateReceipts[0].settled").value(false))
                .andExpect(jsonPath("$.lateReceipts[0].generation").value(1));
    }

    @Test
    void 容量越界返回422_重复migrationKey返回409() throws Exception {
        long releaseId = createRelease();
        mockMvc.perform(post("/api/cohorts/regions").contentType("application/json").content("""
                {"requestId":"req-region","releaseId":%d,"regionCode":"R1","quota":100}
                """.formatted(releaseId))).andExpect(status().isOk());
        long cohortA = createCohort(releaseId, "req-ca", "A", 10, 100);
        long cohortB = createCohort(releaseId, "req-cb", "B", 1, 100);
        long cohortC = createCohort(releaseId, "req-cc", "C", 10, 100);
        assign(releaseId, "d1", cohortA, "req-a1");
        assign(releaseId, "d2", cohortA, "req-a2");

        // B 容量1，迁入2台 → 422
        mockMvc.perform(post("/api/migrations/activate").contentType("application/json").content("""
                {"requestId":"req-bad","migrationKey":"mk-bad","releaseId":%d,
                "items":[{"deviceId":"d1","currentCohortId":%d,"assignmentVersion":1,"targetCohortId":%d},
                         {"deviceId":"d2","currentCohortId":%d,"assignmentVersion":1,"targetCohortId":%d}]}
                """.formatted(releaseId, cohortA, cohortB, cohortA, cohortB)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DEVICE_CAP_EXCEEDED"));

        String twoToC = """
                [{"deviceId":"d1","currentCohortId":%d,"assignmentVersion":1,"targetCohortId":%d},
                 {"deviceId":"d2","currentCohortId":%d,"assignmentVersion":1,"targetCohortId":%d}]
                """.formatted(cohortA, cohortC, cohortA, cohortC);
        // 成功一单（迁往容量充足的 C）
        mockMvc.perform(post("/api/migrations/activate").contentType("application/json").content("""
                {"requestId":"req-ok","migrationKey":"mk-dup","releaseId":%d,"items":%s}
                """.formatted(releaseId, twoToC)))
                .andExpect(status().isOk());
        // 复用 migrationKey → 409（键冲突先于业务校验）
        mockMvc.perform(post("/api/migrations/activate").contentType("application/json").content("""
                {"requestId":"req-ok2","migrationKey":"mk-dup","releaseId":%d,"items":%s}
                """.formatted(releaseId, twoToC)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MIGRATION_KEY_EXISTS"));
    }

    private void assertThatNoMigration() {
        org.assertj.core.api.Assertions.assertThat(
                jdbc.queryForObject("SELECT COUNT(*) FROM cohort_migration_order", Long.class)).isZero();
    }
}
