package com.example.starter.firmware;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 固件硬件兼容矩阵：矩阵版本、拉取拦截、失败率隔离、矩阵缩窄边界、发布预检与查询
 * （真实 H2 内存库，MODE=MySQL，唯一约束与事务边界均由数据库验证）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class FirmwareHardwareCompatTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanUp() {
        jdbc.update("DELETE FROM device_incompatible_record");
        jdbc.update("DELETE FROM rollout_task");
        jdbc.update("DELETE FROM release_pause_record");
        jdbc.update("DELETE FROM release_resume_record");
        jdbc.update("DELETE FROM release_order");
        jdbc.update("DELETE FROM device");
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM firmware_compat_matrix");
        jdbc.update("DELETE FROM hardware_model");
    }

    private static long idOf(MvcResult result, String path) throws Exception {
        Number value = com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), path);
        return value.longValue();
    }

    private void registerDevice(String requestId, String deviceId, String hardwareModel, String version, int bucket)
            throws Exception {
        mockMvc.perform(post("/api/devices").contentType("application/json").content("""
                {"requestId":"%s","deviceId":"%s","model":"m1","hardwareModel":"%s","currentVersion":"%s","bucketNo":%d}
                """.formatted(requestId, deviceId, hardwareModel, version, bucket)))
                .andExpect(status().isOk());
    }

    private void registerHardwareModel(String requestId, String hardwareModel) throws Exception {
        mockMvc.perform(post("/api/hardware-models").contentType("application/json")
                        .content("{\"requestId\":\"%s\",\"hardwareModel\":\"%s\"}".formatted(requestId, hardwareModel)))
                .andExpect(status().isOk());
    }

    private long createRelease(String requestId, String toVersion, int ratio) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                {"requestId":"%s","model":"m1","fromVersion":"1.0.0","toVersion":"%s","ratio":%d}
                """.formatted(requestId, toVersion, ratio)))
                .andExpect(status().isOk())
                .andReturn();
        return idOf(result, "$.releaseId");
    }

    private long createReleaseWithMonitor(String requestId, String toVersion, int ratio, int floor, int threshold)
            throws Exception {
        MvcResult result = mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                {"requestId":"%s","model":"m1","fromVersion":"1.0.0","toVersion":"%s","ratio":%d,
                "sampleFloor":%d,"failureThresholdPercent":%d}
                """.formatted(requestId, toVersion, ratio, floor, threshold)))
                .andExpect(status().isOk())
                .andReturn();
        return idOf(result, "$.releaseId");
    }

    private void startOk(long releaseId, String requestId) throws Exception {
        mockMvc.perform(post("/api/releases/" + releaseId + "/start").contentType("application/json")
                        .content("{\"requestId\":\"%s\",\"expectedVersion\":1}".formatted(requestId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    private void updateMatrix(String requestId, String firmware, int expectedVersion, String modelsJson)
            throws Exception {
        mockMvc.perform(post("/api/firmware/compat-matrix").contentType("application/json").content("""
                {"requestId":"%s","firmwareVersion":"%s","expectedVersion":%d,"models":%s}
                """.formatted(requestId, firmware, expectedVersion, modelsJson)))
                .andExpect(status().isOk());
    }

    @Test
    void 矩阵查询_未配置为版本0兼容全部() throws Exception {
        mockMvc.perform(get("/api/firmware/9.9.9/compat-matrix"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.firmwareVersion").value("9.9.9"))
                .andExpect(jsonPath("$.version").value(0))
                .andExpect(jsonPath("$.compatibleWithAll").value(true))
                .andExpect(jsonPath("$.models.length()").value(0));
    }

    @Test
    void 矩阵配置_首配版本1_换序同参不升版_变更升版_空集合兼容全部() throws Exception {
        registerDevice("rd", "d1", "HW-A", "1.0.0", 1);
        registerDevice("rb", "d2", "HW-B", "1.0.0", 1);

        // 首配 expectedVersion 必须为 0
        updateMatrix("m1", "2.0.0", 0, "[\"HW-B\"]");
        mockMvc.perform(get("/api/firmware/2.0.0/compat-matrix"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.compatibleWithAll").value(false))
                .andExpect(jsonPath("$.models[0]").value("HW-B"));

        // 同 requestId 同参重放首配请求：幂等返回版本1，响应一致，不再插入
        MvcResult replay1 = mockMvc.perform(post("/api/firmware/compat-matrix").contentType("application/json")
                        .content("""
                                {"requestId":"m1","firmwareVersion":"2.0.0","expectedVersion":0,"models":["HW-B"]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andReturn();
        MvcResult replay2 = mockMvc.perform(post("/api/firmware/compat-matrix").contentType("application/json")
                        .content("""
                                {"requestId":"m1","firmwareVersion":"2.0.0","expectedVersion":0,"models":["HW-B"]}
                                """))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(replay1.getResponse().getContentAsString())
                .isEqualTo(replay2.getResponse().getContentAsString());

        // 换序视为同参：[HW-B, HW-A] 与 [HW-A, HW-B] 内容相同，第二次不升版
        updateMatrix("m2", "2.0.0", 1, "[\"HW-B\",\"HW-A\"]");
        mockMvc.perform(get("/api/firmware/2.0.0/compat-matrix"))
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.models[0]").value("HW-A"))
                .andExpect(jsonPath("$.models[1]").value("HW-B"));
        updateMatrix("m3", "2.0.0", 2, "[\"HW-A\",\"HW-B\"]");
        mockMvc.perform(get("/api/firmware/2.0.0/compat-matrix"))
                .andExpect(jsonPath("$.version").value(2));

        // 真正缩窄为单型号：版本升到 3
        updateMatrix("m4", "2.0.0", 2, "[\"HW-B\"]");
        mockMvc.perform(get("/api/firmware/2.0.0/compat-matrix"))
                .andExpect(jsonPath("$.version").value(3));

        // 置空集合：兼容全部，内容变化版本升到 4
        updateMatrix("m5", "2.0.0", 3, "[]");
        mockMvc.perform(get("/api/firmware/2.0.0/compat-matrix"))
                .andExpect(jsonPath("$.version").value(4))
                .andExpect(jsonPath("$.compatibleWithAll").value(true));
    }

    @Test
    void 矩阵配置_型号重复或未知422_版本不符409_失败不占键() throws Exception {
        registerDevice("rd", "d1", "HW-A", "1.0.0", 1);

        // 型号重复：422
        mockMvc.perform(post("/api/firmware/compat-matrix").contentType("application/json").content("""
                        {"requestId":"bad1","firmwareVersion":"2.0.0","expectedVersion":0,"models":["HW-A","HW-A"]}
                        """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("MATRIX_DUPLICATE_MODEL"));

        // 未知型号：422
        mockMvc.perform(post("/api/firmware/compat-matrix").contentType("application/json").content("""
                        {"requestId":"bad2","firmwareVersion":"2.0.0","expectedVersion":0,"models":["HW-GHOST"]}
                        """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("MATRIX_UNKNOWN_MODEL"));

        // 首配 expectedVersion 非 0：409
        mockMvc.perform(post("/api/firmware/compat-matrix").contentType("application/json").content("""
                        {"requestId":"bad3","firmwareVersion":"2.0.0","expectedVersion":1,"models":["HW-A"]}
                        """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MATRIX_VERSION_CONFLICT"));

        // 422 失败不占键：同一 requestId 改合法参数后成功
        updateMatrix("bad1", "2.0.0", 0, "[\"HW-A\"]");
        mockMvc.perform(get("/api/firmware/2.0.0/compat-matrix"))
                .andExpect(jsonPath("$.version").value(1));

        // 已配置后期望版本不符：409
        mockMvc.perform(post("/api/firmware/compat-matrix").contentType("application/json").content("""
                        {"requestId":"bad4","firmwareVersion":"2.0.0","expectedVersion":99,"models":["HW-A"]}
                        """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MATRIX_VERSION_CONFLICT"));

        // 同 requestId 异参：409
        mockMvc.perform(post("/api/firmware/compat-matrix").contentType("application/json").content("""
                        {"requestId":"bad1","firmwareVersion":"2.0.0","expectedVersion":1,"models":[]}
                        """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));
    }

    @Test
    void 拉取_不兼容返回INCOMPATIBLE_不建任务不计样本不改状态并记录() throws Exception {
        registerDevice("ra", "dA", "HW-A", "1.0.0", 1);
        registerDevice("rb", "dB", "HW-B", "1.0.0", 1);
        long releaseId = createReleaseWithMonitor("rr", "2.0.0", 100, 2, 50);
        updateMatrix("rm", "2.0.0", 0, "[\"HW-B\"]");
        startOk(releaseId, "rs");

        // HW-A 被拦截
        mockMvc.perform(post("/api/devices/dA/pull").contentType("application/json")
                        .content("{\"requestId\":\"pA1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("INCOMPATIBLE"))
                .andExpect(jsonPath("$.task").doesNotExist());

        // HW-B 正常投放，任务保存拉取时矩阵版本 1
        MvcResult pullB = mockMvc.perform(post("/api/devices/dB/pull").contentType("application/json")
                        .content("{\"requestId\":\"pB1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("TASK"))
                .andExpect(jsonPath("$.task.status").value("PENDING"))
                .andExpect(jsonPath("$.task.compatMatrixVersion").value(1))
                .andReturn();
        long taskB = idOf(pullB, "$.task.taskId");

        // 不构任务、不改设备状态
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rollout_task", Long.class)).isEqualTo(1);
        mockMvc.perform(get("/api/devices/dA"))
                .andExpect(jsonPath("$.currentVersion").value("1.0.0"));

        // 不兼容记录可查，含矩阵版本与目标版本
        mockMvc.perform(get("/api/devices/dA/incompatible-records"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.records.length()").value(1))
                .andExpect(jsonPath("$.records[0].deviceId").value("dA"))
                .andExpect(jsonPath("$.records[0].hardwareModel").value("HW-A"))
                .andExpect(jsonPath("$.records[0].releaseId").value(releaseId))
                .andExpect(jsonPath("$.records[0].toVersion").value("2.0.0"))
                .andExpect(jsonPath("$.records[0].matrixVersion").value(1));
        mockMvc.perform(get("/api/devices/dB/incompatible-records"))
                .andExpect(jsonPath("$.records.length()").value(0));

        // 再次拉取仍被拦截，再落一条记录（每次拦截可审计）
        mockMvc.perform(post("/api/devices/dA/pull").contentType("application/json")
                        .content("{\"requestId\":\"pA2\"}"))
                .andExpect(jsonPath("$.result").value("INCOMPATIBLE"));
        mockMvc.perform(get("/api/devices/dA/incompatible-records"))
                .andExpect(jsonPath("$.records.length()").value(2));

        // 兼容设备失败：样本仅 1，未达下限 2，发布单仍 ACTIVE——证明拦截未计入失败率样本
        mockMvc.perform(post("/api/tasks/" + taskB + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"rcB\",\"result\":\"FAILED\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/releases/" + releaseId + "/monitor"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.roundFailed").value(1))
                .andExpect(jsonPath("$.roundSuccess").value(0));
    }

    @Test
    void 矩阵缩窄只影响后续拉取_已下发任务仍可回执并更新版本() throws Exception {
        registerDevice("ra1", "dA1", "HW-A", "1.0.0", 1);
        registerDevice("ra2", "dA2", "HW-A", "1.0.0", 2);
        registerDevice("rb", "dB", "HW-B", "1.0.0", 3);
        long releaseId = createRelease("rr", "2.0.0", 100);
        // 初始矩阵兼容 A、B（版本1）
        updateMatrix("rm1", "2.0.0", 0, "[\"HW-A\",\"HW-B\"]");
        startOk(releaseId, "rs");

        MvcResult pullA = mockMvc.perform(post("/api/devices/dA1/pull").contentType("application/json")
                        .content("{\"requestId\":\"pA1\"}"))
                .andExpect(jsonPath("$.result").value("TASK"))
                .andExpect(jsonPath("$.task.compatMatrixVersion").value(1))
                .andReturn();
        long taskA = idOf(pullA, "$.task.taskId");

        // 缩窄为仅 B（版本2）
        updateMatrix("rm2", "2.0.0", 1, "[\"HW-B\"]");

        // 已下发任务不受缩窄影响，成功回执并更新设备版本，任务仍记录版本1
        mockMvc.perform(post("/api/tasks/" + taskA + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"rcA\",\"result\":\"SUCCESS\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"));
        mockMvc.perform(get("/api/devices/dA1"))
                .andExpect(jsonPath("$.currentVersion").value("2.0.0"));
        assertThat(jdbc.queryForObject("SELECT compat_matrix_version FROM rollout_task WHERE id = ?",
                Integer.class, taskA)).isEqualTo(1);

        // 缩窄后新 HW-A 设备被拦截；HW-B 设备拿到版本2的任务
        mockMvc.perform(post("/api/devices/dA2/pull").contentType("application/json")
                        .content("{\"requestId\":\"pA2\"}"))
                .andExpect(jsonPath("$.result").value("INCOMPATIBLE"));
        mockMvc.perform(post("/api/devices/dB/pull").contentType("application/json")
                        .content("{\"requestId\":\"pB\"}"))
                .andExpect(jsonPath("$.result").value("TASK"))
                .andExpect(jsonPath("$.task.compatMatrixVersion").value(2));
    }

    @Test
    void 发布预检_全部不兼容422带按型号汇总_部分不兼容放行_无候选不阻断() throws Exception {
        registerDevice("ra", "dA", "HW-A", "1.0.0", 1);
        registerDevice("rb", "dB", "HW-B", "1.0.0", 2);
        registerHardwareModel("rh", "HW-C");

        // 全部候选（A、B）均不兼容 C 矩阵：422，按型号汇总
        long releaseId1 = createRelease("rr1", "2.0.0", 100);
        updateMatrix("rm1", "2.0.0", 0, "[\"HW-C\"]");
        mockMvc.perform(post("/api/releases/" + releaseId1 + "/start").contentType("application/json")
                        .content("{\"requestId\":\"s1\",\"expectedVersion\":1}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ALL_CANDIDATES_INCOMPATIBLE"))
                .andExpect(jsonPath("$.models.length()").value(2))
                .andExpect(jsonPath("$.models[0].hardwareModel").value("HW-A"))
                .andExpect(jsonPath("$.models[0].candidates").value(1))
                .andExpect(jsonPath("$.models[0].compatible").value(0))
                .andExpect(jsonPath("$.models[0].incompatible").value(1))
                .andExpect(jsonPath("$.models[1].hardwareModel").value("HW-B"))
                .andExpect(jsonPath("$.models[1].incompatible").value(1));
        // 被拦截的发布单保持 DRAFT，拉取不投放
        mockMvc.perform(get("/api/releases/" + releaseId1 + "/monitor"))
                .andExpect(jsonPath("$.status").value("DRAFT"));
        mockMvc.perform(post("/api/devices/dA/pull").contentType("application/json")
                        .content("{\"requestId\":\"p1\"}"))
                .andExpect(jsonPath("$.result").value("EMPTY"));

        // 422 失败不占键：矩阵放开 B 后，同一 requestId 启动成功（部分不兼容不阻断兼容设备）
        updateMatrix("rm2", "2.0.0", 1, "[\"HW-B\"]");
        mockMvc.perform(post("/api/releases/" + releaseId1 + "/start").contentType("application/json")
                        .content("{\"requestId\":\"s1\",\"expectedVersion\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        mockMvc.perform(post("/api/devices/dB/pull").contentType("application/json")
                        .content("{\"requestId\":\"p2\"}"))
                .andExpect(jsonPath("$.result").value("TASK"));

        // 无候选设备：新产品型号下没有任何设备，即使矩阵只允许 HW-C 也不阻断启动
        MvcResult release2 = mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                        {"requestId":"rr2","model":"mEmpty","fromVersion":"1.0.0","toVersion":"3.0.0","ratio":100}
                        """))
                .andExpect(status().isOk())
                .andReturn();
        long releaseId2 = idOf(release2, "$.releaseId");
        updateMatrix("rm3", "3.0.0", 0, "[\"HW-C\"]");
        mockMvc.perform(post("/api/releases/" + releaseId2 + "/start").contentType("application/json")
                        .content("{\"requestId\":\"s2\",\"expectedVersion\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void 启动_版本校验_重复启动与状态门禁() throws Exception {
        registerDevice("ra", "dA", "HW-A", "1.0.0", 1);
        long releaseId = createRelease("rr", "2.0.0", 100);

        // DRAFT 不能扩量
        mockMvc.perform(post("/api/releases/" + releaseId + "/expand").contentType("application/json")
                        .content("{\"requestId\":\"e0\",\"expectedVersion\":1,\"ratio\":100}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RELEASE_NOT_ACTIVE"));

        // expectedVersion 不符：409
        mockMvc.perform(post("/api/releases/" + releaseId + "/start").contentType("application/json")
                        .content("{\"requestId\":\"s0\",\"expectedVersion\":99}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));

        startOk(releaseId, "s1");

        // 重复启动：409
        mockMvc.perform(post("/api/releases/" + releaseId + "/start").contentType("application/json")
                        .content("{\"requestId\":\"s2\",\"expectedVersion\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RELEASE_ALREADY_STARTED"));
    }

    @Test
    void 拉取幂等_指纹含发布单版本与目标版本_同键重放首个响应() throws Exception {
        registerDevice("ra", "dA", "HW-A", "1.0.0", 1);
        long releaseId = createRelease("rr", "2.0.0", 10);
        startOk(releaseId, "rs");

        MvcResult first = mockMvc.perform(post("/api/devices/dA/pull").contentType("application/json")
                        .content("{\"requestId\":\"rp\"}"))
                .andExpect(status().isOk())
                .andReturn();
        MvcResult replay = mockMvc.perform(post("/api/devices/dA/pull").contentType("application/json")
                        .content("{\"requestId\":\"rp\"}"))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(replay.getResponse().getContentAsString()).isEqualTo(first.getResponse().getContentAsString());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rollout_task", Long.class)).isEqualTo(1);

        // 扩量使发布单版本变化：同 requestId 指纹不同 → 409
        mockMvc.perform(post("/api/releases/" + releaseId + "/expand").contentType("application/json")
                        .content("{\"requestId\":\"re\",\"expectedVersion\":1,\"ratio\":100}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/devices/dA/pull").contentType("application/json")
                        .content("{\"requestId\":\"rp\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));

        // 取消后新建目标版本不同的发布单：同 requestId 指纹含目标版本 → 409
        mockMvc.perform(post("/api/releases/" + releaseId + "/cancel").contentType("application/json")
                        .content("{\"requestId\":\"rc\"}"))
                .andExpect(status().isOk());
        long releaseId2 = createRelease("rr2", "3.0.0", 100);
        startOk(releaseId2, "rs2");
        mockMvc.perform(post("/api/devices/dA/pull").contentType("application/json")
                        .content("{\"requestId\":\"rp\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));
        // 新 requestId 正常拉取新目标
        mockMvc.perform(post("/api/devices/dA/pull").contentType("application/json")
                        .content("{\"requestId\":\"rp2\"}"))
                .andExpect(jsonPath("$.result").value("TASK"))
                .andExpect(jsonPath("$.task.toVersion").value("3.0.0"));
    }

    @Test
    void 按型号投放统计_合并任务状态与不兼容拦截() throws Exception {
        registerDevice("ra", "dA", "HW-A", "1.0.0", 1);
        registerDevice("rb1", "dB1", "HW-B", "1.0.0", 2);
        long releaseId = createRelease("rr", "2.0.0", 100);
        updateMatrix("rm", "2.0.0", 0, "[\"HW-B\"]");
        startOk(releaseId, "rs");

        // HW-A 拦截两次
        mockMvc.perform(post("/api/devices/dA/pull").contentType("application/json")
                .content("{\"requestId\":\"pA1\"}")).andExpect(jsonPath("$.result").value("INCOMPATIBLE"));
        mockMvc.perform(post("/api/devices/dA/pull").contentType("application/json")
                .content("{\"requestId\":\"pA2\"}")).andExpect(jsonPath("$.result").value("INCOMPATIBLE"));
        // HW-B 拿任务并成功
        MvcResult pullB = mockMvc.perform(post("/api/devices/dB1/pull").contentType("application/json")
                        .content("{\"requestId\":\"pB\"}"))
                .andExpect(jsonPath("$.result").value("TASK")).andReturn();
        long taskB = idOf(pullB, "$.task.taskId");
        mockMvc.perform(post("/api/tasks/" + taskB + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"rcB\",\"result\":\"SUCCESS\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/releases/" + releaseId + "/model-stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stats.length()").value(2))
                .andExpect(jsonPath("$.stats[0].hardwareModel").value("HW-B"))
                .andExpect(jsonPath("$.stats[0].success").value(1))
                .andExpect(jsonPath("$.stats[0].incompatible").value(0))
                .andExpect(jsonPath("$.stats[1].hardwareModel").value("HW-A"))
                .andExpect(jsonPath("$.stats[1].success").value(0))
                .andExpect(jsonPath("$.stats[1].incompatible").value(2));

        // 发布单不存在：404
        mockMvc.perform(get("/api/releases/9999/model-stats"))
                .andExpect(status().isNotFound());
        // 不兼容记录查询对不存在设备：404
        mockMvc.perform(get("/api/devices/ghost/incompatible-records"))
                .andExpect(status().isNotFound());
    }

    @Test
    void 硬件型号目录_登记幂等_列表可查() throws Exception {
        mockMvc.perform(post("/api/hardware-models").contentType("application/json")
                        .content("{\"requestId\":\"h1\",\"hardwareModel\":\"HW-X\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.registered").value(true));
        mockMvc.perform(post("/api/hardware-models").contentType("application/json")
                        .content("{\"requestId\":\"h2\",\"hardwareModel\":\"HW-X\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.registered").value(false));
        mockMvc.perform(get("/api/hardware-models"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hardwareModels.length()").value(1))
                .andExpect(jsonPath("$.hardwareModels[0]").value("HW-X"));
    }
}
