package com.example.starter.maintenance;

import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * 大幅读数修订见证流程测试（真实 H2 内存库，MODE=MySQL）：
 * 主流程、阈值边界、422 失败分支、待确认期间的保养/周期隔离与幂等重放。
 */
@SpringBootTest
@AutoConfigureMockMvc
class EquipmentWitnessTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM idempotency_request");
        jdbc.update("DELETE FROM reading_witness");
        jdbc.update("DELETE FROM maintenance");
        jdbc.update("DELETE FROM reading_revision");
        jdbc.update("DELETE FROM reading");
        jdbc.update("DELETE FROM equipment");
    }

    private MvcResult postJson(String url, String body) throws Exception {
        return mockMvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn();
    }

    private void register(String equipmentId, long periodMinutes, long thresholdMinutes) throws Exception {
        mockMvc.perform(post("/api/equipment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"reg-%s","equipmentId":"%s","maintenancePeriodMinutes":%d,
                                 "witnessThresholdMinutes":%d}
                                """.formatted(equipmentId, equipmentId, periodMinutes, thresholdMinutes)))
                .andExpect(status().isCreated());
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

    private String revise(String equipmentId, String readingId, String requestId, long expectedVersion,
                          long cumulativeMinutes, String submittedBy) throws Exception {
        return """
                {"requestId":"%s","expectedVersion":%d,"cumulativeMinutes":%d,"submittedBy":"%s"}
                """.formatted(requestId, expectedVersion, cumulativeMinutes, submittedBy);
    }

    private String witness(String requestId, long expectedVersion, int expectedRevisionNo,
                           long cumulativeMinutes, String witnessedBy) {
        return """
                {"requestId":"%s","expectedVersion":%d,"expectedRevisionNo":%d,
                 "cumulativeMinutes":%d,"witnessedBy":"%s"}
                """.formatted(requestId, expectedVersion, expectedRevisionNo,
                cumulativeMinutes, witnessedBy);
    }

    // ---------- 题干主场景：设备 D，100h 有效、阈值 10h、申请 112h ----------

    @Test
    void equipmentD_largeRevisionPendingUntilWitnessed() throws Exception {
        // 保养周期 6500 分钟（108h20m），见证阈值 600 分钟（10 小时）
        register("eq-D", 6500, 600);
        addReading("eq-D", "d-add", 1, "r1", "2026-03-01T08:00:00Z", 6000);

        // 申请改为 112h（6720）：差额 720 > 600，进入待确认
        mockMvc.perform(post("/api/equipment/eq-D/readings/r1/revisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(revise("eq-D", "r1", "d-rev", 2, 6720, "alice")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.cumulativeMinutes").value(6000))
                .andExpect(jsonPath("$.revisionNo").value(1))
                .andExpect(jsonPath("$.pendingCumulativeMinutes").value(6720))
                .andExpect(jsonPath("$.pendingRevisionNo").value(2))
                .andExpect(jsonPath("$.differenceMinutes").value(720))
                .andExpect(jsonPath("$.thresholdMinutes").value(600))
                .andExpect(jsonPath("$.submittedBy").value("alice"))
                .andExpect(jsonPath("$.witnessedBy").value(org.hamcrest.Matchers.nullValue()));

        // 见证前：有效读数仍是 100h，保养周期按 6000 计算，不触发 DUE
        mockMvc.perform(get("/api/equipment/eq-D/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.witnessThresholdMinutes").value(600))
                .andExpect(jsonPath("$.latestCumulativeMinutes").value(6000))
                .andExpect(jsonPath("$.runMinutes").value(6000))
                .andExpect(jsonPath("$.status").value("OK"));

        // 待确认读数不得触发保养放行
        mockMvc.perform(post("/api/equipment/eq-D/maintenances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"d-mnt-blocked","expectedVersion":3,
                                 "readingId":"r1","anchorRevisionNo":1}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("READING_PENDING_WITNESS"));

        // 查询同时展示有效读数、待确认值、差额、阈值（见证人见证前为空）
        mockMvc.perform(get("/api/equipment/eq-D/readings"))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].cumulativeMinutes").value(6000))
                .andExpect(jsonPath("$[0].pendingCumulativeMinutes").value(6720))
                .andExpect(jsonPath("$[0].differenceMinutes").value(720))
                .andExpect(jsonPath("$[0].thresholdMinutes").value(600))
                .andExpect(jsonPath("$[0].submittedBy").value("alice"))
                .andExpect(jsonPath("$[0].witnessedBy").value(org.hamcrest.Matchers.nullValue()));
        mockMvc.perform(get("/api/equipment/eq-D/witnesses"))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].readingId").value("r1"))
                .andExpect(jsonPath("$[0].pendingRevisionNo").value(2))
                .andExpect(jsonPath("$[0].baseCumulativeMinutes").value(6000));
        mockMvc.perform(get("/api/equipment/eq-D/readings/r1/witness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pendingCumulativeMinutes").value(6720));

        // 待确认期间有效读数不被覆盖
        Integer effective = jdbc.queryForObject(
                "SELECT cumulative_minutes FROM reading WHERE equipment_id = 'eq-D' AND reading_id = 'r1'",
                Integer.class);
        assertEquals(6000, effective);
        Integer revisionCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM reading_revision WHERE equipment_id = 'eq-D' AND reading_id = 'r1'",
                Integer.class);
        assertEquals(1, revisionCount, "见证前不得追加修订历史");

        // 另一名维护人 bob 见证同一读数版本（待生效修订号 2、值 6720）
        mockMvc.perform(post("/api/equipment/eq-D/readings/r1/witness")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(witness("d-wit", 3, 2, 6720, "bob")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.cumulativeMinutes").value(6720))
                .andExpect(jsonPath("$.revisionNo").value(2))
                .andExpect(jsonPath("$.pendingCumulativeMinutes").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.equipmentVersion").value(4));

        // 见证后：读数生效、周期按 112h 计算并达到 DUE
        mockMvc.perform(get("/api/equipment/eq-D/status"))
                .andExpect(jsonPath("$.latestCumulativeMinutes").value(6720))
                .andExpect(jsonPath("$.runMinutes").value(6720))
                .andExpect(jsonPath("$.status").value("DUE"));
        mockMvc.perform(get("/api/equipment/eq-D/readings"))
                .andExpect(jsonPath("$[0].cumulativeMinutes").value(6720))
                .andExpect(jsonPath("$[0].pendingCumulativeMinutes").value(org.hamcrest.Matchers.nullValue()));
        mockMvc.perform(get("/api/equipment/eq-D/witnesses"))
                .andExpect(jsonPath("$", hasSize(0)));

        // 历史周期按当时生效读数解释：修订历史保留阈值/差额/提交人/见证人快照
        mockMvc.perform(get("/api/equipment/eq-D/readings/r1/revisions"))
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].revisionNo").value(1))
                .andExpect(jsonPath("$[0].cumulativeMinutes").value(6000))
                .andExpect(jsonPath("$[1].revisionNo").value(2))
                .andExpect(jsonPath("$[1].cumulativeMinutes").value(6720))
                .andExpect(jsonPath("$[1].thresholdMinutes").value(600))
                .andExpect(jsonPath("$[1].differenceMinutes").value(720))
                .andExpect(jsonPath("$[1].submittedBy").value("alice"))
                .andExpect(jsonPath("$[1].witnessedBy").value("bob"));

        // 见证后可正常保养放行（锚点为生效修订号 2）
        mockMvc.perform(post("/api/equipment/eq-D/maintenances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"d-mnt-ok","expectedVersion":4,
                                 "readingId":"r1","anchorRevisionNo":2}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.anchorCumulativeMinutes").value(6720));
    }

    // ---------- 阈值边界：差额等于阈值即时生效，严格大于才待确认 ----------

    @Test
    void thresholdBoundary_equalDifferenceTakesEffectImmediately() throws Exception {
        register("eq-b", 100000, 100);
        addReading("eq-b", "b-add", 1, "r1", "2026-03-01T08:00:00Z", 1000);

        // 差额恰好 100 = 阈值：即时生效
        mockMvc.perform(post("/api/equipment/eq-b/readings/r1/revisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(revise("eq-b", "r1", "b-rev-eq", 2, 1100, "alice")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.revisionNo").value(2))
                .andExpect(jsonPath("$.cumulativeMinutes").value(1100))
                .andExpect(jsonPath("$.pendingCumulativeMinutes").value(org.hamcrest.Matchers.nullValue()));
        mockMvc.perform(get("/api/equipment/eq-b/witnesses"))
                .andExpect(jsonPath("$", hasSize(0)));

        // 差额 101 > 100：待确认
        mockMvc.perform(post("/api/equipment/eq-b/readings/r1/revisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(revise("eq-b", "r1", "b-rev-over", 3, 1201, "alice")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.cumulativeMinutes").value(1100))
                .andExpect(jsonPath("$.pendingCumulativeMinutes").value(1201))
                .andExpect(jsonPath("$.differenceMinutes").value(101));
    }

    // ---------- 422 失败分支 ----------

    @Test
    void failureBranches_return422() throws Exception {
        register("eq-f", 100000, 100);
        addReading("eq-f", "f-add-1", 1, "r1", "2026-03-01T08:00:00Z", 1000);
        addReading("eq-f", "f-add-2", 2, "r2", "2026-03-01T09:00:00Z", 1050);

        // 读数倒退（修订 r2 低于前邻 r1 的 1000 边界：950 < 1000）→ 422，即使是大幅修订也先判单调性
        mockMvc.perform(post("/api/equipment/eq-f/readings/r2/revisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(revise("eq-f", "r2", "f-back", 3, 950, "alice")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("READING_ORDER_VIOLATION"));

        // 大幅修订待确认
        mockMvc.perform(post("/api/equipment/eq-f/readings/r2/revisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(revise("eq-f", "r2", "f-big", 3, 1200, "alice")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.pendingRevisionNo").value(2));

        // 待确认期间再次修订（无论大小）→ 422（待确认提交后版本已前进到 4）
        mockMvc.perform(post("/api/equipment/eq-f/readings/r2/revisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(revise("eq-f", "r2", "f-big2", 4, 1055, "alice")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("READING_PENDING_WITNESS"));

        // 同人见证 → 422
        mockMvc.perform(post("/api/equipment/eq-f/readings/r2/witness")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(witness("f-wit-self", 4, 2, 1200, "alice")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("WITNESS_SAME_MAINTAINER"));

        // 见证版本号不一致（旧见证）→ 422
        mockMvc.perform(post("/api/equipment/eq-f/readings/r2/witness")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(witness("f-wit-oldrev", 4, 9, 1200, "bob")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("WITNESS_VERSION_CHANGED"));

        // 见证读数不一致 → 422
        mockMvc.perform(post("/api/equipment/eq-f/readings/r2/witness")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(witness("f-wit-badval", 4, 2, 1201, "bob")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("WITNESS_VALUE_MISMATCH"));

        // 失败均不改变有效读数与设备版本（待确认提交已使版本前进到 4，其后失败回滚）
        mockMvc.perform(get("/api/equipment/eq-f/status"))
                .andExpect(jsonPath("$.version").value(4))
                .andExpect(jsonPath("$.latestCumulativeMinutes").value(1050));
        mockMvc.perform(get("/api/equipment/eq-f/readings/r2/revisions"))
                .andExpect(jsonPath("$", hasSize(1)));

        // bob 正确见证后生效（版本前进到 5）
        mockMvc.perform(post("/api/equipment/eq-f/readings/r2/witness")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(witness("f-wit-ok", 4, 2, 1200, "bob")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.cumulativeMinutes").value(1200));

        // 见证后重复见证 → 404（已无待确认申请）
        mockMvc.perform(post("/api/equipment/eq-f/readings/r2/witness")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(witness("f-wit-again", 5, 2, 1200, "carol")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("WITNESS_NOT_FOUND"));
    }

    // ---------- 幂等边界 ----------

    @Test
    void idempotency_reviseReplayAndMismatch() throws Exception {
        register("eq-i", 100000, 100);
        addReading("eq-i", "i-add", 1, "r1", "2026-03-01T08:00:00Z", 1000);

        String body = revise("eq-i", "r1", "i-rev", 2, 1200, "alice");
        MvcResult first = postJson("/api/equipment/eq-i/readings/r1/revisions", body);
        assertEquals(201, first.getResponse().getStatus());

        // 同 requestId 同参重放：待确认期间重放首次的待确认结果
        MvcResult replay = postJson("/api/equipment/eq-i/readings/r1/revisions", body);
        assertEquals(201, replay.getResponse().getStatus());
        assertEquals(first.getResponse().getContentAsString(),
                replay.getResponse().getContentAsString(), "同键同参应原样重放待确认响应");
        Integer witnessRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM reading_witness WHERE equipment_id = 'eq-i'", Integer.class);
        assertEquals(1, witnessRows, "重放不得重复创建待确认申请");

        // 见证生效后再重放修订请求：按当前状态重放为生效视图（读数 1200、修订号 2、无待确认值）
        mockMvc.perform(post("/api/equipment/eq-i/readings/r1/witness")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(witness("i-wit", 3, 2, 1200, "bob")))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/equipment/eq-i/readings/r1/revisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.cumulativeMinutes").value(1200))
                .andExpect(jsonPath("$.revisionNo").value(2))
                .andExpect(jsonPath("$.pendingCumulativeMinutes").value(org.hamcrest.Matchers.nullValue()));

        // 同 requestId 换读数（异参）→ 409
        mockMvc.perform(post("/api/equipment/eq-i/readings/r1/revisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(revise("eq-i", "r1", "i-rev", 2, 1201, "alice")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));
    }

    @Test
    void idempotency_witnessReplay() throws Exception {
        register("eq-iw", 100000, 100);
        addReading("eq-iw", "iw-add", 1, "r1", "2026-03-01T08:00:00Z", 1000);
        mockMvc.perform(post("/api/equipment/eq-iw/readings/r1/revisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(revise("eq-iw", "r1", "iw-rev", 2, 1200, "alice")))
                .andExpect(status().isCreated());

        String body = witness("iw-wit", 3, 2, 1200, "bob");
        MvcResult first = postJson("/api/equipment/eq-iw/readings/r1/witness", body);
        assertEquals(201, first.getResponse().getStatus());
        // 见证生效后同键同参重放原生效响应，不重复追加修订
        MvcResult replay = postJson("/api/equipment/eq-iw/readings/r1/witness", body);
        assertEquals(201, replay.getResponse().getStatus());
        assertEquals(first.getResponse().getContentAsString(),
                replay.getResponse().getContentAsString());
        mockMvc.perform(get("/api/equipment/eq-iw/readings/r1/revisions"))
                .andExpect(jsonPath("$", hasSize(2)));

        // 同见证 requestId 换见证人 → 409
        mockMvc.perform(post("/api/equipment/eq-iw/readings/r1/witness")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(witness("iw-wit", 4, 3, 1300, "carol")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));
    }

    // ---------- 旧见证不得在新版本上生效（构造版本漂移） ----------

    @Test
    void staleWitness_afterNewPending_rejected() throws Exception {
        register("eq-s", 100000, 100);
        addReading("eq-s", "s-add", 1, "r1", "2026-03-01T08:00:00Z", 1000);

        // 第一次大幅修订 1200（待生效修订号 2），由 bob 见证生效
        mockMvc.perform(post("/api/equipment/eq-s/readings/r1/revisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(revise("eq-s", "r1", "s-rev-1", 2, 1200, "alice")))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/equipment/eq-s/readings/r1/witness")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(witness("s-wit-1", 3, 2, 1200, "bob")))
                .andExpect(status().isCreated());

        // 第二次大幅修订 1350（待生效修订号 3）
        mockMvc.perform(post("/api/equipment/eq-s/readings/r1/revisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(revise("eq-s", "r1", "s-rev-2", 4, 1350, "alice")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.pendingRevisionNo").value(3));

        // 旧见证（指定版本 2、旧值 1200）不得批准新读数
        mockMvc.perform(post("/api/equipment/eq-s/readings/r1/witness")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(witness("s-wit-stale", 5, 2, 1200, "carol")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("WITNESS_VERSION_CHANGED"));
        mockMvc.perform(get("/api/equipment/eq-s/status"))
                .andExpect(jsonPath("$.latestCumulativeMinutes").value(1200));

        // 新见证（版本 3、值 1350）正常生效
        mockMvc.perform(post("/api/equipment/eq-s/readings/r1/witness")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(witness("s-wit-2", 5, 3, 1350, "carol")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.cumulativeMinutes").value(1350));
    }

    // ---------- 阈值保存在申请中：设备阈值语义以申请快照为准 ----------

    @Test
    void thresholdSnapshot_persistedWithApplication() throws Exception {
        register("eq-t", 100000, 100);
        addReading("eq-t", "t-add", 1, "r1", "2026-03-01T08:00:00Z", 1000);
        mockMvc.perform(post("/api/equipment/eq-t/readings/r1/revisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(revise("eq-t", "r1", "t-rev", 2, 1150, "alice")))
                        .andExpect(status().isCreated());

        Long snapshot = jdbc.queryForObject(
                "SELECT threshold_minutes FROM reading_witness WHERE request_id = 't-rev'",
                Long.class);
        assertEquals(100L, snapshot, "申请须保存提交时设备当前阈值");
        Long base = jdbc.queryForObject(
                "SELECT base_cumulative_minutes FROM reading_witness WHERE request_id = 't-rev'",
                Long.class);
        assertEquals(1000L, base, "申请须保存提交时有效基准读数");
    }
}
