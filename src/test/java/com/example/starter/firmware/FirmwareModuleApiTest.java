package com.example.starter.firmware;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 固件模块兼容 API 集成测试（真实 H2 内存库，MODE=MySQL）：
 * 主流程、MODULE_BLOCKED 明细与排序、422、依赖环回滚、豁免放行/到期/不绕过门禁、
 * 历史快照固化、幂等与只读诊断。业务时钟为可控 UTC 时钟。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties =
        "spring.datasource.url=jdbc:h2:mem:firmware_module_api;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
class FirmwareModuleApiTest {

    private static final Instant T0 = Instant.parse("2026-09-28T00:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private Clock clock;

    private Instant now = T0;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM module_block_record");
        jdbc.update("DELETE FROM rollout_task");
        jdbc.update("DELETE FROM release_pause_record");
        jdbc.update("DELETE FROM release_resume_record");
        jdbc.update("DELETE FROM module_exemption_history");
        jdbc.update("DELETE FROM module_exemption");
        jdbc.update("DELETE FROM release_order");
        jdbc.update("DELETE FROM device_module");
        jdbc.update("DELETE FROM device_module_report");
        jdbc.update("DELETE FROM firmware_module_requirement");
        jdbc.update("DELETE FROM module_dependency");
        jdbc.update("DELETE FROM firmware_matrix");
        jdbc.update("DELETE FROM device");
        jdbc.update("DELETE FROM idempotency_record");
        now = T0;
        when(clock.instant()).thenAnswer(inv -> now);
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
    }

    private void advanceSeconds(long seconds) {
        now = now.plusSeconds(seconds);
    }

    private MvcResult postJson(String path, String json) throws Exception {
        return mockMvc.perform(post(path).contentType("application/json").content(json)).andReturn();
    }

    private String writeJson(Map<String, Object> fields) throws Exception {
        return objectMapper.writeValueAsString(fields);
    }

    private Map<String, Object> obj(Object... kv) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            map.put((String) kv[i], kv[i + 1]);
        }
        return map;
    }

    private void expectOk(MvcResult result) throws Exception {
        assertThat(result.getResponse().getStatus())
                .as("期望 200，实际: %s %s", result.getResponse().getStatus(),
                        result.getResponse().getContentAsString())
                .isEqualTo(200);
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private void registerDevice(String requestId, String deviceId, String group) throws Exception {
        MvcResult result = postJson("/api/devices", writeJson(obj(
                "requestId", requestId,
                "deviceId", deviceId,
                "model", "m1",
                "currentVersion", "1.0.0",
                "bucketNo", 5,
                "groupName", group)));
        expectOk(result);
    }

    private long createRelease(String requestId, int ratio, int floor, int threshold) throws Exception {
        MvcResult result = postJson("/api/releases", writeJson(obj(
                "requestId", requestId,
                "model", "m1",
                "fromVersion", "1.0.0",
                "toVersion", "2.0.0",
                "ratio", ratio,
                "sampleFloor", floor,
                "failureThresholdPercent", threshold)));
        expectOk(result);
        return json(result).get("releaseId").asLong();
    }

    private int registerMatrix(String requestId, String firmware, String requirementsJson,
                               String dependenciesJson) throws Exception {
        MvcResult result = postJson("/api/firmware-matrices", writeJson(obj(
                "requestId", requestId,
                "firmwareVersion", firmware,
                "requirements", objectMapper.readTree(requirementsJson),
                "dependencies", objectMapper.readTree(dependenciesJson))));
        expectOk(result);
        return json(result).get("matrixVersion").asInt();
    }

    private void reportModules(String requestId, String deviceId, String modulesJson) throws Exception {
        MvcResult result = postJson("/api/devices/%s/modules".formatted(deviceId),
                writeJson(obj("requestId", requestId,
                        "modules", objectMapper.readTree(modulesJson))));
        expectOk(result);
    }

    @Test
    void 主流程_完整依赖集合满足_下发任务并固化快照_成功回执只更新固件版本() throws Exception {
        // 固件 2.0.0 直接需要 modem@1.0；modem 依赖 crypto@2.0（完整依赖集合 = modem+crypto）
        registerMatrix("r-m", "2.0.0",
                "[{\"module\":\"modem\",\"minVersion\":\"1.0\"}]",
                "[{\"module\":\"modem\",\"dependsOn\":\"crypto\",\"minVersion\":\"2.0\"}]");
        registerDevice("r-d", "d1", null);
        reportModules("r-r", "d1",
                "[{\"module\":\"crypto\",\"version\":\"2.1\"},{\"module\":\"modem\",\"version\":\"1.0\"}]");
        createRelease("r-rel", 100, 2, 50);

        MvcResult pull = postJson("/api/devices/d1/pull", "{\"requestId\":\"r-pull\"}");
        expectOk(pull);
        JsonNode resp = json(pull);
        assertThat(resp.get("blockCode").isNull()).isTrue();
        assertThat(resp.get("task").get("status").asText()).isEqualTo("PENDING");
        assertThat(resp.get("task").get("matrixVersion").asInt()).isEqualTo(1);
        assertThat(resp.get("task").get("exemptionVersion").isNull()).isTrue();
        // 固化的设备模块摘要按模块名排序：crypto 在 modem 前
        assertThat(resp.get("task").get("moduleSummary").get(0).get("module").asText())
                .isEqualTo("crypto");
        long taskId = resp.get("task").get("taskId").asLong();

        // 成功回执：只更新设备固件版本，设备模块清单不被虚构升级
        MvcResult receipt = postJson("/api/tasks/" + taskId + "/receipt",
                "{\"requestId\":\"r-rc\",\"result\":\"SUCCESS\"}");
        expectOk(receipt);
        MvcResult device = mockMvc.perform(get("/api/devices/d1")).andReturn();
        assertThat(json(device).get("currentVersion").asText()).isEqualTo("2.0.0");
        MvcResult modules = mockMvc.perform(get("/api/devices/d1/modules")).andReturn();
        assertThat(modules.getResponse().getStatus()).isEqualTo(200);
        JsonNode moduleList = json(modules).get("modules");
        assertThat(moduleList).hasSize(2);
        assertThat(moduleList.get(0).get("version").asText()).isEqualTo("2.1");
    }

    @Test
    void 缺失或低版本模块_返回MODULE_BLOCKED_稳定排序_不建任务_不计失败率() throws Exception {
        registerMatrix("r-m", "2.0.0",
                "[{\"module\":\"modem\",\"minVersion\":\"1.0\"},{\"module\":\"crypto\",\"minVersion\":\"2.0\"}]",
                "[]");
        registerDevice("r-d", "d1", null);
        // 只上报 crypto 且版本偏低，modem 缺失
        reportModules("r-r", "d1", "[{\"module\":\"crypto\",\"version\":\"1.9\"}]");
        long releaseId = createRelease("r-rel", 100, 2, 50);

        MvcResult pull = postJson("/api/devices/d1/pull", "{\"requestId\":\"r-pull\"}");
        expectOk(pull);
        JsonNode resp = json(pull);
        assertThat(resp.get("task").isNull()).isTrue();
        assertThat(resp.get("blockCode").asText()).isEqualTo("MODULE_BLOCKED");
        assertThat(resp.get("matrixVersion").asInt()).isEqualTo(1);
        assertThat(resp.get("exemptionVersion").isNull()).isTrue();
        JsonNode missing = resp.get("missingModules");
        // 按模块名稳定排序：crypto 在 modem 前
        assertThat(missing).hasSize(2);
        assertThat(resp.get("missingCount").asInt()).isEqualTo(2);
        assertThat(missing.get(0).get("module").asText()).isEqualTo("crypto");
        assertThat(missing.get(0).get("deviceVersion").asText()).isEqualTo("1.9");
        assertThat(missing.get(0).get("requiredVersion").asText()).isEqualTo("2.0");
        assertThat(missing.get(1).get("module").asText()).isEqualTo("modem");
        assertThat(missing.get(1).get("deviceVersion").isNull()).isTrue();
        assertThat(missing.get(1).get("requiredVersion").asText()).isEqualTo("1.0");
        long blockId = resp.get("blockId").asLong();
        assertThat(blockId).isPositive();

        // 不创建任务
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rollout_task", Long.class)).isZero();
        // 阻断可诊断查询
        MvcResult blocks = mockMvc.perform(get("/api/module-blocks/releases/" + releaseId)).andReturn();
        JsonNode blockList = json(blocks);
        assertThat(blockList).hasSize(1);
        assertThat(blockList.get(0).get("blockId").asLong()).isEqualTo(blockId);
        assertThat(blockList.get(0).get("requestId").asText()).isEqualTo("r-pull");
        assertThat(blockList.get(0).get("deviceSummary").get(0).get("module").asText())
                .isEqualTo("crypto");
        // 不计入失败率：监控统计仍为 0，发布单保持 ACTIVE
        MvcResult monitor = mockMvc.perform(get("/api/releases/" + releaseId + "/monitor")).andReturn();
        assertThat(json(monitor).get("roundFailed").asInt()).isZero();
        assertThat(json(monitor).get("status").asText()).isEqualTo("ACTIVE");
    }

    @Test
    void 点分版本非法_登记矩阵与设备上报均422() throws Exception {
        MvcResult badMatrix = postJson("/api/firmware-matrices",
                "{\"requestId\":\"r1\",\"firmwareVersion\":\"2.0.0\","
                        + "\"requirements\":[{\"module\":\"m\",\"minVersion\":\"1.0-rc\"}],\"dependencies\":[]}");
        assertThat(badMatrix.getResponse().getStatus()).isEqualTo(422);
        assertThat(badMatrix.getResponse().getContentAsString()).contains("INVALID_MODULE_VERSION");

        MvcResult badEdge = postJson("/api/firmware-matrices",
                "{\"requestId\":\"r2\",\"firmwareVersion\":\"2.0.0\",\"requirements\":[{\"module\":\"a\",\"minVersion\":\"1\"}],"
                        + "\"dependencies\":[{\"module\":\"a\",\"dependsOn\":\"b\",\"minVersion\":\"oops\"}]}");
        assertThat(badEdge.getResponse().getStatus()).isEqualTo(422);

        registerDevice("r-d", "d9", null);
        MvcResult badReport = postJson("/api/devices/d9/modules",
                "{\"requestId\":\"r3\",\"modules\":[{\"module\":\"m\",\"version\":\"1.2.\"}]}");
        assertThat(badReport.getResponse().getStatus()).isEqualTo(422);

        // 失败不占键：同 requestId 换合法参数可以成功
        MvcResult okReport = postJson("/api/devices/d9/modules",
                "{\"requestId\":\"r3\",\"modules\":[{\"module\":\"m\",\"version\":\"1.2.0\"}]}");
        expectOk(okReport);
    }

    @Test
    void 登记形成依赖环_422且事务回滚不留部分结果() throws Exception {
        // 先合法登记 A@1.0 + 边 A->B
        registerMatrix("r-ok", "fw", "[{\"module\":\"A\",\"minVersion\":\"1.0\"}]",
                "[{\"module\":\"A\",\"dependsOn\":\"B\",\"minVersion\":\"1.0\"}]");

        // 再登记使图成环的边 B->A：422
        MvcResult cyclic = postJson("/api/firmware-matrices",
                "{\"requestId\":\"r-cycle\",\"firmwareVersion\":\"fw2\",\"requirements\":[{\"module\":\"B\",\"minVersion\":\"1.0\"}],"
                        + "\"dependencies\":[{\"module\":\"B\",\"dependsOn\":\"A\",\"minVersion\":\"1.0\"}]}");
        assertThat(cyclic.getResponse().getStatus()).isEqualTo(422);
        assertThat(cyclic.getResponse().getContentAsString()).contains("MODULE_DEPENDENCY_CYCLE");

        // 完整回滚：新需求未落库，矩阵头未产生 fw2，依赖边仍是 1 条，fw 矩阵版本仍为 1
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM firmware_module_requirement"
                + " WHERE firmware_version = 'fw2'", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM firmware_matrix", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM module_dependency", Long.class)).isEqualTo(1);
        MvcResult matrix = mockMvc.perform(get("/api/firmware-matrices/fw")).andReturn();
        expectOk(matrix);
        assertThat(json(matrix).get("matrixVersion").asInt()).isEqualTo(1);
    }

    @Test
    void 豁免_显式列出模块才放行_到期自动失效_历史可查() throws Exception {
        registerMatrix("r-m", "2.0.0",
                "[{\"module\":\"crypto\",\"minVersion\":\"2.0\"},{\"module\":\"modem\",\"minVersion\":\"1.0\"}]",
                "[]");
        registerDevice("r-d", "d1", "beta");
        // 两个模块都缺失
        long releaseId = createRelease("r-rel", 100, 2, 50);

        // 豁免 crypto 到 T0+3600：modem 仍缺失，继续阻断，记录豁免版本1
        MvcResult exemption = postJson("/api/releases/" + releaseId + "/exemptions",
                "{\"requestId\":\"r-ex1\",\"groupName\":\"beta\",\"modules\":[\"crypto\"],"
                        + "\"expiresAtUtc\":\"2026-09-28T01:00:00Z\"}");
        expectOk(exemption);
        assertThat(json(exemption).get("version").asInt()).isEqualTo(1);
        assertThat(json(exemption).get("active").asBoolean()).isTrue();
        // 时长实际值=3600秒，剩余差额=3600秒（当前时钟仍为 T0）
        assertThat(json(exemption).get("durationSeconds").asLong()).isEqualTo(3600);
        assertThat(json(exemption).get("remainingSeconds").asLong()).isEqualTo(3600);

        MvcResult blocked = postJson("/api/devices/d1/pull", "{\"requestId\":\"r-pull1\"}");
        JsonNode blockedResp = json(blocked);
        assertThat(blockedResp.get("blockCode").asText()).isEqualTo("MODULE_BLOCKED");
        assertThat(blockedResp.get("missingModules")).hasSize(1);
        assertThat(blockedResp.get("missingModules").get(0).get("module").asText()).isEqualTo("modem");
        assertThat(blockedResp.get("exemptionVersion").asInt()).isEqualTo(1);

        // 修订豁免：加上 modem（版本2）
        MvcResult exemption2 = postJson("/api/releases/" + releaseId + "/exemptions",
                "{\"requestId\":\"r-ex2\",\"groupName\":\"beta\",\"modules\":[\"crypto\",\"modem\"],"
                        + "\"expiresAtUtc\":\"2026-09-28T02:00:00Z\"}");
        expectOk(exemption2);
        assertThat(json(exemption2).get("version").asInt()).isEqualTo(2);

        // 全部豁免：放行，任务固化豁免版本2
        MvcResult pull = postJson("/api/devices/d1/pull", "{\"requestId\":\"r-pull2\"}");
        expectOk(pull);
        JsonNode taskResp = json(pull);
        assertThat(taskResp.get("blockCode").isNull()).isTrue();
        assertThat(taskResp.get("task").get("exemptionVersion").asInt()).isEqualTo(2);
        assertThat(taskResp.get("task").get("moduleSummary").isNull()).isTrue();

        // 历史：两个版本，v1 有 supersededAt，v2 为当前
        MvcResult history = mockMvc.perform(get("/api/releases/" + releaseId + "/exemptions/history")
                .param("group", "beta")).andReturn();
        JsonNode historyList = json(history);
        assertThat(historyList).hasSize(2);
        assertThat(historyList.get(0).get("version").asInt()).isEqualTo(1);
        assertThat(historyList.get(0).get("supersededAtUtc").isNull()).isFalse();
        assertThat(historyList.get(1).get("version").asInt()).isEqualTo(2);
        assertThat(historyList.get(1).get("supersededAtUtc").isNull()).isTrue();

        // 到期后自动失效：推进时钟超过 02:00，同组新设备不再享受豁免
        advanceSeconds(7200);
        MvcResult getCurrent = mockMvc.perform(get("/api/releases/" + releaseId + "/exemptions")
                .param("group", "beta")).andReturn();
        expectOk(getCurrent);
        assertThat(json(getCurrent).get("active").asBoolean()).isFalse();
        // 到期后剩余差额 <= 0
        assertThat(json(getCurrent).get("remainingSeconds").asLong()).isLessThanOrEqualTo(0);
        assertThat(json(getCurrent).get("durationSeconds").asLong()).isEqualTo(7200);
        registerDevice("r-d2", "d2", "beta");
        MvcResult expiredBlock = postJson("/api/devices/d2/pull", "{\"requestId\":\"r-pull3\"}");
        JsonNode expiredResp = json(expiredBlock);
        assertThat(expiredResp.get("blockCode").asText()).isEqualTo("MODULE_BLOCKED");
        assertThat(expiredResp.get("exemptionVersion").isNull()).isTrue();
        assertThat(expiredResp.get("missingModules")).hasSize(2);
    }

    @Test
    void 豁免_到期时刻必须未来_显式模块不可为空_未分组设备不豁免() throws Exception {
        long releaseId = createRelease("r-rel", 100, 2, 50);

        MvcResult past = postJson("/api/releases/" + releaseId + "/exemptions",
                "{\"requestId\":\"r1\",\"groupName\":\"g\",\"modules\":[\"m\"],"
                        + "\"expiresAtUtc\":\"2026-09-27T00:00:00Z\"}");
        assertThat(past.getResponse().getStatus()).isEqualTo(400);
        assertThat(past.getResponse().getContentAsString()).contains("EXEMPTION_ALREADY_EXPIRED");

        MvcResult badTime = postJson("/api/releases/" + releaseId + "/exemptions",
                "{\"requestId\":\"r2\",\"groupName\":\"g\",\"modules\":[\"m\"],\"expiresAtUtc\":\"not-a-time\"}");
        assertThat(badTime.getResponse().getStatus()).isEqualTo(400);

        MvcResult empty = postJson("/api/releases/" + releaseId + "/exemptions",
                "{\"requestId\":\"r3\",\"groupName\":\"g\",\"modules\":[],"
                        + "\"expiresAtUtc\":\"2026-09-28T01:00:00Z\"}");
        assertThat(empty.getResponse().getStatus()).isEqualTo(400);

        MvcResult duplicate = postJson("/api/releases/" + releaseId + "/exemptions",
                "{\"requestId\":\"r4\",\"groupName\":\"g\",\"modules\":[\"m\",\"m\"],"
                        + "\"expiresAtUtc\":\"2026-09-28T01:00:00Z\"}");
        assertThat(duplicate.getResponse().getStatus()).isEqualTo(400);

        // 未分组设备不享受组豁免
        registerMatrix("r-m", "2.0.0", "[{\"module\":\"m\",\"minVersion\":\"1.0\"}]", "[]");
        registerDevice("r-d", "d1", null);
        MvcResult pull = postJson("/api/devices/d1/pull", "{\"requestId\":\"r-pull\"}");
        assertThat(json(pull).get("blockCode").asText()).isEqualTo("MODULE_BLOCKED");
        assertThat(json(pull).get("exemptionVersion").isNull()).isTrue();
    }

    @Test
    void 豁免不绕过失败率暂停_暂停期间不创建新任务() throws Exception {
        registerMatrix("r-m", "2.0.0", "[{\"module\":\"m\",\"minVersion\":\"1.0\"}]", "[]");
        // d1 满足模块，d2 缺模块但组豁免
        registerDevice("r-d1", "d1", null);
        registerDevice("r-d2", "d2", "beta");
        reportModules("r-r1", "d1", "[{\"module\":\"m\",\"version\":\"1.0\"}]");
        long releaseId = createRelease("r-rel", 100, 2, 100);
        postJson("/api/releases/" + releaseId + "/exemptions",
                "{\"requestId\":\"r-ex\",\"groupName\":\"beta\",\"modules\":[\"m\"],"
                        + "\"expiresAtUtc\":\"2026-09-28T05:00:00Z\"}");

        long t1 = json(postJson("/api/devices/d1/pull", "{\"requestId\":\"r-p1\"}"))
                .get("task").get("taskId").asLong();
        postJson("/api/tasks/" + t1 + "/receipt",
                "{\"requestId\":\"r-rc1\",\"result\":\"FAILED\"}");
        // floor=2 需要第二个样本，用 d2 下发后也失败
        MvcResult p2 = postJson("/api/devices/d2/pull", "{\"requestId\":\"r-p2\"}");
        long t2 = json(p2).get("task").get("taskId").asLong();
        postJson("/api/tasks/" + t2 + "/receipt",
                "{\"requestId\":\"r-rc2\",\"result\":\"FAILED\"}");
        assertThat(json(mockMvc.perform(get("/api/releases/" + releaseId + "/monitor")).andReturn())
                .get("status").asText()).isEqualTo("PAUSED");

        // PAUSED 后新设备即使有有效豁免也不创建任务（豁免不绕过暂停门禁）
        registerDevice("r-d3", "d3", "beta");
        MvcResult pullDuringPause = postJson("/api/devices/d3/pull", "{\"requestId\":\"r-p3\"}");
        expectOk(pullDuringPause);
        assertThat(json(pullDuringPause).get("task").isNull()).isTrue();
        assertThat(json(pullDuringPause).get("blockCode").isNull()).isTrue();
    }

    @Test
    void 矩阵修订与设备重报不改写历史任务快照() throws Exception {
        registerMatrix("r-m1", "2.0.0", "[{\"module\":\"m\",\"minVersion\":\"1.0\"}]", "[]");
        registerDevice("r-d", "d1", null);
        reportModules("r-r1", "d1", "[{\"module\":\"m\",\"version\":\"1.5\"}]");
        long releaseId = createRelease("r-rel", 100, 2, 50);
        JsonNode firstPull = json(postJson("/api/devices/d1/pull", "{\"requestId\":\"r-p1\"}"));
        long taskId = firstPull.get("task").get("taskId").asLong();
        assertThat(firstPull.get("task").get("matrixVersion").asInt()).isEqualTo(1);

        // 矩阵修订抬高要求、设备重报变化版本
        registerMatrix("r-m2", "2.0.0", "[{\"module\":\"m\",\"minVersion\":\"3.0\"}]", "[]");
        reportModules("r-r2", "d1", "[{\"module\":\"m\",\"version\":\"9.9.9\"}]");

        // 历史任务仍返回固化快照
        JsonNode tasks = json(mockMvc.perform(get("/api/releases/" + releaseId + "/tasks")).andReturn());
        JsonNode task = tasks.get("tasks").get(0);
        assertThat(task.get("matrixVersion").asInt()).isEqualTo(1);
        assertThat(task.get("moduleSummary").get(0).get("version").asText()).isEqualTo("1.5");
        // 同设备重复拉取仍返回原任务（原任务 PENDING）
        JsonNode replayPull = json(postJson("/api/devices/d1/pull", "{\"requestId\":\"r-p2\"}"));
        assertThat(replayPull.get("task").get("taskId").asLong()).isEqualTo(taskId);
        assertThat(replayPull.get("task").get("moduleSummary").get(0).get("version").asText())
                .isEqualTo("1.5");
    }

    @Test
    void 幂等_阻断重放同一记录_矩阵登记重放不加版本_异参409_失败不占键() throws Exception {
        registerMatrix("r-m1", "2.0.0", "[{\"module\":\"m\",\"minVersion\":\"1.0\"}]", "[]");
        registerDevice("r-d", "d1", null);
        long releaseId = createRelease("r-rel", 100, 2, 50);

        MvcResult b1 = postJson("/api/devices/d1/pull", "{\"requestId\":\"r-same\"}");
        MvcResult b2 = postJson("/api/devices/d1/pull", "{\"requestId\":\"r-same\"}");
        expectOk(b2);
        assertThat(b1.getResponse().getContentAsString()).isEqualTo(b2.getResponse().getContentAsString());
        // 阻断诊断只落一条
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM module_block_record", Long.class)).isEqualTo(1);

        // 矩阵登记同 requestId 同参重放：版本不加
        String matrixJson = "{\"requestId\":\"r-mx\",\"firmwareVersion\":\"3.0.0\","
                + "\"requirements\":[{\"module\":\"m\",\"minVersion\":\"1.0\"}],\"dependencies\":[]}";
        MvcResult m1 = postJson("/api/firmware-matrices", matrixJson);
        MvcResult m2 = postJson("/api/firmware-matrices", matrixJson);
        expectOk(m2);
        assertThat(m1.getResponse().getContentAsString()).isEqualTo(m2.getResponse().getContentAsString());
        assertThat(jdbc.queryForObject("SELECT matrix_version FROM firmware_matrix WHERE firmware_version = '3.0.0'",
                Integer.class)).isEqualTo(1);

        // 同 requestId 异参：409
        MvcResult conflict = postJson("/api/firmware-matrices",
                "{\"requestId\":\"r-mx\",\"firmwareVersion\":\"4.0.0\","
                        + "\"requirements\":[{\"module\":\"m\",\"minVersion\":\"1.0\"}],\"dependencies\":[]}");
        assertThat(conflict.getResponse().getStatus()).isEqualTo(409);
        assertThat(conflict.getResponse().getContentAsString()).contains("REQUEST_ID_CONFLICT");
    }

    @Test
    void 豁免左闭右开_到期时刻恰好失效_前一秒仍生效() throws Exception {
        registerMatrix("r-m", "2.0.0", "[{\"module\":\"m\",\"minVersion\":\"1.0\"}]", "[]");
        registerDevice("r-d1", "d1", "beta");
        registerDevice("r-d2", "d2", "beta");
        long releaseId = createRelease("r-rel", 100, 2, 50);
        // 豁免到 T0+100 秒
        MvcResult exemption = postJson("/api/releases/" + releaseId + "/exemptions",
                writeJson(obj("requestId", "r-ex", "groupName", "beta",
                        "modules", List.of("m"),
                        "expiresAtUtc", "2026-09-28T00:01:40Z")));
        expectOk(exemption);
        assertThat(json(exemption).get("durationSeconds").asLong()).isEqualTo(100);

        // T0+99：仍在 [创建, 到期) 区间内，放行
        advanceSeconds(99);
        MvcResult before = postJson("/api/devices/d1/pull", "{\"requestId\":\"r-pull-before\"}");
        expectOk(before);
        assertThat(json(before).get("task")).isNotNull();
        assertThat(json(before).get("task").get("exemptionVersion").asInt()).isEqualTo(1);

        // T0+100：恰好到期，左闭右开区间关闭，阻断且豁免版本为 null
        advanceSeconds(1);
        MvcResult atExpiry = postJson("/api/devices/d2/pull", "{\"requestId\":\"r-pull-expiry\"}");
        expectOk(atExpiry);
        JsonNode blocked = json(atExpiry);
        assertThat(blocked.get("blockCode").asText()).isEqualTo("MODULE_BLOCKED");
        assertThat(blocked.get("exemptionVersion").isNull()).isTrue();
        assertThat(blocked.get("missingCount").asInt()).isEqualTo(1);
    }

    @Test
    void 设备上报_按提交顺序全量替换_版本递增() throws Exception {
        registerDevice("r-d", "d1", null);
        MvcResult r1 = postJson("/api/devices/d1/modules",
                "{\"requestId\":\"r1\",\"modules\":[{\"module\":\"a\",\"version\":\"1.0\"}]}");
        expectOk(r1);
        assertThat(json(r1).get("reportVersion").asInt()).isEqualTo(1);
        MvcResult r2 = postJson("/api/devices/d1/modules",
                "{\"requestId\":\"r2\",\"modules\":[{\"module\":\"a\",\"version\":\"2.0\"},"
                        + "{\"module\":\"b\",\"version\":\"1.0\"}]}");
        expectOk(r2);
        assertThat(json(r2).get("reportVersion").asInt()).isEqualTo(2);
        // 全量替换：清单为 2 条，旧版本不残留
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM device_module", Long.class)).isEqualTo(2);
        // 重复模块 400
        MvcResult dup = postJson("/api/devices/d1/modules",
                "{\"requestId\":\"r3\",\"modules\":[{\"module\":\"a\",\"version\":\"1.0\"},"
                        + "{\"module\":\"a\",\"version\":\"2.0\"}]}");
        assertThat(dup.getResponse().getStatus()).isEqualTo(400);
        // 设备不存在 404
        MvcResult ghost = postJson("/api/devices/ghost/modules",
                "{\"requestId\":\"r4\",\"modules\":[{\"module\":\"a\",\"version\":\"1.0\"}]}");
        assertThat(ghost.getResponse().getStatus()).isEqualTo(404);
        // 查询不存在的矩阵 404
        assertThat(mockMvc.perform(get("/api/firmware-matrices/none")).andReturn()
                .getResponse().getStatus()).isEqualTo(404);
    }
}
