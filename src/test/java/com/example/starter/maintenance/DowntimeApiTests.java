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
 * 设备停机区间 API 测试：区间校验、扣减量计算、读数变更新算、撤销、保养结算与幂等（真实 H2 内存库）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class DowntimeApiTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM idempotency_request");
        jdbc.update("DELETE FROM maintenance");
        jdbc.update("DELETE FROM downtime");
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
                .andExpect(status().isCreated());
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

    private MvcResult registerDowntime(String equipmentId, String requestId, long expectedVersion,
                                       String downtimeKey, String startAt, String endAt,
                                       String reason) throws Exception {
        return postJson("/api/equipment/" + equipmentId + "/downtimes", """
                {"requestId":"%s","expectedVersion":%d,"downtimeKey":"%s",
                 "startAt":"%s","endAt":"%s","reason":"%s"}
                """.formatted(requestId, expectedVersion, downtimeKey, startAt, endAt, reason));
    }

    // ---------- 主流程：登记停机 → 状态扣减 → 清单与扣减明细 ----------

    @Test
    void registerDowntime_mainFlow_deductionAndQueries() throws Exception {
        register("eq-dt1", 1000);
        addReading("eq-dt1", "d1-r1", 1, "r1", "2026-01-01T10:00:00Z", 100, 201);
        addReading("eq-dt1", "d1-r2", 2, "r2", "2026-01-01T11:00:00Z", 200, 201);
        addReading("eq-dt1", "d1-r3", 3, "r3", "2026-01-01T12:00:00Z", 350, 201);

        // 登记停机 [10:30, 11:30)：扣减量 = 不晚于 11:30 的最近读数(200) - 不晚于 10:30 的最近读数(100)
        mockMvc.perform(post("/api/equipment/eq-dt1/downtimes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"d1-dt1","expectedVersion":4,"downtimeKey":"dk-a",
                                 "startAt":"2026-01-01T10:30:00Z","endAt":"2026-01-01T11:30:00Z",
                                 "reason":"计划检修"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.downtimeKey").value("dk-a"))
                .andExpect(jsonPath("$.deductionMinutes").value(100))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.equipmentVersion").value(5));

        // 状态：本轮运行分钟 = 350 - 0 - 100 = 250
        mockMvc.perform(get("/api/equipment/eq-dt1/status"))
                .andExpect(jsonPath("$.version").value(5))
                .andExpect(jsonPath("$.latestCumulativeMinutes").value(350))
                .andExpect(jsonPath("$.downtimeDeductionMinutes").value(100))
                .andExpect(jsonPath("$.runMinutes").value(250))
                .andExpect(jsonPath("$.status").value("OK"));

        // 停机清单
        mockMvc.perform(get("/api/equipment/eq-dt1/downtimes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].downtimeKey").value("dk-a"))
                .andExpect(jsonPath("$[0].reason").value("计划检修"))
                .andExpect(jsonPath("$[0].deductionMinutes").value(100))
                .andExpect(jsonPath("$[0].status").value("ACTIVE"));

        // 扣减明细
        mockMvc.perform(get("/api/equipment/eq-dt1/deductions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalDeductionMinutes").value(100))
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].downtimeKey").value("dk-a"))
                .andExpect(jsonPath("$.items[0].deductionMinutes").value(100));
    }

    // ---------- 登记校验分支 ----------

    @Test
    void registerDowntime_validationFailures() throws Exception {
        // 无读数设备：起止无法落在读数范围内
        register("eq-noread", 1000);
        mockMvc.perform(post("/api/equipment/eq-noread/downtimes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"nr-2","expectedVersion":1,"downtimeKey":"dk-nr2",
                                 "startAt":"2026-01-01T10:00:00Z","endAt":"2026-01-01T11:00:00Z",
                                 "reason":"检修"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DOWNTIME_OUT_OF_READINGS"));

        register("eq-val", 1000);
        addReading("eq-val", "v-r1", 1, "r1", "2026-01-01T10:00:00Z", 100, 201);
        addReading("eq-val", "v-r2", 2, "r2", "2026-01-01T12:00:00Z", 300, 201);

        // 结束未晚于开始 → 422
        mockMvc.perform(post("/api/equipment/eq-val/downtimes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"v-t1","expectedVersion":3,"downtimeKey":"dk-t1",
                                 "startAt":"2026-01-01T11:00:00Z","endAt":"2026-01-01T11:00:00Z",
                                 "reason":"检修"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DOWNTIME_TIME_INVALID"));
        mockMvc.perform(post("/api/equipment/eq-val/downtimes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"v-t2","expectedVersion":3,"downtimeKey":"dk-t2",
                                 "startAt":"2026-01-01T12:00:00Z","endAt":"2026-01-01T11:00:00Z",
                                 "reason":"检修"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DOWNTIME_TIME_INVALID"));

        // 原因为空 → 400
        mockMvc.perform(post("/api/equipment/eq-val/downtimes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"v-t3","expectedVersion":3,"downtimeKey":"dk-t3",
                                 "startAt":"2026-01-01T10:00:00Z","endAt":"2026-01-01T11:00:00Z",
                                 "reason":"  "}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        // 起止超出最早/最晚读数采样时刻 → 422
        mockMvc.perform(post("/api/equipment/eq-val/downtimes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"v-t4","expectedVersion":3,"downtimeKey":"dk-t4",
                                 "startAt":"2026-01-01T09:00:00Z","endAt":"2026-01-01T11:00:00Z",
                                 "reason":"检修"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DOWNTIME_OUT_OF_READINGS"));
        mockMvc.perform(post("/api/equipment/eq-val/downtimes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"v-t5","expectedVersion":3,"downtimeKey":"dk-t5",
                                 "startAt":"2026-01-01T11:00:00Z","endAt":"2026-01-01T13:00:00Z",
                                 "reason":"检修"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DOWNTIME_OUT_OF_READINGS"));

        // 版本冲突 → 409，且失败不改变版本
        mockMvc.perform(post("/api/equipment/eq-val/downtimes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"v-t6","expectedVersion":99,"downtimeKey":"dk-t6",
                                 "startAt":"2026-01-01T10:00:00Z","endAt":"2026-01-01T11:00:00Z",
                                 "reason":"检修"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
        mockMvc.perform(get("/api/equipment/eq-val/status"))
                .andExpect(jsonPath("$.version").value(3));

        // 失败不占键：422 后同 requestId / 同 downtimeKey 修正参数可成功
        MvcResult failed = registerDowntime("eq-val", "v-t7", 3, "dk-t7",
                "2026-01-01T09:00:00Z", "2026-01-01T11:00:00Z", "检修");
        assert failed.getResponse().getStatus() == 422;
        mockMvc.perform(post("/api/equipment/eq-val/downtimes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"v-t7","expectedVersion":3,"downtimeKey":"dk-t7",
                                 "startAt":"2026-01-01T10:00:00Z","endAt":"2026-01-01T11:00:00Z",
                                 "reason":"检修"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.equipmentVersion").value(4));

        // 设备不存在 → 404
        mockMvc.perform(post("/api/equipment/nope/downtimes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"v-t8","expectedVersion":1,"downtimeKey":"dk-t8",
                                 "startAt":"2026-01-01T10:00:00Z","endAt":"2026-01-01T11:00:00Z",
                                 "reason":"检修"}
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EQUIPMENT_NOT_FOUND"));
        mockMvc.perform(get("/api/equipment/nope/downtimes"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/equipment/nope/deductions"))
                .andExpect(status().isNotFound());
    }

    // ---------- 重叠规则：生效区间不得重叠，端点相接合法，撤销后不再占位 ----------

    @Test
    void registerDowntime_overlapRules() throws Exception {
        register("eq-ov", 1000);
        addReading("eq-ov", "ov-r1", 1, "r1", "2026-01-01T10:00:00Z", 100, 201);
        addReading("eq-ov", "ov-r2", 2, "r2", "2026-01-01T11:00:00Z", 200, 201);
        addReading("eq-ov", "ov-r3", 3, "r3", "2026-01-01T12:00:00Z", 300, 201);
        addReading("eq-ov", "ov-r4", 4, "r4", "2026-01-01T13:00:00Z", 400, 201);

        MvcResult first = registerDowntime("eq-ov", "ov-d1", 5, "dk-1",
                "2026-01-01T10:00:00Z", "2026-01-01T11:00:00Z", "检修");
        assert first.getResponse().getStatus() == 201;

        // 部分重叠 / 完全重叠 / 包含均 422
        mockMvc.perform(post("/api/equipment/eq-ov/downtimes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"ov-d2","expectedVersion":6,"downtimeKey":"dk-2",
                                 "startAt":"2026-01-01T10:30:00Z","endAt":"2026-01-01T11:30:00Z",
                                 "reason":"检修"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DOWNTIME_OVERLAP"));
        mockMvc.perform(post("/api/equipment/eq-ov/downtimes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"ov-d3","expectedVersion":6,"downtimeKey":"dk-3",
                                 "startAt":"2026-01-01T10:00:00Z","endAt":"2026-01-01T11:00:00Z",
                                 "reason":"检修"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DOWNTIME_OVERLAP"));

        // 端点相接合法：[11:00,12:00) 与 [12:00,13:00)
        MvcResult touching1 = registerDowntime("eq-ov", "ov-d4", 6, "dk-4",
                "2026-01-01T11:00:00Z", "2026-01-01T12:00:00Z", "检修");
        assert touching1.getResponse().getStatus() == 201;
        mockMvc.perform(get("/api/equipment/eq-ov/downtimes"))
                .andExpect(jsonPath("$", hasSize(2)));
        MvcResult touching2 = registerDowntime("eq-ov", "ov-d5", 7, "dk-5",
                "2026-01-01T12:00:00Z", "2026-01-01T13:00:00Z", "检修");
        assert touching2.getResponse().getStatus() == 201;
        mockMvc.perform(get("/api/equipment/eq-ov/downtimes"))
                .andExpect(jsonPath("$", hasSize(3)));

        // downtimeKey 全局唯一：同设备与跨设备复用均 409
        register("eq-ov2", 1000);
        addReading("eq-ov2", "ov2-r1", 1, "r1", "2026-01-01T10:00:00Z", 100, 201);
        addReading("eq-ov2", "ov2-r2", 2, "r2", "2026-01-01T11:00:00Z", 200, 201);
        mockMvc.perform(post("/api/equipment/eq-ov2/downtimes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"ov2-d1","expectedVersion":3,"downtimeKey":"dk-1",
                                 "startAt":"2026-01-01T10:00:00Z","endAt":"2026-01-01T11:00:00Z",
                                 "reason":"检修"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DOWNTIME_KEY_EXISTS"));
    }

    // ---------- 不得跨越保养锚点 ----------

    @Test
    void registerDowntime_crossesAnchor_rejected() throws Exception {
        register("eq-an", 1000);
        addReading("eq-an", "an-r1", 1, "r1", "2026-01-01T10:00:00Z", 100, 201);
        addReading("eq-an", "an-r2", 2, "r2", "2026-01-01T11:00:00Z", 200, 201);
        addReading("eq-an", "an-r3", 3, "r3", "2026-01-01T12:00:00Z", 300, 201);

        // 保养锚点：r2 @ 11:00
        mockMvc.perform(post("/api/equipment/eq-an/maintenances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"an-m1","expectedVersion":4,"readingId":"r2","anchorRevisionNo":1}
                                """))
                .andExpect(status().isCreated());

        // 跨越锚点时刻 11:00 → 422
        mockMvc.perform(post("/api/equipment/eq-an/downtimes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"an-d1","expectedVersion":5,"downtimeKey":"dk-x",
                                 "startAt":"2026-01-01T10:30:00Z","endAt":"2026-01-01T11:30:00Z",
                                 "reason":"检修"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DOWNTIME_CROSSES_ANCHOR"));

        // 端点与锚点相接合法：[10:00,11:00) 不计入本轮（开始早于锚点），[11:00,12:00) 计入
        MvcResult before = registerDowntime("eq-an", "an-d2", 5, "dk-before",
                "2026-01-01T10:00:00Z", "2026-01-01T11:00:00Z", "检修");
        assert before.getResponse().getStatus() == 201;
        MvcResult after = registerDowntime("eq-an", "an-d3", 6, "dk-after",
                "2026-01-01T11:00:00Z", "2026-01-01T12:00:00Z", "检修");
        assert after.getResponse().getStatus() == 201;

        // 本轮（锚点 11:00 之后）仅 dk-after 参与扣减：扣减 = 300 - 200 = 100
        mockMvc.perform(get("/api/equipment/eq-an/status"))
                .andExpect(jsonPath("$.lastMaintenanceAnchorCumulativeMinutes").value(200))
                .andExpect(jsonPath("$.downtimeDeductionMinutes").value(100))
                .andExpect(jsonPath("$.runMinutes").value(0));
        mockMvc.perform(get("/api/equipment/eq-an/deductions"))
                .andExpect(jsonPath("$.anchorSampledAt").value("2026-01-01T11:00:00Z"))
                .andExpect(jsonPath("$.totalDeductionMinutes").value(100))
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].downtimeKey").value("dk-after"));
    }

    // ---------- 扣减量边界取数 ----------

    @Test
    void registerDowntime_deductionBoundaryReadings() throws Exception {
        // 区间内无读数：两端取到同一条读数，扣减为 0
        register("eq-b1", 1000);
        addReading("eq-b1", "b1-r1", 1, "r1", "2026-01-01T10:00:00Z", 100, 201);
        addReading("eq-b1", "b1-r2", 2, "r2", "2026-01-01T12:00:00Z", 300, 201);
        mockMvc.perform(post("/api/equipment/eq-b1/downtimes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"b1-d1","expectedVersion":3,"downtimeKey":"dk-b1",
                                 "startAt":"2026-01-01T10:30:00Z","endAt":"2026-01-01T11:30:00Z",
                                 "reason":"检修"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.deductionMinutes").value(0));

        // 区间端点恰有读数：扣减 = 端点读数差
        register("eq-b2", 1000);
        addReading("eq-b2", "b2-r1", 1, "r1", "2026-01-01T10:00:00Z", 100, 201);
        addReading("eq-b2", "b2-r2", 2, "r2", "2026-01-01T11:00:00Z", 200, 201);
        addReading("eq-b2", "b2-r3", 3, "r3", "2026-01-01T12:00:00Z", 300, 201);
        mockMvc.perform(post("/api/equipment/eq-b2/downtimes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"b2-d1","expectedVersion":4,"downtimeKey":"dk-b2",
                                 "startAt":"2026-01-01T10:00:00Z","endAt":"2026-01-01T12:00:00Z",
                                 "reason":"检修"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.deductionMinutes").value(200));
    }

    // ---------- 修订读数触发扣减重算（边界读数可修订） ----------

    @Test
    void reviseReading_recomputesDeduction() throws Exception {
        register("eq-rr", 1000);
        addReading("eq-rr", "rr-r1", 1, "r1", "2026-01-01T10:00:00Z", 100, 201);
        addReading("eq-rr", "rr-r2", 2, "r2", "2026-01-01T11:00:00Z", 200, 201);
        addReading("eq-rr", "rr-r3", 3, "r3", "2026-01-01T12:00:00Z", 300, 201);
        MvcResult registered = registerDowntime("eq-rr", "rr-d1", 4, "dk-rr",
                "2026-01-01T10:00:00Z", "2026-01-01T11:00:00Z", "检修");
        assert registered.getResponse().getStatus() == 201;
        mockMvc.perform(get("/api/equipment/eq-rr/status"))
                .andExpect(jsonPath("$.downtimeDeductionMinutes").value(100))
                .andExpect(jsonPath("$.runMinutes").value(200));

        // 停机区间边界读数 r2 可修订：200 → 250，扣减重算为 250 - 100 = 150
        mockMvc.perform(post("/api/equipment/eq-rr/readings/r2/revisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"rr-rev1","expectedVersion":5,"cumulativeMinutes":250}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(get("/api/equipment/eq-rr/downtimes"))
                .andExpect(jsonPath("$[0].deductionMinutes").value(150));
        mockMvc.perform(get("/api/equipment/eq-rr/status"))
                .andExpect(jsonPath("$.downtimeDeductionMinutes").value(150))
                .andExpect(jsonPath("$.runMinutes").value(150));

        // 起点边界读数 r1 可修订：100 → 150，扣减重算为 250 - 150 = 100
        mockMvc.perform(post("/api/equipment/eq-rr/readings/r1/revisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"rr-rev2","expectedVersion":6,"cumulativeMinutes":150}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(get("/api/equipment/eq-rr/downtimes"))
                .andExpect(jsonPath("$[0].deductionMinutes").value(100));
        mockMvc.perform(get("/api/equipment/eq-rr/status"))
                .andExpect(jsonPath("$.downtimeDeductionMinutes").value(100))
                .andExpect(jsonPath("$.runMinutes").value(200));

        // 保养锚点读数仍不可修订 → 409
        mockMvc.perform(post("/api/equipment/eq-rr/maintenances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"rr-m1","expectedVersion":7,"readingId":"r3","anchorRevisionNo":1}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/equipment/eq-rr/readings/r3/revisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"rr-rev3","expectedVersion":8,"cumulativeMinutes":310}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("READING_ANCHORED"));
    }

    // ---------- 新增读数触发扣减重算 ----------

    @Test
    void addReading_recomputesDeduction() throws Exception {
        register("eq-ar", 1000);
        addReading("eq-ar", "ar-r1", 1, "r1", "2026-01-01T10:00:00Z", 100, 201);
        addReading("eq-ar", "ar-r2", 2, "r2", "2026-01-01T12:00:00Z", 300, 201);

        // 区间 [10:00, 11:30) 内 initially 无读数：扣减 = 100 - 100 = 0
        mockMvc.perform(post("/api/equipment/eq-ar/downtimes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"ar-d1","expectedVersion":3,"downtimeKey":"dk-ar",
                                 "startAt":"2026-01-01T10:00:00Z","endAt":"2026-01-01T11:30:00Z",
                                 "reason":"检修"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.deductionMinutes").value(0));

        // 补录区间内读数 r3@11:00=200：扣减重算为 200 - 100 = 100
        addReading("eq-ar", "ar-r3", 4, "r3", "2026-01-01T11:00:00Z", 200, 201);
        mockMvc.perform(get("/api/equipment/eq-ar/downtimes"))
                .andExpect(jsonPath("$[0].deductionMinutes").value(100));
        mockMvc.perform(get("/api/equipment/eq-ar/status"))
                .andExpect(jsonPath("$.downtimeDeductionMinutes").value(100))
                .andExpect(jsonPath("$.runMinutes").value(200));
    }

    // ---------- 撤销：不再参与扣减、记录保留不可改写、重复撤销 409 ----------

    @Test
    void revokeDowntime_flow() throws Exception {
        register("eq-rv", 1000);
        addReading("eq-rv", "rv-r1", 1, "r1", "2026-01-01T10:00:00Z", 100, 201);
        addReading("eq-rv", "rv-r2", 2, "r2", "2026-01-01T11:00:00Z", 200, 201);
        addReading("eq-rv", "rv-r3", 3, "r3", "2026-01-01T12:00:00Z", 400, 201);
        MvcResult registered = registerDowntime("eq-rv", "rv-d1", 4, "dk-rv",
                "2026-01-01T10:00:00Z", "2026-01-01T11:00:00Z", "检修");
        assert registered.getResponse().getStatus() == 201;
        mockMvc.perform(get("/api/equipment/eq-rv/status"))
                .andExpect(jsonPath("$.runMinutes").value(300));

        // 版本冲突 → 409
        mockMvc.perform(post("/api/equipment/eq-rv/downtimes/dk-rv/revocation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"rv-k0","expectedVersion":99}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));

        // 正常撤销：扣减量固化在响应中，版本加一
        MvcResult revoked = postJson("/api/equipment/eq-rv/downtimes/dk-rv/revocation", """
                {"requestId":"rv-k1","expectedVersion":5}
                """);
        assert revoked.getResponse().getStatus() == 201;
        mockMvc.perform(post("/api/equipment/eq-rv/downtimes/dk-rv/revocation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"rv-k1","expectedVersion":5}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("REVOKED"))
                .andExpect(jsonPath("$.deductionMinutes").value(100))
                .andExpect(jsonPath("$.revokedAt").exists())
                .andExpect(jsonPath("$.equipmentVersion").value(6));

        // 撤销后不再参与扣减
        mockMvc.perform(get("/api/equipment/eq-rv/status"))
                .andExpect(jsonPath("$.downtimeDeductionMinutes").value(0))
                .andExpect(jsonPath("$.runMinutes").value(400));
        mockMvc.perform(get("/api/equipment/eq-rv/deductions"))
                .andExpect(jsonPath("$.totalDeductionMinutes").value(0))
                .andExpect(jsonPath("$.items", hasSize(0)));

        // 原区间记录保留（含固化扣减量与撤销时刻）
        mockMvc.perform(get("/api/equipment/eq-rv/downtimes"))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].status").value("REVOKED"))
                .andExpect(jsonPath("$[0].deductionMinutes").value(100))
                .andExpect(jsonPath("$[0].revokedAt").exists());

        // 重复撤销 → 409；撤销不存在的键 → 404
        mockMvc.perform(post("/api/equipment/eq-rv/downtimes/dk-rv/revocation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"rv-k2","expectedVersion":6}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DOWNTIME_ALREADY_REVOKED"));
        mockMvc.perform(post("/api/equipment/eq-rv/downtimes/dk-nope/revocation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"rv-k3","expectedVersion":6}
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DOWNTIME_NOT_FOUND"));

        // 已撤销区间不再阻挡新区间（同范围可重新登记）
        mockMvc.perform(post("/api/equipment/eq-rv/downtimes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"rv-d2","expectedVersion":6,"downtimeKey":"dk-rv2",
                                 "startAt":"2026-01-01T10:00:00Z","endAt":"2026-01-01T11:00:00Z",
                                 "reason":"再次检修"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(get("/api/equipment/eq-rv/downtimes"))
                .andExpect(jsonPath("$", hasSize(2)));
    }

    // ---------- 保养结算：固化当时运行分钟与扣减合计 ----------

    @Test
    void completeMaintenance_settlesAndFreezesDeduction() throws Exception {
        register("eq-st", 1000);
        addReading("eq-st", "st-r1", 1, "r1", "2026-01-01T10:00:00Z", 100, 201);
        addReading("eq-st", "st-r2", 2, "r2", "2026-01-01T11:00:00Z", 200, 201);
        addReading("eq-st", "st-r3", 3, "r3", "2026-01-01T12:00:00Z", 350, 201);
        MvcResult dt1 = registerDowntime("eq-st", "st-d1", 4, "dk-st1",
                "2026-01-01T10:00:00Z", "2026-01-01T11:00:00Z", "检修");
        assert dt1.getResponse().getStatus() == 201;

        // 完成保养：固化当时运行分钟 = 350 - 0 - 100 = 250，扣减合计 100
        mockMvc.perform(post("/api/equipment/eq-st/maintenances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"st-m1","expectedVersion":5,"readingId":"r3","anchorRevisionNo":1}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.runMinutes").value(250))
                .andExpect(jsonPath("$.deductionTotalMinutes").value(100));

        // 保养记录固化快照可查询
        mockMvc.perform(get("/api/equipment/eq-st/maintenances"))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].runMinutes").value(250))
                .andExpect(jsonPath("$[0].deductionTotalMinutes").value(100));

        // 新周期：锚点前的停机区间不再参与扣减
        mockMvc.perform(get("/api/equipment/eq-st/status"))
                .andExpect(jsonPath("$.downtimeDeductionMinutes").value(0))
                .andExpect(jsonPath("$.runMinutes").value(0));

        // 新周期内的停机区间参与扣减：dt2 [12:00,13:00) 扣减 = 500 - 350 = 150
        addReading("eq-st", "st-r4", 6, "r4", "2026-01-01T13:00:00Z", 500, 201);
        MvcResult dt2 = registerDowntime("eq-st", "st-d2", 7, "dk-st2",
                "2026-01-01T12:00:00Z", "2026-01-01T13:00:00Z", "检修");
        assert dt2.getResponse().getStatus() == 201;
        mockMvc.perform(get("/api/equipment/eq-st/status"))
                .andExpect(jsonPath("$.downtimeDeductionMinutes").value(150))
                .andExpect(jsonPath("$.runMinutes").value(0));

        // 第二次保养：按上一锚点之后的扣减量结算（仅 dt2）
        mockMvc.perform(post("/api/equipment/eq-st/maintenances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"st-m2","expectedVersion":8,"readingId":"r4","anchorRevisionNo":1}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.runMinutes").value(0))
                .andExpect(jsonPath("$.deductionTotalMinutes").value(150));
        mockMvc.perform(get("/api/equipment/eq-st/maintenances"))
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[1].runMinutes").value(0))
                .andExpect(jsonPath("$[1].deductionTotalMinutes").value(150));
    }

    // ---------- 停机登记幂等 ----------

    @Test
    void registerDowntime_idempotency() throws Exception {
        register("eq-id", 1000);
        addReading("eq-id", "id-r1", 1, "r1", "2026-01-01T10:00:00Z", 100, 201);
        addReading("eq-id", "id-r2", 2, "r2", "2026-01-01T11:00:00Z", 200, 201);

        MvcResult first = registerDowntime("eq-id", "idem-d1", 3, "dk-i1",
                "2026-01-01T10:00:00Z", "2026-01-01T11:00:00Z", "检修");
        assert first.getResponse().getStatus() == 201;

        // 同键同参：重放首次结果，业务效果不重复
        MvcResult replay = registerDowntime("eq-id", "idem-d1", 3, "dk-i1",
                "2026-01-01T10:00:00Z", "2026-01-01T11:00:00Z", "检修");
        assert replay.getResponse().getStatus() == 201;
        assert replay.getResponse().getContentAsString()
                .equals(first.getResponse().getContentAsString());
        mockMvc.perform(get("/api/equipment/eq-id/downtimes"))
                .andExpect(jsonPath("$", hasSize(1)));
        mockMvc.perform(get("/api/equipment/eq-id/status"))
                .andExpect(jsonPath("$.version").value(4));

        // 同键异参 → 409
        mockMvc.perform(post("/api/equipment/eq-id/downtimes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"idem-d1","expectedVersion":3,"downtimeKey":"dk-i1",
                                 "startAt":"2026-01-01T10:00:00Z","endAt":"2026-01-01T11:00:00Z",
                                 "reason":"其他原因"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));

        // 失败不占键：422 后同 requestId 修正参数可成功
        addReading("eq-id", "id-r3", 4, "r3", "2026-01-01T12:00:00Z", 300, 201);
        mockMvc.perform(post("/api/equipment/eq-id/downtimes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"idem-d2","expectedVersion":5,"downtimeKey":"dk-i2",
                                 "startAt":"2026-01-01T12:00:00Z","endAt":"2026-01-01T11:00:00Z",
                                 "reason":"检修"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DOWNTIME_TIME_INVALID"));
        mockMvc.perform(post("/api/equipment/eq-id/downtimes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"idem-d2","expectedVersion":5,"downtimeKey":"dk-i2",
                                 "startAt":"2026-01-01T11:00:00Z","endAt":"2026-01-01T12:00:00Z",
                                 "reason":"检修"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(get("/api/equipment/eq-id/downtimes"))
                .andExpect(jsonPath("$", hasSize(2)));
    }
}
