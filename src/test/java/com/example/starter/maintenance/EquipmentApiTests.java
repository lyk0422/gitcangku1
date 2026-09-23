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
 * 设备工时保养 API 主流程与失败分支测试（真实 H2 内存库，MySQL 兼容模式）。
 * 工时以小时登记（rawHours），保养周期仍为分钟；整小时读数与分钟换算为 60 倍关系。
 */
@SpringBootTest
@AutoConfigureMockMvc
class EquipmentApiTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM idempotency_request");
        jdbc.update("DELETE FROM meter_chain_recompute");
        jdbc.update("DELETE FROM maintenance");
        jdbc.update("DELETE FROM reading_revision");
        jdbc.update("DELETE FROM reading");
        jdbc.update("DELETE FROM meter");
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
                            String readingId, String sampledAt, double rawHours,
                            int expectedStatus) throws Exception {
        mockMvc.perform(post("/api/equipment/" + equipmentId + "/readings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"%s","expectedVersion":%d,"readingId":"%s",
                                 "sampledAt":"%s","rawHours":%s}
                                """.formatted(requestId, expectedVersion, readingId, sampledAt,
                                formatHours(rawHours))))
                .andExpect(status().is(expectedStatus));
    }

    private static String formatHours(double hours) {
        if (hours == Math.rint(hours)) {
            return String.valueOf((long) hours);
        }
        return String.valueOf(hours);
    }

    // ---------- 主流程：登记 → 读数 → DUE → 保养 → OK → 历史 ----------

    @Test
    void mainFlow_statusTransitionsAndHistory() throws Exception {
        register("eq-main", 6000);

        // 初始状态：虚拟工时 0，OK；初始 ACTIVE 表 meterKey 等于设备标识
        mockMvc.perform(get("/api/equipment/eq-main/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.activeMeterKey").value("eq-main"))
                .andExpect(jsonPath("$.latestVirtualMinutes").value(0))
                .andExpect(jsonPath("$.runMinutes").value(0))
                .andExpect(jsonPath("$.status").value("OK"));

        addReading("eq-main", "add-r1", 1, "r1", "2026-01-01T10:00:00Z", 50, 201);
        mockMvc.perform(get("/api/equipment/eq-main/status"))
                .andExpect(jsonPath("$.latestVirtualHours").value(50))
                .andExpect(jsonPath("$.runMinutes").value(3000))
                .andExpect(jsonPath("$.status").value("OK"));

        // 达到保养周期（130h = 7800min >= 6000min）→ DUE
        addReading("eq-main", "add-r2", 2, "r2", "2026-01-01T11:00:00Z", 130, 201);
        mockMvc.perform(get("/api/equipment/eq-main/status"))
                .andExpect(jsonPath("$.runMinutes").value(7800))
                .andExpect(jsonPath("$.status").value("DUE"));

        // 完成保养：锚点 r2 修订号 1，虚拟工时 130h = 7800min
        mockMvc.perform(post("/api/equipment/eq-main/maintenances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"mnt-1","expectedVersion":3,"readingId":"r2","anchorRevisionNo":1}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.anchorSampledAt").value("2026-01-01T11:00:00Z"))
                .andExpect(jsonPath("$.anchorVirtualHours").value(130))
                .andExpect(jsonPath("$.anchorCumulativeMinutes").value(7800))
                .andExpect(jsonPath("$.equipmentVersion").value(4));

        // 保养后本轮运行分钟从锚点重算
        mockMvc.perform(get("/api/equipment/eq-main/status"))
                .andExpect(jsonPath("$.version").value(4))
                .andExpect(jsonPath("$.lastMaintenanceAnchorCumulativeMinutes").value(7800))
                .andExpect(jsonPath("$.runMinutes").value(0))
                .andExpect(jsonPath("$.status").value("OK"));

        addReading("eq-main", "add-r3", 4, "r3", "2026-01-01T12:00:00Z", 180, 201);
        mockMvc.perform(get("/api/equipment/eq-main/status"))
                .andExpect(jsonPath("$.runMinutes").value(3000))
                .andExpect(jsonPath("$.status").value("OK"));

        // 读数历史：按采样时刻升序，r2 已锚定，均挂在初始表
        mockMvc.perform(get("/api/equipment/eq-main/readings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[0].readingId").value("r1"))
                .andExpect(jsonPath("$[0].meterKey").value("eq-main"))
                .andExpect(jsonPath("$[1].readingId").value("r2"))
                .andExpect(jsonPath("$[1].anchored").value(true))
                .andExpect(jsonPath("$[2].readingId").value("r3"));

        // 保养历史：锚点快照保留
        mockMvc.perform(get("/api/equipment/eq-main/maintenances"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].readingId").value("r2"))
                .andExpect(jsonPath("$[0].anchorRevisionNo").value(1))
                .andExpect(jsonPath("$[0].anchorCumulativeMinutes").value(7800));

        // 修订历史：初始登记为修订 1
        mockMvc.perform(get("/api/equipment/eq-main/readings/r2/revisions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].revisionNo").value(1))
                .andExpect(jsonPath("$[0].rawHours").value(130));
    }

    // ---------- 设备登记 ----------

    @Test
    void register_duplicateEquipment_conflict() throws Exception {
        register("eq-dup", 6000);
        mockMvc.perform(post("/api/equipment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"reg-dup-2","equipmentId":"eq-dup","maintenancePeriodMinutes":12000}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EQUIPMENT_EXISTS"));
    }

    @Test
    void register_invalidParams_badRequest() throws Exception {
        // 周期必须为正整数
        mockMvc.perform(post("/api/equipment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"reg-bad-1","equipmentId":"eq-bad","maintenancePeriodMinutes":0}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        // requestId 必填
        mockMvc.perform(post("/api/equipment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"equipmentId":"eq-bad","maintenancePeriodMinutes":6000}
                                """))
                .andExpect(status().isBadRequest());
    }

    // ---------- 版本校验 ----------

    @Test
    void write_versionConflict_returns409() throws Exception {
        register("eq-ver", 6000);
        addReading("eq-ver", "add-v1", 99, "r1", "2026-01-01T10:00:00Z", 10, 409);
        // 失败的写操作不改变版本
        mockMvc.perform(get("/api/equipment/eq-ver/status"))
                .andExpect(jsonPath("$.version").value(1));
        addReading("eq-ver", "add-v2", 1, "r1", "2026-01-01T10:00:00Z", 10, 201);
        // 修订同样校验版本
        mockMvc.perform(post("/api/equipment/eq-ver/readings/r1/revisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"rev-v1","expectedVersion":1,"rawHours":20}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
    }

    // ---------- 单调性与补录 ----------

    @Test
    void addReading_backfillMustSatisfyBothNeighbors() throws Exception {
        register("eq-mono", 60000);
        addReading("eq-mono", "m1", 1, "r1", "2026-01-01T10:00:00Z", 100, 201);
        addReading("eq-mono", "m2", 2, "r3", "2026-01-01T12:00:00Z", 300, 201);

        // 合法补录：100 <= 200 <= 300
        addReading("eq-mono", "m3", 3, "r2", "2026-01-01T11:00:00Z", 200, 201);

        // 补录超过后邻（300）：即使不小于前邻也必须 422，不得仅与最新值比较
        addReading("eq-mono", "m4", 4, "r2x", "2026-01-01T11:30:00Z", 350, 422);
        // 补录低于前邻（100）
        addReading("eq-mono", "m5", 4, "r2y", "2026-01-01T10:30:00Z", 50, 422);
        // 末尾追加低于最新值
        addReading("eq-mono", "m6", 4, "r4", "2026-01-01T13:00:00Z", 250, 422);
        // 边界相等允许（单调不减）
        addReading("eq-mono", "m7", 4, "r4", "2026-01-01T13:00:00Z", 300, 201);

        // 失败分支均未落库：共 4 条读数
        mockMvc.perform(get("/api/equipment/eq-mono/readings"))
                .andExpect(jsonPath("$", hasSize(4)));
    }

    @Test
    void addReading_sameTimestampAndDuplicateId_rejected() throws Exception {
        register("eq-same", 60000);
        addReading("eq-same", "s1", 1, "r1", "2026-01-01T10:00:00Z", 100, 201);
        // 同一设备同一时刻仅一条
        addReading("eq-same", "s2", 2, "r2", "2026-01-01T10:00:00Z", 150, 422);
        // readingId 设备内唯一
        addReading("eq-same", "s3", 2, "r1", "2026-01-01T11:00:00Z", 150, 409);
    }

    @Test
    void addReading_negativeAndBelowInitial_rejected() throws Exception {
        register("eq-neg", 60000);
        // 负数被校验层拦截（400）
        mockMvc.perform(post("/api/equipment/eq-neg/readings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"neg-1","expectedVersion":1,"readingId":"r1",
                                 "sampledAt":"2026-01-01T10:00:00Z","rawHours":-1}
                                """))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/equipment/eq-neg/readings"))
                .andExpect(jsonPath("$", hasSize(0)));
    }

    // ---------- 修订 ----------

    @Test
    void revise_keepsHistoryAndValidatesNeighbors() throws Exception {
        register("eq-rev", 60000);
        addReading("eq-rev", "rv1", 1, "r1", "2026-01-01T10:00:00Z", 100, 201);
        addReading("eq-rev", "rv2", 2, "r2", "2026-01-01T11:00:00Z", 200, 201);

        // 修订只改原始工时，不改采样时刻；修订号 +1；虚拟工时同步
        mockMvc.perform(post("/api/equipment/eq-rev/readings/r1/revisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"rv3","expectedVersion":3,"rawHours":150}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sampledAt").value("2026-01-01T10:00:00Z"))
                .andExpect(jsonPath("$.rawHours").value(150))
                .andExpect(jsonPath("$.virtualHours").value(150))
                .andExpect(jsonPath("$.virtualMinutes").value(9000))
                .andExpect(jsonPath("$.revisionNo").value(2))
                .andExpect(jsonPath("$.equipmentVersion").value(4));

        // 历史保留：修订 1（初始值 100）与修订 2（150）
        mockMvc.perform(get("/api/equipment/eq-rev/readings/r1/revisions"))
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].revisionNo").value(1))
                .andExpect(jsonPath("$[0].rawHours").value(100))
                .andExpect(jsonPath("$[1].revisionNo").value(2))
                .andExpect(jsonPath("$[1].rawHours").value(150));

        // 修订须同时符合前后相邻值
        mockMvc.perform(post("/api/equipment/eq-rev/readings/r1/revisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"rv4","expectedVersion":4,"rawHours":250}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("READING_ORDER_VIOLATION"));
        mockMvc.perform(post("/api/equipment/eq-rev/readings/r2/revisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"rv5","expectedVersion":4,"rawHours":50}
                                """))
                .andExpect(status().isUnprocessableEntity());
    }

    // ---------- 保养锚点规则 ----------

    @Test
    void maintenance_anchorRules() throws Exception {
        register("eq-mnt", 60000);
        addReading("eq-mnt", "a1", 1, "r1", "2026-01-01T10:00:00Z", 100, 201);
        addReading("eq-mnt", "a2", 2, "r2", "2026-01-01T11:00:00Z", 200, 201);

        // 锚点读数不存在 → 404
        mockMvc.perform(post("/api/equipment/eq-mnt/maintenances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"mn-x","expectedVersion":3,"readingId":"nope","anchorRevisionNo":1}
                                """))
                .andExpect(status().isNotFound());

        // 锚点修订号与当前修订号不一致 → 409
        mockMvc.perform(post("/api/equipment/eq-mnt/maintenances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"mn-x2","expectedVersion":3,"readingId":"r1","anchorRevisionNo":7}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ANCHOR_REVISION_CONFLICT"));

        // 第一次保养可选任意读数
        mockMvc.perform(post("/api/equipment/eq-mnt/maintenances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"mn-1","expectedVersion":3,"readingId":"r1","anchorRevisionNo":1}
                                """))
                .andExpect(status().isCreated());

        // 锚点时间必须严格晚于上次保养锚点：同读数/更早读数均 422
        mockMvc.perform(post("/api/equipment/eq-mnt/maintenances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"mn-2","expectedVersion":4,"readingId":"r1","anchorRevisionNo":1}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ANCHOR_TIME_NOT_LATER"));

        // 作为历史保养锚点的读数不可修订 → 409
        mockMvc.perform(post("/api/equipment/eq-mnt/readings/r1/revisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"mn-3","expectedVersion":4,"rawHours":120}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("READING_ANCHORED"));

        // 非锚点读数可正常修订，随后以其新修订号完成第二次保养
        mockMvc.perform(post("/api/equipment/eq-mnt/readings/r2/revisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"mn-4","expectedVersion":4,"rawHours":210}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.revisionNo").value(2));
        mockMvc.perform(post("/api/equipment/eq-mnt/maintenances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"mn-5","expectedVersion":5,"readingId":"r2","anchorRevisionNo":2}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.anchorVirtualHours").value(210))
                .andExpect(jsonPath("$.anchorCumulativeMinutes").value(12600));

        // 保养记录保存锚点快照且不允许删除（无删除接口），历史完整
        mockMvc.perform(get("/api/equipment/eq-mnt/maintenances"))
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].anchorCumulativeMinutes").value(6000))
                .andExpect(jsonPath("$[1].anchorCumulativeMinutes").value(12600));
    }

    // ---------- 幂等 ----------

    @Test
    void idempotency_replayMismatchAndFailureNotOccupying() throws Exception {
        register("eq-idem", 60000);

        MvcResult first = postJson("/api/equipment/eq-idem/readings", """
                {"requestId":"idem-1","expectedVersion":1,"readingId":"r1",
                 "sampledAt":"2026-01-01T10:00:00Z","rawHours":100}
                """);
        assert first.getResponse().getStatus() == 201;

        // 同键同参：重放原成功结果，业务效果不重复
        MvcResult replay = postJson("/api/equipment/eq-idem/readings", """
                {"requestId":"idem-1","expectedVersion":1,"readingId":"r1",
                 "sampledAt":"2026-01-01T10:00:00Z","rawHours":100}
                """);
        assert replay.getResponse().getStatus() == 201;
        assert replay.getResponse().getContentAsString()
                .equals(first.getResponse().getContentAsString());
        mockMvc.perform(get("/api/equipment/eq-idem/readings"))
                .andExpect(jsonPath("$", hasSize(1)));
        mockMvc.perform(get("/api/equipment/eq-idem/status"))
                .andExpect(jsonPath("$.version").value(2));

        // 同键异参 → 409
        mockMvc.perform(post("/api/equipment/eq-idem/readings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"idem-1","expectedVersion":1,"readingId":"r1",
                                 "sampledAt":"2026-01-01T10:00:00Z","rawHours":101}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));

        // 失败不占键：422 后同 requestId 修正参数可成功
        addReading("eq-idem", "idem-2", 2, "r2", "2026-01-01T11:00:00Z", 50, 422);
        addReading("eq-idem", "idem-2", 2, "r2", "2026-01-01T11:00:00Z", 150, 201);

        // 版本冲突失败同样不占键
        addReading("eq-idem", "idem-3", 99, "r3", "2026-01-01T12:00:00Z", 200, 409);
        addReading("eq-idem", "idem-3", 3, "r3", "2026-01-01T12:00:00Z", 200, 201);

        mockMvc.perform(get("/api/equipment/eq-idem/readings"))
                .andExpect(jsonPath("$", hasSize(3)));
    }

    // ---------- 资源不存在 ----------

    @Test
    void notFound_branches() throws Exception {
        mockMvc.perform(get("/api/equipment/nope/status"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EQUIPMENT_NOT_FOUND"));
        mockMvc.perform(get("/api/equipment/nope/readings"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/equipment/nope/maintenances"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/equipment/nope/meters"))
                .andExpect(status().isNotFound());
        addReading("nope", "nf-1", 1, "r1", "2026-01-01T10:00:00Z", 1, 404);

        register("eq-nf", 6000);
        mockMvc.perform(post("/api/equipment/eq-nf/readings/nope/revisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"nf-2","expectedVersion":1,"rawHours":10}
                                """))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/equipment/eq-nf/readings/nope/revisions"))
                .andExpect(status().isNotFound());
    }
}
