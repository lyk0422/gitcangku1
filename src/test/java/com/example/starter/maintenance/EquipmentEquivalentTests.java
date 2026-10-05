package com.example.starter.maintenance;

import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;

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
 * 负荷加权等效寿命测试：等效累计、修订重算、等效到期、保养冻结快照、
 * 结构化幂等指纹与失败分支（真实 H2 内存库，MySQL 兼容模式）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class EquipmentEquivalentTests {

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
        jdbc.update("DELETE FROM equipment");
    }

    private MvcResult postJson(String url, String body) throws Exception {
        return mockMvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn();
    }

    private void register(String equipmentId, long periodMinutes, Long equivalentPeriod) throws Exception {
        String equivalent = equivalentPeriod == null ? "null" : equivalentPeriod.toString();
        mockMvc.perform(post("/api/equipment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"reg-%s","equipmentId":"%s","maintenancePeriodMinutes":%d,
                                 "equivalentPeriodMinutes":%s}
                                """.formatted(equipmentId, equipmentId, periodMinutes, equivalent)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(1));
    }

    private void addReading(String equipmentId, String requestId, long expectedVersion,
                            String readingId, String sampledAt, long cumulativeMinutes,
                            String loadType, int expectedStatus) throws Exception {
        mockMvc.perform(post("/api/equipment/" + equipmentId + "/readings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"%s","expectedVersion":%d,"readingId":"%s",
                                 "sampledAt":"%s","cumulativeMinutes":%d,"loadType":"%s"}
                                """.formatted(requestId, expectedVersion, readingId, sampledAt,
                                cumulativeMinutes, loadType)))
                .andExpect(status().is(expectedStatus));
    }

    // ---------- 题干合成场景：100 NORMAL + 150 HEAVY → 等效 200；首条改 80 → 80+140=220 ----------

    @Test
    void syntheticScenario_reviseRecomputesOwnAndNextInterval() throws Exception {
        register("eq-eqv", 100000, 300L);
        addReading("eq-eqv", "eq1", 1, "r1", "2026-01-01T10:00:00Z", 100, "NORMAL", 201);
        addReading("eq-eqv", "eq2", 2, "r2", "2026-01-01T11:00:00Z", 150, "HEAVY", 201);

        // 等效累计 = 100×1 + (150-100)×2 = 200；物理口径不受影响
        mockMvc.perform(get("/api/equipment/eq-eqv/status"))
                .andExpect(jsonPath("$.latestCumulativeMinutes").value(150))
                .andExpect(jsonPath("$.latestEquivalentMinutes").value(200))
                .andExpect(jsonPath("$.equivalentPeriodMinutes").value(300))
                .andExpect(jsonPath("$.equivalentRemainingMinutes").value(100))
                .andExpect(jsonPath("$.runMinutes").value(150))
                .andExpect(jsonPath("$.physicalRemainingMinutes").value(99850))
                .andExpect(jsonPath("$.status").value("OK"));

        // 修订首条 100 → 80：重算本区间（80×1）与后一区间（(150-80)×2=140），等效 = 220
        mockMvc.perform(post("/api/equipment/eq-eqv/readings/r1/revisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"eq3","expectedVersion":3,"cumulativeMinutes":80}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.cumulativeMinutes").value(80))
                .andExpect(jsonPath("$.loadType").value("NORMAL"))
                .andExpect(jsonPath("$.revisionNo").value(2));

        mockMvc.perform(get("/api/equipment/eq-eqv/status"))
                .andExpect(jsonPath("$.latestCumulativeMinutes").value(150))
                .andExpect(jsonPath("$.latestEquivalentMinutes").value(220))
                .andExpect(jsonPath("$.equivalentRemainingMinutes").value(80))
                .andExpect(jsonPath("$.status").value("OK"));
    }

    // ---------- 等效到期：剩余 <= 0 即 DUE，物理分钟规则保留 ----------

    @Test
    void equivalentDue_whenRemainingNotPositive() throws Exception {
        register("eq-due", 100000, 200L);
        addReading("eq-due", "d1", 1, "r1", "2026-01-01T10:00:00Z", 100, "NORMAL", 201);
        mockMvc.perform(get("/api/equipment/eq-due/status"))
                .andExpect(jsonPath("$.equivalentRemainingMinutes").value(100))
                .andExpect(jsonPath("$.status").value("OK"));

        // 等效累计 = 100 + 50×2 = 200，剩余 0 → 到期；物理剩余仍充足
        addReading("eq-due", "d2", 2, "r2", "2026-01-01T11:00:00Z", 150, "HEAVY", 201);
        mockMvc.perform(get("/api/equipment/eq-due/status"))
                .andExpect(jsonPath("$.latestEquivalentMinutes").value(200))
                .andExpect(jsonPath("$.equivalentRemainingMinutes").value(0))
                .andExpect(jsonPath("$.physicalRemainingMinutes").value(99850))
                .andExpect(jsonPath("$.status").value("DUE"));

        // 超过周期后为负剩余，仍 DUE
        addReading("eq-due", "d3", 3, "r3", "2026-01-01T12:00:00Z", 200, "HEAVY", 201);
        mockMvc.perform(get("/api/equipment/eq-due/status"))
                .andExpect(jsonPath("$.equivalentRemainingMinutes").value(-100))
                .andExpect(jsonPath("$.status").value("DUE"));
    }

    // ---------- 未启用等效寿命：等效字段为 null，仅物理口径判定 ----------

    @Test
    void equivalentDisabled_nullEquivalentFields() throws Exception {
        register("eq-noeq", 100, null);
        mockMvc.perform(get("/api/equipment/eq-noeq/status"))
                .andExpect(jsonPath("$.equivalentPeriodMinutes").value(nullValue()))
                .andExpect(jsonPath("$.equivalentRemainingMinutes").value(nullValue()))
                .andExpect(jsonPath("$.status").value("OK"));

        addReading("eq-noeq", "n1", 1, "r1", "2026-01-01T10:00:00Z", 130, "HEAVY", 201);
        // 物理到期规则不变；等效剩余保持 null（即使等效累计可计算也不参与判定）
        mockMvc.perform(get("/api/equipment/eq-noeq/status"))
                .andExpect(jsonPath("$.runMinutes").value(130))
                .andExpect(jsonPath("$.physicalRemainingMinutes").value(-30))
                .andExpect(jsonPath("$.equivalentRemainingMinutes").value(nullValue()))
                .andExpect(jsonPath("$.status").value("DUE"));
    }

    // ---------- 保养冻结：冻结锚点读数与当时等效累计，后续读数/修订不改变历史快照 ----------

    @Test
    void maintenanceFreezesEquivalentSnapshot() throws Exception {
        register("eq-frz", 1000, 500L);
        addReading("eq-frz", "f1", 1, "r1", "2026-01-01T10:00:00Z", 100, "NORMAL", 201);
        addReading("eq-frz", "f2", 2, "r2", "2026-01-01T11:00:00Z", 150, "HEAVY", 201);

        // 完成保养：冻结锚点 r2 与当时等效累计 200
        mockMvc.perform(post("/api/equipment/eq-frz/maintenances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"f-m1","expectedVersion":3,"readingId":"r2","anchorRevisionNo":1}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.readingId").value("r2"))
                .andExpect(jsonPath("$.anchorCumulativeMinutes").value(150))
                .andExpect(jsonPath("$.anchorEquivalentMinutes").value(200))
                .andExpect(jsonPath("$.equipmentVersion").value(4));

        // 状态同时显示物理与等效剩余（均自冻结基准起算）
        mockMvc.perform(get("/api/equipment/eq-frz/status"))
                .andExpect(jsonPath("$.lastMaintenanceAnchorCumulativeMinutes").value(150))
                .andExpect(jsonPath("$.lastMaintenanceAnchorEquivalentMinutes").value(200))
                .andExpect(jsonPath("$.runMinutes").value(0))
                .andExpect(jsonPath("$.physicalRemainingMinutes").value(1000))
                .andExpect(jsonPath("$.equivalentRemainingMinutes").value(500))
                .andExpect(jsonPath("$.status").value("OK"));

        // 新读数推进两套口径：等效累计 = 200 + (260-150)×2 = 420
        addReading("eq-frz", "f3", 4, "r3", "2026-01-01T12:00:00Z", 260, "HEAVY", 201);
        mockMvc.perform(get("/api/equipment/eq-frz/status"))
                .andExpect(jsonPath("$.latestEquivalentMinutes").value(420))
                .andExpect(jsonPath("$.runMinutes").value(110))
                .andExpect(jsonPath("$.physicalRemainingMinutes").value(890))
                .andExpect(jsonPath("$.equivalentRemainingMinutes").value(280))
                .andExpect(jsonPath("$.status").value("OK"));

        // 修订锚点之后的读数：等效剩余按新值重算（最新等效 200+90×2=380，剩余 500-(380-200)=320），历史快照不变
        mockMvc.perform(post("/api/equipment/eq-frz/readings/r3/revisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"f4","expectedVersion":5,"cumulativeMinutes":240}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(get("/api/equipment/eq-frz/status"))
                .andExpect(jsonPath("$.latestEquivalentMinutes").value(380))
                .andExpect(jsonPath("$.equivalentRemainingMinutes").value(320))
                .andExpect(jsonPath("$.lastMaintenanceAnchorEquivalentMinutes").value(200));
        mockMvc.perform(get("/api/equipment/eq-frz/maintenances"))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].anchorEquivalentMinutes").value(200))
                .andExpect(jsonPath("$[0].anchorCumulativeMinutes").value(150));

        // 锚点读数 r2 不可更正
        mockMvc.perform(post("/api/equipment/eq-frz/readings/r2/revisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"f5","expectedVersion":6,"cumulativeMinutes":140}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("READING_ANCHORED"));
    }

    // ---------- 失败分支：负荷类型必填且合法；等效周期为正整数；失败不推进版本、不写修订 ----------

    @Test
    void invalidLoadParams_badRequest() throws Exception {
        register("eq-bad-load", 1000, 300L);
        // 缺 loadType → 400
        mockMvc.perform(post("/api/equipment/eq-bad-load/readings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"bl-1","expectedVersion":1,"readingId":"r1",
                                 "sampledAt":"2026-01-01T10:00:00Z","cumulativeMinutes":100}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        // 非法负荷类型 → 400
        mockMvc.perform(post("/api/equipment/eq-bad-load/readings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"bl-2","expectedVersion":1,"readingId":"r1",
                                 "sampledAt":"2026-01-01T10:00:00Z","cumulativeMinutes":100,"loadType":"STRONG"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        // 等效周期必须为正整数
        mockMvc.perform(post("/api/equipment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"bl-3","equipmentId":"eq-bad-eq","maintenancePeriodMinutes":100,
                                 "equivalentPeriodMinutes":0}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        // 参数校验失败不占幂等键、不推进版本：修正参数后同 requestId 可成功
        addReading("eq-bad-load", "bl-1", 1, "r1", "2026-01-01T10:00:00Z", 100, "NORMAL", 201);
        mockMvc.perform(get("/api/equipment/eq-bad-load/status"))
                .andExpect(jsonPath("$.version").value(2));
    }

    @Test
    void failedRevise_noVersionAdvanceNoRevision() throws Exception {
        register("eq-frv", 1000, null);
        addReading("eq-frv", "v1", 1, "r1", "2026-01-01T10:00:00Z", 100, "NORMAL", 201);
        addReading("eq-frv", "v2", 2, "r2", "2026-01-01T11:00:00Z", 200, "HEAVY", 201);

        // 修订破坏单调性 → 422，不推进版本、不追加修订
        mockMvc.perform(post("/api/equipment/eq-frv/readings/r1/revisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"v3","expectedVersion":3,"cumulativeMinutes":250}
                                """))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(get("/api/equipment/eq-frv/status"))
                .andExpect(jsonPath("$.version").value(3));
        mockMvc.perform(get("/api/equipment/eq-frv/readings/r1/revisions"))
                .andExpect(jsonPath("$", hasSize(1)));
        Integer revisionRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM reading_revision WHERE equipment_id = 'eq-frv'", Integer.class);
        org.junit.jupiter.api.Assertions.assertEquals(2, revisionRows, "失败修订不得写入修订历史");
    }

    // ---------- 历史读数（无负荷权重列值）按 NORMAL 处理 ----------

    @Test
    void legacyReadingWithoutWeight_defaultsToNormal() throws Exception {
        register("eq-legacy", 100000, 1000L);
        // 模拟功能上线前的旧读数：直接落库，load_weight 取列默认值 1
        OffsetDateTime at = OffsetDateTime.parse("2026-01-01T09:00:00Z");
        jdbc.update("INSERT INTO reading (equipment_id, reading_id, sampled_at, cumulative_minutes,"
                        + " revision_no, created_at, updated_at) VALUES (?, ?, ?, ?, 1, ?, ?)",
                "eq-legacy", "r-old", at, 50L, at, at);

        // 旧读数按 NORMAL：等效累计 = 50×1 + (150-50)×2 = 250
        addReading("eq-legacy", "lg1", 1, "r2", "2026-01-01T10:00:00Z", 150, "HEAVY", 201);
        mockMvc.perform(get("/api/equipment/eq-legacy/status"))
                .andExpect(jsonPath("$.latestEquivalentMinutes").value(250))
                .andExpect(jsonPath("$.equivalentRemainingMinutes").value(750));
        mockMvc.perform(get("/api/equipment/eq-legacy/readings"))
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].readingId").value("r-old"))
                .andExpect(jsonPath("$[0].loadType").value("NORMAL"))
                .andExpect(jsonPath("$[1].loadType").value("HEAVY"));
    }

    // ---------- 结构化幂等指纹：参数含分隔符不碰撞；同键同参重放、异参 409 ----------

    @Test
    void structuredFingerprint_noSeparatorCollision() throws Exception {
        // 旧 "|" 拼接方案下两条请求指纹相同：x|y + eq-fp-a 与 x + y|eq-fp-a
        MvcResult first = postJson("/api/equipment", """
                {"requestId":"fp-1","operator":"x|y","equipmentId":"eq-fp-a","maintenancePeriodMinutes":100}
                """);
        org.junit.jupiter.api.Assertions.assertEquals(201, first.getResponse().getStatus());

        // 同键异参（拼接会碰撞的参数组合）→ 必须 409，而非错误重放
        mockMvc.perform(post("/api/equipment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"fp-1","operator":"x","equipmentId":"y|eq-fp-a","maintenancePeriodMinutes":100}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));

        // 同键同参（含分隔符）→ 重放首次完整响应
        MvcResult replay = postJson("/api/equipment", """
                {"requestId":"fp-1","operator":"x|y","equipmentId":"eq-fp-a","maintenancePeriodMinutes":100}
                """);
        org.junit.jupiter.api.Assertions.assertEquals(201, replay.getResponse().getStatus());
        org.junit.jupiter.api.Assertions.assertEquals(
                first.getResponse().getContentAsString(), replay.getResponse().getContentAsString());
        // 异参请求未生效：设备 y|eq-fp-a 不存在
        mockMvc.perform(get("/api/equipment/y|eq-fp-a/status"))
                .andExpect(status().isNotFound());
    }

    // ---------- 完成保养幂等：同键同参重放，异参 409，业务效果只发生一次 ----------

    @Test
    void maintenance_idempotentReplay() throws Exception {
        register("eq-mrp", 1000, 300L);
        addReading("eq-mrp", "p1", 1, "r1", "2026-01-01T10:00:00Z", 100, "NORMAL", 201);
        addReading("eq-mrp", "p2", 2, "r2", "2026-01-01T11:00:00Z", 150, "HEAVY", 201);

        MvcResult first = postJson("/api/equipment/eq-mrp/maintenances", """
                {"requestId":"p-m1","expectedVersion":3,"readingId":"r2","anchorRevisionNo":1}
                """);
        org.junit.jupiter.api.Assertions.assertEquals(201, first.getResponse().getStatus());

        MvcResult replay = postJson("/api/equipment/eq-mrp/maintenances", """
                {"requestId":"p-m1","expectedVersion":3,"readingId":"r2","anchorRevisionNo":1}
                """);
        org.junit.jupiter.api.Assertions.assertEquals(201, replay.getResponse().getStatus());
        org.junit.jupiter.api.Assertions.assertEquals(
                first.getResponse().getContentAsString(), replay.getResponse().getContentAsString());
        mockMvc.perform(get("/api/equipment/eq-mrp/maintenances"))
                .andExpect(jsonPath("$", hasSize(1)));
        mockMvc.perform(get("/api/equipment/eq-mrp/status"))
                .andExpect(jsonPath("$.version").value(4));

        // 同键异参 → 409
        mockMvc.perform(post("/api/equipment/eq-mrp/maintenances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"p-m1","expectedVersion":3,"readingId":"r1","anchorRevisionNo":1}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));
    }
}

