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
        jdbc.update("DELETE FROM component_installation");
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
                                {"requestId":"reg-%s","equipmentId":"%s","maintenancePeriodMinutes":1000}
                                """.formatted(equipmentId, equipmentId)))
                .andExpect(status().isCreated());
    }

    private void addReading(String equipmentId, String readingId, long expectedVersion,
                            String sampledAt, long cumulativeMinutes) throws Exception {
        mockMvc.perform(post("/api/equipment/" + equipmentId + "/readings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"add-%s-%s","expectedVersion":%d,"readingId":"%s",
                                 "sampledAt":"%s","cumulativeMinutes":%d}
                                """.formatted(equipmentId, readingId, expectedVersion, readingId,
                                sampledAt, cumulativeMinutes)))
                .andExpect(status().isCreated());
    }

    private void registerComponent(String componentId, long lifetimeMinutes) throws Exception {
        mockMvc.perform(post("/api/components")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"regc-%s","operator":"tester","componentId":"%s",
                                 "lifetimeMinutes":%d}
                                """.formatted(componentId, componentId, lifetimeMinutes)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.usedMinutes").value(0))
                .andExpect(jsonPath("$.version").value(1));
    }

    private MvcResult install(String componentId, String requestId, String equipmentId,
                              String readingId, long expectedVersion) throws Exception {
        return postJson("/api/components/" + componentId + "/installations", """
                {"requestId":"%s","operator":"tester","equipmentId":"%s","readingId":"%s",
                 "expectedVersion":%d}
                """.formatted(requestId, equipmentId, readingId, expectedVersion));
    }

    private MvcResult remove(String componentId, String requestId, String readingId,
                             long expectedVersion) throws Exception {
        return postJson("/api/components/" + componentId + "/removals", """
                {"requestId":"%s","operator":"tester","readingId":"%s","expectedVersion":%d}
                """.formatted(requestId, readingId, expectedVersion));
    }

    // ---------- 主流程：E1 装 100 拆 160，E2 装 500 拆 530，累计 90；重放首次拆卸仍返回 60 ----------

    @Test
    void mainFlow_crossEquipmentAccumulationAndReplay() throws Exception {
        registerEquipment("E1");
        registerEquipment("E2");
        registerComponent("comp-1", 1000);
        addReading("E1", "r1", 1, "2026-02-01T10:00:00Z", 100);
        addReading("E1", "r2", 2, "2026-02-01T11:00:00Z", 160);
        addReading("E2", "s1", 1, "2026-02-01T12:00:00Z", 500);
        addReading("E2", "s2", 2, "2026-02-01T13:00:00Z", 530);

        // E1：100 装 → 160 拆，计入 60
        assert install("comp-1", "inst-1", "E1", "r1", 1).getResponse().getStatus() == 201;
        mockMvc.perform(get("/api/components/comp-1"))
                .andExpect(jsonPath("$.currentEquipmentId").value("E1"))
                .andExpect(jsonPath("$.usedMinutes").value(0));
        MvcResult firstRemoval = remove("comp-1", "rm-1", "r2", 2);
        assert firstRemoval.getResponse().getStatus() == 201;
        mockMvc.perform(get("/api/components/comp-1"))
                .andExpect(jsonPath("$.currentEquipmentId").value(nullValue()))
                .andExpect(jsonPath("$.usedMinutes").value(60))
                .andExpect(jsonPath("$.version").value(3));

        // E2：500 装 → 530 拆，再计入 30，累计 90
        assert install("comp-1", "inst-2", "E2", "s1", 3).getResponse().getStatus() == 201;
        MvcResult secondRemoval = remove("comp-1", "rm-2", "s2", 4);
        assert secondRemoval.getResponse().getStatus() == 201;
        mockMvc.perform(get("/api/components/comp-1"))
                .andExpect(jsonPath("$.usedMinutes").value(90))
                .andExpect(jsonPath("$.remainingMinutes").value(910))
                .andExpect(jsonPath("$.exhausted").value(false))
                .andExpect(jsonPath("$.currentEquipmentId").value(nullValue()))
                .andExpect(jsonPath("$.installations", hasSize(2)))
                .andExpect(jsonPath("$.installations[0].equipmentId").value("E1"))
                .andExpect(jsonPath("$.installations[0].installCumulativeMinutes").value(100))
                .andExpect(jsonPath("$.installations[0].removeCumulativeMinutes").value(160))
                .andExpect(jsonPath("$.installations[1].equipmentId").value("E2"))
                .andExpect(jsonPath("$.installations[1].installCumulativeMinutes").value(500))
                .andExpect(jsonPath("$.installations[1].removeCumulativeMinutes").value(530));

        // 重放第一次拆卸：返回首次完整响应（区间差值 60），业务效果不重复
        MvcResult replay = remove("comp-1", "rm-1", "r2", 2);
        assert replay.getResponse().getStatus() == 201;
        assert replay.getResponse().getContentAsString()
                .equals(firstRemoval.getResponse().getContentAsString());
        mockMvc.perform(get("/api/components/comp-1"))
                .andExpect(jsonPath("$.usedMinutes").value(90))
                .andExpect(jsonPath("$.installations", hasSize(2)));

        // 原设备完成保养不重置部件寿命
        mockMvc.perform(post("/api/equipment/E1/maintenances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"mnt-e1","expectedVersion":3,"readingId":"r2","anchorRevisionNo":1}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(get("/api/components/comp-1"))
                .andExpect(jsonPath("$.usedMinutes").value(90));
    }

    // ---------- 登记与安装拒绝分支 ----------

    @Test
    void register_duplicateAndInvalidParams_rejected() throws Exception {
        registerComponent("comp-dup", 100);
        mockMvc.perform(post("/api/components")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"regc-dup-2","operator":"tester","componentId":"comp-dup",
                                 "lifetimeMinutes":200}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("COMPONENT_EXISTS"));
        // 寿命必须为正整数；operator 必填
        mockMvc.perform(post("/api/components")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"regc-bad","operator":"tester","componentId":"comp-bad",
                                 "lifetimeMinutes":0}
                                """))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/components")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"regc-bad2","componentId":"comp-bad","lifetimeMinutes":100}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void install_rejectionBranches() throws Exception {
        registerEquipment("E1");
        registerEquipment("E2");
        registerComponent("comp-r", 1000);
        addReading("E1", "r1", 1, "2026-02-01T10:00:00Z", 100);

        // 部件不存在 → 404
        assert install("comp-nope", "ir-0", "E1", "r1", 1).getResponse().getStatus() == 404;
        // 设备不存在 → 404
        assert install("comp-r", "ir-1", "E-nope", "r1", 1).getResponse().getStatus() == 404;
        // 读数不存在 → 404
        assert install("comp-r", "ir-2", "E1", "r-nope", 1).getResponse().getStatus() == 404;
        // 部件版本冲突 → 409，失败不占键：同 requestId 修正版本后成功
        MvcResult wrongVersion = install("comp-r", "ir-3", "E1", "r1", 99);
        assert wrongVersion.getResponse().getStatus() == 409;
        assert wrongVersion.getResponse().getContentAsString().contains("VERSION_CONFLICT");
        assert install("comp-r", "ir-3", "E1", "r1", 1).getResponse().getStatus() == 201;

        // 已安装部件不能直接换设备，必须先拆卸 → 409
        addReading("E2", "s1", 1, "2026-02-01T10:00:00Z", 500);
        MvcResult move = install("comp-r", "ir-4", "E2", "s1", 2);
        assert move.getResponse().getStatus() == 409;
        assert move.getResponse().getContentAsString().contains("COMPONENT_ALREADY_INSTALLED");
        // 未拆卸部件查询：当前设备为 E1
        mockMvc.perform(get("/api/components/comp-r"))
                .andExpect(jsonPath("$.currentEquipmentId").value("E1"));
        // 部件不存在查询 → 404
        mockMvc.perform(get("/api/components/comp-nope"))
                .andExpect(status().isNotFound());
    }

    // ---------- 寿命上限 ----------

    @Test
    void lifetime_exhaustedForbidsInstallButAllowsRemoval() throws Exception {
        registerEquipment("E1");
        registerComponent("comp-life", 60);
        addReading("E1", "r1", 1, "2026-02-01T10:00:00Z", 100);
        addReading("E1", "r2", 2, "2026-02-01T11:00:00Z", 160);
        addReading("E1", "r3", 3, "2026-02-01T12:00:00Z", 250);

        // 拆卸后恰好达到寿命上限 60
        assert install("comp-life", "lf-1", "E1", "r1", 1).getResponse().getStatus() == 201;
        assert remove("comp-life", "lf-2", "r2", 2).getResponse().getStatus() == 201;
        mockMvc.perform(get("/api/components/comp-life"))
                .andExpect(jsonPath("$.usedMinutes").value(60))
                .andExpect(jsonPath("$.exhausted").value(true));
        // 达到上限后禁止再安装 → 409
        MvcResult reinstall = install("comp-life", "lf-3", "E1", "r2", 3);
        assert reinstall.getResponse().getStatus() == 409;
        assert reinstall.getResponse().getContentAsString().contains("COMPONENT_LIFE_EXHAUSTED");

        // 已安装者即使越过上限仍可拆卸
        registerComponent("comp-life2", 60);
        assert install("comp-life2", "lf-4", "E1", "r1", 1).getResponse().getStatus() == 201;
        MvcResult overRemoval = remove("comp-life2", "lf-5", "r3", 2);
        assert overRemoval.getResponse().getStatus() == 201;
        mockMvc.perform(get("/api/components/comp-life2"))
                .andExpect(jsonPath("$.usedMinutes").value(150))
                .andExpect(jsonPath("$.exhausted").value(true));
    }

    // ---------- 拆卸校验与区间规则 ----------

    @Test
    void remove_validationBranches() throws Exception {
        registerEquipment("E1");
        registerComponent("comp-rm", 1000);
        addReading("E1", "r1", 1, "2026-02-01T10:00:00Z", 100);
        addReading("E1", "r2", 2, "2026-02-01T11:00:00Z", 160);

        // 未安装拆卸 → 409，失败不占键
        MvcResult notInstalled = remove("comp-rm", "rm-x", "r2", 1);
        assert notInstalled.getResponse().getStatus() == 409;
        assert notInstalled.getResponse().getContentAsString().contains("COMPONENT_NOT_INSTALLED");

        // 拆卸累计不得小于安装累计 → 422
        assert install("comp-rm", "rm-i", "E1", "r2", 1).getResponse().getStatus() == 201;
        MvcResult backwards = remove("comp-rm", "rm-b", "r1", 2);
        assert backwards.getResponse().getStatus() == 422;
        assert backwards.getResponse().getContentAsString().contains("COMPONENT_INTERVAL_INVALID");
        // 拆卸读数须属于当前所在设备 → 404
        assert remove("comp-rm", "rm-c", "r-nope", 2).getResponse().getStatus() == 404;
        // 正常拆卸
        assert remove("comp-rm", "rm-d", "r2", 2).getResponse().getStatus() == 201;
        mockMvc.perform(get("/api/components/comp-rm"))
                .andExpect(jsonPath("$.usedMinutes").value(0));
    }

    @Test
    void interval_leftClosedRightOpen_adjacentAllowedOverlapRejected() throws Exception {
        registerEquipment("E1");
        registerComponent("comp-int", 1000);
        addReading("E1", "r1", 1, "2026-02-01T10:00:00Z", 100);
        addReading("E1", "r2", 2, "2026-02-01T11:00:00Z", 160);
        addReading("E1", "r3", 3, "2026-02-01T12:00:00Z", 200);

        assert install("comp-int", "in-1", "E1", "r1", 1).getResponse().getStatus() == 201;
        assert remove("comp-int", "in-2", "r2", 2).getResponse().getStatus() == 201;

        // 新区间起点早于上次拆卸时刻（区间重叠）→ 422
        MvcResult overlap = install("comp-int", "in-3", "E1", "r1", 3);
        assert overlap.getResponse().getStatus() == 422;
        assert overlap.getResponse().getContentAsString().contains("COMPONENT_INTERVAL_OVERLAP");

        // 相邻接续合法：以上次拆卸读数作为下次安装起点（左闭右开）
        assert install("comp-int", "in-4", "E1", "r2", 3).getResponse().getStatus() == 201;
        assert remove("comp-int", "in-5", "r3", 4).getResponse().getStatus() == 201;
        mockMvc.perform(get("/api/components/comp-int"))
                .andExpect(jsonPath("$.usedMinutes").value(100))
                .andExpect(jsonPath("$.installations", hasSize(2)))
                .andExpect(jsonPath("$.installations[1].installCumulativeMinutes").value(160))
                .andExpect(jsonPath("$.installations[1].removeCumulativeMinutes").value(200));
    }

    // ---------- 装拆引用的读数禁止修订 ----------

    @Test
    void readingReferencedByInstallation_cannotBeRevised() throws Exception {
        registerEquipment("E1");
        registerComponent("comp-anchor", 1000);
        addReading("E1", "r1", 1, "2026-02-01T10:00:00Z", 100);
        addReading("E1", "r2", 2, "2026-02-01T11:00:00Z", 160);
        addReading("E1", "r3", 3, "2026-02-01T12:00:00Z", 200);

        assert install("comp-anchor", "an-1", "E1", "r1", 1).getResponse().getStatus() == 201;
        // 安装端点读数禁止修订 → 409
        mockMvc.perform(post("/api/equipment/E1/readings/r1/revisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"an-r1","expectedVersion":4,"cumulativeMinutes":120}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("READING_ANCHORED"));

        assert remove("comp-anchor", "an-2", "r2", 2).getResponse().getStatus() == 201;
        // 拆卸端点读数同样禁止修订 → 409
        mockMvc.perform(post("/api/equipment/E1/readings/r2/revisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"an-r2","expectedVersion":4,"cumulativeMinutes":150}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("READING_ANCHORED"));

        // 未被引用的读数可正常修订
        mockMvc.perform(post("/api/equipment/E1/readings/r3/revisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"an-r3","expectedVersion":4,"cumulativeMinutes":210}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.revisionNo").value(2));
        // 已记寿命不受后续修订影响
        mockMvc.perform(get("/api/components/comp-anchor"))
                .andExpect(jsonPath("$.usedMinutes").value(60));
    }

    // ---------- 幂等：同键异参 409、操作者参与指纹、失败不占键 ----------

    @Test
    void idempotency_mismatchOperatorAndFailureNotOccupying() throws Exception {
        registerEquipment("E1");
        registerComponent("comp-idem", 1000);
        addReading("E1", "r1", 1, "2026-02-01T10:00:00Z", 100);
        addReading("E1", "r2", 2, "2026-02-01T11:00:00Z", 160);

        assert install("comp-idem", "idem-i", "E1", "r1", 1).getResponse().getStatus() == 201;
        // 同键同参重放
        assert install("comp-idem", "idem-i", "E1", "r1", 1).getResponse().getStatus() == 201;
        // 同键异参（不同读数）→ 409
        MvcResult mismatch = postJson("/api/components/comp-idem/installations", """
                {"requestId":"idem-i","operator":"tester","equipmentId":"E1","readingId":"r2",
                 "expectedVersion":1}
                """);
        assert mismatch.getResponse().getStatus() == 409;
        assert mismatch.getResponse().getContentAsString().contains("REQUEST_ID_CONFLICT");
        // 同键仅操作者不同 → 409（操作者参与指纹）
        MvcResult otherOperator = postJson("/api/components/comp-idem/installations", """
                {"requestId":"idem-i","operator":"other","equipmentId":"E1","readingId":"r1",
                 "expectedVersion":1}
                """);
        assert otherOperator.getResponse().getStatus() == 409;
        assert otherOperator.getResponse().getContentAsString().contains("REQUEST_ID_CONFLICT");

        // 失败不占键：版本冲突失败后同 requestId 修正参数可成功
        assert remove("comp-idem", "idem-r", "r2", 99).getResponse().getStatus() == 409;
        MvcResult removed = remove("comp-idem", "idem-r", "r2", 2);
        assert removed.getResponse().getStatus() == 201;
        assert removed.getResponse().getContentAsString().contains("\"deltaMinutes\":60");
    }
}
