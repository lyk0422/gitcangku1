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
 * 固件模块兼容矩阵与设备任务原子筛选 API 测试（H2 内存库，MODE=MySQL）：
 * 矩阵登记与依赖环、设备模块上报、拉取阻断与豁免、快照固化、幂等与只读查询。
 */
@SpringBootTest
@AutoConfigureMockMvc
class FirmwareModuleCompatTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanUp() {
        jdbc.update("DELETE FROM module_block_item");
        jdbc.update("DELETE FROM module_block_record");
        jdbc.update("DELETE FROM release_exemption");
        jdbc.update("DELETE FROM device_module_report");
        jdbc.update("DELETE FROM device_module");
        jdbc.update("DELETE FROM compat_matrix_revision");
        jdbc.update("DELETE FROM firmware_requirement");
        jdbc.update("DELETE FROM module_dependency");
        jdbc.update("UPDATE compat_matrix_meta SET version = 0 WHERE id = 1");
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

    private void registerDevice(String requestId, String deviceId, String model, String version,
                                int bucket, String group) throws Exception {
        mockMvc.perform(post("/api/devices").contentType("application/json").content("""
                {"requestId":"%s","deviceId":"%s","model":"%s","currentVersion":"%s","bucketNo":%d,"deviceGroup":"%s"}
                """.formatted(requestId, deviceId, model, version, bucket, group)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deviceGroup").value(group))
                .andExpect(jsonPath("$.moduleReportVersion").value(0));
    }

    private long createRelease(String requestId, String model, int ratio) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/releases").contentType("application/json").content("""
                {"requestId":"%s","model":"%s","fromVersion":"1.0.0","toVersion":"2.0.0","ratio":%d}
                """.formatted(requestId, model, ratio)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exemptionVersion").value(0))
                .andReturn();
        return idOf(result, "$.releaseId");
    }

    private void reportModules(String requestId, String deviceId, String modulesJson) throws Exception {
        mockMvc.perform(post("/api/devices/" + deviceId + "/modules").contentType("application/json")
                        .content("{\"requestId\":\"%s\",\"modules\":%s}".formatted(requestId, modulesJson)))
                .andExpect(status().isOk());
    }

    private MvcResult pull(String requestId, String deviceId) throws Exception {
        return mockMvc.perform(post("/api/devices/" + deviceId + "/pull").contentType("application/json")
                        .content("{\"requestId\":\"%s\"}".formatted(requestId)))
                .andExpect(status().isOk())
                .andReturn();
    }

    @Test
    void 矩阵登记_版本递增_当前矩阵与登记历史查询_只读不改状态() throws Exception {
        mockMvc.perform(post("/api/compat/matrix").contentType("application/json").content("""
                        {"requestId":"mx1","firmwareRequirements":[
                          {"firmwareVersion":"2.0.0","module":"modem","minVersion":"1.2.0"}],
                         "moduleDependencies":[
                          {"module":"modem","dependsOn":"baseband","minVersion":"2.0.0"}]}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matrixVersion").value(1))
                .andExpect(jsonPath("$.requirementCount").value(1))
                .andExpect(jsonPath("$.dependencyCount").value(1))
                .andExpect(jsonPath("$.registeredAtUtc").isString());

        mockMvc.perform(get("/api/compat/matrix"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matrixVersion").value(1))
                .andExpect(jsonPath("$.requirements[0].firmwareVersion").value("2.0.0"))
                .andExpect(jsonPath("$.requirements[0].module").value("modem"))
                .andExpect(jsonPath("$.requirements[0].minVersion").value("1.2.0"))
                .andExpect(jsonPath("$.dependencies[0].module").value("modem"))
                .andExpect(jsonPath("$.dependencies[0].dependsOn").value("baseband"));

        // 再次登记：矩阵修订，版本加一，历史只增不改
        mockMvc.perform(post("/api/compat/matrix").contentType("application/json").content("""
                        {"requestId":"mx2","firmwareRequirements":[
                          {"firmwareVersion":"2.0.0","module":"gps","minVersion":"2.0"}],
                         "moduleDependencies":[]}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matrixVersion").value(2));

        mockMvc.perform(get("/api/compat/matrix/revisions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revisions.length()").value(2))
                .andExpect(jsonPath("$.revisions[0].version").value(1))
                .andExpect(jsonPath("$.revisions[1].version").value(2))
                .andExpect(jsonPath("$.revisions[1].requirementCount").value(1))
                .andExpect(jsonPath("$.revisions[1].dependencyCount").value(0));

        // 只读查询不改变矩阵版本
        mockMvc.perform(get("/api/compat/matrix"));
        mockMvc.perform(get("/api/compat/matrix"))
                .andExpect(jsonPath("$.matrixVersion").value(2))
                .andExpect(jsonPath("$.requirements.length()").value(2));
    }

    @Test
    void 矩阵登记_版本格式非法422_失败不占键() throws Exception {
        mockMvc.perform(post("/api/compat/matrix").contentType("application/json").content("""
                        {"requestId":"mx-bad","firmwareRequirements":[
                          {"firmwareVersion":"2.0.0","module":"modem","minVersion":"1.2.x"}],
                         "moduleDependencies":[]}
                        """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_VERSION_FORMAT"));

        mockMvc.perform(post("/api/compat/matrix").contentType("application/json").content("""
                        {"requestId":"mx-bad2","firmwareRequirements":[],
                         "moduleDependencies":[
                          {"module":"a","dependsOn":"b","minVersion":"1.-2"}]}
                        """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_VERSION_FORMAT"));

        // 失败不占键：同 requestId 修正参数后成功
        mockMvc.perform(post("/api/compat/matrix").contentType("application/json").content("""
                        {"requestId":"mx-bad","firmwareRequirements":[
                          {"firmwareVersion":"2.0.0","module":"modem","minVersion":"1.2.0"}],
                         "moduleDependencies":[]}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matrixVersion").value(1));

        // 失败的登记不产生版本与半成品
        mockMvc.perform(get("/api/compat/matrix/revisions"))
                .andExpect(jsonPath("$.revisions.length()").value(1));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM module_dependency", Long.class)).isZero();
    }

    @Test
    void 矩阵登记_模块依赖环409_含自依赖_请求内重复400() throws Exception {
        mockMvc.perform(post("/api/compat/matrix").contentType("application/json").content("""
                        {"requestId":"mx1","firmwareRequirements":[],
                         "moduleDependencies":[{"module":"a","dependsOn":"b","minVersion":"1.0"}]}
                        """))
                .andExpect(status().isOk());

        // 新增 b->a 与既有 a->b 成环：整体拒绝
        mockMvc.perform(post("/api/compat/matrix").contentType("application/json").content("""
                        {"requestId":"mx2","firmwareRequirements":[],
                         "moduleDependencies":[{"module":"b","dependsOn":"a","minVersion":"1.0"}]}
                        """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MODULE_DEPENDENCY_CYCLE"));

        // 自依赖也是环
        mockMvc.perform(post("/api/compat/matrix").contentType("application/json").content("""
                        {"requestId":"mx3","firmwareRequirements":[],
                         "moduleDependencies":[{"module":"c","dependsOn":"c","minVersion":"1.0"}]}
                        """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MODULE_DEPENDENCY_CYCLE"));

        // 请求内重复条目：400
        mockMvc.perform(post("/api/compat/matrix").contentType("application/json").content("""
                        {"requestId":"mx4","firmwareRequirements":[
                          {"firmwareVersion":"2.0.0","module":"m","minVersion":"1.0"},
                          {"firmwareVersion":"2.0.0","module":"m","minVersion":"1.1"}],
                         "moduleDependencies":[]}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DUPLICATE_MATRIX_ENTRY"));

        // 环与重复均未入库：矩阵版本仍为1，依赖边只有 a->b
        mockMvc.perform(get("/api/compat/matrix"))
                .andExpect(jsonPath("$.matrixVersion").value(1))
                .andExpect(jsonPath("$.dependencies.length()").value(1));
    }

    @Test
    void 设备上报_摘要与版本递增_历史_失败分支() throws Exception {
        registerDevice("d1", "dev1", "m1", "1.0.0", 5, "g1");

        MvcResult first = mockMvc.perform(post("/api/devices/dev1/modules").contentType("application/json")
                        .content("""
                                {"requestId":"up1","modules":[
                                  {"module":"modem","version":"1.3.0"},
                                  {"module":"baseband","version":"2.0.0"}]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.moduleReportVersion").value(1))
                .andExpect(jsonPath("$.modules[0].module").value("baseband"))
                .andExpect(jsonPath("$.modules[1].module").value("modem"))
                .andReturn();
        String digest = com.jayway.jsonpath.JsonPath.read(first.getResponse().getContentAsString(),
                "$.moduleDigest");
        assertThat(digest).isNotBlank();

        // 清单内容相同、顺序不同 → 摘要稳定
        mockMvc.perform(post("/api/devices/dev1/modules").contentType("application/json")
                        .content("""
                                {"requestId":"up2","modules":[
                                  {"module":"baseband","version":"2.0.0"},
                                  {"module":"modem","version":"1.3.0"}]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.moduleReportVersion").value(2))
                .andExpect(jsonPath("$.moduleDigest").value(digest));

        mockMvc.perform(get("/api/devices/dev1/modules"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.moduleReportVersion").value(2))
                .andExpect(jsonPath("$.moduleDigest").value(digest))
                .andExpect(jsonPath("$.modules.length()").value(2));

        mockMvc.perform(get("/api/devices/dev1/modules/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reports.length()").value(2))
                .andExpect(jsonPath("$.reports[0].reportVersion").value(1))
                .andExpect(jsonPath("$.reports[1].reportVersion").value(2))
                .andExpect(jsonPath("$.reports[0].moduleDigest").value(digest));

        // 设备视图携带上报版本与摘要
        mockMvc.perform(get("/api/devices/dev1"))
                .andExpect(jsonPath("$.moduleReportVersion").value(2))
                .andExpect(jsonPath("$.moduleDigest").value(digest));

        // 版本格式非法：422，不改变当前清单
        mockMvc.perform(post("/api/devices/dev1/modules").contentType("application/json")
                        .content("""
                                {"requestId":"up3","modules":[{"module":"modem","version":"1.x"}]}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_VERSION_FORMAT"));
        mockMvc.perform(get("/api/devices/dev1/modules"))
                .andExpect(jsonPath("$.moduleReportVersion").value(2))
                .andExpect(jsonPath("$.modules.length()").value(2));

        // 请求内模块重复：400
        mockMvc.perform(post("/api/devices/dev1/modules").contentType("application/json")
                        .content("""
                                {"requestId":"up4","modules":[
                                  {"module":"modem","version":"1.0"},
                                  {"module":"modem","version":"1.1"}]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DUPLICATE_MODULE"));

        // 设备不存在：404
        mockMvc.perform(post("/api/devices/ghost/modules").contentType("application/json")
                        .content("""
                                {"requestId":"up5","modules":[{"module":"modem","version":"1.0"}]}
                                """))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/devices/ghost/modules"))
                .andExpect(status().isNotFound());
    }

    @Test
    void 拉取_缺失与低版本模块阻断_稳定排序_不建任务不计失败率() throws Exception {
        mockMvc.perform(post("/api/compat/matrix").contentType("application/json").content("""
                        {"requestId":"mx1","firmwareRequirements":[
                          {"firmwareVersion":"2.0.0","module":"modem","minVersion":"1.2.0"},
                          {"firmwareVersion":"2.0.0","module":"gps","minVersion":"2.0"}],
                         "moduleDependencies":[]}
                        """))
                .andExpect(status().isOk());
        registerDevice("d1", "dev1", "m1", "1.0.0", 1, "g1");
        reportModules("up1", "dev1", "[{\"module\":\"gps\",\"version\":\"1.0\"}]");
        long releaseId = createRelease("r1", "m1", 100);

        mockMvc.perform(post("/api/devices/dev1/pull").contentType("application/json")
                        .content("{\"requestId\":\"p1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task").doesNotExist())
                .andExpect(jsonPath("$.block.code").value("MODULE_BLOCKED"))
                .andExpect(jsonPath("$.block.matrixVersion").value(1))
                .andExpect(jsonPath("$.block.exemptionVersion").value(0))
                .andExpect(jsonPath("$.block.moduleDigest").isString())
                .andExpect(jsonPath("$.block.missingModules.length()").value(2))
                .andExpect(jsonPath("$.block.missingModules[0].module").value("gps"))
                .andExpect(jsonPath("$.block.missingModules[0].deviceVersion").value("1.0"))
                .andExpect(jsonPath("$.block.missingModules[0].minVersion").value("2.0"))
                .andExpect(jsonPath("$.block.missingModules[1].module").value("modem"))
                .andExpect(jsonPath("$.block.missingModules[1].deviceVersion").doesNotExist())
                .andExpect(jsonPath("$.block.missingModules[1].minVersion").value("1.2.0"));

        // 不创建任务、不计入失败率
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rollout_task", Long.class)).isZero();
        mockMvc.perform(get("/api/releases/" + releaseId + "/monitor"))
                .andExpect(jsonPath("$.roundSuccess").value(0))
                .andExpect(jsonPath("$.roundFailed").value(0));

        // 阻断诊断查询：记录与明细稳定可读
        mockMvc.perform(get("/api/releases/" + releaseId + "/blocks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.blocks.length()").value(1))
                .andExpect(jsonPath("$.blocks[0].code").value("MODULE_BLOCKED"))
                .andExpect(jsonPath("$.blocks[0].deviceId").value("dev1"))
                .andExpect(jsonPath("$.blocks[0].matrixVersion").value(1))
                .andExpect(jsonPath("$.blocks[0].missingModules.length()").value(2))
                .andExpect(jsonPath("$.blocks[0].missingModules[0].module").value("gps"));

        // 同 requestId 重放阻断响应，不重复落阻断记录
        mockMvc.perform(post("/api/devices/dev1/pull").contentType("application/json")
                        .content("{\"requestId\":\"p1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.block.code").value("MODULE_BLOCKED"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM module_block_record", Long.class))
                .isEqualTo(1);
    }

    @Test
    void 拉取_传递依赖闭包_多路径取最高版本_补齐后放行() throws Exception {
        // 固件直接需要 a>=1.0、b>=3.0；a 依赖 b>=2.0 → b 的完整要求取最高 3.0
        mockMvc.perform(post("/api/compat/matrix").contentType("application/json").content("""
                        {"requestId":"mx1","firmwareRequirements":[
                          {"firmwareVersion":"2.0.0","module":"a","minVersion":"1.0"},
                          {"firmwareVersion":"2.0.0","module":"b","minVersion":"3.0"}],
                         "moduleDependencies":[
                          {"module":"a","dependsOn":"b","minVersion":"2.0"}]}
                        """))
                .andExpect(status().isOk());
        registerDevice("d1", "dev1", "m1", "1.0.0", 1, "g1");
        reportModules("up1", "dev1",
                "[{\"module\":\"a\",\"version\":\"1.0\"},{\"module\":\"b\",\"version\":\"2.5\"}]");
        createRelease("r1", "m1", 100);

        mockMvc.perform(post("/api/devices/dev1/pull").contentType("application/json")
                        .content("{\"requestId\":\"p1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.block.code").value("MODULE_BLOCKED"))
                .andExpect(jsonPath("$.block.missingModules.length()").value(1))
                .andExpect(jsonPath("$.block.missingModules[0].module").value("b"))
                .andExpect(jsonPath("$.block.missingModules[0].deviceVersion").value("2.5"))
                .andExpect(jsonPath("$.block.missingModules[0].minVersion").value("3.0"));

        // 补齐到最高要求后放行
        reportModules("up2", "dev1",
                "[{\"module\":\"a\",\"version\":\"1.0\"},{\"module\":\"b\",\"version\":\"3.0\"}]");
        mockMvc.perform(post("/api/devices/dev1/pull").contentType("application/json")
                        .content("{\"requestId\":\"p2\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.block").doesNotExist())
                .andExpect(jsonPath("$.task.status").value("PENDING"))
                .andExpect(jsonPath("$.task.matrixVersion").value(1))
                .andExpect(jsonPath("$.task.exemptionVersion").value(0));
    }

    @Test
    void 快照固化_矩阵修订与设备再上报不改写历史任务与阻断记录() throws Exception {
        mockMvc.perform(post("/api/compat/matrix").contentType("application/json").content("""
                        {"requestId":"mx1","firmwareRequirements":[
                          {"firmwareVersion":"2.0.0","module":"modem","minVersion":"1.0"}],
                         "moduleDependencies":[]}
                        """))
                .andExpect(status().isOk());
        registerDevice("d1", "dev1", "m1", "1.0.0", 1, "g1");
        registerDevice("d2", "dev2", "m1", "1.0.0", 2, "g1");
        reportModules("up1", "dev1", "[{\"module\":\"modem\",\"version\":\"1.5\"}]");
        long releaseId = createRelease("r1", "m1", 100);

        // dev1 满足要求：创建任务，固化矩阵版本1与当时摘要
        MvcResult pull = pull("p1", "dev1");
        long taskId = idOf(pull, "$.task.taskId");
        String digestV1 = com.jayway.jsonpath.JsonPath.read(pull.getResponse().getContentAsString(),
                "$.task.moduleDigest");
        // dev2 未上报：阻断，摘要为 null
        mockMvc.perform(post("/api/devices/dev2/pull").contentType("application/json")
                        .content("{\"requestId\":\"p2\"}"))
                .andExpect(jsonPath("$.block.code").value("MODULE_BLOCKED"))
                .andExpect(jsonPath("$.block.moduleDigest").doesNotExist());

        // 矩阵修订（提高要求到9.9）与设备再上报（升级到9.9）不改写历史
        mockMvc.perform(post("/api/compat/matrix").contentType("application/json").content("""
                        {"requestId":"mx2","firmwareRequirements":[
                          {"firmwareVersion":"2.0.0","module":"modem","minVersion":"9.9"}],
                         "moduleDependencies":[]}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matrixVersion").value(2));
        reportModules("up2", "dev1", "[{\"module\":\"modem\",\"version\":\"9.9\"}]");

        mockMvc.perform(get("/api/releases/" + releaseId + "/tasks"))
                .andExpect(jsonPath("$.tasks.length()").value(1))
                .andExpect(jsonPath("$.tasks[0].taskId").value(taskId))
                .andExpect(jsonPath("$.tasks[0].matrixVersion").value(1))
                .andExpect(jsonPath("$.tasks[0].exemptionVersion").value(0))
                .andExpect(jsonPath("$.tasks[0].moduleDigest").value(digestV1));

        mockMvc.perform(get("/api/releases/" + releaseId + "/blocks"))
                .andExpect(jsonPath("$.blocks.length()").value(1))
                .andExpect(jsonPath("$.blocks[0].matrixVersion").value(1))
                .andExpect(jsonPath("$.blocks[0].missingModules[0].minVersion").value("1.0"));

        // 已有任务设备重复拉取仍返回原任务，不按新矩阵重判
        mockMvc.perform(post("/api/devices/dev1/pull").contentType("application/json")
                        .content("{\"requestId\":\"p3\"}"))
                .andExpect(jsonPath("$.task.taskId").value(taskId))
                .andExpect(jsonPath("$.task.matrixVersion").value(1));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rollout_task", Long.class)).isEqualTo(1);
    }

    @Test
    void 豁免_按组放行_到期自动失效_不绕过既有门禁() throws Exception {
        mockMvc.perform(post("/api/compat/matrix").contentType("application/json").content("""
                        {"requestId":"mx1","firmwareRequirements":[
                          {"firmwareVersion":"2.0.0","module":"modem","minVersion":"1.2.0"}],
                         "moduleDependencies":[]}
                        """))
                .andExpect(status().isOk());
        registerDevice("d1", "dev1", "m1", "1.0.0", 1, "g1");
        registerDevice("d2", "dev2", "m1", "1.0.0", 2, "g2");
        registerDevice("d3", "dev3", "m1", "1.0.0", 90, "g1");
        long releaseId = createRelease("r1", "m1", 10);

        // 未豁免前：dev1 缺 modem 被阻断
        mockMvc.perform(post("/api/devices/dev1/pull").contentType("application/json")
                        .content("{\"requestId\":\"p0\"}"))
                .andExpect(jsonPath("$.block.code").value("MODULE_BLOCKED"));

        // 配置 g1 组 modem 豁免，到期时刻未来
        mockMvc.perform(post("/api/releases/" + releaseId + "/exemptions")
                        .contentType("application/json").content("""
                        {"requestId":"ex1","exemptions":[
                          {"deviceGroup":"g1","modules":["modem"],"expiresAtUtc":"2099-01-01T00:00:00Z"}]}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exemptionVersion").value(1))
                .andExpect(jsonPath("$.exemptions[0].deviceGroup").value("g1"))
                .andExpect(jsonPath("$.exemptions[0].modules[0]").value("modem"))
                .andExpect(jsonPath("$.exemptions[0].expiresAtUtc").value("2099-01-01T00:00:00Z"));

        // g1 的 dev1 放行并固化豁免版本1；g2 的 dev2 仍被阻断（豁免按组生效）
        mockMvc.perform(post("/api/devices/dev1/pull").contentType("application/json")
                        .content("{\"requestId\":\"p1\"}"))
                .andExpect(jsonPath("$.task.status").value("PENDING"))
                .andExpect(jsonPath("$.task.exemptionVersion").value(1));
        mockMvc.perform(post("/api/devices/dev2/pull").contentType("application/json")
                        .content("{\"requestId\":\"p2\"}"))
                .andExpect(jsonPath("$.block.code").value("MODULE_BLOCKED"))
                .andExpect(jsonPath("$.block.exemptionVersion").value(1));

        // 豁免不绕过既有门禁：dev3 分桶号90 >= 比例10，即使组已豁免也不投放、不阻断
        mockMvc.perform(post("/api/devices/dev3/pull").contentType("application/json")
                        .content("{\"requestId\":\"p3\"}"))
                .andExpect(jsonPath("$.task").doesNotExist())
                .andExpect(jsonPath("$.block").doesNotExist());

        // 到期后自动失效：将豁免到期时刻改为过去，g1 新设备再次被阻断
        registerDevice("d4", "dev4", "m1", "1.0.0", 3, "g1");
        jdbc.update("UPDATE release_exemption SET expires_at_utc = '2020-01-01T00:00:00Z'"
                + " WHERE release_id = ?", releaseId);
        mockMvc.perform(post("/api/devices/dev4/pull").contentType("application/json")
                        .content("{\"requestId\":\"p4\"}"))
                .andExpect(jsonPath("$.block.code").value("MODULE_BLOCKED"))
                .andExpect(jsonPath("$.block.missingModules[0].module").value("modem"));

        // 当前豁免与历史查询（只读）
        mockMvc.perform(get("/api/releases/" + releaseId + "/exemptions"))
                .andExpect(jsonPath("$.exemptionVersion").value(1))
                .andExpect(jsonPath("$.exemptions[0].deviceGroup").value("g1"));
        mockMvc.perform(get("/api/releases/" + releaseId + "/exemptions/history"))
                .andExpect(jsonPath("$.versions.length()").value(1))
                .andExpect(jsonPath("$.versions[0].exemptionVersion").value(1));
    }

    @Test
    void 豁免_失败分支_过期400_格式400_重复400_已取消409_不存在404() throws Exception {
        long releaseId = createRelease("r1", "m1", 100);

        mockMvc.perform(post("/api/releases/" + releaseId + "/exemptions")
                        .contentType("application/json").content("""
                        {"requestId":"ex1","exemptions":[
                          {"deviceGroup":"g1","modules":["modem"],"expiresAtUtc":"2020-01-01T00:00:00Z"}]}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_EXEMPTION_EXPIRY"));

        mockMvc.perform(post("/api/releases/" + releaseId + "/exemptions")
                        .contentType("application/json").content("""
                        {"requestId":"ex2","exemptions":[
                          {"deviceGroup":"g1","modules":["modem"],"expiresAtUtc":"not-a-time"}]}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_EXEMPTION_EXPIRY"));

        mockMvc.perform(post("/api/releases/" + releaseId + "/exemptions")
                        .contentType("application/json").content("""
                        {"requestId":"ex3","exemptions":[
                          {"deviceGroup":"g1","modules":[],"expiresAtUtc":"2099-01-01T00:00:00Z"}]}
                        """))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/releases/" + releaseId + "/exemptions")
                        .contentType("application/json").content("""
                        {"requestId":"ex4","exemptions":[
                          {"deviceGroup":"g1","modules":["modem"],"expiresAtUtc":"2099-01-01T00:00:00Z"},
                          {"deviceGroup":"g1","modules":["modem"],"expiresAtUtc":"2099-06-01T00:00:00Z"}]}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DUPLICATE_EXEMPTION"));

        // 失败不占键也不产生半成品：豁免版本仍为0且无豁免行
        mockMvc.perform(get("/api/releases/" + releaseId + "/exemptions"))
                .andExpect(jsonPath("$.exemptionVersion").value(0))
                .andExpect(jsonPath("$.exemptions.length()").value(0));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM release_exemption", Long.class)).isZero();

        mockMvc.perform(post("/api/releases/9999/exemptions").contentType("application/json").content("""
                {"requestId":"ex5","exemptions":[
                  {"deviceGroup":"g1","modules":["modem"],"expiresAtUtc":"2099-01-01T00:00:00Z"}]}
                """))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/releases/" + releaseId + "/cancel").contentType("application/json")
                        .content("{\"requestId\":\"cx1\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/releases/" + releaseId + "/exemptions")
                        .contentType("application/json").content("""
                        {"requestId":"ex6","exemptions":[
                          {"deviceGroup":"g1","modules":["modem"],"expiresAtUtc":"2099-01-01T00:00:00Z"}]}
                        """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RELEASE_CANCELLED"));
    }

    @Test
    void 幂等_矩阵登记上报豁免_同参重放_异参409() throws Exception {
        String matrixBody = """
                {"requestId":"mx1","firmwareRequirements":[
                  {"firmwareVersion":"2.0.0","module":"modem","minVersion":"1.0"}],
                 "moduleDependencies":[]}
                """;
        MvcResult first = mockMvc.perform(post("/api/compat/matrix").contentType("application/json")
                        .content(matrixBody))
                .andExpect(status().isOk())
                .andReturn();
        MvcResult replay = mockMvc.perform(post("/api/compat/matrix").contentType("application/json")
                        .content(matrixBody))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(replay.getResponse().getContentAsString())
                .isEqualTo(first.getResponse().getContentAsString());
        mockMvc.perform(get("/api/compat/matrix"))
                .andExpect(jsonPath("$.matrixVersion").value(1));

        mockMvc.perform(post("/api/compat/matrix").contentType("application/json").content("""
                        {"requestId":"mx1","firmwareRequirements":[
                          {"firmwareVersion":"2.0.0","module":"modem","minVersion":"2.0"}],
                         "moduleDependencies":[]}
                        """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));

        registerDevice("d1", "dev1", "m1", "1.0.0", 1, "g1");
        String reportBody = """
                {"requestId":"up1","modules":[{"module":"modem","version":"1.0"}]}
                """;
        MvcResult reportFirst = mockMvc.perform(post("/api/devices/dev1/modules")
                        .contentType("application/json").content(reportBody))
                .andExpect(status().isOk())
                .andReturn();
        String firstDigest = com.jayway.jsonpath.JsonPath.read(
                reportFirst.getResponse().getContentAsString(), "$.moduleDigest");
        MvcResult reportReplay = mockMvc.perform(post("/api/devices/dev1/modules")
                        .contentType("application/json").content(reportBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.moduleReportVersion").value(1))
                .andReturn();
        String replayDigest = com.jayway.jsonpath.JsonPath.read(
                reportReplay.getResponse().getContentAsString(), "$.moduleDigest");
        assertThat(replayDigest).isEqualTo(firstDigest);
        mockMvc.perform(post("/api/devices/dev1/modules").contentType("application/json").content("""
                        {"requestId":"up1","modules":[{"module":"modem","version":"2.0"}]}
                        """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));

        long releaseId = createRelease("r1", "m1", 100);
        String exemptionBody = """
                {"requestId":"ex1","exemptions":[
                  {"deviceGroup":"g1","modules":["modem"],"expiresAtUtc":"2099-01-01T00:00:00Z"}]}
                """;
        mockMvc.perform(post("/api/releases/" + releaseId + "/exemptions")
                        .contentType("application/json").content(exemptionBody))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/releases/" + releaseId + "/exemptions")
                        .contentType("application/json").content(exemptionBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exemptionVersion").value(1));
        mockMvc.perform(post("/api/releases/" + releaseId + "/exemptions")
                        .contentType("application/json").content("""
                        {"requestId":"ex1","exemptions":[
                          {"deviceGroup":"g2","modules":["modem"],"expiresAtUtc":"2099-01-01T00:00:00Z"}]}
                        """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));
        // 重放不产生新版本
        mockMvc.perform(get("/api/releases/" + releaseId + "/exemptions/history"))
                .andExpect(jsonPath("$.versions.length()").value(1));
    }

    @Test
    void 回执成功_只更新固件版本_不虚构模块升级() throws Exception {
        mockMvc.perform(post("/api/compat/matrix").contentType("application/json").content("""
                        {"requestId":"mx1","firmwareRequirements":[
                          {"firmwareVersion":"2.0.0","module":"modem","minVersion":"1.0"}],
                         "moduleDependencies":[]}
                        """))
                .andExpect(status().isOk());
        registerDevice("d1", "dev1", "m1", "1.0.0", 1, "g1");
        reportModules("up1", "dev1", "[{\"module\":\"modem\",\"version\":\"1.5\"}]");
        long releaseId = createRelease("r1", "m1", 100);
        long taskId = idOf(pull("p1", "dev1"), "$.task.taskId");

        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"rc1\",\"result\":\"SUCCESS\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"));

        // 固件版本更新，模块清单与摘要保持设备上报原样
        mockMvc.perform(get("/api/devices/dev1"))
                .andExpect(jsonPath("$.currentVersion").value("2.0.0"))
                .andExpect(jsonPath("$.moduleReportVersion").value(1));
        mockMvc.perform(get("/api/devices/dev1/modules"))
                .andExpect(jsonPath("$.modules.length()").value(1))
                .andExpect(jsonPath("$.modules[0].module").value("modem"))
                .andExpect(jsonPath("$.modules[0].version").value("1.5"));

        // 阻断记录与任务快照不被回执改写
        mockMvc.perform(get("/api/releases/" + releaseId + "/tasks"))
                .andExpect(jsonPath("$.tasks[0].matrixVersion").value(1));
    }

    @Test
    void 阻断后补齐模块_回执失败正常计数_阻断本身不计入() throws Exception {
        mockMvc.perform(post("/api/compat/matrix").contentType("application/json").content("""
                        {"requestId":"mx1","firmwareRequirements":[
                          {"firmwareVersion":"2.0.0","module":"modem","minVersion":"1.0"}],
                         "moduleDependencies":[]}
                        """))
                .andExpect(status().isOk());
        registerDevice("d1", "dev1", "m1", "1.0.0", 1, "g1");
        long releaseId = createRelease("r1", "m1", 100);

        // 未上报：阻断
        pull("p1", "dev1");
        // 补齐后放行并回执失败：失败计数只来自真实任务回执
        reportModules("up1", "dev1", "[{\"module\":\"modem\",\"version\":\"1.0\"}]");
        long taskId = idOf(pull("p2", "dev1"), "$.task.taskId");
        mockMvc.perform(post("/api/tasks/" + taskId + "/receipt").contentType("application/json")
                        .content("{\"requestId\":\"rc1\",\"result\":\"FAILED\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/releases/" + releaseId + "/monitor"))
                .andExpect(jsonPath("$.roundSuccess").value(0))
                .andExpect(jsonPath("$.roundFailed").value(1))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }
}
