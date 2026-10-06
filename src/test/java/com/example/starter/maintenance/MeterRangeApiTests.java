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
 * 量程回绕（raw/wrapCount）API 测试：真实 H2 内存库（MySQL 兼容模式）。
 * 覆盖量程登记、原始读数与修订、溢出/非法 400、单调 422、锚点 409、
 * 原累计接口 422、保养快照冻结与幂等边界。
 */
@SpringBootTest
@AutoConfigureMockMvc
class MeterRangeApiTests {

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

    private void register(String equipmentId, long periodMinutes) throws Exception {
        mockMvc.perform(post("/api/equipment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"reg-%s","equipmentId":"%s","maintenancePeriodMinutes":%d}
                                """.formatted(equipmentId, equipmentId, periodMinutes)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.meterRangeMinutes").doesNotExist());
    }

    private void setMeterRange(String equipmentId, String requestId, long expectedVersion,
                               long meterRange, int expectedStatus) throws Exception {
        mockMvc.perform(post("/api/equipment/" + equipmentId + "/meter-range")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"%s","operator":"tester","expectedVersion":%d,
                                 "meterRangeMinutes":%d}
                                """.formatted(requestId, expectedVersion, meterRange)))
                .andExpect(status().is(expectedStatus));
    }

    private void addRaw(String equipmentId, String requestId, long expectedVersion,
                        String readingId, String sampledAt, long raw, long wrapCount,
                        int expectedStatus) throws Exception {
        mockMvc.perform(post("/api/equipment/" + equipmentId + "/readings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"%s","expectedVersion":%d,"readingId":"%s",
                                 "sampledAt":"%s","raw":%d,"wrapCount":%d}
                                """.formatted(requestId, expectedVersion, readingId, sampledAt,
                                raw, wrapCount)))
                .andExpect(status().is(expectedStatus));
    }

    // ---------- 量程登记 ----------

    @Test
    void setMeterRange_lifecycleImmutabilityAndVersion() throws Exception {
        register("eq-mr", 100);

        // 尚无读数时可登记；版本随写操作推进
        setMeterRange("eq-mr", "mr-1", 1, 1000, 201);
        mockMvc.perform(get("/api/equipment/eq-mr/status"))
                .andExpect(jsonPath("$.version").value(2));

        // 不可变：重复登记 409
        mockMvc.perform(post("/api/equipment/eq-mr/meter-range")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"mr-2","expectedVersion":2,"meterRangeMinutes":2000}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("METER_RANGE_ALREADY_SET"));

        // 版本冲突 409，且失败不占键：同 requestId 修正版本后仍因不可变而 409
        mockMvc.perform(post("/api/equipment/eq-mr/meter-range")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"mr-3","expectedVersion":99,"meterRangeMinutes":2000}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));

        // 量程必须为正整数
        mockMvc.perform(post("/api/equipment/eq-mr/meter-range")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"mr-4","expectedVersion":2,"meterRangeMinutes":0}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void setMeterRange_afterReadingsExist_rejected422() throws Exception {
        register("eq-mr-late", 100);
        mockMvc.perform(post("/api/equipment/eq-mr-late/readings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"late-r1","expectedVersion":1,"readingId":"r1",
                                 "sampledAt":"2026-01-01T10:00:00Z","cumulativeMinutes":10}
                                """))
                .andExpect(status().isCreated());
        setMeterRange("eq-mr-late", "mr-late", 2, 1000, 422);
    }

    // ---------- 原始读数：归一与单调 ----------

    @Test
    void rawReading_normalizedMonotonic_noGuessingFromDecrease() throws Exception {
        register("eq-raw", 10000);
        setMeterRange("eq-raw", "mr-raw", 1, 1000, 201);

        // 合成场景：前读数 raw=990/wrap=0 → 归一 990
        addRaw("eq-raw", "raw-1", 2, "r1", "2026-01-01T10:00:00Z", 990, 0, 201);
        // 后读数 raw=5/wrap=1 → 归一 1005；raw 数值下降但显式回绕被接受
        mockMvc.perform(post("/api/equipment/eq-raw/readings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"raw-2","expectedVersion":3,"readingId":"r2",
                                 "sampledAt":"2026-01-01T11:00:00Z","raw":5,"wrapCount":1}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.cumulativeMinutes").value(1005))
                .andExpect(jsonPath("$.raw").value(5))
                .andExpect(jsonPath("$.wrapCount").value(1))
                .andExpect(jsonPath("$.equipmentVersion").value(4));

        // 不根据数值下降猜回绕：raw=5/wrap=0 归一 5 < 990，违反单调 → 422
        addRaw("eq-raw", "raw-3", 4, "r3", "2026-01-01T12:00:00Z", 5, 0, 422);

        // 状态按归一最新值计算
        mockMvc.perform(get("/api/equipment/eq-raw/status"))
                .andExpect(jsonPath("$.latestCumulativeMinutes").value(1005))
                .andExpect(jsonPath("$.runMinutes").value(1005));
    }

    @Test
    void rawReading_invalidAndOverflow_rejected400() throws Exception {
        register("eq-bad-raw", 10000);
        setMeterRange("eq-bad-raw", "mr-bad", 1, 1000, 201);

        // raw 必须满足 0 <= raw < M
        addRaw("eq-bad-raw", "bad-1", 2, "r1", "2026-01-01T10:00:00Z", 1000, 0, 400);
        addRaw("eq-bad-raw", "bad-2", 2, "r1", "2026-01-01T10:00:00Z", -1, 0, 400);
        // wrapCount 必须非负
        addRaw("eq-bad-raw", "bad-3", 2, "r1", "2026-01-01T10:00:00Z", 5, -1, 400);
        // 缺少 raw 或 wrapCount
        mockMvc.perform(post("/api/equipment/eq-bad-raw/readings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"bad-4","expectedVersion":2,"readingId":"r1",
                                 "sampledAt":"2026-01-01T10:00:00Z","raw":5}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RAW_READING_REQUIRED"));
        // 归一结果 long 溢出 → 400
        mockMvc.perform(post("/api/equipment/eq-bad-raw/readings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"bad-5","expectedVersion":2,"readingId":"r1",
                                 "sampledAt":"2026-01-01T10:00:00Z","raw":5,"wrapCount":9223372036854775807}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CUMULATIVE_OVERFLOW"));

        // 失败不占键：同 requestId 修正参数后成功
        addRaw("eq-bad-raw", "bad-5", 2, "r1", "2026-01-01T10:00:00Z", 5, 0, 201);
        mockMvc.perform(get("/api/equipment/eq-bad-raw/readings"))
                .andExpect(jsonPath("$", hasSize(1)));
    }

    // ---------- 原累计接口与量程互斥 ----------

    @Test
    void cumulativeInterface_disabledForRangedEquipment_422() throws Exception {
        register("eq-no-cum", 10000);
        setMeterRange("eq-no-cum", "mr-nc", 1, 1000, 201);

        // 已启用量程设备的新写走原累计接口 → 422
        mockMvc.perform(post("/api/equipment/eq-no-cum/readings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"nc-1","expectedVersion":2,"readingId":"r1",
                                 "sampledAt":"2026-01-01T10:00:00Z","cumulativeMinutes":100}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CUMULATIVE_INTERFACE_DISABLED"));

        addRaw("eq-no-cum", "nc-2", 2, "r1", "2026-01-01T10:00:00Z", 100, 0, 201);

        // 修订同样拒绝原累计接口
        mockMvc.perform(post("/api/equipment/eq-no-cum/readings/r1/revisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"nc-3","expectedVersion":3,"readingRevision":1,
                                 "cumulativeMinutes":150}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CUMULATIVE_INTERFACE_DISABLED"));
    }

    @Test
    void rawFields_rejectedForNonRangedEquipment_400() throws Exception {
        register("eq-legacy", 10000);
        // 未启用量程设备保持旧契约：raw/wrapCount 非法
        mockMvc.perform(post("/api/equipment/eq-legacy/readings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"lg-1","expectedVersion":1,"readingId":"r1",
                                 "sampledAt":"2026-01-01T10:00:00Z","raw":5,"wrapCount":0}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RAW_FIELDS_REQUIRE_METER_RANGE"));

        // 旧契约仍可用
        mockMvc.perform(post("/api/equipment/eq-legacy/readings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"lg-2","expectedVersion":1,"readingId":"r1",
                                 "sampledAt":"2026-01-01T10:00:00Z","cumulativeMinutes":100}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.cumulativeMinutes").value(100))
                .andExpect(jsonPath("$.raw").doesNotExist());

        mockMvc.perform(post("/api/equipment/eq-legacy/readings/r1/revisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"lg-3","expectedVersion":2,"raw":6,"wrapCount":0}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RAW_FIELDS_REQUIRE_METER_RANGE"));
    }

    // ---------- 原始修订：合成场景与修订号 ----------

    @Test
    void reviseRaw_syntheticScenario_wrapToZeroRejected() throws Exception {
        register("eq-syn", 10000);
        setMeterRange("eq-syn", "mr-syn", 1, 1000, 201);
        // 合成数据：M=1000，前读数 raw=990/wrap=0，后读数 raw=5/wrap=1 → 累计 1005
        addRaw("eq-syn", "syn-1", 2, "r1", "2026-01-01T10:00:00Z", 990, 0, 201);
        addRaw("eq-syn", "syn-2", 3, "r2", "2026-01-01T11:00:00Z", 5, 1, 201);

        // 把后读数 wrap 改为 0：归一值将变为 5 < 990，必须拒绝，不能变成累计 5
        mockMvc.perform(post("/api/equipment/eq-syn/readings/r2/revisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"syn-3","expectedVersion":4,"readingRevision":1,
                                 "raw":5,"wrapCount":0}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("READING_ORDER_VIOLATION"));

        // 拒绝后原值不变
        mockMvc.perform(get("/api/equipment/eq-syn/readings"))
                .andExpect(jsonPath("$[1].cumulativeMinutes").value(1005))
                .andExpect(jsonPath("$[1].wrapCount").value(1))
                .andExpect(jsonPath("$[1].revisionNo").value(1));

        // 修订号不一致 → 409
        mockMvc.perform(post("/api/equipment/eq-syn/readings/r2/revisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"syn-4","expectedVersion":4,"readingRevision":7,
                                 "raw":10,"wrapCount":1}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("READING_REVISION_CONFLICT"));

        // 缺少 readingRevision → 400
        mockMvc.perform(post("/api/equipment/eq-syn/readings/r2/revisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"syn-5","expectedVersion":4,"raw":10,"wrapCount":1}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("READING_REVISION_REQUIRED"));

        // 合法修订：raw=10/wrap=1 → 归一 1010，采样时刻不变，修订号 +1
        mockMvc.perform(post("/api/equipment/eq-syn/readings/r2/revisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"syn-6","expectedVersion":4,"readingRevision":1,
                                 "raw":10,"wrapCount":1}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sampledAt").value("2026-01-01T11:00:00Z"))
                .andExpect(jsonPath("$.cumulativeMinutes").value(1010))
                .andExpect(jsonPath("$.raw").value(10))
                .andExpect(jsonPath("$.wrapCount").value(1))
                .andExpect(jsonPath("$.revisionNo").value(2))
                .andExpect(jsonPath("$.equipmentVersion").value(5));

        // 修订历史保留每次 raw/wrapCount
        mockMvc.perform(get("/api/equipment/eq-syn/readings/r2/revisions"))
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].revisionNo").value(1))
                .andExpect(jsonPath("$[0].raw").value(5))
                .andExpect(jsonPath("$[0].wrapCount").value(1))
                .andExpect(jsonPath("$[0].cumulativeMinutes").value(1005))
                .andExpect(jsonPath("$[1].revisionNo").value(2))
                .andExpect(jsonPath("$[1].raw").value(10))
                .andExpect(jsonPath("$[1].cumulativeMinutes").value(1010));
    }

    // ---------- 保养快照冻结 ----------

    @Test
    void maintenance_freezesRawWrapAndMeterRange() throws Exception {
        register("eq-frz", 100);
        setMeterRange("eq-frz", "mr-frz", 1, 1000, 201);
        addRaw("eq-frz", "frz-1", 2, "r1", "2026-01-01T10:00:00Z", 990, 0, 201);
        addRaw("eq-frz", "frz-2", 3, "r2", "2026-01-01T11:00:00Z", 5, 1, 201);

        // 保养锚定 r2（归一 1005）：冻结 raw/wrapCount/M 及归一值
        mockMvc.perform(post("/api/equipment/eq-frz/maintenances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"frz-m1","expectedVersion":4,"readingId":"r2","anchorRevisionNo":1}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.anchorCumulativeMinutes").value(1005))
                .andExpect(jsonPath("$.anchorRaw").value(5))
                .andExpect(jsonPath("$.anchorWrapCount").value(1))
                .andExpect(jsonPath("$.anchorMeterRangeMinutes").value(1000))
                .andExpect(jsonPath("$.equipmentVersion").value(5));

        // 状态 = 归一最新值 - 保养锚点
        mockMvc.perform(get("/api/equipment/eq-frz/status"))
                .andExpect(jsonPath("$.lastMaintenanceAnchorCumulativeMinutes").value(1005))
                .andExpect(jsonPath("$.runMinutes").value(0))
                .andExpect(jsonPath("$.status").value("OK"));

        // 锚点读数不可修订 → 409
        mockMvc.perform(post("/api/equipment/eq-frz/readings/r2/revisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"frz-r","expectedVersion":5,"readingRevision":1,
                                 "raw":10,"wrapCount":1}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("READING_ANCHORED"));

        // 后续回绕读数推进状态：raw=50/wrap=1 → 归一 1050，runMinutes = 45
        addRaw("eq-frz", "frz-3", 5, "r3", "2026-01-01T12:00:00Z", 50, 1, 201);
        mockMvc.perform(get("/api/equipment/eq-frz/status"))
                .andExpect(jsonPath("$.latestCumulativeMinutes").value(1050))
                .andExpect(jsonPath("$.runMinutes").value(45))
                .andExpect(jsonPath("$.status").value("OK"));

        // 旧历史不随后续回绕变值
        mockMvc.perform(get("/api/equipment/eq-frz/maintenances"))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].anchorCumulativeMinutes").value(1005))
                .andExpect(jsonPath("$[0].anchorRaw").value(5))
                .andExpect(jsonPath("$[0].anchorWrapCount").value(1))
                .andExpect(jsonPath("$[0].anchorMeterRangeMinutes").value(1000));
    }

    // ---------- 幂等：结构化身份 ----------

    @Test
    void idempotency_structuredIdentity_replayAndCollision() throws Exception {
        register("eq-fp", 10000);
        setMeterRange("eq-fp", "mr-fp", 1, 1000, 201);

        String body = """
                {"requestId":"fp-1","operator":"alice","expectedVersion":2,"readingId":"r1",
                 "sampledAt":"2026-01-01T10:00:00Z","raw":100,"wrapCount":0}
                """;
        MvcResult first = postJson("/api/equipment/eq-fp/readings", body);
        assert first.getResponse().getStatus() == 201;

        // 同键同参：重放首次完整响应
        MvcResult replay = postJson("/api/equipment/eq-fp/readings", body);
        assert replay.getResponse().getStatus() == 201;
        assert replay.getResponse().getContentAsString()
                .equals(first.getResponse().getContentAsString());
        mockMvc.perform(get("/api/equipment/eq-fp/readings"))
                .andExpect(jsonPath("$", hasSize(1)));

        // 操作者属于幂等身份：仅 operator 不同 → 409
        mockMvc.perform(post("/api/equipment/eq-fp/readings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"fp-1","operator":"bob","expectedVersion":2,"readingId":"r1",
                                 "sampledAt":"2026-01-01T10:00:00Z","raw":100,"wrapCount":0}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));

        // 结构化比较避免分隔符碰撞：含 "|" 的参数不会被拼接混淆
        mockMvc.perform(post("/api/equipment/eq-fp/readings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"fp-2","operator":"p|q","expectedVersion":3,"readingId":"r|2",
                                 "sampledAt":"2026-01-01T11:00:00Z","raw":200,"wrapCount":0}
                                """))
                .andExpect(status().isCreated());
        // 同键但参数在拼接式指纹下可能碰撞（operator/readingId 段互换）→ 结构化判定为异参 409
        mockMvc.perform(post("/api/equipment/eq-fp/readings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"fp-2","operator":"p","expectedVersion":3,"readingId":"q|r|2",
                                 "sampledAt":"2026-01-01T11:00:00Z","raw":200,"wrapCount":0}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));

        // 失败不占键：单调 422 后同 requestId 修正参数成功
        addRaw("eq-fp", "fp-3", 4, "r3", "2026-01-01T12:00:00Z", 50, 0, 422);
        addRaw("eq-fp", "fp-3", 4, "r3", "2026-01-01T12:00:00Z", 300, 0, 201);
        mockMvc.perform(get("/api/equipment/eq-fp/readings"))
                .andExpect(jsonPath("$", hasSize(3)));
    }
}
