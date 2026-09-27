package com.example.starter.firmware;

import com.example.starter.firmware.service.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 固件签名证书轮换全链路 H2 数据库测试：证书时态、唯一当前链、设备信任集合、兼容矩阵、
 * 批量启动 422 原子性、任务链快照冻结、撤销后回执复核状态与写操作幂等。
 */
@SpringBootTest
@AutoConfigureMockMvc
class FirmwareCertRolloverTest {

    private static final String BASE = "2026-09-01T00:00:00Z";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MutableClock clock;

    @BeforeEach
    void cleanUp() {
        jdbc.update("DELETE FROM receipt_review");
        jdbc.update("DELETE FROM rollout_task");
        jdbc.update("DELETE FROM cert_chain_device");
        jdbc.update("DELETE FROM firmware_cert_chain");
        jdbc.update("DELETE FROM firmware_compatibility");
        jdbc.update("DELETE FROM firmware_version");
        jdbc.update("DELETE FROM device_trust");
        jdbc.update("DELETE FROM release_pause_record");
        jdbc.update("DELETE FROM release_resume_record");
        jdbc.update("DELETE FROM release_order");
        jdbc.update("DELETE FROM device");
        jdbc.update("DELETE FROM idempotency_record");
        clock.setInstant(Instant.parse(BASE));
    }

    private String body(String json) {
        return json;
    }

    private void registerFirmware(String requestId, String model, String version) throws Exception {
        mockMvc.perform(post("/api/firmware/versions").contentType("application/json").content(body("""
                {"requestId":"%s","model":"%s","version":"%s","packageUri":"oss://fw/%s/%s.bin"}
                """.formatted(requestId, model, version, model, version))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.model").value(model))
                .andExpect(jsonPath("$.version").value(version));
    }

    private void trust(String deviceId, boolean trusted) throws Exception {
        mockMvc.perform(put("/api/devices/" + deviceId + "/trust").contentType("application/json").content("""
                {"requestId":"trust-%s-%b","anchorFingerprint":"root-anchor-%s","trusted":%b,"operator":"ops"}
                """.formatted(deviceId, trusted, deviceId, trusted)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trusted").value(trusted));
    }

    private String registerChain(String requestId, int chainVersion, String from, String to,
                                 String devicesJson) throws Exception {
        String toField = to == null ? "null" : ("\"" + to + "\"");
        String devices = devicesJson == null ? "[]" : devicesJson;
        MvcResult result = mockMvc.perform(post("/api/firmware/cert-chains").contentType("application/json")
                        .content("""
                                {"requestId":"%s","model":"m1","firmwareVersion":"2.0.0",
                                "subjectDn":"CN=sign-v%d","issuerDn":"CN=root-v%d","fingerprintSha256":"fp-v%d",
                                "validFromUtc":"%s","validToUtc":%s,"deviceIds":%s,"operator":"ops"}
                                """.formatted(requestId, chainVersion, chainVersion, chainVersion,
                                from, toField, devices)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chainVersion").value(chainVersion))
                .andReturn();
        return result.getResponse().getContentAsString();
    }

    private Integer currentChainVersion(String atUtc) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/firmware/cert-chains/current")
                        .param("model", "m1").param("firmwareVersion", "2.0.0").param("atUtc", atUtc))
                .andExpect(status().isOk())
                .andReturn();
        String text = result.getResponse().getContentAsString();
        if (text.isEmpty() || "null".equals(text)) {
            return null;
        }
        Number value = com.jayway.jsonpath.JsonPath.read(text, "$.chainVersion");
        return value == null ? null : value.intValue();
    }

    private void registerDevice(String requestId, String deviceId, String version, int bucket) throws Exception {
        mockMvc.perform(post("/api/devices").contentType("application/json").content("""
                {"requestId":"%s","deviceId":"%s","model":"m1","currentVersion":"%s","bucketNo":%d}
                """.formatted(requestId, deviceId, version, bucket)))
                .andExpect(status().isOk());
    }

    private long createRelease(String requestId, int ratio) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                {"requestId":"%s","model":"m1","fromVersion":"1.0.0","toVersion":"2.0.0","ratio":%d}
                """.formatted(requestId, ratio)))
                .andExpect(status().isOk())
                .andReturn();
        Number value = com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.releaseId");
        return value.longValue();
    }

    private MvcResult pull(String deviceId, String requestId) throws Exception {
        return mockMvc.perform(post("/api/devices/" + deviceId + "/pull").contentType("application/json")
                .content("{\"requestId\":\"%s\"}".formatted(requestId))).andReturn();
    }

    private void receipt(long taskId, String requestId, String result) throws Exception {
        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"%s\",\"result\":\"%s\"}".formatted(requestId, result)))
                .andExpect(status().isOk());
    }

    private long taskIdOf(MvcResult result) throws Exception {
        Number value = com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.task.taskId");
        return value.longValue();
    }

    @Test
    void 登记链_时态查询当前链_生效前与半开到期边界为空() throws Exception {
        registerFirmware("fw", "m1", "2.0.0");
        registerChain("c1", 1, "2026-10-01T00:00:00Z", null, null);

        assertThat(currentChainVersion("2026-09-30T23:59:59Z")).isNull();
        assertThat(currentChainVersion("2026-10-01T00:00:00Z")).isEqualTo(1);
        assertThat(currentChainVersion("2026-11-01T00:00:00Z")).isEqualTo(1);

        mockMvc.perform(get("/api/firmware/cert-chains")
                        .param("model", "m1").param("firmwareVersion", "2.0.0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$[0].validToUtc").doesNotExist());
    }

    @Test
    void 登记区间重叠409_端点相接允许_任意时刻至多一条当前链() throws Exception {
        registerFirmware("fw", "m1", "2.0.0");
        registerChain("c1", 1, "2026-10-01T00:00:00Z", "2026-11-01T00:00:00Z", null);

        // 交叉区间：409
        mockMvc.perform(post("/api/firmware/cert-chains").contentType("application/json").content("""
                {"requestId":"c2","model":"m1","firmwareVersion":"2.0.0",
                "subjectDn":"CN=x","issuerDn":"CN=y","fingerprintSha256":"fp2",
                "validFromUtc":"2026-10-15T00:00:00Z","validToUtc":null,"deviceIds":[],"operator":"ops"}
                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CERT_INTERVAL_OVERLAP"));
        // 失败不占键：同 requestId 改为不重叠区间后成功，链版本仍为 2
        registerChain("c2", 2, "2026-11-01T00:00:00Z", null, null);

        // 任意时刻唯一当前链：交接点前后
        assertThat(currentChainVersion("2026-10-31T23:59:59Z")).isEqualTo(1);
        assertThat(currentChainVersion("2026-11-01T00:00:00Z")).isEqualTo(2);

        // 非法区间（生效不早于到期）：400
        mockMvc.perform(post("/api/firmware/cert-chains").contentType("application/json").content("""
                {"requestId":"c3","model":"m1","firmwareVersion":"2.0.0",
                "subjectDn":"CN=x","issuerDn":"CN=y","fingerprintSha256":"fp3",
                "validFromUtc":"2026-12-01T00:00:00Z","validToUtc":"2026-12-01T00:00:00Z",
                "deviceIds":[],"operator":"ops"}
                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CERT_INTERVAL"));
    }

    @Test
    void 轮换指定旧新版本_旧链截断_新链生效前后当前链切换_同键重放() throws Exception {
        registerFirmware("fw", "m1", "2.0.0");
        registerChain("c1", 1, "2026-10-01T00:00:00Z", null, null);

        // 新链版本不是下一序号：409
        mockMvc.perform(post("/api/firmware/cert-chains/rollover").contentType("application/json").content("""
                {"requestId":"r-bad","model":"m1","firmwareVersion":"2.0.0","oldChainVersion":1,"newChainVersion":3,
                "effectiveAtUtc":"2026-11-01T00:00:00Z","subjectDn":"CN=n","issuerDn":"CN=r",
                "fingerprintSha256":"fp2","deviceIds":[],"operator":"ops"}
                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CERT_CHAIN_VERSION_SEQUENCE"));

        // 生效时刻早于请求时刻：409
        mockMvc.perform(post("/api/firmware/cert-chains/rollover").contentType("application/json").content("""
                {"requestId":"r-past","model":"m1","firmwareVersion":"2.0.0","oldChainVersion":1,"newChainVersion":2,
                "effectiveAtUtc":"2026-08-01T00:00:00Z","subjectDn":"CN=n","issuerDn":"CN=r",
                "fingerprintSha256":"fp2","deviceIds":[],"operator":"ops"}
                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ROLLOVER_IN_PAST"));

        String rolloverBody = """
                {"requestId":"r-ok","model":"m1","firmwareVersion":"2.0.0","oldChainVersion":1,"newChainVersion":2,
                "effectiveAtUtc":"2026-11-01T00:00:00Z","subjectDn":"CN=sign-v2","issuerDn":"CN=root-v2",
                "fingerprintSha256":"fp-v2","deviceIds":[],"operator":"ops"}
                """;
        String first = mockMvc.perform(post("/api/firmware/cert-chains/rollover")
                        .contentType("application/json").content(rolloverBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chainVersion").value(2))
                .andExpect(jsonPath("$.validFromUtc").value("2026-11-01T00:00:00Z"))
                .andReturn().getResponse().getContentAsString();
        // 同键同参重放：响应一致，不新增链
        String replay = mockMvc.perform(post("/api/firmware/cert-chains/rollover")
                        .contentType("application/json").content(rolloverBody))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(replay).isEqualTo(first);
        mockMvc.perform(get("/api/firmware/cert-chains")
                        .param("model", "m1").param("firmwareVersion", "2.0.0"))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].validToUtc").value("2026-11-01T00:00:00Z"));

        // 同键异参：409
        mockMvc.perform(post("/api/firmware/cert-chains/rollover").contentType("application/json")
                        .content(rolloverBody.replace("fp-v2", "fp-other")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));

        assertThat(currentChainVersion("2026-10-31T23:59:59Z")).isEqualTo(1);
        assertThat(currentChainVersion("2026-11-01T00:00:00Z")).isEqualTo(2);
    }

    @Test
    void 启动发布_当前无有效链409_显式不兼容409_未启用证书沿用历史放行() throws Exception {
        registerFirmware("fw", "m1", "2.0.0");
        registerChain("c1", 1, "2026-10-01T00:00:00Z", null, null);

        // 请求时刻（2026-09-01）新链尚未生效：不能启动
        mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                {"requestId":"rel-early","model":"m1","fromVersion":"1.0.0","toVersion":"2.0.0","ratio":100}
                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NO_CURRENT_CERT_CHAIN"));
        // 失败不占键
        clock.setInstant(Instant.parse("2026-10-02T00:00:00Z"));
        createRelease("rel-early", 100);

        // 显式不兼容矩阵：启动 409
        registerFirmware("fw2", "m2", "2.0.0");
        mockMvc.perform(post("/api/firmware/compatibility").contentType("application/json").content("""
                {"requestId":"compat","model":"m2","fromVersion":"1.0.0","toVersion":"2.0.0","allowed":false}
                """)).andExpect(status().isOk());
        mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                {"requestId":"rel-bad","model":"m2","fromVersion":"1.0.0","toVersion":"2.0.0","ratio":100}
                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("FIRMWARE_INCOMPATIBLE"));

        // 未登记固件（未启用证书、未配置矩阵）的型号沿用历史放行
        mockMvc.perform(post("/api/devices").contentType("application/json").content("""
                {"requestId":"d9","deviceId":"d9","model":"m9","currentVersion":"1.0.0","bucketNo":1}
                """)).andExpect(status().isOk());
        MvcResult rel = mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                {"requestId":"rel9","model":"m9","fromVersion":"1.0.0","toVersion":"2.0.0","ratio":100}
                """)).andExpect(status().isOk()).andReturn();
        long releaseId = ((Number) com.jayway.jsonpath.JsonPath.read(
                rel.getResponse().getContentAsString(), "$.releaseId")).longValue();
        mockMvc.perform(post("/api/devices/d9/pull").contentType("application/json")
                        .content("{\"requestId\":\"pull9\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.frozenChainId").doesNotExist())
                .andExpect(jsonPath("$.task.frozenChainVersion").doesNotExist());
        assertThat(releaseId).isPositive();
    }

    @Test
    void 拉取_信任集合与兼容矩阵校验_失败不占键_任务冻结当前链() throws Exception {
        clock.setInstant(Instant.parse("2026-10-02T00:00:00Z"));
        registerFirmware("fw", "m1", "2.0.0");
        registerChain("c1", 1, "2026-10-01T00:00:00Z", null, "[\"d1\",\"d5\"]");
        createRelease("rel", 100);
        registerDevice("dev1", "d1", "1.0.0", 1);
        registerDevice("dev2", "d2", "1.0.0", 2);
        registerDevice("dev3", "d3", "1.0.0", 3);
        registerDevice("dev4", "d4", "1.0.0", 4);
        registerDevice("dev5", "d5", "1.0.0", 5);
        trust("d1", true);
        trust("d2", false);
        trust("d4", true);
        trust("d5", true);

        // d1：信任且在集合内 → 创建任务并冻结链版本 1
        mockMvc.perform(post("/api/devices/d1/pull").contentType("application/json")
                        .content("{\"requestId\":\"pull1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.status").value("PENDING"))
                .andExpect(jsonPath("$.task.frozenChainId").isNumber())
                .andExpect(jsonPath("$.task.frozenChainVersion").value(1));

        // d2：显式不信任 → 422
        mockMvc.perform(post("/api/devices/d2/pull").contentType("application/json")
                        .content("{\"requestId\":\"pull2\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DEVICE_NOT_TRUSTED"));
        // d3：无信任记录 → 422
        mockMvc.perform(post("/api/devices/d3/pull").contentType("application/json")
                        .content("{\"requestId\":\"pull3\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DEVICE_NOT_TRUSTED"));
        // d4：信任但不在链授权集合 → 422
        mockMvc.perform(post("/api/devices/d4/pull").contentType("application/json")
                        .content("{\"requestId\":\"pull4\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DEVICE_NOT_IN_CERT_SCOPE"));

        // 显式不兼容矩阵：合格设备 d5 拉取 → 409
        mockMvc.perform(post("/api/firmware/compatibility").contentType("application/json").content("""
                {"requestId":"compat","model":"m1","fromVersion":"1.0.0","toVersion":"2.0.0","allowed":false}
                """)).andExpect(status().isOk());
        mockMvc.perform(post("/api/devices/d5/pull").contentType("application/json")
                        .content("{\"requestId\":\"pull5\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("FIRMWARE_INCOMPATIBLE"));

        // 失败不占键：pull2 曾 422 未占键，改用于合格设备 d1 拉取（已有任务直接返回）成功
        mockMvc.perform(post("/api/devices/d1/pull").contentType("application/json")
                        .content("{\"requestId\":\"pull2\"}"))
                .andExpect(status().isOk());

        // 只有 d1 一条任务
        Long taskCount = jdbc.queryForObject("SELECT COUNT(*) FROM rollout_task", Long.class);
        assertThat(taskCount).isEqualTo(1);
    }

    @Test
    void 新链生效前与旧链到期后不新建任务_已有任务仍可查看() throws Exception {
        clock.setInstant(Instant.parse("2026-10-02T00:00:00Z"));
        registerFirmware("fw", "m1", "2.0.0");
        registerChain("c1", 1, "2026-10-01T00:00:00Z", "2026-10-15T00:00:00Z", "[\"d1\",\"d2\"]");
        registerDevice("dev1", "d1", "1.0.0", 1);
        registerDevice("dev2", "d2", "1.0.0", 2);
        trust("d1", true);
        trust("d2", true);
        long releaseId = createRelease("rel", 100);

        clock.setInstant(Instant.parse("2026-10-14T23:59:59Z"));
        MvcResult pulled = pull("d1", "pull1");
        assertThat(pulled.getResponse().getStatus()).isEqualTo(200);
        long taskId = taskIdOf(pulled);

        // 旧链到期时刻：新设备不创建任务（非 422，task 为 null）
        clock.setInstant(Instant.parse("2026-10-15T00:00:00Z"));
        mockMvc.perform(post("/api/devices/d2/pull").contentType("application/json")
                        .content("{\"requestId\":\"pull2\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task").doesNotExist());
        // 已有任务仍返回，冻结链版本不变
        mockMvc.perform(post("/api/devices/d1/pull").contentType("application/json")
                        .content("{\"requestId\":\"pull1b\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.taskId").value(taskId))
                .andExpect(jsonPath("$.task.frozenChainVersion").value(1));
        // 发布单任务明细可查冻结版本
        mockMvc.perform(get("/api/releases/" + releaseId + "/tasks"))
                .andExpect(jsonPath("$.tasks.length()").value(1))
                .andExpect(jsonPath("$.tasks[0].frozenChainVersion").value(1));
    }

    @Test
    void 批量启动_任一不信任422且整批不创建_全体通过才冻结同一当前链() throws Exception {
        clock.setInstant(Instant.parse("2026-10-02T00:00:00Z"));
        registerFirmware("fw", "m1", "2.0.0");
        registerChain("c1", 1, "2026-10-01T00:00:00Z", null, null);
        registerDevice("dev1", "d1", "1.0.0", 1);
        registerDevice("dev2", "d2", "1.0.0", 2);
        registerDevice("dev3", "d3", "1.0.0", 60);
        trust("d1", true);
        trust("d2", true);
        trust("d3", true);
        long releaseId = createRelease("rel", 50);

        // d4 显式不信任
        registerDevice("dev4", "d4", "1.0.0", 1);
        trust("d4", false);

        // 含一个不信任设备：422，全部任务不创建
        mockMvc.perform(post("/api/releases/" + releaseId + "/batch-start").contentType("application/json")
                        .content("""
                                {"requestId":"batch-bad","deviceIds":["d1","d4","d2"]}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DEVICE_NOT_TRUSTED"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rollout_task", Long.class)).isZero();

        // 不合格投放条件（当前版本/分桶）：422
        mockMvc.perform(post("/api/releases/" + releaseId + "/batch-start").contentType("application/json")
                        .content("""
                                {"requestId":"batch-bucket","deviceIds":["d1","d3"]}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DEVICE_NOT_ELIGIBLE"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rollout_task", Long.class)).isZero();

        // 失败不占键：修复后同 requestId 成功
        trust("d4", true);
        String batchOk = """
                {"requestId":"batch-bad","deviceIds":["d1","d4","d2"]}
                """;
        mockMvc.perform(post("/api/releases/" + releaseId + "/batch-start").contentType("application/json")
                        .content(batchOk))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tasks.length()").value(3))
                .andExpect(jsonPath("$.tasks[0].frozenChainVersion").value(1));
        // 同键同参重放：不重复创建
        mockMvc.perform(post("/api/releases/" + releaseId + "/batch-start").contentType("application/json")
                        .content(batchOk))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tasks.length()").value(3));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rollout_task", Long.class)).isEqualTo(3);
        Long distinctChains = jdbc.queryForObject(
                "SELECT COUNT(DISTINCT frozen_chain_id) FROM rollout_task WHERE frozen_chain_id IS NOT NULL",
                Long.class);
        assertThat(distinctChains).isEqualTo(1);
    }

    @Test
    void 撤销链_回执转复核不升版本_确认后更新_拒绝保持_新拉取用新链() throws Exception {
        clock.setInstant(Instant.parse("2026-10-02T00:00:00Z"));
        registerFirmware("fw", "m1", "2.0.0");
        registerChain("c1", 1, "2026-10-01T00:00:00Z", null, null);
        registerDevice("dev1", "d1", "1.0.0", 1);
        registerDevice("dev2", "d2", "1.0.0", 2);
        registerDevice("dev3", "d3", "1.0.0", 3);
        trust("d1", true);
        trust("d2", true);
        trust("d3", true);
        long releaseId = createRelease("rel", 100);
        long task1 = taskIdOf(pull("d1", "pull1"));
        long task2 = taskIdOf(pull("d2", "pull2"));

        // 撤销链
        clock.setInstant(Instant.parse("2026-10-20T00:00:00Z"));
        mockMvc.perform(post("/api/firmware/cert-chains/revoke").contentType("application/json").content("""
                {"requestId":"rev1","model":"m1","firmwareVersion":"2.0.0","chainVersion":1,"operator":"secops"}
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"))
                .andExpect(jsonPath("$.validToUtc").value("2026-10-20T00:00:00Z"));
        // 重复撤销：409
        mockMvc.perform(post("/api/firmware/cert-chains/revoke").contentType("application/json").content("""
                {"requestId":"rev2","model":"m1","firmwareVersion":"2.0.0","chainVersion":1,"operator":"secops"}
                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CERT_NOT_ACTIVE"));
        // 撤销后新拉取无当前链：不创建任务
        mockMvc.perform(post("/api/devices/d3/pull").contentType("application/json")
                        .content("{\"requestId\":\"pull3\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task").doesNotExist());

        // 已冻结旧链的任务仍可提交 SUCCESS 回执，但标记复核且不升版本
        receipt(task1, "rc1", "SUCCESS");
        mockMvc.perform(get("/api/devices/d1"))
                .andExpect(jsonPath("$.currentVersion").value("1.0.0"));
        mockMvc.perform(get("/api/tasks/" + task1 + "/review"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_REVIEW"))
                .andExpect(jsonPath("$.chainVersion").value(1));
        // 任务视图带复核标记
        mockMvc.perform(get("/api/releases/" + releaseId + "/tasks").param("status", "SUCCESS"))
                .andExpect(jsonPath("$.tasks[0].reviewStatus").value("CERTIFICATE_REVOKED_REVIEW"));

        // 重复回执不改变结论：仍只有一条复核单，版本不更新
        receipt(task1, "rc1-dup", "SUCCESS");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM receipt_review", Long.class)).isEqualTo(1);
        mockMvc.perform(get("/api/devices/d1"))
                .andExpect(jsonPath("$.currentVersion").value("1.0.0"));

        // 人工确认后才更新版本
        mockMvc.perform(post("/api/tasks/" + task1 + "/review/confirm").contentType("application/json")
                        .content("{\"requestId\":\"confirm1\",\"operator\":\"manager\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));
        mockMvc.perform(get("/api/devices/d1"))
                .andExpect(jsonPath("$.currentVersion").value("2.0.0"));
        mockMvc.perform(post("/api/tasks/" + task1 + "/review/confirm").contentType("application/json")
                        .content("{\"requestId\":\"confirm1\",\"operator\":\"manager\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));

        // 第二个任务：拒绝路径，版本永不更新
        receipt(task2, "rc2", "SUCCESS");
        mockMvc.perform(post("/api/tasks/" + task2 + "/review/reject").contentType("application/json")
                        .content("{\"requestId\":\"reject2\",\"reason\":\"镜像哈希异常\",\"operator\":\"manager\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.rejectReason").value("镜像哈希异常"));
        mockMvc.perform(get("/api/devices/d2"))
                .andExpect(jsonPath("$.currentVersion").value("1.0.0"));
        // 拒绝后不能确认
        mockMvc.perform(post("/api/tasks/" + task2 + "/review/confirm").contentType("application/json")
                        .content("{\"requestId\":\"confirm2\",\"operator\":\"manager\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REVIEW_ALREADY_DECIDED"));

        // 新拉取必须使用当前有效链：登记/轮换出新链后任务冻结链版本 2
        mockMvc.perform(post("/api/firmware/cert-chains/rollover").contentType("application/json").content("""
                {"requestId":"roll2","model":"m1","firmwareVersion":"2.0.0","oldChainVersion":1,"newChainVersion":2,
                "effectiveAtUtc":"2026-10-21T00:00:00Z","subjectDn":"CN=sign-v2","issuerDn":"CN=root",
                "fingerprintSha256":"fp-v2","deviceIds":[],"operator":"ops"}
                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OLD_CERT_NOT_ACTIVE"));
        registerChain("c2", 2, "2026-10-21T00:00:00Z", null, null);
        clock.setInstant(Instant.parse("2026-10-21T00:00:00Z"));
        mockMvc.perform(post("/api/devices/d3/pull").contentType("application/json")
                        .content("{\"requestId\":\"pull3b\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.frozenChainVersion").value(2));
    }
}
