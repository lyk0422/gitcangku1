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
 * 多保养项目 API 测试（真实 H2 内存库，MySQL 兼容模式）：
 * 覆盖独立周期、共享读数、共享锚点、引用拦截明细、单项目/汇总查询与项目级幂等。
 */
@SpringBootTest
@AutoConfigureMockMvc
class MaintenanceItemApiTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM idempotency_request");
        jdbc.update("DELETE FROM maintenance");
        jdbc.update("DELETE FROM reading_revision");
        jdbc.update("DELETE FROM reading");
        jdbc.update("DELETE FROM maintenance_item");
        jdbc.update("DELETE FROM equipment");
    }

    private MvcResult postJson(String url, String body) throws Exception {
        return mockMvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn();
    }

    private void register(String equipmentId, long periodMinutes) throws Exception {
        mockMvc.perform(post("/api/equipment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"reg-%s","equipmentId":"%s","maintenancePeriodMinutes":%d}
                                """.formatted(equipmentId, equipmentId, periodMinutes)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(1));
    }

    private void addReading(String equipmentId, String requestId, long expectedVersion,
                            String readingId, String sampledAt, long cumulativeMinutes,
                            int expectedStatus) throws Exception {
        mockMvc.perform(post("/api/equipment/" + equipmentId + "/readings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"%s","expectedVersion":%d,"readingId":"%s",
                                 "sampledAt":"%s","cumulativeMinutes":%d}
                                """.formatted(requestId, expectedVersion, readingId, sampledAt,
                                cumulativeMinutes)))
                .andExpect(status().is(expectedStatus));
    }

    private void addItem(String equipmentId, String requestId, long expectedVersion,
                         String itemCode, long periodMinutes, int expectedStatus) throws Exception {
        mockMvc.perform(post("/api/equipment/" + equipmentId + "/items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"%s","expectedVersion":%d,"itemCode":"%s",
                                 "maintenancePeriodMinutes":%d}
                                """.formatted(requestId, expectedVersion, itemCode, periodMinutes)))
                .andExpect(status().is(expectedStatus));
    }

    // ---------- 项目新增：版本、唯一、上限、校验 ----------

    @Test
    void addItem_versionIncrementAndDefaults() throws Exception {
        register("eq-item", 100);

        addItem("eq-item", "item-1", 1, "OIL", 200, 201);
        mockMvc.perform(get("/api/equipment/eq-item/status"))
                .andExpect(jsonPath("$.version").value(2));

        // 设备内 itemCode 唯一
        addItem("eq-item", "item-2", 2, "OIL", 300, 409);
        mockMvc.perform(post("/api/equipment/eq-item/items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"item-3","expectedVersion":2,"itemCode":"DEFAULT",
                                 "maintenancePeriodMinutes":300}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ITEM_EXISTS"));

        // 版本不匹配 → 409，版本不变
        addItem("eq-item", "item-4", 99, "FILTER", 300, 409);
        mockMvc.perform(get("/api/equipment/eq-item/status"))
                .andExpect(jsonPath("$.version").value(2));

        // 参数校验：周期必须为正整数；itemCode 字符合法
        addItem("eq-item", "item-5", 2, "BAD CODE", 300, 400);
        addItem("eq-item", "item-6", 2, "FILTER", 0, 400);

        // 项目创建后不可修改或删除：无更新/删除接口；尝试重复创建即冲突
        addItem("eq-item", "item-7", 2, "FILTER", 999, 201);
        addItem("eq-item", "item-8", 3, "FILTER", 999, 409);
    }

    @Test
    void addItem_atMostTwentyExtraItems() throws Exception {
        register("eq-limit", 100);
        for (int i = 1; i <= 20; i++) {
            addItem("eq-limit", "lim-" + i, i, "ITEM" + i, 100 + i, 201);
        }
        // 含 DEFAULT 共 21 个项目，第 21 个新增项目被拒绝
        addItem("eq-limit", "lim-21", 21, "ITEM21", 500, 422);
        mockMvc.perform(get("/api/equipment/eq-limit/items/status"))
                .andExpect(jsonPath("$.items.length()").value(21));
    }

    // ---------- 独立周期、共享读数、新增即算状态、不补造记录 ----------

    @Test
    void items_independentPeriodsSharedReadings_noFabricatedRecords() throws Exception {
        register("eq-shared", 100);
        addReading("eq-shared", "sr-1", 1, "r1", "2026-01-01T10:00:00Z", 50, 201);
        addReading("eq-shared", "sr-2", 2, "r2", "2026-01-01T11:00:00Z", 130, 201);

        // 在已有读数后新增项目：立即按最新读数从 0 锚点计算，周期不同则状态可能不同
        addItem("eq-shared", "si-oil", 3, "OIL", 200, 201);

        mockMvc.perform(get("/api/equipment/eq-shared/items/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(4))
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[0].itemCode").value("DEFAULT"))
                .andExpect(jsonPath("$.items[0].maintenancePeriodMinutes").value(100))
                .andExpect(jsonPath("$.items[0].runMinutes").value(130))
                .andExpect(jsonPath("$.items[0].status").value("DUE"))
                .andExpect(jsonPath("$.items[1].itemCode").value("OIL"))
                .andExpect(jsonPath("$.items[1].latestCumulativeMinutes").value(130))
                .andExpect(jsonPath("$.items[1].lastMaintenanceAnchorSampledAt").doesNotExist())
                .andExpect(jsonPath("$.items[1].runMinutes").value(130))
                .andExpect(jsonPath("$.items[1].status").value("OK"));

        // 单项目查询一致；未知项目 404
        mockMvc.perform(get("/api/equipment/eq-shared/items/OIL/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itemCode").value("OIL"))
                .andExpect(jsonPath("$.status").value("OK"));
        mockMvc.perform(get("/api/equipment/eq-shared/items/NOPE/status"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ITEM_NOT_FOUND"));

        // 新增项目未补造任何保养记录
        mockMvc.perform(get("/api/equipment/eq-shared/items/OIL/maintenances"))
                .andExpect(jsonPath("$", hasSize(0)));
        mockMvc.perform(get("/api/equipment/eq-shared/maintenances"))
                .andExpect(jsonPath("$", hasSize(0)));
    }

    // ---------- 共享锚点：同一读数可锚定不同项目，项目内锚点时间严格递增 ----------

    @Test
    void maintenance_sharedAnchorAcrossItemsAndPerItemOrdering() throws Exception {
        register("eq-anchor", 100);
        addReading("eq-anchor", "an-1", 1, "r1", "2026-01-01T10:00:00Z", 50, 201);
        addReading("eq-anchor", "an-2", 2, "r2", "2026-01-01T11:00:00Z", 130, 201);
        addItem("eq-anchor", "ai-oil", 3, "OIL", 200, 201);

        // 两个项目都以 r2 为锚点（同一读数可作为不同项目的锚点）
        mockMvc.perform(post("/api/equipment/eq-anchor/maintenances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"am-oil","expectedVersion":4,"itemCode":"OIL",
                                 "readingId":"r2","anchorRevisionNo":1}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.itemCode").value("OIL"))
                .andExpect(jsonPath("$.equipmentVersion").value(5));
        mockMvc.perform(post("/api/equipment/eq-anchor/maintenances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"am-def","expectedVersion":5,"readingId":"r2",
                                 "anchorRevisionNo":1}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.itemCode").value("DEFAULT"))
                .andExpect(jsonPath("$.equipmentVersion").value(6));

        // 两项目本轮运行分钟各自归零，但 OIL 周期 200 仍 OK、DEFAULT 周期 100 也 OK
        mockMvc.perform(get("/api/equipment/eq-anchor/items/status"))
                .andExpect(jsonPath("$.items[0].itemCode").value("DEFAULT"))
                .andExpect(jsonPath("$.items[0].runMinutes").value(0))
                .andExpect(jsonPath("$.items[1].itemCode").value("OIL"))
                .andExpect(jsonPath("$.items[1].runMinutes").value(0));

        // OIL 再以同一/更早读数锚定 → 422（项目内锚点时间必须严格更晚）
        mockMvc.perform(post("/api/equipment/eq-anchor/maintenances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"am-oil-same","expectedVersion":6,"itemCode":"OIL",
                                 "readingId":"r2","anchorRevisionNo":1}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ANCHOR_TIME_NOT_LATER"));
        mockMvc.perform(post("/api/equipment/eq-anchor/maintenances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"am-oil-old","expectedVersion":6,"itemCode":"OIL",
                                 "readingId":"r1","anchorRevisionNo":1}
                                """))
                .andExpect(status().isUnprocessableEntity());

        // 但 DEFAULT 尚未锚定 r1：DEFAULT 也不能再锚定更早的 r1（其上次锚点是 r2）
        mockMvc.perform(post("/api/equipment/eq-anchor/maintenances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"am-def-old","expectedVersion":6,
                                 "readingId":"r1","anchorRevisionNo":1}
                                """))
                .andExpect(status().isUnprocessableEntity());

        // 未知项目完成保养 → 404
        mockMvc.perform(post("/api/equipment/eq-anchor/maintenances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"am-nope","expectedVersion":6,"itemCode":"NOPE",
                                 "readingId":"r1","anchorRevisionNo":1}
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ITEM_NOT_FOUND"));

        // 历史按项目隔离，稳定按锚点时间升序
        mockMvc.perform(get("/api/equipment/eq-anchor/maintenances"))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].itemCode").value("DEFAULT"))
                .andExpect(jsonPath("$[0].readingId").value("r2"));
        mockMvc.perform(get("/api/equipment/eq-anchor/items/OIL/maintenances"))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].itemCode").value("OIL"))
                .andExpect(jsonPath("$[0].readingId").value("r2"));

        // r2 已被两个项目引用，读数列表 anchored=true
        mockMvc.perform(get("/api/equipment/eq-anchor/readings"))
                .andExpect(jsonPath("$[1].readingId").value("r2"))
                .andExpect(jsonPath("$[1].anchored").value(true));
    }

    // ---------- 引用拦截：409 列出全部 itemCode；未引用读数仍按相邻值校验 ----------

    @Test
    void revise_blockedByAnyItemWithAllItemCodes_andNeighborCheckStays() throws Exception {
        register("eq-block", 100);
        addReading("eq-block", "bk-1", 1, "r1", "2026-01-01T10:00:00Z", 100, 201);
        addReading("eq-block", "bk-2", 2, "r2", "2026-01-01T11:00:00Z", 200, 201);
        addReading("eq-block", "bk-3", 3, "r3", "2026-01-01T12:00:00Z", 300, 201);
        addItem("eq-block", "bi-a", 4, "ALPHA", 500, 201);
        addItem("eq-block", "bi-z", 5, "ZETA", 500, 201);

        // 仅 ALPHA 与 ZETA 锚定 r1（DEFAULT 不锚定）
        mockMvc.perform(post("/api/equipment/eq-block/maintenances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"bm-a","expectedVersion":6,"itemCode":"ALPHA",
                                 "readingId":"r1","anchorRevisionNo":1}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/equipment/eq-block/maintenances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"bm-z","expectedVersion":7,"itemCode":"ZETA",
                                 "readingId":"r1","anchorRevisionNo":1}
                                """))
                .andExpect(status().isCreated());

        // 修订 r1 → 409，details 列出引用它的全部 itemCode（去重、字典序）
        mockMvc.perform(post("/api/equipment/eq-block/readings/r1/revisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"br-1","expectedVersion":8,"cumulativeMinutes":110}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("READING_ANCHORED"))
                .andExpect(jsonPath("$.details.readingId").value("r1"))
                .andExpect(jsonPath("$.details.itemCodes", hasSize(2)))
                .andExpect(jsonPath("$.details.itemCodes[0]").value("ALPHA"))
                .andExpect(jsonPath("$.details.itemCodes[1]").value("ZETA"));

        // 未引用读数继续按前后相邻值校验：r2 修订到 400 大于后邻 r3=300 → 422
        mockMvc.perform(post("/api/equipment/eq-block/readings/r2/revisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"br-2","expectedVersion":8,"cumulativeMinutes":400}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("READING_ORDER_VIOLATION"));
        // 合法修订成功，版本前进
        mockMvc.perform(post("/api/equipment/eq-block/readings/r2/revisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"br-3","expectedVersion":8,"cumulativeMinutes":250}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.revisionNo").value(2))
                .andExpect(jsonPath("$.equipmentVersion").value(9));
    }

    // ---------- 汇总排序：按 itemCode 字典序 ----------

    @Test
    void summary_sortedByItemCode() throws Exception {
        register("eq-sort", 100);
        addItem("eq-sort", "so-z", 1, "ZETA", 10, 201);
        addItem("eq-sort", "so-a", 2, "ALPHA", 10, 201);
        addItem("eq-sort", "so-m", 3, "MU", 10, 201);

        mockMvc.perform(get("/api/equipment/eq-sort/items/status"))
                .andExpect(jsonPath("$.items", hasSize(4)))
                .andExpect(jsonPath("$.items[0].itemCode").value("ALPHA"))
                .andExpect(jsonPath("$.items[1].itemCode").value("DEFAULT"))
                .andExpect(jsonPath("$.items[2].itemCode").value("MU"))
                .andExpect(jsonPath("$.items[3].itemCode").value("ZETA"));
    }

    // ---------- 项目级幂等：同参重放、异参 409、失败不占键 ----------

    @Test
    void addItem_idempotencyBoundaries() throws Exception {
        register("eq-iitem", 100);

        MvcResult first = postJson("/api/equipment/eq-iitem/items", """
                {"requestId":"ii-1","expectedVersion":1,"itemCode":"OIL","maintenancePeriodMinutes":200}
                """);
        org.junit.jupiter.api.Assertions.assertEquals(201, first.getResponse().getStatus());

        MvcResult replay = postJson("/api/equipment/eq-iitem/items", """
                {"requestId":"ii-1","expectedVersion":1,"itemCode":"OIL","maintenancePeriodMinutes":200}
                """);
        org.junit.jupiter.api.Assertions.assertEquals(201, replay.getResponse().getStatus());
        org.junit.jupiter.api.Assertions.assertEquals(
                first.getResponse().getContentAsString(), replay.getResponse().getContentAsString());

        // 业务效果只发生一次
        mockMvc.perform(get("/api/equipment/eq-iitem/items/status"))
                .andExpect(jsonPath("$.items", hasSize(2)));
        mockMvc.perform(get("/api/equipment/eq-iitem/status"))
                .andExpect(jsonPath("$.version").value(2));

        // 同键异参 → 409
        mockMvc.perform(post("/api/equipment/eq-iitem/items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"ii-1","expectedVersion":1,"itemCode":"OIL",
                                 "maintenancePeriodMinutes":201}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));

        // 失败不占键：版本冲突后同一 requestId 用正确版本重试成功
        addItem("eq-iitem", "ii-2", 99, "FILTER", 300, 409);
        addItem("eq-iitem", "ii-2", 2, "FILTER", 300, 201);
        mockMvc.perform(get("/api/equipment/eq-iitem/status"))
                .andExpect(jsonPath("$.version").value(3));
    }
}
