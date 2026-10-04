package com.example.starter.maintenance;

import static org.hamcrest.Matchers.hasSize;
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
                                {"requestId":"reg-eq-%s","equipmentId":"%s","maintenancePeriodMinutes":100000}
                                """.formatted(equipmentId, equipmentId)))
                .andExpect(status().isCreated());
    }

    private void registerComponent(String componentId, long lifespanMinutes) throws Exception {
        mockMvc.perform(post("/api/components")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"reg-cp-%s","operator":"tester","componentId":"%s",
                                 "lifespanMinutes":%d}
                                """.formatted(componentId, componentId, lifespanMinutes)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.accumulatedMinutes").value(0));
    }

    private void addReading(String equipmentId, String requestId, long expectedVersion,
                            String readingId, String sampledAt, long cumulativeMinutes) throws Exception {
        mockMvc.perform(post("/api/equipment/" + equipmentId + "/readings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"%s","expectedVersion":%d,"readingId":"%s",
                                 "sampledAt":"%s","cumulativeMinutes":%d}
                                """.formatted(requestId, expectedVersion, readingId, sampledAt,
                                cumulativeMinutes)))
                .andExpect(status().isCreated());
    }

    private MvcResult install(String componentId, String requestId, long expectedVersion,
                              String equipmentId, String readingId) throws Exception {
        return postJson("/api/components/" + componentId + "/installs", """
                {"requestId":"%s","operator":"tester","expectedVersion":%d,
                 "equipmentId":"%s","readingId":"%s"}
                """.formatted(requestId, expectedVersion, equipmentId, readingId));
    }

    private MvcResult remove(String componentId, String requestId, long expectedVersion,
                             String equipmentId, String readingId) throws Exception {
        return postJson("/api/components/" + componentId + "/removals", """
                {"requestId":"%s","operator":"tester","expectedVersion":%d,
                 "equipmentId":"%s","readingId":"%s"}
                """.formatted(requestId, expectedVersion, equipmentId, readingId));
    }

    // ---------- 合成场景：E1 从 100 装到 160 拆，E2 从 500 装到 530 拆，累计 90 ----------

    @Test
    void syntheticScenario_crossEquipmentRotation_accumulates90() throws Exception {
        registerEquipment("E1");
        registerEquipment("E2");
        registerComponent("C1", 1000);

        addReading("E1", "e1-r1", 1, "r1", "2026-01-01T10:00:00Z", 100);
        addReading("E1", "e1-r2", 2, "r2", "2026-01-01T11:00:00Z", 160);
        addReading("E2", "e2-r1", 1, "r1", "2026-01-01T10:00:00Z", 500);
        addReading("E2", "e2-r2", 2, "r2", "2026-01-01T11:00:00Z", 530);

        // E1：100 装 → 160 拆，区间 60
        MvcResult installed = install("C1", "ins-1", 1, "E1", "r1");
        assert installed.getResponse().getStatus() == 201;
        mockMvc.perform(get("/api/components/C1"))
                .andExpect(jsonPath("$.currentEquipmentId").value("E1"))
                .andExpect(jsonPath("$.accumulatedMinutes").value(0))
                .andExpect(jsonPath("$.version").value(2));

        MvcResult firstRemoval = remove("C1", "rem-1", 2, "E1", "r2");
        assert firstRemoval.getResponse().getStatus() == 201;

        // 重放第一次拆卸：仍返回 60，累计不重复结算
        MvcResult replay = remove("C1", "rem-1", 2, "E1", "r2");
        assert replay.getResponse().getStatus() == 201;
        assert replay.getResponse().getContentAsString()
                .equals(firstRemoval.getResponse().getContentAsString());
        mockMvc.perform(get("/api/components/C1"))
                .andExpect(jsonPath("$.accumulatedMinutes").value(60))
                .andExpect(jsonPath("$.currentEquipmentId").doesNotExist())
                .andExpect(jsonPath("$.version").value(3));

        // E2：500 装 → 530 拆，区间 30；累计 90
        MvcResult installed2 = install("C1", "ins-2", 3, "E2", "r1");
        assert installed2.getResponse().getStatus() == 201;
        MvcResult removed2 = remove("C1", "rem-2", 4, "E2", "r2");
        assert removed2.getResponse().getStatus() == 201;
        mockMvc.perform(get("/api/components/C1"))
                .andExpect(jsonPath("$.accumulatedMinutes").value(90))
                .andExpect(jsonPath("$.exhausted").value(false))
                .andExpect(jsonPath("$.version").value(5));

        // 装拆账：两段区间，左闭右开快照齐全
        mockMvc.perform(get("/api/components/C1/installs"))
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].equipmentId").value("E1"))
                .andExpect(jsonPath("$[0].installCumulativeMinutes").value(100))
                .andExpect(jsonPath("$[0].removeCumulativeMinutes").value(160))
                .andExpect(jsonPath("$[0].segmentMinutes").value(60))
                .andExpect(jsonPath("$[0].open").value(false))
                .andExpect(jsonPath("$[1].equipmentId").value("E2"))
                .andExpect(jsonPath("$[1].installCumulativeMinutes").value(500))
                .andExpect(jsonPath("$[1].segmentMinutes").value(30));
    }
    // ---------- 安装拒绝分支 ----------

    @Test
    void install_rejections() throws Exception {
        registerEquipment("E1");
        registerEquipment("E2");
        registerComponent("C1", 1000);
        addReading("E1", "e1-r1", 1, "r1", "2026-01-01T10:00:00Z", 100);
        addReading("E2", "e2-r1", 1, "r1", "2026-01-01T10:00:00Z", 500);

        // 部件不存在 → 404
        MvcResult r1 = install("NOPE", "ins-x1", 1, "E1", "r1");
        assert r1.getResponse().getStatus() == 404;
        assert r1.getResponse().getContentAsString().contains("COMPONENT_NOT_FOUND");

        // 设备不存在 → 404
        MvcResult r2 = install("C1", "ins-x2", 1, "NOPE", "r1");
        assert r2.getResponse().getStatus() == 404;
        assert r2.getResponse().getContentAsString().contains("EQUIPMENT_NOT_FOUND");

        // 读数不存在 → 404
        MvcResult r3 = install("C1", "ins-x3", 1, "E1", "nope");
        assert r3.getResponse().getStatus() == 404;
        assert r3.getResponse().getContentAsString().contains("READING_NOT_FOUND");

        // 部件版本冲突 → 409
        MvcResult r4 = install("C1", "ins-x4", 99, "E1", "r1");
        assert r4.getResponse().getStatus() == 409;
        assert r4.getResponse().getContentAsString().contains("VERSION_CONFLICT");

        // 正常安装
        MvcResult ok = install("C1", "ins-1", 1, "E1", "r1");
        assert ok.getResponse().getStatus() == 201;

        // 在装部件未拆卸不得再装（同设备或换设备均拒绝）→ 409
        MvcResult r5 = install("C1", "ins-x5", 2, "E1", "r1");
        assert r5.getResponse().getStatus() == 409;
        assert r5.getResponse().getContentAsString().contains("COMPONENT_INSTALLED");
        MvcResult r6 = install("C1", "ins-x6", 2, "E2", "r1");
        assert r6.getResponse().getStatus() == 409;
        assert r6.getResponse().getContentAsString().contains("COMPONENT_INSTALLED");

        // 拒绝分支均未改变部件状态
        mockMvc.perform(get("/api/components/C1"))
                .andExpect(jsonPath("$.currentEquipmentId").value("E1"))
                .andExpect(jsonPath("$.accumulatedMinutes").value(0))
                .andExpect(jsonPath("$.version").value(2));
    }

    // ---------- 拆卸规则 ----------

    @Test
    void removal_rules() throws Exception {
        registerEquipment("E1");
        registerEquipment("E2");
        registerComponent("C1", 1000);
        addReading("E1", "e1-r1", 1, "r1", "2026-01-01T10:00:00Z", 100);
        addReading("E1", "e1-r2", 2, "r2", "2026-01-01T11:00:00Z", 160);
        addReading("E2", "e2-r1", 1, "r1", "2026-01-01T10:00:00Z", 160);

        // 未在装不可拆 → 409
        MvcResult r1 = remove("C1", "rem-x1", 1, "E1", "r1");
        assert r1.getResponse().getStatus() == 409;
        assert r1.getResponse().getContentAsString().contains("COMPONENT_NOT_INSTALLED");

        // 装在 160（r2@11:00），拆在 100（r1@10:00）：时刻更早且累计更小，区间无效 → 422
        install("C1", "ins-1", 1, "E1", "r2");
        MvcResult r2 = remove("C1", "rem-x2", 2, "E1", "r1");
        assert r2.getResponse().getStatus() == 422;
        assert r2.getResponse().getContentAsString().contains("REMOVAL_TIME_BEFORE_INSTALL");

        // 拆卸设备与在装设备不符 → 409
        MvcResult r3 = remove("C1", "rem-x3", 2, "E2", "r1");
        assert r3.getResponse().getStatus() == 409;
        assert r3.getResponse().getContentAsString().contains("COMPONENT_INSTALLED_ELSEWHERE");

        // 同读数拆卸：区间长度 0，合法（左闭右开空区间）
        MvcResult r4 = remove("C1", "rem-1", 2, "E1", "r2");
        assert r4.getResponse().getStatus() == 201;
        assert r4.getResponse().getContentAsString().contains("\"segmentMinutes\":0");
        mockMvc.perform(get("/api/components/C1"))
                .andExpect(jsonPath("$.accumulatedMinutes").value(0))
                .andExpect(jsonPath("$.version").value(3));

        // 相邻接续合法：E1 在 160 拆，E2 在 160 装（前区间终点=后区间起点）
        install("C1", "ins-2", 3, "E1", "r2");
        remove("C1", "rem-2", 4, "E1", "r2");
        MvcResult adjacent = install("C1", "ins-3", 5, "E2", "r1");
        assert adjacent.getResponse().getStatus() == 201;
        mockMvc.perform(get("/api/components/C1"))
                .andExpect(jsonPath("$.currentEquipmentId").value("E2"))
                .andExpect(jsonPath("$.accumulatedMinutes").value(0));
    }

    // ---------- 寿命上限 ----------

    @Test
    void lifespan_exhaustionRules() throws Exception {
        registerEquipment("E1");
        registerComponent("C1", 60);
        addReading("E1", "e1-r1", 1, "r1", "2026-01-01T10:00:00Z", 100);
        addReading("E1", "e1-r2", 2, "r2", "2026-01-01T11:00:00Z", 160);
        addReading("E1", "e1-r3", 3, "r3", "2026-01-01T12:00:00Z", 200);

        // 累计达到寿命上限后禁止再安装
        install("C1", "ins-1", 1, "E1", "r1");
        remove("C1", "rem-1", 2, "E1", "r2");
        mockMvc.perform(get("/api/components/C1"))
                .andExpect(jsonPath("$.accumulatedMinutes").value(60))
                .andExpect(jsonPath("$.exhausted").value(true));
        MvcResult r1 = install("C1", "ins-x1", 3, "E1", "r2");
        assert r1.getResponse().getStatus() == 409;
        assert r1.getResponse().getContentAsString().contains("LIFESPAN_EXHAUSTED");

        // 越界拆卸仍允许：寿命 100，在装区间可拆到累计越过上限，其后禁止再装
        registerComponent("C2", 100);
        install("C2", "c2-ins-1", 1, "E1", "r1");
        remove("C2", "c2-rem-1", 2, "E1", "r2");
        install("C2", "c2-ins-2", 3, "E1", "r2");
        MvcResult cross = remove("C2", "c2-rem-2", 4, "E1", "r3");
        assert cross.getResponse().getStatus() == 201;
        assert cross.getResponse().getContentAsString().contains("\"segmentMinutes\":40");
        mockMvc.perform(get("/api/components/C2"))
                .andExpect(jsonPath("$.accumulatedMinutes").value(100))
                .andExpect(jsonPath("$.exhausted").value(true));
        MvcResult r2 = install("C2", "c2-ins-x", 5, "E1", "r3");
        assert r2.getResponse().getStatus() == 409;
        assert r2.getResponse().getContentAsString().contains("LIFESPAN_EXHAUSTED");
    }
    // ---------- 装拆引用的读数禁止更正 ----------

    @Test
    void revision_blockedWhenReferencedByInstallLedger() throws Exception {
        registerEquipment("E1");
        registerComponent("C1", 1000);
        addReading("E1", "e1-r1", 1, "r1", "2026-01-01T10:00:00Z", 100);
        addReading("E1", "e1-r2", 2, "r2", "2026-01-01T11:00:00Z", 160);
        addReading("E1", "e1-r3", 3, "r3", "2026-01-01T12:00:00Z", 200);

        install("C1", "ins-1", 1, "E1", "r1");

        // 安装引用的 r1 禁止修订
        mockMvc.perform(post("/api/equipment/E1/readings/r1/revisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"rev-x1","expectedVersion":4,"cumulativeMinutes":120}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("READING_ANCHORED"));

        // 未被引用的 r3 可正常修订（200 → 190，仍满足单调性）
        mockMvc.perform(post("/api/equipment/E1/readings/r3/revisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"rev-ok","expectedVersion":4,"cumulativeMinutes":190}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.revisionNo").value(2));

        remove("C1", "rem-1", 2, "E1", "r2");

        // 拆卸引用的 r2 同样禁止修订；装拆账快照不受后续操作影响
        mockMvc.perform(post("/api/equipment/E1/readings/r2/revisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"rev-x2","expectedVersion":5,"cumulativeMinutes":170}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("READING_ANCHORED"));
        mockMvc.perform(get("/api/components/C1/installs"))
                .andExpect(jsonPath("$[0].installCumulativeMinutes").value(100))
                .andExpect(jsonPath("$[0].removeCumulativeMinutes").value(160))
                .andExpect(jsonPath("$[0].segmentMinutes").value(60));
    }

    // ---------- 幂等：重放、异参、失败不占键、结构化指纹 ----------

    @Test
    void idempotency_replayMismatchAndFailureNotOccupying() throws Exception {
        registerEquipment("E1");
        registerComponent("C1", 1000);
        addReading("E1", "e1-r1", 1, "r1", "2026-01-01T10:00:00Z", 100);
        addReading("E1", "e1-r2", 2, "r2", "2026-01-01T11:00:00Z", 160);

        // 同键同参重放首次完整响应，业务效果不重复
        MvcResult first = install("C1", "idem-i1", 1, "E1", "r1");
        assert first.getResponse().getStatus() == 201;
        MvcResult replay = install("C1", "idem-i1", 1, "E1", "r1");
        assert replay.getResponse().getStatus() == 201;
        assert replay.getResponse().getContentAsString()
                .equals(first.getResponse().getContentAsString());
        mockMvc.perform(get("/api/components/C1/installs"))
                .andExpect(jsonPath("$", hasSize(1)));
        mockMvc.perform(get("/api/components/C1"))
                .andExpect(jsonPath("$.version").value(2));

        // 同键异参 → 409
        MvcResult mismatch = install("C1", "idem-i1", 1, "E1", "r2");
        assert mismatch.getResponse().getStatus() == 409;
        assert mismatch.getResponse().getContentAsString().contains("REQUEST_ID_CONFLICT");

        // 失败不占键：版本冲突失败后同 requestId 修正参数可成功
        MvcResult failed = remove("C1", "idem-r1", 99, "E1", "r2");
        assert failed.getResponse().getStatus() == 409;
        MvcResult retried = remove("C1", "idem-r1", 2, "E1", "r2");
        assert retried.getResponse().getStatus() == 201;
        mockMvc.perform(get("/api/components/C1"))
                .andExpect(jsonPath("$.accumulatedMinutes").value(60));

        // 结构化指纹：分隔符碰撞场景下异参仍判 409，不错误重放
        registerComponent("C2", 100);
        MvcResult f1 = postJson("/api/components", """
                {"requestId":"fp-1","operator":"a|b","componentId":"C3","lifespanMinutes":50}
                """);
        assert f1.getResponse().getStatus() == 201;
        MvcResult f2 = postJson("/api/components", """
                {"requestId":"fp-1","operator":"a","componentId":"C3","lifespanMinutes":50}
                """);
        assert f2.getResponse().getStatus() == 409;
        assert f2.getResponse().getContentAsString().contains("REQUEST_ID_CONFLICT");
        // 部件号被占（异 requestId）→ 409 业务冲突
        MvcResult f3 = postJson("/api/components", """
                {"requestId":"fp-2","operator":"a","componentId":"C3","lifespanMinutes":80}
                """);
        assert f3.getResponse().getStatus() == 409;
        assert f3.getResponse().getContentAsString().contains("COMPONENT_EXISTS");
    }

    // ---------- 设备保养不重置部件寿命 ----------

    @Test
    void equipmentMaintenance_doesNotResetComponentAccumulation() throws Exception {
        registerEquipment("E1");
        registerComponent("C1", 1000);
        addReading("E1", "e1-r1", 1, "r1", "2026-01-01T10:00:00Z", 100);
        addReading("E1", "e1-r2", 2, "r2", "2026-01-01T11:00:00Z", 160);

        install("C1", "ins-1", 1, "E1", "r1");

        // 原设备完成保养：设备本轮运行清零，部件账不受影响
        mockMvc.perform(post("/api/equipment/E1/maintenances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"mnt-1","expectedVersion":3,"readingId":"r2","anchorRevisionNo":1}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(get("/api/equipment/E1/status"))
                .andExpect(jsonPath("$.runMinutes").value(0));
        mockMvc.perform(get("/api/components/C1"))
                .andExpect(jsonPath("$.currentEquipmentId").value("E1"))
                .andExpect(jsonPath("$.accumulatedMinutes").value(0));

        // 拆卸仍按读数差值结算：160-100=60，与设备保养无关
        MvcResult removal = remove("C1", "rem-1", 2, "E1", "r2");
        assert removal.getResponse().getStatus() == 201;
        assert removal.getResponse().getContentAsString().contains("\"segmentMinutes\":60");
        mockMvc.perform(get("/api/components/C1"))
                .andExpect(jsonPath("$.accumulatedMinutes").value(60));
    }
}
