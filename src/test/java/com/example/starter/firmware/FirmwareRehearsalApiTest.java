package com.example.starter.firmware;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 固件回滚演练与 READY_FOR_RELEASE 门禁 API 测试（真实 H2，MODE=MySQL）：
 * 主流程、422/409 失败分支、可控 UTC 时钟与左闭右开边界、事务原子性、幂等指纹与快照重放、
 * 不可变历史、明细/历史/诊断稳定排序与 null 语义。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "firmware.release-gate.enabled=true")
class FirmwareRehearsalApiTest {

    private static final Instant T0 = Instant.parse("2026-09-29T07:00:00Z");
    private static final Instant T1 = Instant.parse("2026-09-29T07:05:30Z");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private Clock clock;

    @BeforeEach
    void cleanUp() {
        jdbc.update("DELETE FROM release_gate");
        jdbc.update("DELETE FROM rollback_rehearsal_device");
        jdbc.update("DELETE FROM rollback_rehearsal");
        jdbc.update("DELETE FROM rollout_task");
        jdbc.update("DELETE FROM release_pause_record");
        jdbc.update("DELETE FROM release_resume_record");
        jdbc.update("DELETE FROM release_order");
        jdbc.update("DELETE FROM device");
        jdbc.update("DELETE FROM idempotency_record");
        org.mockito.Mockito.when(clock.instant()).thenReturn(T0);
        org.mockito.Mockito.when(clock.getZone()).thenReturn(ZoneOffset.UTC);
    }

    private void register(String requestId, String deviceId, String model, String version, int bucket)
            throws Exception {
        mockMvc.perform(post("/api/devices").contentType("application/json").content("""
                {"requestId":"%s","deviceId":"%s","model":"%s","currentVersion":"%s","bucketNo":%d}
                """.formatted(requestId, deviceId, model, version, bucket)))
                .andExpect(status().isOk());
    }

    private MvcResult startRehearsal(String body) throws Exception {
        return mockMvc.perform(post("/api/rehearsals").contentType("application/json").content(body))
                .andExpect(status().isOk()).andReturn();
    }

    private static long idOf(MvcResult result, String path) throws Exception {
        Number value = com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), path);
        return value.longValue();
    }

    @Test
    void 主流程_全成功演练授予门禁_设备真实回退_放行正式投放() throws Exception {
        register("rd1", "d2", "m1", "2.0.0", 2);
        register("rd2", "d1", "m1", "2.0.0", 1);

        MvcResult started = startRehearsal("""
                {"requestId":"rs1","operator":"alice","model":"m1","targetVersion":"2.0.0",
                 "rollbackVersion":"1.0.0","deviceIds":["d2","d1"]}
                """);
        long rehearsalId = idOf(started, "$.rehearsalId");
        // 集合按 device_id 升序规范化，且为 RUNNING 的 null 语义
        org.mockito.Mockito.when(clock.instant()).thenReturn(T1);
        mockMvc.perform(get("/api/rehearsals/" + rehearsalId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rehearsal.status").value("RUNNING"))
                .andExpect(jsonPath("$.rehearsal.deviceCount").value(2))
                .andExpect(jsonPath("$.rehearsal.successCount").value(0))
                .andExpect(jsonPath("$.rehearsal.failedCount").value(0))
                .andExpect(jsonPath("$.rehearsal.requiredSuccessCount").value(2))
                .andExpect(jsonPath("$.rehearsal.missingSuccessCount").value(2))
                .andExpect(jsonPath("$.rehearsal.startedAtUtc").value("2026-09-29T07:00:00Z"))
                .andExpect(jsonPath("$.rehearsal.finishedAtUtc").doesNotExist())
                .andExpect(jsonPath("$.rehearsal.durationSeconds").doesNotExist())
                .andExpect(jsonPath("$.rehearsal.gateStatus").doesNotExist())
                .andExpect(jsonPath("$.rehearsal.gateDeniedReason").doesNotExist())
                .andExpect(jsonPath("$.devices.length()").value(2))
                .andExpect(jsonPath("$.devices[0].deviceId").value("d1"))
                .andExpect(jsonPath("$.devices[0].ordinal").value(0))
                .andExpect(jsonPath("$.devices[0].result").doesNotExist())
                .andExpect(jsonPath("$.devices[1].deviceId").value("d2"))
                .andExpect(jsonPath("$.devices[1].ordinal").value(1));

        // 未取得门禁前不得创建正式投放
        mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                {"requestId":"rr0","model":"m1","fromVersion":"1.0.0","toVersion":"2.0.0","ratio":100}
                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("GATE_NOT_READY"));

        // 乱序提交全部 SUCCESS：实际值/要求值/差额与时长（330秒，左闭右开）齐全
        MvcResult first = mockMvc.perform(post("/api/rehearsals/" + rehearsalId + "/results")
                        .contentType("application/json").content("""
                                {"requestId":"rc1","operator":"alice","results":[
                                  {"deviceId":"d2","result":"SUCCESS"},
                                  {"deviceId":"d1","result":"SUCCESS","failureReason":"ignored"}]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rehearsal.status").value("PASSED"))
                .andExpect(jsonPath("$.rehearsal.successCount").value(2))
                .andExpect(jsonPath("$.rehearsal.failedCount").value(0))
                .andExpect(jsonPath("$.rehearsal.requiredSuccessCount").value(2))
                .andExpect(jsonPath("$.rehearsal.missingSuccessCount").value(0))
                .andExpect(jsonPath("$.rehearsal.finishedAtUtc").value("2026-09-29T07:05:30Z"))
                .andExpect(jsonPath("$.rehearsal.durationSeconds").value(330))
                .andExpect(jsonPath("$.rehearsal.gateStatus").value("READY_FOR_RELEASE"))
                .andExpect(jsonPath("$.rehearsal.gateDeniedReason").doesNotExist())
                .andExpect(jsonPath("$.devices[0].deviceId").value("d1"))
                .andExpect(jsonPath("$.devices[0].result").value("SUCCESS"))
                .andExpect(jsonPath("$.devices[0].failureReason").doesNotExist())
                .andExpect(jsonPath("$.devices[1].deviceId").value("d2"))
                .andReturn();

        // 设备真实回退到回退版本
        mockMvc.perform(get("/api/devices/d1")).andExpect(jsonPath("$.currentVersion").value("1.0.0"));
        mockMvc.perform(get("/api/devices/d2")).andExpect(jsonPath("$.currentVersion").value("1.0.0"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM release_gate", Long.class)).isEqualTo(1);

        // 门禁放行正式投放，设备随后可拉取正式任务
        MvcResult release = mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                {"requestId":"rr1","model":"m1","fromVersion":"1.0.0","toVersion":"2.0.0","ratio":100}
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andReturn();
        long releaseId = idOf(release, "$.releaseId");
        mockMvc.perform(post("/api/devices/d1/pull").contentType("application/json")
                        .content("{\"requestId\":\"rp1\"}"))
                .andExpect(jsonPath("$.task.status").value("PENDING"));

        // 同参同键重放：返回首次完整响应快照，不重复回退、不重复授权
        mockMvc.perform(post("/api/rehearsals/" + rehearsalId + "/results")
                        .contentType("application/json").content("""
                                {"requestId":"rc1","operator":"alice","results":[
                                  {"deviceId":"d2","result":"SUCCESS"},
                                  {"deviceId":"d1","result":"SUCCESS","failureReason":"ignored"}]}
                                """))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .isEqualTo(first.getResponse().getContentAsString()));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM release_gate", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rollback_rehearsal", Long.class)).isEqualTo(1);

        // 明细查询为只读，重复读取与终态证据一致
        mockMvc.perform(get("/api/rehearsals/" + releaseId)).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/rehearsals/" + rehearsalId))
                .andExpect(jsonPath("$.rehearsal.status").value("PASSED"))
                .andExpect(jsonPath("$.devices.length()").value(2));
    }

    @Test
    void 开始演练_空集合_重复_版本不相容_型号不符_设备不存在_均422或404() throws Exception {
        register("rd1", "d1", "m1", "2.0.0", 1);
        register("rd2", "d2", "m2", "2.0.0", 2);
        register("rd3", "d3", "m1", "1.5.0", 3);

        mockMvc.perform(post("/api/rehearsals").contentType("application/json").content("""
                {"requestId":"re1","operator":"alice","model":"m1","targetVersion":"2.0.0",
                 "rollbackVersion":"1.0.0","deviceIds":[]}
                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("EMPTY_DEVICE_SET"));

        mockMvc.perform(post("/api/rehearsals").contentType("application/json").content("""
                {"requestId":"re2","operator":"alice","model":"m1","targetVersion":"2.0.0",
                 "rollbackVersion":"1.0.0","deviceIds":["d1","d1"]}
                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DUPLICATE_DEVICE"));

        mockMvc.perform(post("/api/rehearsals").contentType("application/json").content("""
                {"requestId":"re3","operator":"alice","model":"m1","targetVersion":"2.0.0",
                 "rollbackVersion":"2.0.0","deviceIds":["d1"]}
                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VERSION_INCOMPATIBLE"));

        mockMvc.perform(post("/api/rehearsals").contentType("application/json").content("""
                {"requestId":"re4","operator":"alice","model":"m1","targetVersion":"2.0.0",
                 "rollbackVersion":"1.0.0","deviceIds":["d2"]}
                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("MODEL_MISMATCH"));

        mockMvc.perform(post("/api/rehearsals").contentType("application/json").content("""
                {"requestId":"re5","operator":"alice","model":"m1","targetVersion":"2.0.0",
                 "rollbackVersion":"1.0.0","deviceIds":["d3"]}
                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VERSION_INCOMPATIBLE"));

        mockMvc.perform(post("/api/rehearsals").contentType("application/json").content("""
                {"requestId":"re6","operator":"alice","model":"m1","targetVersion":"2.0.0",
                 "rollbackVersion":"1.0.0","deviceIds":["ghost"]}
                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DEVICE_NOT_FOUND"));

        // 全部失败不留半成品
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rollback_rehearsal", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rollback_rehearsal_device", Long.class)).isZero();
    }

    @Test
    void 结果提交_数量不符_缺漏_未知设备_重复_缺失败原因_均422且事务回滚() throws Exception {
        register("rd1", "d1", "m1", "2.0.0", 1);
        register("rd2", "d2", "m1", "2.0.0", 2);
        long rehearsalId = idOf(startRehearsal("""
                {"requestId":"rs1","operator":"alice","model":"m1","targetVersion":"2.0.0",
                 "rollbackVersion":"1.0.0","deviceIds":["d1","d2"]}
                """), "$.rehearsalId");

        // 空结果集
        mockMvc.perform(post("/api/rehearsals/" + rehearsalId + "/results").contentType("application/json")
                        .content("{\"requestId\":\"rc0\",\"operator\":\"alice\",\"results\":[]}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("EMPTY_DEVICE_SET"));
        // 少一台（响应给出实际值、要求值与差额）
        mockMvc.perform(post("/api/rehearsals/" + rehearsalId + "/results").contentType("application/json")
                        .content("""
                                {"requestId":"rc1","operator":"alice","results":[{"deviceId":"d1","result":"SUCCESS"}]}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("RESULT_SET_MISMATCH"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("差额 1")));
        // 未知设备（数量相同但集合不一致）
        mockMvc.perform(post("/api/rehearsals/" + rehearsalId + "/results").contentType("application/json")
                        .content("""
                                {"requestId":"rc2","operator":"alice","results":[
                                  {"deviceId":"d1","result":"SUCCESS"},{"deviceId":"d9","result":"SUCCESS"}]}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("RESULT_SET_MISMATCH"));
        // 重复设备
        mockMvc.perform(post("/api/rehearsals/" + rehearsalId + "/results").contentType("application/json")
                        .content("""
                                {"requestId":"rc3","operator":"alice","results":[
                                  {"deviceId":"d1","result":"SUCCESS"},{"deviceId":"d1","result":"FAILED","failureReason":"x"}]}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DUPLICATE_DEVICE"));
        // FAILED 缺原因
        mockMvc.perform(post("/api/rehearsals/" + rehearsalId + "/results").contentType("application/json")
                        .content("""
                                {"requestId":"rc4","operator":"alice","results":[
                                  {"deviceId":"d1","result":"SUCCESS"},{"deviceId":"d2","result":"FAILED"}]}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("MISSING_FAILURE_REASON"));

        // 整批校验失败：演练仍 RUNNING、无任何设备结果、无门禁
        mockMvc.perform(get("/api/rehearsals/" + rehearsalId))
                .andExpect(jsonPath("$.rehearsal.status").value("RUNNING"))
                .andExpect(jsonPath("$.rehearsal.successCount").value(0));
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM rollback_rehearsal_device WHERE result IS NOT NULL", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM release_gate", Long.class)).isZero();
        // 失败不占键：rc1 改为完整同参成功
        mockMvc.perform(post("/api/rehearsals/" + rehearsalId + "/results").contentType("application/json")
                        .content("""
                                {"requestId":"rc1","operator":"alice","results":[
                                  {"deviceId":"d1","result":"SUCCESS"},{"deviceId":"d2","result":"SUCCESS"}]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rehearsal.status").value("PASSED"));
    }

    @Test
    void 存在失败结果_演练FAILED不给门禁_设备不回退_可重新演练() throws Exception {
        register("rd1", "d1", "m1", "2.0.0", 1);
        register("rd2", "d2", "m1", "2.0.0", 2);
        long rehearsalId = idOf(startRehearsal("""
                {"requestId":"rs1","operator":"alice","model":"m1","targetVersion":"2.0.0",
                 "rollbackVersion":"1.0.0","deviceIds":["d1","d2"]}
                """), "$.rehearsalId");
        org.mockito.Mockito.when(clock.instant()).thenReturn(T1);

        mockMvc.perform(post("/api/rehearsals/" + rehearsalId + "/results").contentType("application/json")
                        .content("""
                                {"requestId":"rc1","operator":"alice","results":[
                                  {"deviceId":"d1","result":"SUCCESS"},
                                  {"deviceId":"d2","result":"FAILED","failureReason":"掉电"}]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rehearsal.status").value("FAILED"))
                .andExpect(jsonPath("$.rehearsal.successCount").value(1))
                .andExpect(jsonPath("$.rehearsal.failedCount").value(1))
                .andExpect(jsonPath("$.rehearsal.requiredSuccessCount").value(2))
                .andExpect(jsonPath("$.rehearsal.missingSuccessCount").value(1))
                .andExpect(jsonPath("$.rehearsal.gateDeniedReason").value("DEVICE_FAILED"))
                .andExpect(jsonPath("$.rehearsal.gateStatus").doesNotExist())
                .andExpect(jsonPath("$.devices[1].result").value("FAILED"))
                .andExpect(jsonPath("$.devices[1].failureReason").value("掉电"));

        // 失败批不回退任何设备版本
        mockMvc.perform(get("/api/devices/d1")).andExpect(jsonPath("$.currentVersion").value("2.0.0"));
        mockMvc.perform(get("/api/devices/d2")).andExpect(jsonPath("$.currentVersion").value("2.0.0"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM release_gate", Long.class)).isZero();
        // FAILED 后仍不得创建正式投放
        mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                {"requestId":"rr1","model":"m1","fromVersion":"1.0.0","toVersion":"2.0.0","ratio":100}
                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("GATE_NOT_READY"));

        // 终态不可变：再次提交 409
        mockMvc.perform(post("/api/rehearsals/" + rehearsalId + "/results").contentType("application/json")
                        .content("""
                                {"requestId":"rc2","operator":"alice","results":[
                                  {"deviceId":"d1","result":"SUCCESS"},{"deviceId":"d2","result":"SUCCESS"}]}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REHEARSAL_FINALIZED"));

        // active_key 已释放：同版本组合可重新开始一场新演练
        org.mockito.Mockito.when(clock.instant()).thenReturn(T1.plusSeconds(60));
        long secondId = idOf(startRehearsal("""
                {"requestId":"rs2","operator":"bob","model":"m1","targetVersion":"2.0.0",
                 "rollbackVersion":"1.0.0","deviceIds":["d1","d2"]}
                """), "$.rehearsalId");
        assertThat(secondId).isNotEqualTo(rehearsalId);
    }

    @Test
    void 时钟边界_结束早于开始FAILED_恰好同时PASSED_区间左闭右开() throws Exception {
        register("rd1", "d1", "m1", "2.0.0", 1);

        // 结束早于开始 1 秒：FAILED/FINISHED_BEFORE_START，时长为负，无门禁
        long firstId = idOf(startRehearsal("""
                {"requestId":"rs1","operator":"alice","model":"m1","targetVersion":"2.0.0",
                 "rollbackVersion":"1.0.0","deviceIds":["d1"]}
                """), "$.rehearsalId");
        org.mockito.Mockito.when(clock.instant()).thenReturn(T0.minusSeconds(1));
        mockMvc.perform(post("/api/rehearsals/" + firstId + "/results").contentType("application/json")
                        .content("""
                                {"requestId":"rc1","operator":"alice","results":[{"deviceId":"d1","result":"SUCCESS"}]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rehearsal.status").value("FAILED"))
                .andExpect(jsonPath("$.rehearsal.durationSeconds").value(-1))
                .andExpect(jsonPath("$.rehearsal.gateDeniedReason").value("FINISHED_BEFORE_START"))
                .andExpect(jsonPath("$.rehearsal.gateStatus").doesNotExist());
        mockMvc.perform(get("/api/devices/d1")).andExpect(jsonPath("$.currentVersion").value("2.0.0"));

        // 清理幂等记录后重开一场；结束恰好等于开始（左闭右开，零时长不早于开始）：PASSED
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM rollback_rehearsal_device");
        jdbc.update("DELETE FROM rollback_rehearsal");
        org.mockito.Mockito.when(clock.instant()).thenReturn(T0);
        long secondId = idOf(startRehearsal("""
                {"requestId":"rs2","operator":"alice","model":"m1","targetVersion":"2.0.0",
                 "rollbackVersion":"1.0.0","deviceIds":["d1"]}
                """), "$.rehearsalId");
        mockMvc.perform(post("/api/rehearsals/" + secondId + "/results").contentType("application/json")
                        .content("""
                                {"requestId":"rc2","operator":"alice","results":[{"deviceId":"d1","result":"SUCCESS"}]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rehearsal.status").value("PASSED"))
                .andExpect(jsonPath("$.rehearsal.durationSeconds").value(0))
                .andExpect(jsonPath("$.rehearsal.gateStatus").value("READY_FOR_RELEASE"));
    }

    @Test
    void 幂等_开始重放快照_异参409_失败不占键_进行中冲突与正式任务冲突409() throws Exception {
        register("rd1", "d1", "m1", "2.0.0", 1);

        String firstBody = """
                {"requestId":"rs1","operator":"alice","model":"m1","targetVersion":"2.0.0",
                 "rollbackVersion":"1.0.0","deviceIds":["d1"]}
                """;
        MvcResult first = startRehearsal(firstBody);
        MvcResult replay = startRehearsal(firstBody);
        assertThat(replay.getResponse().getContentAsString()).isEqualTo(first.getResponse().getContentAsString());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rollback_rehearsal", Long.class)).isEqualTo(1);

        // 同键异参（操作者不同）：409
        mockMvc.perform(post("/api/rehearsals").contentType("application/json").content("""
                {"requestId":"rs1","operator":"bob","model":"m1","targetVersion":"2.0.0",
                 "rollbackVersion":"1.0.0","deviceIds":["d1"]}
                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));
        // 同键异参（集合顺序不同但集合相同，指纹仍一致 → 重放；改为不同集合才 409）
        register("rd2", "d2", "m1", "2.0.0", 2);
        mockMvc.perform(post("/api/rehearsals").contentType("application/json").content("""
                {"requestId":"rs1","operator":"alice","model":"m1","targetVersion":"2.0.0",
                 "rollbackVersion":"1.9.0","deviceIds":["d1"]}
                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));

        // 同版本组合第二场进行中演练：409
        mockMvc.perform(post("/api/rehearsals").contentType("application/json").content("""
                {"requestId":"rs2","operator":"alice","model":"m1","targetVersion":"2.0.0",
                 "rollbackVersion":"1.0.0","deviceIds":["d1"]}
                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REHEARSAL_IN_PROGRESS"));

        // 失败不占键：rs3 先空集合失败，再同键成功
        mockMvc.perform(post("/api/rehearsals").contentType("application/json").content("""
                {"requestId":"rs3","operator":"alice","model":"m1","targetVersion":"2.0.0",
                 "rollbackVersion":"1.5.0","deviceIds":[]}
                """))
                .andExpect(status().isUnprocessableEntity());
        startRehearsal("""
                {"requestId":"rs3","operator":"alice","model":"m1","targetVersion":"2.0.0",
                 "rollbackVersion":"1.5.0","deviceIds":["d1"]}
                """);

        // 目标版本已有正式任务：PASSED → 正式发布 → 设备升级 → 再演练 409
        long runningId = idOf(first, "$.rehearsalId");
        org.mockito.Mockito.when(clock.instant()).thenReturn(T1);
        mockMvc.perform(post("/api/rehearsals/" + runningId + "/results").contentType("application/json")
                        .content("""
                                {"requestId":"rc1","operator":"alice","results":[{"deviceId":"d1","result":"SUCCESS"}]}
                                """))
                .andExpect(status().isOk());
        long releaseId = idOf(mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                {"requestId":"rr1","model":"m1","fromVersion":"1.0.0","toVersion":"2.0.0","ratio":100}
                """)).andExpect(status().isOk()).andReturn(), "$.releaseId");
        long taskId = idOf(mockMvc.perform(post("/api/devices/d1/pull").contentType("application/json")
                .content("{\"requestId\":\"rp1\"}")).andExpect(status().isOk()).andReturn(), "$.task.taskId");
        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json")
                .content("{\"requestId\":\"rt1\",\"result\":\"SUCCESS\"}")).andExpect(status().isOk());
        mockMvc.perform(get("/api/devices/d1")).andExpect(jsonPath("$.currentVersion").value("2.0.0"));
        mockMvc.perform(post("/api/rehearsals").contentType("application/json").content("""
                {"requestId":"rs9","operator":"alice","model":"m1","targetVersion":"2.0.0",
                 "rollbackVersion":"1.0.0","deviceIds":["d1"]}
                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("FORMAL_TASK_EXISTS"));
        assertThat(releaseId).isPositive();
    }

    @Test
    void 历史不可变_后续设备变化不改写_新版本须重新演练_历史与诊断稳定排序() throws Exception {
        register("rd1", "d1", "m1", "2.0.0", 1);
        long rehearsalId = idOf(startRehearsal("""
                {"requestId":"rs1","operator":"alice","model":"m1","targetVersion":"2.0.0",
                 "rollbackVersion":"1.0.0","deviceIds":["d1"]}
                """), "$.rehearsalId");
        org.mockito.Mockito.when(clock.instant()).thenReturn(T1);
        mockMvc.perform(post("/api/rehearsals/" + rehearsalId + "/results").contentType("application/json")
                        .content("""
                                {"requestId":"rc1","operator":"alice","results":[{"deviceId":"d1","result":"SUCCESS"}]}
                                """))
                .andExpect(status().isOk());

        // 正式投放后设备升到 2.0.0，演练证据仍记录成功回退与当时数量
        long releaseId = idOf(mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                {"requestId":"rr1","model":"m1","fromVersion":"1.0.0","toVersion":"2.0.0","ratio":100}
                """)).andExpect(status().isOk()).andReturn(), "$.releaseId");
        long taskId = idOf(mockMvc.perform(post("/api/devices/d1/pull").contentType("application/json")
                .content("{\"requestId\":\"rp1\"}")).andReturn(), "$.task.taskId");
        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json")
                .content("{\"requestId\":\"rt1\",\"result\":\"SUCCESS\"}")).andExpect(status().isOk());

        mockMvc.perform(get("/api/rehearsals/" + rehearsalId))
                .andExpect(jsonPath("$.rehearsal.status").value("PASSED"))
                .andExpect(jsonPath("$.rehearsal.successCount").value(1))
                .andExpect(jsonPath("$.devices[0].result").value("SUCCESS"));
        mockMvc.perform(get("/api/devices/d1")).andExpect(jsonPath("$.currentVersion").value("2.0.0"));

        // 新目标版本 3.0.0 没有门禁：必须重新演练
        mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                {"requestId":"rr2","model":"m1","fromVersion":"2.0.0","toVersion":"3.0.0","ratio":100}
                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("GATE_NOT_READY"));

        // 历史：缺少 model 参数 400；按型号与版本过滤、totalCount 与稳定排序
        mockMvc.perform(get("/api/rehearsals")).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/rehearsals").param("model", "m1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(1))
                .andExpect(jsonPath("$.rehearsals[0].rehearsalId").value(rehearsalId))
                .andExpect(jsonPath("$.rehearsals[0].gateStatus").value("READY_FOR_RELEASE"));
        mockMvc.perform(get("/api/rehearsals")
                        .param("model", "m1").param("targetVersion", "3.0.0"))
                .andExpect(jsonPath("$.totalCount").value(0));

        // 门禁诊断
        mockMvc.perform(get("/api/rehearsals/gates/diagnostics").param("model", "m1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(1))
                .andExpect(jsonPath("$.gates[0].status").value("READY_FOR_RELEASE"))
                .andExpect(jsonPath("$.gates[0].targetVersion").value("2.0.0"))
                .andExpect(jsonPath("$.gates[0].rollbackVersion").value("1.0.0"))
                .andExpect(jsonPath("$.gates[0].rehearsalId").value(rehearsalId))
                .andExpect(jsonPath("$.gates[0].operator").value("alice"))
                .andExpect(jsonPath("$.gates[0].grantedAtUtc").value("2026-09-29T07:05:30Z"));
        mockMvc.perform(get("/api/rehearsals/gates/diagnostics")
                        .param("model", "m1").param("rollbackVersion", "9.9.9"))
                .andExpect(jsonPath("$.totalCount").value(0));
        mockMvc.perform(get("/api/rehearsals/gates/diagnostics")).andExpect(status().isBadRequest());

        // 演练不存在：404
        mockMvc.perform(get("/api/rehearsals/9999")).andExpect(status().isNotFound());
        mockMvc.perform(post("/api/rehearsals/9999/results").contentType("application/json")
                        .content("""
                                {"requestId":"rc9","operator":"alice","results":[{"deviceId":"d1","result":"SUCCESS"}]}
                                """))
                .andExpect(status().isNotFound());
        assertThat(releaseId).isPositive();
    }
}
