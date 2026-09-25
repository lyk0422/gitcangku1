package com.example.starter.maintenance;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
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
 * 设备停机区间扣减与保养判定重算测试：区间校验、扣减量计算、读数修订重算、
 * 锚点保护、撤销与幂等（真实 H2 内存库，MySQL 兼容模式）。
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

    private MvcResult revokeDowntime(String equipmentId, String requestId, long expectedVersion,
                                     String downtimeKey) throws Exception {
        return postJson("/api/equipment/" + equipmentId + "/downtimes/" + downtimeKey + "/revoke", """
                {"requestId":"%s","expectedVersion":%d}
                """.formatted(requestId, expectedVersion));
    }

    // ---------- 主流程：登记停机 → 扣减 → 状态重算 → 清单 ----------

    @Test
    void mainFlow_deductionAndStatusRecompute() throws Exception {
        register("eq-dt1", 120);
        addReading("eq-dt1", "d1-r1", 1, "r1", "2026-01-01T10:00:00Z", 100, 201);
        addReading("eq-dt1", "d1-r2", 2, "r2", "2026-01-01T11:00:00Z", 200, 201);
        addReading("eq-dt1", "d1-r3", 3, "r3", "2026-01-01T12:00:00Z", 340, 201);

        // 无停机：本轮运行分钟 = 340，DUE
        mockMvc.perform(get("/api/equipment/eq-dt1/status"))
                .andExpect(jsonPath("$.downtimeDeductionMinutes").value(0))
                .andExpect(jsonPath("$.runMinutes").value(340))
                .andExpect(jsonPath("$.status").value("DUE"));

        // 停机 [10:30,11:30]：扣减 = 不晚于 11:30 的 r2(200) - 不晚于 10:30 的 r1(100) = 100
        mockMvc.perform(post("/api/equipment/eq-dt1/downtimes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"dt1-1","expectedVersion":4,"downtimeKey":"dt-1",
                                 "startAt":"2026-01-01T10:30:00Z","endAt":"2026-01-01T11:30:00Z",
                                 "reason":"计划检修"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.downtimeKey").value("dt-1"))
                .andExpect(jsonPath("$.deductionMinutes").value(100))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.revokedAt").value(nullValue()))
                .andExpect(jsonPath("$.equipmentVersion").value(5));

        mockMvc.perform(get("/api/equipment/eq-dt1/status"))
                .andExpect(jsonPath("$.downtimeDeductionMinutes").value(100))
                .andExpect(jsonPath("$.runMinutes").value(240))
                .andExpect(jsonPath("$.status").value("DUE"));

        // 端点相接的第二段 [11:30,12:00]：扣减 = r3(340) - r2(200) = 140，合法
        mockMvc.perform(post("/api/equipment/eq-dt1/downtimes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"dt1-2","expectedVersion":5,"downtimeKey":"dt-2",
                                 "startAt":"2026-01-01T11:30:00Z","endAt":"2026-01-01T12:00:00Z",
                                 "reason":"换班停线"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.deductionMinutes").value(140));

        // 扣减合计 240，运行分钟 100 < 120 → OK
        mockMvc.perform(get("/api/equipment/eq-dt1/status"))
                .andExpect(jsonPath("$.downtimeDeductionMinutes").value(240))
                .andExpect(jsonPath("$.runMinutes").value(100))
                .andExpect(jsonPath("$.status").value("OK"));

        // 停机清单与扣减明细
        mockMvc.perform(get("/api/equipment/eq-dt1/downtimes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].downtimeKey").value("dt-1"))
                .andExpect(jsonPath("$[0].reason").value("计划检修"))
                .andExpect(jsonPath("$[0].deductionMinutes").value(100))
                .andExpect(jsonPath("$[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$[1].downtimeKey").value("dt-2"))
                .andExpect(jsonPath("$[1].deductionMinutes").value(140));
    }

    // ---------- 登记校验：时刻、范围、重叠、键唯一、版本 ----------

    @Test
    void registerDowntime_validationRules() throws Exception {
        register("eq-dt2", 10000);
        addReading("eq-dt2", "d2-r1", 1, "r1", "2026-01-01T10:00:00Z", 100, 201);
        addReading("eq-dt2", "d2-r2", 2, "r2", "2026-01-01T11:00:00Z", 200, 201);
        addReading("eq-dt2", "d2-r3", 3, "r3", "2026-01-01T12:00:00Z", 300, 201);

        // 结束须晚于开始
        MvcResult equal = registerDowntime("eq-dt2", "d2-bad1", 4, "dt-b1",
                "2026-01-01T11:00:00Z", "2026-01-01T11:00:00Z", "x");
        org.junit.jupiter.api.Assertions.assertEquals(422, equal.getResponse().getStatus());
        MvcResult reversed = registerDowntime("eq-dt2", "d2-bad2", 4, "dt-b2",
                "2026-01-01T11:00:00Z", "2026-01-01T10:00:00Z", "x");
        org.junit.jupiter.api.Assertions.assertEquals(422, reversed.getResponse().getStatus());
        org.junit.jupiter.api.Assertions.assertTrue(
                reversed.getResponse().getContentAsString().contains("DOWNTIME_TIME_INVALID"));

        // 起止须落在最早与最晚读数采样时刻之间
        MvcResult tooEarly = registerDowntime("eq-dt2", "d2-bad3", 4, "dt-b3",
                "2026-01-01T09:00:00Z", "2026-01-01T10:30:00Z", "x");
        org.junit.jupiter.api.Assertions.assertEquals(422, tooEarly.getResponse().getStatus());
        MvcResult tooLate = registerDowntime("eq-dt2", "d2-bad4", 4, "dt-b4",
                "2026-01-01T11:00:00Z", "2026-01-01T12:30:00Z", "x");
        org.junit.jupiter.api.Assertions.assertEquals(422, tooLate.getResponse().getStatus());
        org.junit.jupiter.api.Assertions.assertTrue(
                tooLate.getResponse().getContentAsString().contains("DOWNTIME_OUT_OF_RANGE"));

        // 原因非空（Bean 校验 400）
        mockMvc.perform(post("/api/equipment/eq-dt2/downtimes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"d2-bad5","expectedVersion":4,"downtimeKey":"dt-b5",
                                 "startAt":"2026-01-01T10:00:00Z","endAt":"2026-01-01T11:00:00Z",
                                 "reason":" "}
                                """))
                .andExpect(status().isBadRequest());

        // 边界端点允许：起止恰为最早/最晚读数时刻
        mockMvc.perform(post("/api/equipment/eq-dt2/downtimes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"d2-a","expectedVersion":4,"downtimeKey":"dt-a",
                                 "startAt":"2026-01-01T10:00:00Z","endAt":"2026-01-01T11:00:00Z",
                                 "reason":"首段"}
                                """))
                .andExpect(status().isCreated());

        // 与生效区间重叠 → 422
        MvcResult overlap = registerDowntime("eq-dt2", "d2-b", 5, "dt-b",
                "2026-01-01T10:30:00Z", "2026-01-01T11:30:00Z", "重叠");
        org.junit.jupiter.api.Assertions.assertEquals(422, overlap.getResponse().getStatus());
        org.junit.jupiter.api.Assertions.assertTrue(
                overlap.getResponse().getContentAsString().contains("DOWNTIME_OVERLAP"));

        // 端点相接合法
        mockMvc.perform(post("/api/equipment/eq-dt2/downtimes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"d2-c","expectedVersion":5,"downtimeKey":"dt-c",
                                 "startAt":"2026-01-01T11:00:00Z","endAt":"2026-01-01T12:00:00Z",
                                 "reason":"相接"}
                                """))
                .andExpect(status().isCreated());

        // downtimeKey 全局唯一：同设备与异设备均 409
        MvcResult dupSame = registerDowntime("eq-dt2", "d2-dup", 6, "dt-a",
                "2026-01-01T10:00:00Z", "2026-01-01T10:30:00Z", "重复");
        org.junit.jupiter.api.Assertions.assertEquals(409, dupSame.getResponse().getStatus());
        org.junit.jupiter.api.Assertions.assertTrue(
                dupSame.getResponse().getContentAsString().contains("DOWNTIME_EXISTS"));

        register("eq-dt2c", 10000);
        addReading("eq-dt2c", "d2c-r1", 1, "r1", "2026-01-01T10:00:00Z", 100, 201);
        addReading("eq-dt2c", "d2c-r2", 2, "r2", "2026-01-01T11:00:00Z", 200, 201);
        MvcResult dupOther = registerDowntime("eq-dt2c", "d2c-dup", 3, "dt-a",
                "2026-01-01T10:00:00Z", "2026-01-01T10:30:00Z", "跨设备重复");
        org.junit.jupiter.api.Assertions.assertEquals(409, dupOther.getResponse().getStatus());

        // 版本冲突 → 409，失败不占版本
        MvcResult verConflict = registerDowntime("eq-dt2", "d2-ver", 99, "dt-v",
                "2026-01-01T10:00:00Z", "2026-01-01T10:30:00Z", "版本");
        org.junit.jupiter.api.Assertions.assertEquals(409, verConflict.getResponse().getStatus());
        org.junit.jupiter.api.Assertions.assertTrue(
                verConflict.getResponse().getContentAsString().contains("VERSION_CONFLICT"));
        mockMvc.perform(get("/api/equipment/eq-dt2/status"))
                .andExpect(jsonPath("$.version").value(6));

        // 无读数设备不可登记停机
        register("eq-dt2b", 10000);
        MvcResult noReading = registerDowntime("eq-dt2b", "d2b-1", 1, "dt-nr",
                "2026-01-01T10:00:00Z", "2026-01-01T11:00:00Z", "无读数");
        org.junit.jupiter.api.Assertions.assertEquals(422, noReading.getResponse().getStatus());

        // 设备不存在 → 404
        MvcResult notFound = registerDowntime("nope", "d2-nf", 1, "dt-nf",
                "2026-01-01T10:00:00Z", "2026-01-01T11:00:00Z", "不存在");
        org.junit.jupiter.api.Assertions.assertEquals(404, notFound.getResponse().getStatus());
        mockMvc.perform(get("/api/equipment/nope/downtimes"))
                .andExpect(status().isNotFound());
    }

    // ---------- 不得跨越保养锚点 ----------

    @Test
    void registerDowntime_mustNotCrossMaintenanceAnchor() throws Exception {
        register("eq-dt3", 10000);
        addReading("eq-dt3", "d3-r1", 1, "r1", "2026-01-01T10:00:00Z", 100, 201);
        addReading("eq-dt3", "d3-r2", 2, "r2", "2026-01-01T11:00:00Z", 200, 201);
        addReading("eq-dt3", "d3-r3", 3, "r3", "2026-01-01T12:00:00Z", 300, 201);
        mockMvc.perform(post("/api/equipment/eq-dt3/maintenances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"d3-m1","expectedVersion":4,"readingId":"r2","anchorRevisionNo":1}
                                """))
                .andExpect(status().isCreated());

        // 跨越锚点 11:00 → 422
        MvcResult cross = registerDowntime("eq-dt3", "d3-x", 5, "dt-x",
                "2026-01-01T10:30:00Z", "2026-01-01T11:30:00Z", "跨越锚点");
        org.junit.jupiter.api.Assertions.assertEquals(422, cross.getResponse().getStatus());
        org.junit.jupiter.api.Assertions.assertTrue(
                cross.getResponse().getContentAsString().contains("DOWNTIME_CROSSES_ANCHOR"));

        // 起点恰为锚点 / 终点恰为锚点：不跨越，合法
        mockMvc.perform(post("/api/equipment/eq-dt3/downtimes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"d3-ok1","expectedVersion":5,"downtimeKey":"dt-ok1",
                                 "startAt":"2026-01-01T11:00:00Z","endAt":"2026-01-01T12:00:00Z",
                                 "reason":"锚点之后"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/equipment/eq-dt3/downtimes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"d3-ok2","expectedVersion":6,"downtimeKey":"dt-ok2",
                                 "startAt":"2026-01-01T10:00:00Z","endAt":"2026-01-01T11:00:00Z",
                                 "reason":"锚点之前"}
                                """))
                .andExpect(status().isCreated());
    }

    // ---------- 读数新增/修订触发扣减重算 ----------

    @Test
    void readingChanges_recomputeDeductions() throws Exception {
        register("eq-dt4", 10000);
        addReading("eq-dt4", "d4-r1", 1, "r1", "2026-01-01T10:00:00Z", 100, 201);
        addReading("eq-dt4", "d4-r2", 2, "r2", "2026-01-01T11:00:00Z", 200, 201);
        addReading("eq-dt4", "d4-r3", 3, "r3", "2026-01-01T12:00:00Z", 300, 201);
        mockMvc.perform(post("/api/equipment/eq-dt4/downtimes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"d4-dt","expectedVersion":4,"downtimeKey":"dt-1",
                                 "startAt":"2026-01-01T10:30:00Z","endAt":"2026-01-01T11:30:00Z",
                                 "reason":"待重算"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.deductionMinutes").value(100));

        // 补录 10:20=120：开始侧最近读数变为 120，扣减 200-120=80
        addReading("eq-dt4", "d4-r4", 5, "r4", "2026-01-01T10:20:00Z", 120, 201);
        mockMvc.perform(get("/api/equipment/eq-dt4/downtimes"))
                .andExpect(jsonPath("$[0].deductionMinutes").value(80));
        mockMvc.perform(get("/api/equipment/eq-dt4/status"))
                .andExpect(jsonPath("$.downtimeDeductionMinutes").value(80))
                .andExpect(jsonPath("$.runMinutes").value(220));

        // 修订结束侧边界读数 r2 → 250：扣减 250-120=130
        mockMvc.perform(post("/api/equipment/eq-dt4/readings/r2/revisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"d4-rev","expectedVersion":6,"cumulativeMinutes":250}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(get("/api/equipment/eq-dt4/downtimes"))
                .andExpect(jsonPath("$[0].deductionMinutes").value(130));
        mockMvc.perform(get("/api/equipment/eq-dt4/status"))
                .andExpect(jsonPath("$.downtimeDeductionMinutes").value(130))
                .andExpect(jsonPath("$.runMinutes").value(170));
    }

    // ---------- 边界读数可修订，锚点读数仍受保护 ----------

    @Test
    void revise_boundaryReadingAllowed_anchorStillProtected() throws Exception {
        register("eq-dt5", 10000);
        addReading("eq-dt5", "d5-r1", 1, "r1", "2026-01-01T10:00:00Z", 100, 201);
        addReading("eq-dt5", "d5-r2", 2, "r2", "2026-01-01T11:00:00Z", 200, 201);
        addReading("eq-dt5", "d5-r3", 3, "r3", "2026-01-01T12:00:00Z", 300, 201);
        mockMvc.perform(post("/api/equipment/eq-dt5/downtimes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"d5-dt","expectedVersion":4,"downtimeKey":"dt-1",
                                 "startAt":"2026-01-01T11:00:00Z","endAt":"2026-01-01T12:00:00Z",
                                 "reason":"边界即读数"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.deductionMinutes").value(100));

        // 被停机区间用作边界的读数 r2 仍可修订，且须满足相邻约束
        mockMvc.perform(post("/api/equipment/eq-dt5/readings/r2/revisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"d5-rev","expectedVersion":5,"cumulativeMinutes":220}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.revisionNo").value(2));
        mockMvc.perform(get("/api/equipment/eq-dt5/downtimes"))
                .andExpect(jsonPath("$[0].deductionMinutes").value(80));

        // 完成保养锚定 r3 后，锚点读数不可修订
        mockMvc.perform(post("/api/equipment/eq-dt5/maintenances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"d5-m1","expectedVersion":6,"readingId":"r3","anchorRevisionNo":1}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/equipment/eq-dt5/readings/r3/revisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"d5-rev2","expectedVersion":7,"cumulativeMinutes":310}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("READING_ANCHORED"));
    }

    // ---------- 撤销：扣减退出计算、记录保留不可改写 ----------

    @Test
    void revoke_excludesDeductionAndKeepsRecord() throws Exception {
        register("eq-dt6", 120);
        addReading("eq-dt6", "d6-r1", 1, "r1", "2026-01-01T10:00:00Z", 100, 201);
        addReading("eq-dt6", "d6-r2", 2, "r2", "2026-01-01T11:00:00Z", 200, 201);
        addReading("eq-dt6", "d6-r3", 3, "r3", "2026-01-01T12:00:00Z", 400, 201);
        mockMvc.perform(post("/api/equipment/eq-dt6/downtimes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"d6-dt1","expectedVersion":4,"downtimeKey":"dt-1",
                                 "startAt":"2026-01-01T10:00:00Z","endAt":"2026-01-01T11:00:00Z",
                                 "reason":"第一段"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.deductionMinutes").value(100));
        mockMvc.perform(post("/api/equipment/eq-dt6/downtimes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"d6-dt2","expectedVersion":5,"downtimeKey":"dt-2",
                                 "startAt":"2026-01-01T11:00:00Z","endAt":"2026-01-01T12:00:00Z",
                                 "reason":"第二段"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.deductionMinutes").value(200));
        mockMvc.perform(get("/api/equipment/eq-dt6/status"))
                .andExpect(jsonPath("$.downtimeDeductionMinutes").value(300))
                .andExpect(jsonPath("$.runMinutes").value(100))
                .andExpect(jsonPath("$.status").value("OK"));

        // 撤销 dt-1：扣减 100 退出计算，运行分钟回到 200 → DUE
        mockMvc.perform(post("/api/equipment/eq-dt6/downtimes/dt-1/revoke")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"d6-rv1","expectedVersion":6}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"))
                .andExpect(jsonPath("$.revokedAt").value(notNullValue()))
                .andExpect(jsonPath("$.equipmentVersion").value(7));
        mockMvc.perform(get("/api/equipment/eq-dt6/status"))
                .andExpect(jsonPath("$.downtimeDeductionMinutes").value(200))
                .andExpect(jsonPath("$.runMinutes").value(200))
                .andExpect(jsonPath("$.status").value("DUE"));

        // 原区间记录保留（含冻结的扣减量），不可重复撤销
        mockMvc.perform(get("/api/equipment/eq-dt6/downtimes"))
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].downtimeKey").value("dt-1"))
                .andExpect(jsonPath("$[0].status").value("REVOKED"))
                .andExpect(jsonPath("$[0].deductionMinutes").value(100))
                .andExpect(jsonPath("$[0].revokedAt").value(notNullValue()));
        mockMvc.perform(post("/api/equipment/eq-dt6/downtimes/dt-1/revoke")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"d6-rv2","expectedVersion":7}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DOWNTIME_ALREADY_REVOKED"));

        // 撤销不存在的区间 → 404；版本冲突 → 409
        mockMvc.perform(post("/api/equipment/eq-dt6/downtimes/dt-nope/revoke")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"d6-rv3","expectedVersion":7}
                                """))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/equipment/eq-dt6/downtimes/dt-2/revoke")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"d6-rv4","expectedVersion":99}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));

        // 已撤销区间不再阻挡新登记：原范围可重新登记生效区间
        mockMvc.perform(post("/api/equipment/eq-dt6/downtimes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"d6-dt3","expectedVersion":7,"downtimeKey":"dt-3",
                                 "startAt":"2026-01-01T10:00:00Z","endAt":"2026-01-01T11:00:00Z",
                                 "reason":"撤销后重登记"}
                                """))
                .andExpect(status().isCreated());
    }

    // ---------- 完成保养：固化运行分钟与扣减合计 ----------

    @Test
    void maintenance_freezesRunMinutesAndDeduction() throws Exception {
        register("eq-dt7", 120);
        addReading("eq-dt7", "d7-r1", 1, "r1", "2026-01-01T10:00:00Z", 100, 201);
        addReading("eq-dt7", "d7-r2", 2, "r2", "2026-01-01T11:00:00Z", 200, 201);
        addReading("eq-dt7", "d7-r3", 3, "r3", "2026-01-01T12:00:00Z", 350, 201);
        mockMvc.perform(post("/api/equipment/eq-dt7/downtimes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"d7-dt","expectedVersion":4,"downtimeKey":"dt-1",
                                 "startAt":"2026-01-01T11:00:00Z","endAt":"2026-01-01T12:00:00Z",
                                 "reason":"午后停机"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.deductionMinutes").value(150));

        // 锚定 r1(10:00, 100)：锚点之后生效区间扣减 150，运行分钟 350-100-150=100，固化
        mockMvc.perform(post("/api/equipment/eq-dt7/maintenances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"d7-m1","expectedVersion":5,"readingId":"r1","anchorRevisionNo":1}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.runMinutes").value(100))
                .andExpect(jsonPath("$.downtimeDeductionMinutes").value(150))
                .andExpect(jsonPath("$.equipmentVersion").value(6));

        // 保养后状态按新锚点重算，与固化值一致
        mockMvc.perform(get("/api/equipment/eq-dt7/status"))
                .andExpect(jsonPath("$.lastMaintenanceAnchorCumulativeMinutes").value(100))
                .andExpect(jsonPath("$.downtimeDeductionMinutes").value(150))
                .andExpect(jsonPath("$.runMinutes").value(100))
                .andExpect(jsonPath("$.status").value("OK"));

        // 新读数推进状态，但保养记录固化值不变
        addReading("eq-dt7", "d7-r4", 6, "r4", "2026-01-01T13:00:00Z", 500, 201);
        mockMvc.perform(get("/api/equipment/eq-dt7/status"))
                .andExpect(jsonPath("$.runMinutes").value(250))
                .andExpect(jsonPath("$.status").value("DUE"));
        mockMvc.perform(get("/api/equipment/eq-dt7/maintenances"))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].runMinutes").value(100))
                .andExpect(jsonPath("$[0].downtimeDeductionMinutes").value(150));
    }

    // ---------- 幂等：重放、异参 409、失败不占键 ----------

    @Test
    void downtime_idempotency() throws Exception {
        register("eq-dt8", 10000);
        addReading("eq-dt8", "d8-r1", 1, "r1", "2026-01-01T10:00:00Z", 100, 201);
        addReading("eq-dt8", "d8-r2", 2, "r2", "2026-01-01T11:00:00Z", 200, 201);
        addReading("eq-dt8", "d8-r3", 3, "r3", "2026-01-01T12:00:00Z", 300, 201);

        String body = """
                {"requestId":"d8-idem1","expectedVersion":4,"downtimeKey":"dt-i1",
                 "startAt":"2026-01-01T10:00:00Z","endAt":"2026-01-01T11:00:00Z",
                 "reason":"幂等"}
                """;
        MvcResult first = postJson("/api/equipment/eq-dt8/downtimes", body);
        org.junit.jupiter.api.Assertions.assertEquals(201, first.getResponse().getStatus());

        // 同键同参重放：响应一致，业务效果不重复
        MvcResult replay = postJson("/api/equipment/eq-dt8/downtimes", body);
        org.junit.jupiter.api.Assertions.assertEquals(201, replay.getResponse().getStatus());
        org.junit.jupiter.api.Assertions.assertEquals(
                first.getResponse().getContentAsString(),
                replay.getResponse().getContentAsString());
        mockMvc.perform(get("/api/equipment/eq-dt8/downtimes"))
                .andExpect(jsonPath("$", hasSize(1)));
        mockMvc.perform(get("/api/equipment/eq-dt8/status"))
                .andExpect(jsonPath("$.version").value(5));

        // 同键异参 → 409
        mockMvc.perform(post("/api/equipment/eq-dt8/downtimes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"d8-idem1","expectedVersion":4,"downtimeKey":"dt-i1",
                                 "startAt":"2026-01-01T10:00:00Z","endAt":"2026-01-01T11:00:00Z",
                                 "reason":"异参"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));

        // 失败不占键：422 后同 requestId 修正参数可成功
        MvcResult overlap = registerDowntime("eq-dt8", "d8-idem2", 5, "dt-i2",
                "2026-01-01T10:30:00Z", "2026-01-01T11:30:00Z", "重叠失败");
        org.junit.jupiter.api.Assertions.assertEquals(422, overlap.getResponse().getStatus());
        mockMvc.perform(post("/api/equipment/eq-dt8/downtimes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"d8-idem2","expectedVersion":5,"downtimeKey":"dt-i2",
                                 "startAt":"2026-01-01T11:00:00Z","endAt":"2026-01-01T12:00:00Z",
                                 "reason":"修正后成功"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(get("/api/equipment/eq-dt8/downtimes"))
                .andExpect(jsonPath("$", hasSize(2)));
    }

    // ---------- 运行分钟为负按 0 计 ----------

    @Test
    void runMinutes_flooredAtZero() throws Exception {
        register("eq-dt9", 100);
        addReading("eq-dt9", "d9-r1", 1, "r1", "2026-01-01T10:00:00Z", 100, 201);
        addReading("eq-dt9", "d9-r2", 2, "r2", "2026-01-01T11:00:00Z", 200, 201);
        mockMvc.perform(post("/api/equipment/eq-dt9/downtimes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"d9-dt","expectedVersion":3,"downtimeKey":"dt-1",
                                 "startAt":"2026-01-01T10:00:00Z","endAt":"2026-01-01T11:00:00Z",
                                 "reason":"全程停机"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.deductionMinutes").value(100));
        mockMvc.perform(get("/api/equipment/eq-dt9/status"))
                .andExpect(jsonPath("$.runMinutes").value(100))
                .andExpect(jsonPath("$.status").value("DUE"));

        // 修订开始侧读数至 0：扣减 200，运行分钟 200-200=0（不为负）
        mockMvc.perform(post("/api/equipment/eq-dt9/readings/r1/revisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"d9-rev","expectedVersion":4,"cumulativeMinutes":0}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(get("/api/equipment/eq-dt9/status"))
                .andExpect(jsonPath("$.downtimeDeductionMinutes").value(200))
                .andExpect(jsonPath("$.runMinutes").value(0))
                .andExpect(jsonPath("$.status").value("OK"));
    }
}
