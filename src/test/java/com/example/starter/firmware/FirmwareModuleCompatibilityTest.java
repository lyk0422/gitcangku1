package com.example.starter.firmware;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 固件模块兼容端到端测试（H2 内存库 MODE=MySQL）：矩阵登记与依赖环、设备模块上报、
 * 拉取逐项兼容校验、MODULE_BLOCKED 稳定排序、豁免版本化与到期左闭右开、证据快照冻结、
 * 幂等、事务回滚、并发拉取与失败率暂停门禁。所有时间通过可控 UTC 时钟驱动。
 */
@SpringBootTest
@AutoConfigureMockMvc
class FirmwareModuleCompatibilityTest {

    /**
     * 可控 UTC 时钟：豁免到期边界、登记时刻均由测试推进，杜绝靠睡眠判断。
     */
    static final MutableClock CLOCK = new MutableClock(Instant.parse("2026-10-01T00:00:00Z"));

    static class MutableClock extends Clock {
        private volatile Instant instant;

        MutableClock(Instant instant) {
            this.instant = instant;
        }

        void setInstant(String value) {
            this.instant = Instant.parse(value);
        }

        @Override
        public Instant instant() {
            return instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    @TestConfiguration
    static class ClockConfig {
        @Bean
        @Primary
        Clock testClock() {
            return CLOCK;
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void cleanUp() {
        jdbc.update("DELETE FROM compatibility_check");
        jdbc.update("DELETE FROM rollout_task");
        jdbc.update("DELETE FROM module_exemption");
        jdbc.update("DELETE FROM release_pause_record");
        jdbc.update("DELETE FROM release_resume_record");
        jdbc.update("DELETE FROM release_order");
        jdbc.update("DELETE FROM device_module");
        jdbc.update("DELETE FROM device");
        jdbc.update("DELETE FROM module_dependency");
        jdbc.update("DELETE FROM firmware_requirement");
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("UPDATE matrix_state SET version = 0, updated_at_utc = NULL WHERE id = 0");
        CLOCK.setInstant("2026-10-01T00:00:00Z");
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private void registerDevice(String requestId, String deviceId, String model, String version,
                                int bucket, String group) throws Exception {
        String body = """
                {"requestId":"%s","deviceId":"%s","model":"%s","currentVersion":"%s","bucketNo":%d%s}
                """.formatted(requestId, deviceId, model, version, bucket,
                group == null ? "" : ",\"deviceGroup\":\"" + group + "\"");
        mockMvc.perform(post("/api/devices").contentType("application/json").content(body))
                .andExpect(status().isOk());
    }

    private long createRelease(String requestId, String model, int ratio, Integer floor, Integer threshold)
            throws Exception {
        String body = """
                {"requestId":"%s","model":"%s","fromVersion":"1.0.0","toVersion":"2.0.0","ratio":%d%s%s}
                """.formatted(requestId, model, ratio,
                floor == null ? "" : ",\"sampleFloor\":" + floor,
                threshold == null ? "" : ",\"failureThresholdPercent\":" + threshold);
        MvcResult result = mockMvc.perform(post("/api/releases").contentType("application/json").content(body))
                .andExpect(status().isOk()).andReturn();
        return json(result).get("releaseId").asLong();
    }

    private void registerFirmwareRequirement(String requestId, String model, String firmware,
                                             String module, String minVersion) throws Exception {
        mockMvc.perform(post("/api/matrix/firmware-requirements").contentType("application/json").content("""
                {"requestId":"%s","model":"%s","firmwareVersion":"%s","moduleName":"%s","minVersion":"%s"}
                """.formatted(requestId, model, firmware, module, minVersion)))
                .andExpect(status().isOk());
    }

    private void registerDependency(String requestId, String from, String to, String minVersion) throws Exception {
        mockMvc.perform(post("/api/matrix/module-dependencies").contentType("application/json").content("""
                {"requestId":"%s","moduleName":"%s","dependsOn":"%s","minVersion":"%s"}
                """.formatted(requestId, from, to, minVersion)))
                .andExpect(status().isOk());
    }

    private void reportModules(String deviceId, String requestId, String... triples) throws Exception {
        List<String> entries = new ArrayList<>();
        for (int i = 0; i < triples.length; i += 3) {
            entries.add("""
                    {"moduleName":"%s","version":"%s","digest":"%s"}"""
                    .formatted(triples[i], triples[i + 1], triples[i + 2]));
        }
        mockMvc.perform(post("/api/devices/" + deviceId + "/modules").contentType("application/json").content("""
                {"requestId":"%s","modules":[%s]}""".formatted(requestId, String.join(",", entries))))
                .andExpect(status().isOk());
    }

    private MvcResult pull(String deviceId, String requestId) throws Exception {
        return mockMvc.perform(post("/api/devices/" + deviceId + "/pull").contentType("application/json")
                        .content("{\"requestId\":\"%s\"}".formatted(requestId)))
                .andExpect(status().isOk()).andReturn();
    }

    private void modifyExemptions(long releaseId, String requestId, String entriesJson) throws Exception {
        mockMvc.perform(post("/api/releases/" + releaseId + "/exemptions")
                        .contentType("application/json")
                        .content("{\"requestId\":\"%s\",\"entries\":%s}".formatted(requestId, entriesJson)))
                .andExpect(status().isOk());
    }

    @Test
    void 矩阵登记_成功加版本_非法版本422_依赖成环422且回滚() throws Exception {
        registerFirmwareRequirement("m1", "m1", "2.0.0", "modA", "1.0");
        mockMvc.perform(get("/api/matrix"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.firmwareRequirements").value(1))
                .andExpect(jsonPath("$.updatedAtUtc").value("2026-10-01T00:00:00Z"));

        // 非法点分版本：422，不占键、版本不增加
        mockMvc.perform(post("/api/matrix/firmware-requirements").contentType("application/json").content("""
                {"requestId":"bad1","model":"m1","firmwareVersion":"2.x","moduleName":"modA","minVersion":"1.0"}
                """)).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_VERSION_FORMAT"));
        mockMvc.perform(post("/api/matrix/firmware-requirements").contentType("application/json").content("""
                {"requestId":"bad2","model":"m1","firmwareVersion":"2.0.0","moduleName":"modA","minVersion":"01"}
                """)).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_VERSION_FORMAT"));
        mockMvc.perform(get("/api/matrix")).andExpect(jsonPath("$.version").value(1));

        // 合法依赖边：版本继续增加
        registerDependency("d1", "modA", "modB", "2.0");
        mockMvc.perform(get("/api/matrix"))
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.moduleDependencies").value(1));

        // 成环后态：422，边不落库、版本不增加（无部分结果）
        mockMvc.perform(post("/api/matrix/module-dependencies").contentType("application/json").content("""
                {"requestId":"cyc1","moduleName":"modB","dependsOn":"modA","minVersion":"1.0"}
                """)).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("MODULE_DEPENDENCY_CYCLE"));
        // 自依赖同样成环
        mockMvc.perform(post("/api/matrix/module-dependencies").contentType("application/json").content("""
                {"requestId":"cyc2","moduleName":"modB","dependsOn":"modB","minVersion":"0"}
                """)).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("MODULE_DEPENDENCY_CYCLE"));
        mockMvc.perform(get("/api/matrix")).andExpect(jsonPath("$.version").value(2));
        mockMvc.perform(get("/api/matrix/module-dependencies"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].moduleName").value("modA"))
                .andExpect(jsonPath("$[0].dependsOn").value("modB"));

        // 失败不占键：成环用过的 requestId 换一条合法边可成功
        registerDependency("cyc1", "modB", "modC", "1.0");
        mockMvc.perform(get("/api/matrix")).andExpect(jsonPath("$.version").value(3));
    }

    @Test
    void 设备模块上报_全量替换_非法与重复422_不改固件版本() throws Exception {
        registerDevice("r0", "d1", "m1", "1.0.0", 1, null);
        reportModules("d1", "r1", "modA", "1.0", "sha-a1", "modB", "2.0", "sha-b1");
        mockMvc.perform(get("/api/devices/d1/modules"))
                .andExpect(jsonPath("$.modules.length()").value(2))
                .andExpect(jsonPath("$.modules[0].moduleName").value("modA"))
                .andExpect(jsonPath("$.modules[1].digest").value("sha-b1"))
                .andExpect(jsonPath("$.reportedAtUtc").value("2026-10-01T00:00:00Z"));

        // 非法版本：422，旧清单保留
        mockMvc.perform(post("/api/devices/d1/modules").contentType("application/json").content("""
                {"requestId":"bad","modules":[{"moduleName":"modA","version":"1.x","digest":"x"}]}
                """)).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_VERSION_FORMAT"));
        // 重复模块：422
        mockMvc.perform(post("/api/devices/d1/modules").contentType("application/json").content("""
                {"requestId":"dup","modules":[
                {"moduleName":"modA","version":"1.0","digest":"x"},
                {"moduleName":"modA","version":"2.0","digest":"y"}]}
                """)).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DUPLICATE_MODULE"));
        mockMvc.perform(get("/api/devices/d1/modules")).andExpect(jsonPath("$.modules.length()").value(2));

        // 后提交的上报整体覆盖
        CLOCK.setInstant("2026-10-01T01:00:00Z");
        reportModules("d1", "r2", "modA", "1.5", "sha-a2");
        mockMvc.perform(get("/api/devices/d1/modules"))
                .andExpect(jsonPath("$.modules.length()").value(1))
                .andExpect(jsonPath("$.modules[0].version").value("1.5"))
                .andExpect(jsonPath("$.modules[0].digest").value("sha-a2"))
                .andExpect(jsonPath("$.reportedAtUtc").value("2026-10-01T01:00:00Z"));
        // 上报模块绝不自动改动固件版本
        mockMvc.perform(get("/api/devices/d1")).andExpect(jsonPath("$.currentVersion").value("1.0.0"));

        // 设备不存在：404
        mockMvc.perform(get("/api/devices/ghost/modules")).andExpect(status().isNotFound());
    }

    @Test
    void 主流程_完整依赖闭包逐项满足_GRANTED并固化快照与矩阵版本() throws Exception {
        registerDevice("r0", "d1", "m1", "1.0.0", 1, null);
        long releaseId = createRelease("r1", "m1", 100, null, null);
        registerFirmwareRequirement("m1", "m1", "2.0.0", "modA", "1.0");
        registerDependency("d1", "modA", "modB", "2.0");
        reportModules("d1", "r2", "modA", "1.2", "sha-a", "modB", "2.5", "sha-b");

        MvcResult result = pull("d1", "p1");
        JsonNode body = json(result);
        assertThat(body.get("compatibility").asText()).isEqualTo("GRANTED");
        long taskId = body.get("task").get("taskId").asLong();
        assertThat(body.get("task").get("matrixVersion").asInt()).isEqualTo(2);
        assertThat(body.get("task").get("exemptionVersion").asInt()).isZero();
        assertThat(body.get("task").get("grantedVia").asText()).isEqualTo("DIRECT");
        assertThat(body.get("task").get("deviceModules")).hasSize(2);
        assertThat(body.get("task").get("deviceModules").get(0).get("digest").asText()).isEqualTo("sha-a");
        assertThat(body.get("matrixVersion").asInt()).isEqualTo(2);

        // 回执 SUCCESS 才更新固件版本，模块清单不被虚构升级
        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"rc1\",\"result\":\"SUCCESS\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/devices/d1"))
                .andExpect(jsonPath("$.currentVersion").value("2.0.0"));
        mockMvc.perform(get("/api/devices/d1/modules"))
                .andExpect(jsonPath("$.modules.length()").value(2))
                .andExpect(jsonPath("$.modules[0].version").value("1.2"));

        // 判定证据可查（设备与发布单两个维度）
        mockMvc.perform(get("/api/devices/d1/checks"))
                .andExpect(jsonPath("$.checks.length()").value(1))
                .andExpect(jsonPath("$.checks[0].outcome").value("GRANTED"))
                .andExpect(jsonPath("$.checks[0].matrixVersion").value(2))
                .andExpect(jsonPath("$.checks[0].releaseId").value(releaseId));
        mockMvc.perform(get("/api/releases/" + releaseId + "/checks"))
                .andExpect(jsonPath("$.checks.length()").value(1));
    }

    @Test
    void 缺失模块_MODULE_BLOCKED_稳定排序_无任务不计失败率_证据固化() throws Exception {
        registerDevice("r0", "d1", "m1", "1.0.0", 1, null);
        long releaseId = createRelease("r1", "m1", 100, 2, 50);
        registerFirmwareRequirement("z", "m1", "2.0.0", "zeta", "1.0");
        registerFirmwareRequirement("a", "m1", "2.0.0", "alpha", "1.0");
        registerFirmwareRequirement("m", "m1", "2.0.0", "mid", "1.0");

        MvcResult result = pull("d1", "p1");
        JsonNode body = json(result);
        assertThat(body.get("compatibility").asText()).isEqualTo("MODULE_BLOCKED");
        assertThat(body.get("task").isNull()).isTrue();
        List<String> names = new ArrayList<>();
        for (JsonNode item : body.get("blockedModules")) {
            names.add(item.get("moduleName").asText());
            assertThat(item.get("deviceVersion").isNull()).isTrue();
            assertThat(item.get("requiredVersion").asText()).isEqualTo("1.0");
            assertThat(item.get("reason").asText()).isEqualTo("MISSING");
        }
        assertThat(names).containsExactly("alpha", "mid", "zeta");

        // 不创建任务
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rollout_task", Long.class)).isZero();
        // 阻断不计入失败率：监控仍 ACTIVE 且统计为0
        mockMvc.perform(get("/api/releases/" + releaseId + "/monitor"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.roundFailed").value(0))
                .andExpect(jsonPath("$.roundSuccess").value(0));
        // 阻断证据固化
        mockMvc.perform(get("/api/devices/d1/checks"))
                .andExpect(jsonPath("$.checks.length()").value(1))
                .andExpect(jsonPath("$.checks[0].outcome").value("BLOCKED"))
                .andExpect(jsonPath("$.checks[0].blockedModules.length()").value(3))
                .andExpect(jsonPath("$.checks[0].blockedModules[0].moduleName").value("alpha"));
    }

    @Test
    void 低版本模块_VERSION_TOO_LOW并返回设备版本与最低要求() throws Exception {
        registerDevice("r0", "d1", "m1", "1.0.0", 1, null);
        createRelease("r1", "m1", 100, null, null);
        registerFirmwareRequirement("m1", "m1", "2.0.0", "modA", "1.10");
        // 传递依赖低版本同样阻断
        registerDependency("d1", "modA", "modB", "2.0");
        reportModules("d1", "r2", "modA", "1.9", "sha-a", "modB", "1.0", "sha-b");

        JsonNode body = json(pull("d1", "p1"));
        assertThat(body.get("compatibility").asText()).isEqualTo("MODULE_BLOCKED");
        List<String> names = new ArrayList<>();
        for (JsonNode item : body.get("blockedModules")) {
            names.add(item.get("moduleName").asText());
        }
        assertThat(names).containsExactly("modA", "modB");
        JsonNode modA = body.get("blockedModules").get(0);
        assertThat(modA.get("deviceVersion").asText()).isEqualTo("1.9");
        assertThat(modA.get("requiredVersion").asText()).isEqualTo("1.10");
        assertThat(modA.get("reason").asText()).isEqualTo("VERSION_TOO_LOW");
        JsonNode modB = body.get("blockedModules").get(1);
        assertThat(modB.get("deviceVersion").asText()).isEqualTo("1.0");
        assertThat(modB.get("requiredVersion").asText()).isEqualTo("2.0");
    }

    @Test
    void 豁免_到期前放行并固化版本_到期当刻与到期后自动失效_他组不适用() throws Exception {
        registerDevice("g1", "d1", "m1", "1.0.0", 1, "vip");
        registerDevice("g2", "d2", "m1", "1.0.0", 2, "other");
        registerDevice("g3", "d3", "m1", "1.0.0", 3, null);
        registerDevice("g4", "d4", "m1", "1.0.0", 4, "vip");
        long releaseId = createRelease("r1", "m1", 100, null, null);
        registerFirmwareRequirement("m1", "m1", "2.0.0", "modA", "1.0");
        // d1 未安装 modA：无豁免时阻断
        assertThat(json(pull("d1", "p0")).get("compatibility").asText()).isEqualTo("MODULE_BLOCKED");

        modifyExemptions(releaseId, "ex1", """
                [{"deviceGroup":"vip","moduleName":"modA","expiresAtUtc":"2026-10-02T00:00:00Z"}]""");
        mockMvc.perform(get("/api/releases/" + releaseId + "/exemptions"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.entries[0].deviceGroup").value("vip"))
                .andExpect(jsonPath("$.entries[0].expiresAtUtc").value("2026-10-02T00:00:00Z"));

        // 到期前 1 秒：豁免放行
        CLOCK.setInstant("2026-10-01T23:59:59Z");
        JsonNode granted = json(pull("d1", "p1"));
        assertThat(granted.get("compatibility").asText()).isEqualTo("EXEMPTION");
        assertThat(granted.get("task").get("grantedVia").asText()).isEqualTo("EXEMPTION");
        assertThat(granted.get("task").get("exemptionVersion").asInt()).isEqualTo(1);
        List<String> exemptedModules = new ArrayList<>();
        granted.get("exemptedModules").forEach(node -> exemptedModules.add(node.asText()));
        assertThat(exemptedModules).containsExactly("modA");

        // 他组与无组设备不适用豁免
        assertThat(json(pull("d2", "p2")).get("compatibility").asText()).isEqualTo("MODULE_BLOCKED");
        assertThat(json(pull("d3", "p3")).get("compatibility").asText()).isEqualTo("MODULE_BLOCKED");

        // 到期当刻（左闭右开）：豁免已失效
        CLOCK.setInstant("2026-10-02T00:00:00Z");
        assertThat(json(pull("d4", "p4")).get("compatibility").asText()).isEqualTo("MODULE_BLOCKED");
        // 到期后依旧失效
        CLOCK.setInstant("2026-10-03T00:00:00Z");
        // d1 已有任务仍返回原任务（豁免放行创建的任务不因到期而撤销）
        JsonNode d1Again = json(pull("d1", "p1b"));
        assertThat(d1Again.get("task").get("taskId").asLong())
                .isEqualTo(granted.get("task").get("taskId").asLong());
        assertThat(d1Again.get("task").get("exemptionVersion").asInt()).isEqualTo(1);
    }

    @Test
    void 豁免不绕过失败率暂停_豁免下发任务回执照样计入统计() throws Exception {
        registerDevice("d1r", "d1", "m1", "1.0.0", 1, "vip");
        registerDevice("d2r", "d2", "m1", "1.0.0", 2, "vip");
        registerDevice("d3r", "d3", "m1", "1.0.0", 3, "vip");
        long releaseId = createRelease("r1", "m1", 100, 2, 100);
        registerFirmwareRequirement("m1", "m1", "2.0.0", "modA", "1.0");
        modifyExemptions(releaseId, "ex1", """
                [{"deviceGroup":"vip","moduleName":"modA","expiresAtUtc":"2026-11-01T00:00:00Z"}]""");

        long t1 = json(pull("d1", "p1")).get("task").get("taskId").asLong();
        long t2 = json(pull("d2", "p2")).get("task").get("taskId").asLong();
        mockMvc.perform(post("/api/tasks/" + t1 + "/receipt").contentType("application/json")
                .content("{\"requestId\":\"f1\",\"result\":\"FAILED\"}")).andExpect(status().isOk());
        mockMvc.perform(post("/api/tasks/" + t2 + "/receipt").contentType("application/json")
                .content("{\"requestId\":\"f2\",\"result\":\"FAILED\"}")).andExpect(status().isOk());
        // 豁免放行的任务失败同样计入失败率：发布单自动暂停
        mockMvc.perform(get("/api/releases/" + releaseId + "/monitor"))
                .andExpect(jsonPath("$.status").value("PAUSED"))
                .andExpect(jsonPath("$.roundFailed").value(2));

        // 豁免不绕过失败率暂停：PAUSED 下新设备连兼容判定都不进入，无任务、无判定证据
        JsonNode blockedByPause = json(pull("d3", "p3"));
        assertThat(blockedByPause.get("task").isNull()).isTrue();
        assertThat(blockedByPause.get("compatibility").isNull()).isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM compatibility_check WHERE device_id = 'd3'", Long.class)).isZero();
    }

    @Test
    void 快照冻结_设备再上报与矩阵修订不改写历史任务与证据() throws Exception {
        registerDevice("r0", "d1", "m1", "1.0.0", 1, null);
        long releaseId = createRelease("r1", "m1", 100, null, null);
        registerFirmwareRequirement("m1", "m1", "2.0.0", "modA", "1.0");
        reportModules("d1", "rep1", "modA", "1.0", "sha-old");
        long taskId = json(pull("d1", "p1")).get("task").get("taskId").asLong();

        // 矩阵修订：新版本
        registerFirmwareRequirement("m2", "m1", "2.0.0", "modB", "1.0");
        // 设备再上报变化
        reportModules("d1", "rep2", "modA", "9.9", "sha-new", "modB", "1.0", "sha-b");

        JsonNode again = json(pull("d1", "p2"));
        assertThat(again.get("task").get("taskId").asLong()).isEqualTo(taskId);
        assertThat(again.get("task").get("matrixVersion").asInt()).isEqualTo(1);
        assertThat(again.get("task").get("deviceModules")).hasSize(1);
        assertThat(again.get("task").get("deviceModules").get(0).get("version").asText()).isEqualTo("1.0");
        assertThat(again.get("task").get("deviceModules").get(0).get("digest").asText()).isEqualTo("sha-old");

        // 历史证据不回写：仍是下发时的矩阵版本与旧清单
        mockMvc.perform(get("/api/releases/" + releaseId + "/checks"))
                .andExpect(jsonPath("$.checks.length()").value(1))
                .andExpect(jsonPath("$.checks[0].matrixVersion").value(1))
                .andExpect(jsonPath("$.checks[0].deviceModules.length()").value(1))
                .andExpect(jsonPath("$.checks[0].deviceModules[0].digest").value("sha-old"));
    }

    @Test
    void 拉取幂等_同参重放首次响应_异参409_阻断重放不新增证据() throws Exception {
        registerDevice("r0", "d1", "m1", "1.0.0", 1, null);
        registerDevice("r1", "d2", "m1", "1.0.0", 2, null);
        createRelease("r2", "m1", 100, null, null);
        registerFirmwareRequirement("m1", "m1", "2.0.0", "modA", "1.0");

        String first = pull("d1", "same").getResponse().getContentAsString();
        String replay = pull("d1", "same").getResponse().getContentAsString();
        assertThat(replay).isEqualTo(first);
        // 同参重放不新增判定证据，也不创建任务
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM compatibility_check WHERE request_id = 'same'", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rollout_task", Long.class)).isZero();

        // 同 requestId 异参（换设备）：409
        mockMvc.perform(post("/api/devices/d2/pull").contentType("application/json")
                        .content("{\"requestId\":\"same\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));

        // 放行场景同参重放：任务只创建一次
        reportModules("d2", "rep", "modA", "1.0", "sha");
        MvcResult granted = pull("d2", "grant");
        long taskId = json(granted).get("task").get("taskId").asLong();
        assertThat(json(pull("d2", "grant")).get("task").get("taskId").asLong()).isEqualTo(taskId);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rollout_task", Long.class)).isEqualTo(1);
    }

    @Test
    void 豁免修改_整体版本化_清空与非法后态422不留部分结果_取消后409() throws Exception {
        registerDevice("r0", "d1", "m1", "1.0.0", 1, "vip");
        long releaseId = createRelease("r1", "m1", 100, null, null);

        // 到期时刻已过期：422，不产生版本
        mockMvc.perform(post("/api/releases/" + releaseId + "/exemptions").contentType("application/json")
                .content("""
                        {"requestId":"old","entries":[
                        {"deviceGroup":"vip","moduleName":"modA","expiresAtUtc":"2026-09-01T00:00:00Z"}]}
                        """)).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("EXEMPTION_ALREADY_EXPIRED"));
        // 后态含重复条目：422
        mockMvc.perform(post("/api/releases/" + releaseId + "/exemptions").contentType("application/json")
                .content("""
                        {"requestId":"dup","entries":[
                        {"deviceGroup":"vip","moduleName":"modA","expiresAtUtc":"2026-11-01T00:00:00Z"},
                        {"deviceGroup":"vip","moduleName":"modA","expiresAtUtc":"2026-12-01T00:00:00Z"}]}
                        """)).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DUPLICATE_EXEMPTION"));
        // 非法时刻：422
        mockMvc.perform(post("/api/releases/" + releaseId + "/exemptions").contentType("application/json")
                .content("""
                        {"requestId":"badt","entries":[
                        {"deviceGroup":"vip","moduleName":"modA","expiresAtUtc":"not-a-time"}]}
                        """)).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_INSTANT"));
        mockMvc.perform(get("/api/releases/" + releaseId + "/exemptions"))
                .andExpect(jsonPath("$.version").value(0))
                .andExpect(jsonPath("$.entries.length()").value(0));

        // 失败不占键：old 换合法后态可成功
        modifyExemptions(releaseId, "old", """
                [{"deviceGroup":"vip","moduleName":"modA","expiresAtUtc":"2026-11-01T00:00:00Z"},
                {"deviceGroup":"vip","moduleName":"modB","expiresAtUtc":"2026-12-01T00:00:00Z"}]""");
        mockMvc.perform(get("/api/releases/" + releaseId + "/exemptions"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.entries.length()").value(2));

        // 整体替换为只保留 modB，产生新版本；版本1历史仍可查
        modifyExemptions(releaseId, "old2", """
                [{"deviceGroup":"vip","moduleName":"modB","expiresAtUtc":"2026-12-01T00:00:00Z"}]""");
        mockMvc.perform(get("/api/releases/" + releaseId + "/exemptions"))
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.entries.length()").value(1))
                .andExpect(jsonPath("$.entries[0].moduleName").value("modB"));
        mockMvc.perform(get("/api/releases/" + releaseId + "/exemptions").param("version", "1"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.entries.length()").value(2));

        // 清空豁免同样产生新版本
        modifyExemptions(releaseId, "old3", "[]");
        mockMvc.perform(get("/api/releases/" + releaseId + "/exemptions"))
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.entries.length()").value(0));

        // 取消发布单后不得修改豁免
        mockMvc.perform(post("/api/releases/" + releaseId + "/cancel").contentType("application/json")
                .content("{\"requestId\":\"can\"}")).andExpect(status().isOk());
        mockMvc.perform(post("/api/releases/" + releaseId + "/exemptions").contentType("application/json")
                .content("""
                        {"requestId":"after","entries":[
                        {"deviceGroup":"vip","moduleName":"modA","expiresAtUtc":"2026-12-01T00:00:00Z"}]}
                        """)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RELEASE_CANCELLED"));
        // 不存在的豁免版本：404
        mockMvc.perform(get("/api/releases/" + releaseId + "/exemptions").param("version", "99"))
                .andExpect(status().isNotFound());
    }

    @Test
    void 并发拉取_兼容设备最多一个进行中任务_证据只一条() throws Exception {
        registerDevice("r0", "d1", "m1", "1.0.0", 1, null);
        long releaseId = createRelease("r1", "m1", 100, null, null);
        registerFirmwareRequirement("m1", "m1", "2.0.0", "modA", "1.0");
        reportModules("d1", "rep", "modA", "1.0", "sha");

        int threads = 8;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<MvcResult>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int seq = i;
            futures.add(executor.submit(() -> {
                ready.countDown();
                assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                return pull("d1", "pp" + seq);
            }));
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        Long taskId = null;
        for (Future<MvcResult> future : futures) {
            MvcResult result = future.get(30, TimeUnit.SECONDS);
            JsonNode body = json(result);
            // 落败线程返回已存在任务（仅任务视图）；获胜线程返回 GRANTED
            assertThat(body.get("task")).isNotNull();
            long current = body.get("task").get("taskId").asLong();
            if (taskId == null) {
                taskId = current;
            } else {
                assertThat(current).isEqualTo(taskId);
            }
        }
        executor.shutdownNow();
        assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rollout_task", Long.class)).isEqualTo(1);
        // 每个 requestId 只在首次下发固化一条 GRANTED 证据
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM compatibility_check WHERE release_id = ?", Long.class, releaseId))
                .isEqualTo(1);
    }
}
