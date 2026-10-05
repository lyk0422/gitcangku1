package com.example.starter.maintenance;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 部件装拆账 API 主流程与失败分支测试（真实 H2 内存库，MySQL 兼容模式）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ComponentApiTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM idempotency_request");
        jdbc.update("DELETE FROM component_current");
        jdbc.update("DELETE FROM component_install");
        jdbc.update("DELETE FROM component");
        jdbc.update("DELETE FROM maintenance");
        jdbc.update("DELETE FROM reading_revision");
        jdbc.update("DELETE FROM reading");
        jdbc.update("DELETE FROM equipment");
    }

    private MvcResult postJson(String url, String body) throws Exception {
        return mockMvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn();
    }

    private void registerEquipment(String equipmentId) throws Exception {
        mockMvc.perform(post("/api/equipment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"reg-eq-%s","equipmentId":"%s","maintenancePeriodMinutes":100}
                                """.formatted(equipmentId, equipmentId)))
                .andExpect(status().isCreated());
    }

    private void addReading(String equipmentId, String requestId, long expectedVersion,
                            String readingId, String sampledAt, long cumulativeMinutes)
            throws Exception {
        mockMvc.perform(post("/api/equipment/" + equipmentId + "/readings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"%s","expectedVersion":%d,"readingId":"%s",
                                 "sampledAt":"%s","cumulativeMinutes":%d}
                                """.formatted(requestId, expectedVersion, readingId, sampledAt,
                                cumulativeMinutes)))
                .andExpect(status().isCreated());
    }

    private void registerComponent(String componentId, long lifetimeMinutes) throws Exception {
        mockMvc.perform(post("/api/components")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"reg-cp-%s","operator":"tester","componentId":"%s",
                                 "lifetimeMinutes":%d}
                                """.formatted(componentId, componentId, lifetimeMinutes)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.accumulatedMinutes").value(0));
    }

    private MvcResult install(String componentId, String requestId, long expectedVersion,
                              String equipmentId, String readingId) throws Exception {
        return postJson("/api/components/" + componentId + "/installs", """
                {"requestId":"%s","operator":"tester","expectedVersion":%d,
                 "equipmentId":"%s","readingId":"%s"}
                """.formatted(requestId, expectedVersion, equipmentId, readingId));
    }

    private MvcResult uninstall(String componentId, String requestId, long expectedVersion,
                                String readingId) throws Exception {
        return postJson("/api/components/" + componentId + "/uninstall", """
                {"requestId":"%s","operator":"tester","expectedVersion":%d,"readingId":"%s"}
                """.formatted(requestId, expectedVersion, readingId));
    }

    // ---------- 题干合成场景：E1 从 100 装到 160 拆，E2 从 500 装到 530 拆，累计 90 ----------

    @Test
    void mainFlow_crossEquipmentAccumulates90AndReplayReturns60() throws Exception {
        registerEquipment("E1");
        addReading("E1", "e1-r1", 1, "r1", "2026-01-01T10:00:00Z", 100);
        addReading("E1", "e1-r2", 2, "r2", "2026-01-01T11:00:00Z", 160);
        registerEquipment("E2");
        addReading("E2", "e2-ra", 1, "ra", "2026-01-02T10:00:00Z", 500);
        addReading("E2", "e2-rb", 2, "rb", "2026-01-02T11:00:00Z", 530);
        registerComponent("cmp-1", 1000);

        // E1：100 装
        MvcResult ins1 = install("cmp-1", "ins-1", 1, "E1", "r1");
        assert ins1.getResponse().getStatus() == 201;
        mockMvc.perform(post("/api/components/cmp-1/installs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"ins-1b","operator":"tester","expectedVersion":1,
                                 "equipmentId":"E1","readingId":"r1"}
                                """))
                .andExpect(status().isConflict()); // 版本已被 ins-1 推进
        mockMvc.perform(get("/api/components/cmp-1"))
                .andExpect(jsonPath("$.currentEquipmentId").value("E1"))
                .andExpect(jsonPath("$.accumulatedMinutes").value(0));

        // E1：160 拆，区间 60
        MvcResult firstUninstall = uninstall("cmp-1", "unins-1", 2, "r2");
        assert firstUninstall.getResponse().getStatus() == 201;
        mockMvc.perform(get("/api/components/cmp-1"))
                .andExpect(jsonPath("$.accumulatedMinutes").value(60))
                .andExpect(jsonPath("$.currentEquipmentId").value(nullValue()));

        // 重放第一次拆卸：仍返回 60，累计不重复增加
        MvcResult replay = uninstall("cmp-1", "unins-1", 2, "r2");
        assert replay.getResponse().getStatus() == 201;
        assert replay.getResponse().getContentAsString()
                .equals(firstUninstall.getResponse().getContentAsString());
        mockMvc.perform(get("/api/components/cmp-1"))
                .andExpect(jsonPath("$.accumulatedMinutes").value(60));

        // E2：500 装、530 拆，区间 30；累计 90
        assert install("cmp-1", "ins-2", 3, "E2", "ra").getResponse().getStatus() == 201;
        assert uninstall("cmp-1", "unins-2", 4, "rb").getResponse().getStatus() == 201;

        mockMvc.perform(get("/api/components/cmp-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accumulatedMinutes").value(90))
                .andExpect(jsonPath("$.remainingMinutes").value(910))
                .andExpect(jsonPath("$.version").value(5))
                .andExpect(jsonPath("$.installs", hasSize(2)))
                .andExpect(jsonPath("$.installs[0].equipmentId").value("E1"))
                .andExpect(jsonPath("$.installs[0].installCumulativeMinutes").value(100))
                .andExpect(jsonPath("$.installs[0].uninstallCumulativeMinutes").value(160))
                .andExpect(jsonPath("$.installs[0].durationMinutes").value(60))
                .andExpect(jsonPath("$.installs[0].open").value(false))
                .andExpect(jsonPath("$.installs[1].equipmentId").value("E2"))
                .andExpect(jsonPath("$.installs[1].durationMinutes").value(30));
    }

    // ---------- 在装归属：已安装部件不能直接换设备，须先拆卸 ----------

    @Test
    void installWhileInstalled_conflictThenUninstallFirst() throws Exception {
        registerEquipment("E1");
        addReading("E1", "r-e1", 1, "r1", "2026-01-01T10:00:00Z", 100);
        registerEquipment("E2");
        addReading("E2", "r-e2", 1, "ra", "2026-01-01T10:00:00Z", 500);
        registerComponent("cmp-busy", 1000);

        assert install("cmp-busy", "b-ins-1", 1, "E1", "r1").getResponse().getStatus() == 201;
        // 在装中直接换装到 E2 → 409
        mockMvc.perform(post("/api/components/cmp-busy/installs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"b-ins-2","operator":"tester","expectedVersion":2,
                                 "equipmentId":"E2","readingId":"ra"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("COMPONENT_ALREADY_INSTALLED"));
        mockMvc.perform(get("/api/components/cmp-busy"))
                .andExpect(jsonPath("$.currentEquipmentId").value("E1"));

        // 未在装的部件不能拆卸
        registerComponent("cmp-idle", 1000);
        mockMvc.perform(post("/api/components/cmp-idle/uninstall")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"b-unins-x","operator":"tester","expectedVersion":1,
                                 "readingId":"r1"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("COMPONENT_NOT_INSTALLED"));

        // 先拆卸再换装成功
        assert uninstall("cmp-busy", "b-unins-1", 2, "r1").getResponse().getStatus() == 201;
        assert install("cmp-busy", "b-ins-3", 3, "E2", "ra").getResponse().getStatus() == 201;
        mockMvc.perform(get("/api/components/cmp-busy"))
                .andExpect(jsonPath("$.currentEquipmentId").value("E2"));
    }

    // ---------- 寿命上限：达到后禁止再安装，已安装者仍可拆卸 ----------

    @Test
    void lifetimeExhausted_installForbiddenButUninstallAllowed() throws Exception {
        registerEquipment("E1");
        addReading("E1", "l-r1", 1, "r1", "2026-01-01T10:00:00Z", 100);
        addReading("E1", "l-r2", 2, "r2", "2026-01-01T11:00:00Z", 160);
        addReading("E1", "l-r3", 3, "r3", "2026-01-01T12:00:00Z", 200);
        registerComponent("cmp-life", 50);

        // 安装时累计 0 < 50：允许；拆卸后累计 60 超过上限也允许拆卸
        assert install("cmp-life", "l-ins-1", 1, "E1", "r1").getResponse().getStatus() == 201;
        MvcResult unins = uninstall("cmp-life", "l-unins-1", 2, "r2");
        assert unins.getResponse().getStatus() == 201;
        mockMvc.perform(get("/api/components/cmp-life"))
                .andExpect(jsonPath("$.accumulatedMinutes").value(60))
                .andExpect(jsonPath("$.remainingMinutes").value(0));

        // 寿命达上限：禁止再安装
        mockMvc.perform(post("/api/components/cmp-life/installs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"l-ins-2","operator":"tester","expectedVersion":3,
                                 "equipmentId":"E1","readingId":"r2"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("COMPONENT_LIFE_EXHAUSTED"));
        mockMvc.perform(get("/api/components/cmp-life"))
                .andExpect(jsonPath("$.version").value(3));
    }

    // ---------- 区间规则：拆卸累计不得小于安装累计；左闭右开、相邻接续合法 ----------

    @Test
    void intervalRules_orderViolationAndAdjacentContinuation() throws Exception {
        registerEquipment("E1");
        addReading("E1", "i-r1", 1, "r1", "2026-01-01T10:00:00Z", 100);
        addReading("E1", "i-r2", 2, "r2", "2026-01-01T11:00:00Z", 160);
        addReading("E1", "i-r3", 3, "r3", "2026-01-01T12:00:00Z", 200);
        registerComponent("cmp-int", 1000);

        // 在 160 安装后，引用更早读数（100）拆卸 → 422
        assert install("cmp-int", "i-ins-1", 1, "E1", "r2").getResponse().getStatus() == 201;
        mockMvc.perform(post("/api/components/cmp-int/uninstall")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"i-unins-1","operator":"tester","expectedVersion":2,
                                 "readingId":"r1"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("UNINSTALL_ORDER_VIOLATION"));

        // 同一读数拆卸：零长区间合法，时长 0
        mockMvc.perform(post("/api/components/cmp-int/uninstall")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"i-unins-2","operator":"tester","expectedVersion":2,
                                 "readingId":"r2"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.durationMinutes").value(0));

        // 相邻接续：在同一读数（160）再次安装合法，再到 200 拆卸，区间 40
        assert install("cmp-int", "i-ins-2", 3, "E1", "r2").getResponse().getStatus() == 201;
        mockMvc.perform(post("/api/components/cmp-int/uninstall")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"i-unins-3","operator":"tester","expectedVersion":4,
                                 "readingId":"r3"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.durationMinutes").value(40))
                .andExpect(jsonPath("$.accumulatedMinutes").value(40));
    }

    // ---------- 联合判定：装拆引用的读数禁止更正；部件操作不改变设备版本 ----------

    @Test
    void referencedReading_revisionForbidden() throws Exception {
        registerEquipment("E1");
        addReading("E1", "j-r1", 1, "r1", "2026-01-01T10:00:00Z", 100);
        addReading("E1", "j-r2", 2, "r2", "2026-01-01T11:00:00Z", 160);
        addReading("E1", "j-r3", 3, "r3", "2026-01-01T12:00:00Z", 200);
        registerComponent("cmp-j", 1000);

        assert install("cmp-j", "j-ins-1", 1, "E1", "r1").getResponse().getStatus() == 201;
        // 安装引用的 r1 禁止更正
        mockMvc.perform(post("/api/equipment/E1/readings/r1/revisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"j-rev-1","expectedVersion":4,"cumulativeMinutes":120}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("READING_REFERENCED"));

        assert uninstall("cmp-j", "j-unins-1", 2, "r2").getResponse().getStatus() == 201;
        // 拆卸引用的 r2 同样禁止更正
        mockMvc.perform(post("/api/equipment/E1/readings/r2/revisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"j-rev-2","expectedVersion":4,"cumulativeMinutes":170}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("READING_REFERENCED"));

        // 部件装拆不改变设备版本：r3 未被引用，可按原版本修订
        mockMvc.perform(post("/api/equipment/E1/readings/r3/revisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"j-rev-3","expectedVersion":4,"cumulativeMinutes":210}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.revisionNo").value(2));
    }

    // ---------- 原设备保养完成不重置部件寿命 ----------

    @Test
    void equipmentMaintenance_doesNotResetComponentLifetime() throws Exception {
        registerEquipment("E1");
        addReading("E1", "m-r1", 1, "r1", "2026-01-01T10:00:00Z", 100);
        addReading("E1", "m-r2", 2, "r2", "2026-01-01T11:00:00Z", 160);
        addReading("E1", "m-r3", 3, "r3", "2026-01-01T12:00:00Z", 250);
        registerComponent("cmp-m", 1000);

        assert install("cmp-m", "m-ins-1", 1, "E1", "r1").getResponse().getStatus() == 201;
        // 设备在部件在装期间完成保养（锚点 r2）
        mockMvc.perform(post("/api/equipment/E1/maintenances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"m-mnt-1","expectedVersion":4,"readingId":"r2",
                                 "anchorRevisionNo":1}
                                """))
                .andExpect(status().isCreated());
        // 拆卸仍按安装读数计区间：250 - 100 = 150，与保养锚点无关
        mockMvc.perform(post("/api/components/cmp-m/uninstall")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"m-unins-1","operator":"tester","expectedVersion":2,
                                 "readingId":"r3"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.durationMinutes").value(150))
                .andExpect(jsonPath("$.accumulatedMinutes").value(150));
    }

    // ---------- 幂等：同键异参 409（含操作者不同）、失败不占键、登记重放 ----------

    @Test
    void idempotency_mismatchOperatorAndFailureNotOccupying() throws Exception {
        registerEquipment("E1");
        addReading("E1", "d-r1", 1, "r1", "2026-01-01T10:00:00Z", 100);
        registerComponent("cmp-d", 1000);

        // 版本冲突失败不占键：同 requestId 修正后可成功
        mockMvc.perform(post("/api/components/cmp-d/installs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"d-ins-1","operator":"tester","expectedVersion":99,
                                 "equipmentId":"E1","readingId":"r1"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
        assert install("cmp-d", "d-ins-1", 1, "E1", "r1").getResponse().getStatus() == 201;

        // 同键同参重放：返回首次完整响应
        MvcResult replay = install("cmp-d", "d-ins-1", 1, "E1", "r1");
        assert replay.getResponse().getStatus() == 201;
        mockMvc.perform(get("/api/components/cmp-d"))
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.installs", hasSize(1)));

        // 同键异参（读数不同）→ 409
        mockMvc.perform(post("/api/components/cmp-d/installs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"d-ins-1","operator":"tester","expectedVersion":1,
                                 "equipmentId":"E1","readingId":"rX"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));
        // 同键同参但操作者不同 → 409（操作者属于幂等身份）
        mockMvc.perform(post("/api/components/cmp-d/installs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"d-ins-1","operator":"other","expectedVersion":1,
                                 "equipmentId":"E1","readingId":"r1"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));

        // 登记重放：同键同参返回原响应；重复编号异参 → 409
        mockMvc.perform(post("/api/components")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"reg-cp-cmp-d","operator":"tester",
                                 "componentId":"cmp-d","lifetimeMinutes":1000}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(1));
        mockMvc.perform(post("/api/components")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"reg-cp-2","operator":"tester","componentId":"cmp-d",
                                 "lifetimeMinutes":2000}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("COMPONENT_EXISTS"));
    }

    // ---------- 资源不存在与参数校验 ----------

    @Test
    void notFoundAndValidation_branches() throws Exception {
        mockMvc.perform(get("/api/components/nope"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("COMPONENT_NOT_FOUND"));
        assert install("nope", "nf-1", 1, "E1", "r1").getResponse().getStatus() == 404;

        registerComponent("cmp-nf", 1000);
        // 设备不存在
        assert install("cmp-nf", "nf-2", 1, "E1", "r1").getResponse().getStatus() == 404;
        // 读数不存在
        registerEquipment("E1");
        MvcResult noReading = install("cmp-nf", "nf-3", 1, "E1", "r1");
        assert noReading.getResponse().getStatus() == 404;

        // 寿命必须为正整数；操作者必填
        mockMvc.perform(post("/api/components")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"nf-4","operator":"tester","componentId":"cmp-bad",
                                 "lifetimeMinutes":0}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mockMvc.perform(post("/api/components")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"nf-5","componentId":"cmp-bad","lifetimeMinutes":10}
                                """))
                .andExpect(status().isBadRequest());
    }
}
