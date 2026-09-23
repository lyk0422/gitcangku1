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
 * 队列迁移单 API 测试：批量迁移主流程、配额回滚、迟到回执代次隔离、
 * 幂等重放与各类 409/422 失败分支（H2 内存库，MODE=MySQL）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class CampaignMigrationApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanUp() {
        jdbc.update("DELETE FROM cohort_receipt");
        jdbc.update("DELETE FROM migration_item");
        jdbc.update("DELETE FROM migration_order");
        jdbc.update("DELETE FROM dispatch_command");
        jdbc.update("DELETE FROM device_assignment");
        jdbc.update("DELETE FROM cohort");
        jdbc.update("DELETE FROM campaign");
        jdbc.update("DELETE FROM rollout_task");
        jdbc.update("DELETE FROM release_pause_record");
        jdbc.update("DELETE FROM release_resume_record");
        jdbc.update("DELETE FROM release_order");
        jdbc.update("DELETE FROM device");
        jdbc.update("DELETE FROM idempotency_record");
    }

    private long idOf(MvcResult result, String path) throws Exception {
        Number value = com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), path);
        return value.longValue();
    }

    private void registerDevice(String requestId, String deviceId) throws Exception {
        mockMvc.perform(post("/api/devices").contentType("application/json").content("""
                {"requestId":"%s","deviceId":"%s","model":"m1","currentVersion":"1.0.0","bucketNo":1}
                """.formatted(requestId, deviceId))).andExpect(status().isOk());
    }

    private long createCampaign(String requestId, String name) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/campaigns").contentType("application/json")
                        .content("""
                                {"requestId":"%s","name":"%s"}
                                """.formatted(requestId, name)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andReturn();
        return idOf(result, "$.campaignId");
    }

    private long createCohort(String requestId, long campaignId, String code, String firmware,
                              int regionQuota, int deviceCap, int grayPercent) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/campaigns/" + campaignId + "/cohorts")
                        .contentType("application/json").content("""
                                {"requestId":"%s","code":"%s","firmwareVersion":"%s","region":"R1",
                                 "regionQuota":%d,"deviceCap":%d,"grayPercent":%d}
                                """.formatted(requestId, code, firmware, regionQuota, deviceCap, grayPercent)))
                .andExpect(status().isOk())
                .andReturn();
        return idOf(result, "$.cohortId");
    }

    private void enroll(String requestId, long campaignId, String deviceId, long cohortId)
            throws Exception {
        mockMvc.perform(post("/api/campaigns/" + campaignId + "/assignments")
                        .contentType("application/json").content("""
                                {"requestId":"%s","deviceId":"%s","cohortId":%d}
                                """.formatted(requestId, deviceId, cohortId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignmentVersion").value(1))
                .andExpect(jsonPath("$.assignmentGeneration").value(1));
    }

    private String migrationItems(long cohortA, long cohortB, String... deviceIds) {
        StringBuilder sb = new StringBuilder();
        for (String deviceId : deviceIds) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append("""
                    {"deviceId":"%s","currentCohortId":%d,"assignmentVersion":1,"targetCohortId":%d}
                    """.formatted(deviceId, cohortA, cohortB));
        }
        return sb.toString();
    }

    @Test
    void 主流程_批量迁移_代次递增_指令废弃与签发_查询只读证据() throws Exception {
        registerDevice("r-d1", "d1");
        registerDevice("r-d2", "d2");
        long campaignId = createCampaign("r-c1", "camp-1");
        long cohortA = createCohort("r-k1", campaignId, "A", "2.0.0", 10, 10, 100);
        long cohortB = createCohort("r-k2", campaignId, "B", "2.0.0", 10, 10, 100);
        enroll("r-e1", campaignId, "d1", cohortA);
        enroll("r-e2", campaignId, "d2", cohortA);

        mockMvc.perform(post("/api/campaigns/" + campaignId + "/migrations")
                        .contentType("application/json").content("""
                                {"requestId":"r-m1","migrationKey":"mk-1","items":[%s]}
                                """.formatted(migrationItems(cohortA, cohortB, "d1", "d2"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.migrationKey").value("mk-1"))
                .andExpect(jsonPath("$.status").value("ACTIVATED"))
                .andExpect(jsonPath("$.deviceCount").value(2))
                .andExpect(jsonPath("$.items[0].deviceId").value("d1"))
                .andExpect(jsonPath("$.items[0].fromCohortId").value(cohortA))
                .andExpect(jsonPath("$.items[0].toCohortId").value(cohortB))
                .andExpect(jsonPath("$.items[0].fromGeneration").value(1))
                .andExpect(jsonPath("$.items[0].toGeneration").value(2))
                .andExpect(jsonPath("$.items[0].supersededCommandId").isNumber())
                .andExpect(jsonPath("$.items[0].newCommandId").isNumber())
                .andExpect(jsonPath("$.lateReceipts.length()").value(0));

        mockMvc.perform(get("/api/campaigns/" + campaignId + "/assignments/d1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cohortId").value(cohortB))
                .andExpect(jsonPath("$.assignmentVersion").value(2))
                .andExpect(jsonPath("$.assignmentGeneration").value(2));

        mockMvc.perform(get("/api/campaigns/" + campaignId + "/cohorts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].deviceCount").value(0))
                .andExpect(jsonPath("$[1].deviceCount").value(2));

        Integer pending = jdbc.queryForObject(
                "SELECT COUNT(*) FROM dispatch_command WHERE device_id = 'd1' AND status = 'PENDING'"
                        + " AND generation = 2", Integer.class);
        Integer superseded = jdbc.queryForObject(
                "SELECT COUNT(*) FROM dispatch_command WHERE device_id = 'd1'"
                        + " AND status = 'SUPERSEDED' AND generation = 1", Integer.class);
        org.assertj.core.api.Assertions.assertThat(pending).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(superseded).isEqualTo(1);

        mockMvc.perform(get("/api/campaigns/" + campaignId + "/migrations/mk-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.migrationKey").value("mk-1"))
                .andExpect(jsonPath("$.items.length()").value(2));
    }

    @Test
    void 预览_按完整后态计算且不写数据() throws Exception {
        registerDevice("r-d1", "d1");
        registerDevice("r-d2", "d2");
        long campaignId = createCampaign("r-c1", "camp-1");
        long cohortA = createCohort("r-k1", campaignId, "A", "2.0.0", 10, 10, 100);
        long cohortB = createCohort("r-k2", campaignId, "B", "2.0.0", 10, 10, 100);
        enroll("r-e1", campaignId, "d1", cohortA);
        enroll("r-e2", campaignId, "d2", cohortA);

        mockMvc.perform(post("/api/campaigns/" + campaignId + "/migrations/preview")
                        .contentType("application/json").content("""
                                {"items":[%s]}
                                """.formatted(migrationItems(cohortA, cohortB, "d1", "d2"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].currentGeneration").value(1))
                .andExpect(jsonPath("$.items[0].newGeneration").value(2))
                .andExpect(jsonPath("$.cohorts[0].code").value("A"))
                .andExpect(jsonPath("$.cohorts[0].currentSize").value(2))
                .andExpect(jsonPath("$.cohorts[0].postSize").value(0))
                .andExpect(jsonPath("$.cohorts[1].code").value("B"))
                .andExpect(jsonPath("$.cohorts[1].postSize").value(2))
                .andExpect(jsonPath("$.cohorts[1].totalAssigned").value(2));

        mockMvc.perform(get("/api/campaigns/" + campaignId + "/assignments/d1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cohortId").value(cohortA))
                .andExpect(jsonPath("$.assignmentGeneration").value(1));
        Integer migrations = jdbc.queryForObject("SELECT COUNT(*) FROM migration_order",
                Integer.class);
        org.assertj.core.api.Assertions.assertThat(migrations).isZero();
    }

    @Test
    void 迟到回执_旧代次仅存档为LATE_新代次只结算一次() throws Exception {
        registerDevice("r-d1", "d1");
        registerDevice("r-d2", "d2");
        long campaignId = createCampaign("r-c1", "camp-1");
        long cohortA = createCohort("r-k1", campaignId, "A", "2.0.0", 10, 10, 100);
        long cohortB = createCohort("r-k2", campaignId, "B", "2.0.0", 10, 10, 100);
        enroll("r-e1", campaignId, "d1", cohortA);
        enroll("r-e2", campaignId, "d2", cohortA);
        mockMvc.perform(post("/api/campaigns/" + campaignId + "/migrations")
                        .contentType("application/json").content("""
                                {"requestId":"r-m1","migrationKey":"mk-1","items":[%s]}
                                """.formatted(migrationItems(cohortA, cohortB, "d1", "d2"))))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/campaigns/" + campaignId + "/receipts")
                        .contentType("application/json").content("""
                                {"requestId":"r-rc1","deviceId":"d1","generation":1,"result":"SUCCESS"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.disposition").value("LATE"))
                .andExpect(jsonPath("$.cohortId").value(cohortA));

        mockMvc.perform(get("/api/campaigns/" + campaignId + "/cohorts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].successCount").value(0))
                .andExpect(jsonPath("$[0].roundSuccess").value(0))
                .andExpect(jsonPath("$[1].successCount").value(0));

        mockMvc.perform(post("/api/campaigns/" + campaignId + "/receipts")
                        .contentType("application/json").content("""
                                {"requestId":"r-rc2","deviceId":"d1","generation":2,"result":"SUCCESS"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.disposition").value("SETTLED"))
                .andExpect(jsonPath("$.cohortId").value(cohortB));

        mockMvc.perform(post("/api/campaigns/" + campaignId + "/receipts")
                        .contentType("application/json").content("""
                                {"requestId":"r-rc3","deviceId":"d1","generation":2,"result":"SUCCESS"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.disposition").value("SETTLED"));

        mockMvc.perform(get("/api/campaigns/" + campaignId + "/cohorts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[1].successCount").value(1))
                .andExpect(jsonPath("$[1].roundSuccess").value(1));

        mockMvc.perform(post("/api/campaigns/" + campaignId + "/receipts")
                        .contentType("application/json").content("""
                                {"requestId":"r-rc4","deviceId":"d1","generation":2,"result":"FAILED"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RECEIPT_RESULT_CONFLICT"));

        mockMvc.perform(get("/api/campaigns/" + campaignId + "/migrations/mk-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lateReceipts.length()").value(1))
                .andExpect(jsonPath("$.lateReceipts[0].deviceId").value("d1"))
                .andExpect(jsonPath("$.lateReceipts[0].generation").value(1))
                .andExpect(jsonPath("$.lateReceipts[0].result").value("SUCCESS"));
    }

    @Test
    void 迁移前已结算回执_按旧队列口径保持_迁移后重复上报不改统计() throws Exception {
        registerDevice("r-d1", "d1");
        registerDevice("r-d2", "d2");
        long campaignId = createCampaign("r-c1", "camp-1");
        long cohortA = createCohort("r-k1", campaignId, "A", "2.0.0", 10, 10, 100);
        long cohortB = createCohort("r-k2", campaignId, "B", "2.0.0", 10, 10, 100);
        enroll("r-e1", campaignId, "d1", cohortA);
        enroll("r-e2", campaignId, "d2", cohortA);

        mockMvc.perform(post("/api/campaigns/" + campaignId + "/receipts")
                        .contentType("application/json").content("""
                                {"requestId":"r-rc1","deviceId":"d1","generation":1,"result":"FAILED"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.disposition").value("SETTLED"));

        mockMvc.perform(post("/api/campaigns/" + campaignId + "/migrations")
                        .contentType("application/json").content("""
                                {"requestId":"r-m1","migrationKey":"mk-1","items":[%s]}
                                """.formatted(migrationItems(cohortA, cohortB, "d1", "d2"))))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/campaigns/" + campaignId + "/receipts")
                        .contentType("application/json").content("""
                                {"requestId":"r-rc2","deviceId":"d1","generation":1,"result":"FAILED"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.disposition").value("SETTLED"));

        mockMvc.perform(get("/api/campaigns/" + campaignId + "/cohorts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].roundFailed").value(1))
                .andExpect(jsonPath("$[1].roundFailed").value(0))
                .andExpect(jsonPath("$[1].successCount").value(0));
    }

    @Test
    void 已确认安装成功的设备不得迁移_422() throws Exception {
        registerDevice("r-d1", "d1");
        registerDevice("r-d2", "d2");
        long campaignId = createCampaign("r-c1", "camp-1");
        long cohortA = createCohort("r-k1", campaignId, "A", "2.0.0", 10, 10, 100);
        long cohortB = createCohort("r-k2", campaignId, "B", "2.0.0", 10, 10, 100);
        enroll("r-e1", campaignId, "d1", cohortA);
        enroll("r-e2", campaignId, "d2", cohortA);
        mockMvc.perform(post("/api/campaigns/" + campaignId + "/receipts")
                        .contentType("application/json").content("""
                                {"requestId":"r-rc1","deviceId":"d1","generation":1,"result":"SUCCESS"}
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/campaigns/" + campaignId + "/migrations")
                        .contentType("application/json").content("""
                                {"requestId":"r-m1","migrationKey":"mk-1","items":[%s]}
                                """.formatted(migrationItems(cohortA, cohortB, "d1", "d2"))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DEVICE_ALREADY_INSTALLED"));

        mockMvc.perform(get("/api/campaigns/" + campaignId + "/assignments/d2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cohortId").value(cohortA))
                .andExpect(jsonPath("$.assignmentGeneration").value(1));
    }

    @Test
    void 目标队列固件版本不一致_422() throws Exception {
        registerDevice("r-d1", "d1");
        registerDevice("r-d2", "d2");
        long campaignId = createCampaign("r-c1", "camp-1");
        long cohortA = createCohort("r-k1", campaignId, "A", "2.0.0", 10, 10, 100);
        long cohortC = createCohort("r-k2", campaignId, "C", "3.0.0", 10, 10, 100);
        enroll("r-e1", campaignId, "d1", cohortA);
        enroll("r-e2", campaignId, "d2", cohortA);

        mockMvc.perform(post("/api/campaigns/" + campaignId + "/migrations")
                        .contentType("application/json").content("""
                                {"requestId":"r-m1","migrationKey":"mk-1","items":[%s]}
                                """.formatted(migrationItems(cohortA, cohortC, "d1", "d2"))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("FIRMWARE_VERSION_MISMATCH"));
    }

    @Test
    void 完整后态越界_422且整单回滚不废弃指令() throws Exception {
        registerDevice("r-d1", "d1");
        registerDevice("r-d2", "d2");
        long campaignId = createCampaign("r-c1", "camp-1");
        long cohortA = createCohort("r-k1", campaignId, "A", "2.0.0", 10, 10, 100);
        long cohortB = createCohort("r-k2", campaignId, "B", "2.0.0", 10, 1, 100);
        enroll("r-e1", campaignId, "d1", cohortA);
        enroll("r-e2", campaignId, "d2", cohortA);

        mockMvc.perform(post("/api/campaigns/" + campaignId + "/migrations")
                        .contentType("application/json").content("""
                                {"requestId":"r-m1","migrationKey":"mk-1","items":[%s]}
                                """.formatted(migrationItems(cohortA, cohortB, "d1", "d2"))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DEVICE_CAP_EXCEEDED"));

        mockMvc.perform(get("/api/campaigns/" + campaignId + "/cohorts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].deviceCount").value(2))
                .andExpect(jsonPath("$[1].deviceCount").value(0));
        mockMvc.perform(get("/api/campaigns/" + campaignId + "/assignments/d1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cohortId").value(cohortA))
                .andExpect(jsonPath("$.assignmentGeneration").value(1));
        Integer pending = jdbc.queryForObject(
                "SELECT COUNT(*) FROM dispatch_command WHERE status = 'PENDING' AND generation = 1",
                Integer.class);
        Integer superseded = jdbc.queryForObject(
                "SELECT COUNT(*) FROM dispatch_command WHERE status = 'SUPERSEDED'", Integer.class);
        Integer migrations = jdbc.queryForObject("SELECT COUNT(*) FROM migration_order",
                Integer.class);
        org.assertj.core.api.Assertions.assertThat(pending).isEqualTo(2);
        org.assertj.core.api.Assertions.assertThat(superseded).isZero();
        org.assertj.core.api.Assertions.assertThat(migrations).isZero();
    }

    @Test
    void 设备分配版本变化_整单409() throws Exception {
        registerDevice("r-d1", "d1");
        registerDevice("r-d2", "d2");
        long campaignId = createCampaign("r-c1", "camp-1");
        long cohortA = createCohort("r-k1", campaignId, "A", "2.0.0", 10, 10, 100);
        long cohortB = createCohort("r-k2", campaignId, "B", "2.0.0", 10, 10, 100);
        enroll("r-e1", campaignId, "d1", cohortA);
        enroll("r-e2", campaignId, "d2", cohortA);

        mockMvc.perform(post("/api/campaigns/" + campaignId + "/migrations")
                        .contentType("application/json").content("""
                                {"requestId":"r-m1","migrationKey":"mk-1","items":[
                                 {"deviceId":"d1","currentCohortId":%d,"assignmentVersion":2,"targetCohortId":%d},
                                 {"deviceId":"d2","currentCohortId":%d,"assignmentVersion":1,"targetCohortId":%d}]}
                                """.formatted(cohortA, cohortB, cohortA, cohortB)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ASSIGNMENT_VERSION_CONFLICT"));

        mockMvc.perform(post("/api/campaigns/" + campaignId + "/migrations")
                        .contentType("application/json").content("""
                                {"requestId":"r-m2","migrationKey":"mk-2","items":[
                                 {"deviceId":"d1","currentCohortId":%d,"assignmentVersion":1,"targetCohortId":%d},
                                 {"deviceId":"d2","currentCohortId":%d,"assignmentVersion":1,"targetCohortId":%d}]}
                                """.formatted(cohortB, cohortB, cohortA, cohortB)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("COHORT_MISMATCH"));
    }

    @Test
    void 活动结束后迁移_409() throws Exception {
        registerDevice("r-d1", "d1");
        registerDevice("r-d2", "d2");
        long campaignId = createCampaign("r-c1", "camp-1");
        long cohortA = createCohort("r-k1", campaignId, "A", "2.0.0", 10, 10, 100);
        long cohortB = createCohort("r-k2", campaignId, "B", "2.0.0", 10, 10, 100);
        enroll("r-e1", campaignId, "d1", cohortA);
        enroll("r-e2", campaignId, "d2", cohortA);

        mockMvc.perform(post("/api/campaigns/" + campaignId + "/end")
                        .contentType("application/json").content("{\"requestId\":\"r-end\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ENDED"));

        mockMvc.perform(post("/api/campaigns/" + campaignId + "/migrations")
                        .contentType("application/json").content("""
                                {"requestId":"r-m1","migrationKey":"mk-1","items":[%s]}
                                """.formatted(migrationItems(cohortA, cohortB, "d1", "d2"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CAMPAIGN_ENDED"));
    }

    @Test
    void 幂等_同参换序重放首次快照_异参409_migrationKey唯一() throws Exception {
        registerDevice("r-d1", "d1");
        registerDevice("r-d2", "d2");
        long campaignId = createCampaign("r-c1", "camp-1");
        long cohortA = createCohort("r-k1", campaignId, "A", "2.0.0", 10, 10, 100);
        long cohortB = createCohort("r-k2", campaignId, "B", "2.0.0", 10, 10, 100);
        enroll("r-e1", campaignId, "d1", cohortA);
        enroll("r-e2", campaignId, "d2", cohortA);

        mockMvc.perform(post("/api/campaigns/" + campaignId + "/migrations")
                        .contentType("application/json").content("""
                                {"requestId":"r-m1","migrationKey":"mk-1","items":[
                                 {"deviceId":"d1","currentCohortId":%d,"assignmentVersion":1,"targetCohortId":%d},
                                 {"deviceId":"d2","currentCohortId":%d,"assignmentVersion":1,"targetCohortId":%d}]}
                                """.formatted(cohortA, cohortB, cohortA, cohortB)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.migrationKey").value("mk-1"));

        mockMvc.perform(post("/api/campaigns/" + campaignId + "/migrations")
                        .contentType("application/json").content("""
                                {"requestId":"r-m1","migrationKey":"mk-1","items":[
                                 {"deviceId":"d2","currentCohortId":%d,"assignmentVersion":1,"targetCohortId":%d},
                                 {"deviceId":"d1","currentCohortId":%d,"assignmentVersion":1,"targetCohortId":%d}]}
                                """.formatted(cohortA, cohortB, cohortA, cohortB)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.migrationKey").value("mk-1"))
                .andExpect(jsonPath("$.deviceCount").value(2));

        Integer migrations = jdbc.queryForObject("SELECT COUNT(*) FROM migration_order",
                Integer.class);
        org.assertj.core.api.Assertions.assertThat(migrations).isEqualTo(1);

        mockMvc.perform(post("/api/campaigns/" + campaignId + "/migrations")
                        .contentType("application/json").content("""
                                {"requestId":"r-m1","migrationKey":"mk-9","items":[%s]}
                                """.formatted(migrationItems(cohortA, cohortB, "d1", "d2"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));

        mockMvc.perform(post("/api/campaigns/" + campaignId + "/migrations")
                        .contentType("application/json").content("""
                                {"requestId":"r-m2","migrationKey":"mk-1","items":[%s]}
                                """.formatted(migrationItems(cohortA, cohortB, "d1", "d2"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MIGRATION_KEY_EXISTS"));
    }

    @Test
    void 失败率自动暂停_人工恢复_暂停队列不可作为迁移目标() throws Exception {
        registerDevice("r-d1", "d1");
        registerDevice("r-d2", "d2");
        registerDevice("r-d3", "d3");
        registerDevice("r-d4", "d4");
        long campaignId = createCampaign("r-c1", "camp-1");
        MvcResult cohortPResult = mockMvc.perform(post("/api/campaigns/" + campaignId + "/cohorts")
                        .contentType("application/json").content("""
                                {"requestId":"r-k1","code":"P","firmwareVersion":"2.0.0","region":"R1",
                                 "regionQuota":10,"deviceCap":10,"grayPercent":100,
                                 "sampleFloor":2,"failureThresholdPercent":50}
                                """))
                .andExpect(status().isOk())
                .andReturn();
        long cohortP = idOf(cohortPResult, "$.cohortId");
        long cohortA = createCohort("r-k2", campaignId, "A", "2.0.0", 10, 10, 100);
        enroll("r-e1", campaignId, "d1", cohortP);
        enroll("r-e2", campaignId, "d2", cohortP);
        enroll("r-e3", campaignId, "d3", cohortA);
        enroll("r-e4", campaignId, "d4", cohortA);

        mockMvc.perform(post("/api/campaigns/" + campaignId + "/receipts")
                        .contentType("application/json").content("""
                                {"requestId":"r-rc1","deviceId":"d1","generation":1,"result":"FAILED"}
                                """))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/campaigns/" + campaignId + "/receipts")
                        .contentType("application/json").content("""
                                {"requestId":"r-rc2","deviceId":"d2","generation":1,"result":"FAILED"}
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/campaigns/" + campaignId + "/cohorts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("PAUSED"))
                .andExpect(jsonPath("$[0].roundFailed").value(2));

        mockMvc.perform(post("/api/campaigns/" + campaignId + "/migrations")
                        .contentType("application/json").content("""
                                {"requestId":"r-m1","migrationKey":"mk-1","items":[%s]}
                                """.formatted(migrationItems(cohortA, cohortP, "d3", "d4"))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("TARGET_COHORT_NOT_APPLICABLE"));

        mockMvc.perform(post("/api/campaigns/" + campaignId + "/cohorts/" + cohortP + "/resume")
                        .contentType("application/json").content("{\"requestId\":\"r-resume\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.monitorRound").value(2))
                .andExpect(jsonPath("$.roundFailed").value(0));
    }

    @Test
    void 设备项数量与重复校验() throws Exception {
        registerDevice("r-d1", "d1");
        long campaignId = createCampaign("r-c1", "camp-1");
        long cohortA = createCohort("r-k1", campaignId, "A", "2.0.0", 10, 10, 100);
        long cohortB = createCohort("r-k2", campaignId, "B", "2.0.0", 10, 10, 100);
        enroll("r-e1", campaignId, "d1", cohortA);

        mockMvc.perform(post("/api/campaigns/" + campaignId + "/migrations")
                        .contentType("application/json").content("""
                                {"requestId":"r-m1","migrationKey":"mk-1","items":[
                                 {"deviceId":"d1","currentCohortId":%d,"assignmentVersion":1,"targetCohortId":%d}]}
                                """.formatted(cohortA, cohortB)))
                .andExpect(status().isBadRequest());

        registerDevice("r-d2", "d2");
        enroll("r-e2", campaignId, "d2", cohortA);
        mockMvc.perform(post("/api/campaigns/" + campaignId + "/migrations")
                        .contentType("application/json").content("""
                                {"requestId":"r-m2","migrationKey":"mk-2","items":[
                                 {"deviceId":"d1","currentCohortId":%d,"assignmentVersion":1,"targetCohortId":%d},
                                 {"deviceId":"d1","currentCohortId":%d,"assignmentVersion":1,"targetCohortId":%d}]}
                                """.formatted(cohortA, cohortB, cohortA, cohortB)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DUPLICATE_DEVICE_ITEM"));
    }
}
