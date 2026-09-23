package com.example.starter.maintenance;

import static org.hamcrest.Matchers.containsString;
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
 * 多保养项目能力测试（真实 H2 内存库，MySQL 兼容模式）：
 * 独立周期、共享读数、独立/共享锚点、引用拦截、项目规则、单项目查询与幂等边界。
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
        jdbc.update("DELETE FROM maintenance_item");
        jdbc.update("DELETE FROM reading_revision");
        jdbc.update("DELETE FROM reading");
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

    // ---------- 独立周期、共享读数、新增项目即时计算状态 ----------

    @Test
    void independentPeriodsSharedReadings_statusPerItem() throws Exception {
        register("eq-itm", 100);
        // DEFAULT 周期 100；新增 BELT 项目周期 60，版本加一
        addItem("eq-itm", "item-belt", 1, "BELT", 60, 201);

        // 项目列表按 itemCode 排序：BELT(B) 在 DEFAULT(D) 之前
        mockMvc.perform(get("/api/equipment/eq-itm/items"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].itemCode").value("BELT"))
                .andExpect(jsonPath("$[0].maintenancePeriodMinutes").value(60))
                .andExpect(jsonPath("$[1].itemCode").value("DEFAULT"))
                .andExpect(jsonPath("$[1].maintenancePeriodMinutes").value(100));

        // 新项目无读数时从 0 计算，且不补造保养记录
        mockMvc.perform(get("/api/equipment/eq-itm/items/BELT/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itemCode").value("BELT"))
                .andExpect(jsonPath("$.latestCumulativeMinutes").value(0))
                .andExpect(jsonPath("$.runMinutes").value(0))
                .andExpect(jsonPath("$.status").value("OK"))
                .andExpect(jsonPath("$.lastMaintenanceAnchorSampledAt").doesNotExist());

        addReading("eq-itm", "i-r1", 2, "r1", "2026-02-01T10:00:00Z", 50, 201);
        addReading("eq-itm", "i-r2", 3, "r2", "2026-02-01T11:00:00Z", 90, 201);

        // 同一读数 90：BELT(60) DUE，DEFAULT(100) OK
        mockMvc.perform(get("/api/equipment/eq-itm/items/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(4))
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[0].itemCode").value("BELT"))
                .andExpect(jsonPath("$.items[0].runMinutes").value(90))
                .andExpect(jsonPath("$.items[0].status").value("DUE"))
                .andExpect(jsonPath("$.items[1].itemCode").value("DEFAULT"))
                .andExpect(jsonPath("$.items[1].runMinutes").value(90))
                .andExpect(jsonPath("$.items[1].status").value("OK"));

        // 旧 DEFAULT 状态接口与新单项目接口一致且保持原响应结构
        mockMvc.perform(get("/api/equipment/eq-itm/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.maintenancePeriodMinutes").value(100))
                .andExpect(jsonPath("$.runMinutes").value(90))
                .andExpect(jsonPath("$.status").value("OK"))
                .andExpect(jsonPath("$.itemCode").doesNotExist());

        addReading("eq-itm", "i-r3", 4, "r3", "2026-02-01T12:00:00Z", 120, 201);
        mockMvc.perform(get("/api/equipment/eq-itm/items/DEFAULT/status"))
                .andExpect(jsonPath("$.runMinutes").value(120))
                .andExpect(jsonPath("$.status").value("DUE"));
        mockMvc.perform(get("/api/equipment/eq-itm/items/BELT/status"))
                .andExpect(jsonPath("$.runMinutes").value(120))
                .andExpect(jsonPath("$.status").value("DUE"));

        // 新项目从未保养：历史为空、库中无其保养记录（未补造）
        mockMvc.perform(get("/api/equipment/eq-itm/items/BELT/maintenances"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
        Integer beltMaintenanceCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM maintenance WHERE equipment_id = 'eq-itm' AND item_code = 'BELT'",
                Integer.class);
        org.junit.jupiter.api.Assertions.assertEquals(0, beltMaintenanceCount,
                "新增项目不应补造保养记录");
    }

    // ---------- 独立锚点序列，同一读数可被不同项目引用 ----------

    @Test
    void independentAnchors_sameReadingSharedAcrossItems() throws Exception {
        register("eq-anc", 1000);
        addItem("eq-anc", "a-belt", 1, "BELT", 500, 201);
        addReading("eq-anc", "a-r1", 2, "r1", "2026-03-01T10:00:00Z", 100, 201);
        addReading("eq-anc", "a-r2", 3, "r2", "2026-03-01T11:00:00Z", 200, 201);

        // DEFAULT 以 r1 为锚点
        mockMvc.perform(post("/api/equipment/eq-anc/maintenances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"a-mn-d1","expectedVersion":4,"readingId":"r1",
                                 "anchorRevisionNo":1}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.itemCode").value("DEFAULT"))
                .andExpect(jsonPath("$.equipmentVersion").value(5));

        // BELT 以同一读数、同一修订号为锚点：允许
        mockMvc.perform(post("/api/equipment/eq-anc/maintenances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"a-mn-b1","expectedVersion":5,"readingId":"r1",
                                 "anchorRevisionNo":1,"itemCode":"BELT"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.itemCode").value("BELT"))
                .andExpect(jsonPath("$.equipmentVersion").value(6));

        // 锚点时间只与本项目上次锚点比较：BELT 重复 r1 → 422，不影响 DEFAULT
        mockMvc.perform(post("/api/equipment/eq-anc/maintenances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"a-mn-b2","expectedVersion":6,"readingId":"r1",
                                 "anchorRevisionNo":1,"itemCode":"BELT"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ANCHOR_TIME_NOT_LATER"));

        // DEFAULT 先推进到 r2
        mockMvc.perform(post("/api/equipment/eq-anc/maintenances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"a-mn-d2","expectedVersion":6,"readingId":"r2",
                                 "anchorRevisionNo":1}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.equipmentVersion").value(7));
        // BELT 也推进到 r2
        mockMvc.perform(post("/api/equipment/eq-anc/maintenances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"a-mn-b3","expectedVersion":7,"readingId":"r2",
                                 "anchorRevisionNo":1,"itemCode":"BELT"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.equipmentVersion").value(8));

        // 各项目历史独立、按锚点时间升序；旧接口只返回 DEFAULT
        mockMvc.perform(get("/api/equipment/eq-anc/maintenances"))
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].itemCode").value("DEFAULT"))
                .andExpect(jsonPath("$[0].readingId").value("r1"))
                .andExpect(jsonPath("$[1].itemCode").value("DEFAULT"))
                .andExpect(jsonPath("$[1].readingId").value("r2"));
        mockMvc.perform(get("/api/equipment/eq-anc/items/BELT/maintenances"))
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].readingId").value("r1"))
                .andExpect(jsonPath("$[1].readingId").value("r2"));

        // 状态各自从自己的锚点工时 200 起算
        addReading("eq-anc", "a-r3", 8, "r3", "2026-03-01T12:00:00Z", 230, 201);
        mockMvc.perform(get("/api/equipment/eq-anc/items/status"))
                .andExpect(jsonPath("$.items[0].itemCode").value("BELT"))
                .andExpect(jsonPath("$.items[0].runMinutes").value(30))
                .andExpect(jsonPath("$.items[1].itemCode").value("DEFAULT"))
                .andExpect(jsonPath("$.items[1].runMinutes").value(30));
    }

    // ---------- 引用拦截：409 列出全部引用 itemCode ----------

    @Test
    void anchoredReading_blockedWithAllReferencingItemCodes() throws Exception {
        register("eq-blk", 1000);
        addItem("eq-blk", "b-belt", 1, "BELT", 500, 201);
        addReading("eq-blk", "b-r1", 2, "r1", "2026-04-01T10:00:00Z", 100, 201);
        addReading("eq-blk", "b-r2", 3, "r2", "2026-04-01T11:00:00Z", 200, 201);
        addReading("eq-blk", "b-r3", 4, "r3", "2026-04-01T12:00:00Z", 300, 201);

        // 仅 BELT 引用 r1：409 且消息列出 BELT
        postJson("/api/equipment/eq-blk/maintenances", """
                {"requestId":"b-mn-b1","expectedVersion":5,"readingId":"r1",
                 "anchorRevisionNo":1,"itemCode":"BELT"}
                """);
        mockMvc.perform(post("/api/equipment/eq-blk/readings/r1/revisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"b-rev-1","expectedVersion":6,"cumulativeMinutes":120}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("READING_ANCHORED"))
                .andExpect(jsonPath("$.message", containsString("BELT")));

        // DEFAULT 也引用 r1：409 需列出全部引用项目
        postJson("/api/equipment/eq-blk/maintenances", """
                {"requestId":"b-mn-d1","expectedVersion":6,"readingId":"r1",
                 "anchorRevisionNo":1}
                """);
        mockMvc.perform(post("/api/equipment/eq-blk/readings/r1/revisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"b-rev-2","expectedVersion":7,"cumulativeMinutes":120}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("READING_ANCHORED"))
                .andExpect(jsonPath("$.message", containsString("BELT")))
                .andExpect(jsonPath("$.message", containsString("DEFAULT")));

        // 未引用读数继续按前后相邻值校验：250 合法、350 超后邻 422
        mockMvc.perform(post("/api/equipment/eq-blk/readings/r2/revisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"b-rev-3","expectedVersion":7,"cumulativeMinutes":350}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("READING_ORDER_VIOLATION"));
        mockMvc.perform(post("/api/equipment/eq-blk/readings/r2/revisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"b-rev-4","expectedVersion":7,"cumulativeMinutes":250}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.revisionNo").value(2))
                .andExpect(jsonPath("$.equipmentVersion").value(8));

        // 读数列表 anchored 标记为跨项目聚合
        mockMvc.perform(get("/api/equipment/eq-blk/readings"))
                .andExpect(jsonPath("$[0].readingId").value("r1"))
                .andExpect(jsonPath("$[0].anchored").value(true))
                .andExpect(jsonPath("$[1].readingId").value("r2"))
                .andExpect(jsonPath("$[1].anchored").value(false));
    }

    // ---------- 新增项目规则与上限 ----------

    @Test
    void addItem_rulesAndLimit() throws Exception {
        register("eq-rules", 1000);

        // 版本冲突失败不占键：同一 requestId 修正版本后成功
        addItem("eq-rules", "r-retry", 999, "I00", 50, 409);
        addItem("eq-rules", "r-retry", 1, "I00", 50, 201);

        // itemCode 设备内唯一
        addItem("eq-rules", "r-dup", 2, "I00", 60, 409);

        // DEFAULT 为保留编码
        mockMvc.perform(post("/api/equipment/eq-rules/items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"r-def","expectedVersion":2,"itemCode":"DEFAULT",
                                 "maintenancePeriodMinutes":100}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        // 非法字符与非正周期
        addItem("eq-rules", "r-bad1", 2, "bad code", 100, 400);
        addItem("eq-rules", "r-bad2", 2, "BADPER", 0, 400);

        // 失败均不推进版本
        mockMvc.perform(get("/api/equipment/eq-rules/status"))
                .andExpect(jsonPath("$.version").value(2));

        // 再加 19 个自定义项目，合计 20 个自定义 + DEFAULT = 21
        for (int i = 2; i <= 20; i++) {
            addItem("eq-rules", "r-i" + i, i, "I%02d".formatted(i), 100 + i, 201);
        }
        // 第 21 个自定义项目超出上限：422
        addItem("eq-rules", "r-over", 21, "I21", 100, 422);
        mockMvc.perform(get("/api/equipment/eq-rules/items"))
                .andExpect(jsonPath("$", hasSize(21)));
        mockMvc.perform(get("/api/equipment/eq-rules/status"))
                .andExpect(jsonPath("$.version").value(21));

        // 不存在项目的查询与写入 → 404
        mockMvc.perform(get("/api/equipment/eq-rules/items/NOPE/status"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ITEM_NOT_FOUND"));
        mockMvc.perform(get("/api/equipment/eq-rules/items/NOPE/maintenances"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/equipment/eq-rules/maintenances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"r-nope","expectedVersion":21,"readingId":"r1",
                                 "anchorRevisionNo":1,"itemCode":"NOPE"}
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ITEM_NOT_FOUND"));
    }

    // ---------- 项目新增与项目保养的幂等 ----------

    @Test
    void itemWrites_idempotentReplayMismatchAndFailure() throws Exception {
        register("eq-idem2", 1000);

        // 新增项目同参重放：响应一致、版本只加一次
        MvcResult first = postJson("/api/equipment/eq-idem2/items", """
                {"requestId":"idem-item","expectedVersion":1,"itemCode":"BELT",
                 "maintenancePeriodMinutes":60}
                """);
        org.junit.jupiter.api.Assertions.assertEquals(201, first.getResponse().getStatus());
        MvcResult replay = postJson("/api/equipment/eq-idem2/items", """
                {"requestId":"idem-item","expectedVersion":1,"itemCode":"BELT",
                 "maintenancePeriodMinutes":60}
                """);
        org.junit.jupiter.api.Assertions.assertEquals(201, replay.getResponse().getStatus());
        org.junit.jupiter.api.Assertions.assertEquals(first.getResponse().getContentAsString(),
                replay.getResponse().getContentAsString());
        // 同键异参 → 409
        mockMvc.perform(post("/api/equipment/eq-idem2/items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"idem-item","expectedVersion":1,"itemCode":"BELT",
                                 "maintenancePeriodMinutes":61}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));
        mockMvc.perform(get("/api/equipment/eq-idem2/items"))
                .andExpect(jsonPath("$", hasSize(2)));
        mockMvc.perform(get("/api/equipment/eq-idem2/status"))
                .andExpect(jsonPath("$.version").value(2));

        addReading("eq-idem2", "idem-r1", 2, "r1", "2026-05-01T10:00:00Z", 100, 201);
        addReading("eq-idem2", "idem-r2", 3, "r2", "2026-05-01T11:00:00Z", 200, 201);

        // 项目保养同参重放：只生成一条记录
        MvcResult mnt1 = postJson("/api/equipment/eq-idem2/maintenances", """
                {"requestId":"idem-mnt","expectedVersion":4,"readingId":"r1",
                 "anchorRevisionNo":1,"itemCode":"BELT"}
                """);
        org.junit.jupiter.api.Assertions.assertEquals(201, mnt1.getResponse().getStatus());
        MvcResult mntReplay = postJson("/api/equipment/eq-idem2/maintenances", """
                {"requestId":"idem-mnt","expectedVersion":4,"readingId":"r1",
                 "anchorRevisionNo":1,"itemCode":"BELT"}
                """);
        org.junit.jupiter.api.Assertions.assertEquals(mnt1.getResponse().getContentAsString(),
                mntReplay.getResponse().getContentAsString());
        mockMvc.perform(get("/api/equipment/eq-idem2/items/BELT/maintenances"))
                .andExpect(jsonPath("$", hasSize(1)));
        // 同键异参 → 409
        mockMvc.perform(post("/api/equipment/eq-idem2/maintenances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"idem-mnt","expectedVersion":4,"readingId":"r2",
                                 "anchorRevisionNo":1,"itemCode":"BELT"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));

        // 业务失败（锚点未晚于上次）不占键：同一 requestId 稍后以更晚锚点成功
        postJson("/api/equipment/eq-idem2/maintenances", """
                {"requestId":"idem-fail","expectedVersion":5,"readingId":"r1",
                 "anchorRevisionNo":1,"itemCode":"BELT"}
                """).getResponse().getStatus();
        MvcResult retried = postJson("/api/equipment/eq-idem2/maintenances", """
                {"requestId":"idem-fail","expectedVersion":5,"readingId":"r2",
                 "anchorRevisionNo":1,"itemCode":"BELT"}
                """);
        org.junit.jupiter.api.Assertions.assertEquals(201, retried.getResponse().getStatus(),
                "失败的项目保养请求不应占用 requestId");
        mockMvc.perform(get("/api/equipment/eq-idem2/items/BELT/maintenances"))
                .andExpect(jsonPath("$", hasSize(2)));
    }
}
