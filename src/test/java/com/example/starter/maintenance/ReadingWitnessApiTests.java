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
 * 大幅读数修订见证流程测试（真实 H2 内存库，MODE=MySQL）：
 * 题干设备 D（有效读数 100 小时、阈值 10 小时、申请改为 112 小时）主流程、
 * 失败分支（倒退/同人见证/版本变化）、待确认期间周期不被触发或重置、幂等与查询展示。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ReadingWitnessApiTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM idempotency_request");
        jdbc.update("DELETE FROM maintenance");
        jdbc.update("DELETE FROM reading_revision_request");
        jdbc.update("DELETE FROM reading_revision");
        jdbc.update("DELETE FROM reading");
        jdbc.update("DELETE FROM equipment");
    }

    private MvcResult postJson(String url, String body) throws Exception {
        return mockMvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn();
    }

    /** 登记设备 D：周期 6500 分钟（介于 100h=6000 与 112h=6720 之间），阈值 10 小时=600 分钟。 */
    private void registerDeviceD() throws Exception {
        mockMvc.perform(post("/api/equipment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"reg-d","equipmentId":"eq-d","maintenancePeriodMinutes":6500,
                                 "revisionThresholdMinutes":600}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.revisionThresholdMinutes").value(600));
    }

    private void addReading100h() throws Exception {
        mockMvc.perform(post("/api/equipment/eq-d/readings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"add-d1","expectedVersion":1,"readingId":"r1",
                                 "sampledAt":"2026-01-01T00:00:00Z","cumulativeMinutes":6000}
                                """))
                .andExpect(status().isCreated());
    }

    // ---------- 题干主流程：100h → 申请 112h 待见证 → 见证生效 ----------

    @Test
    void witnessFlow_deviceD_pendingDoesNotChangeCycleThenEffective() throws Exception {
        registerDeviceD();
        addReading100h();

        // 维护人 alice 提交 100h → 112h（差额 720 分钟 > 阈值 600），先待确认
        mockMvc.perform(post("/api/equipment/eq-d/readings/r1/revisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"rev-big","expectedVersion":2,"cumulativeMinutes":6720,
                                 "submitterId":"alice"}
                                """))
                .andExpect(status().isCreated())
                // 有效读数不被覆盖：仍为 6000、修订号 1
                .andExpect(jsonPath("$.cumulativeMinutes").value(6000))
                .andExpect(jsonPath("$.revisionNo").value(1))
                // 同时展示待确认值、差额、阈值、提交人
                .andExpect(jsonPath("$.pendingRequestId").value("rev-big"))
                .andExpect(jsonPath("$.pendingCumulativeMinutes").value(6720))
                .andExpect(jsonPath("$.pendingDeltaMinutes").value(720))
                .andExpect(jsonPath("$.pendingThresholdMinutes").value(600))
                .andExpect(jsonPath("$.pendingSubmitterId").value("alice"))
                .andExpect(jsonPath("$.equipmentVersion").value(3));

        // 见证前保养周期仍按 100 小时（6000）计算：runMinutes=6000 < 6500 → OK，未被待确认值放行
        mockMvc.perform(get("/api/equipment/eq-d/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.revisionThresholdMinutes").value(600))
                .andExpect(jsonPath("$.latestCumulativeMinutes").value(6000))
                .andExpect(jsonPath("$.runMinutes").value(6000))
                .andExpect(jsonPath("$.status").value("OK"));

        // 读数列表同样展示有效值与待确认值
        mockMvc.perform(get("/api/equipment/eq-d/readings"))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].cumulativeMinutes").value(6000))
                .andExpect(jsonPath("$[0].pendingCumulativeMinutes").value(6720))
                .andExpect(jsonPath("$[0].pendingDeltaMinutes").value(720));

        // 修订历史在见证前只有初始登记行；申请处于 PENDING，基准版本 1、生效后将占修订号 2
        mockMvc.perform(get("/api/equipment/eq-d/readings/r1/revisions"))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].revisionNo").value(1));
        mockMvc.perform(get("/api/equipment/eq-d/readings/r1/revision-requests"))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].status").value("PENDING"))
                .andExpect(jsonPath("$[0].baseRevisionNo").value(1))
                .andExpect(jsonPath("$[0].proposedRevisionNo").value(2))
                .andExpect(jsonPath("$[0].thresholdMinutes").value(600))
                .andExpect(jsonPath("$[0].submitterId").value("alice"))
                .andExpect(jsonPath("$[0].witnessId").value(nullValue()));

        // 待确认修订号（2）尚不是有效版本，不能作为保养锚点放行
        mockMvc.perform(post("/api/equipment/eq-d/maintenances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"mnt-stale","expectedVersion":3,"readingId":"r1",
                                 "anchorRevisionNo":2}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ANCHOR_REVISION_CONFLICT"));

        // 失败分支：同人见证 → 422，且不改变状态
        mockMvc.perform(post("/api/equipment/eq-d/readings/r1/witness")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"wit-self","expectedVersion":3,"witnessId":"alice",
                                 "baseRevisionNo":1}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("WITNESS_SAME_AS_SUBMITTER"));

        // 失败分支：见证指定版本与申请基准版本不一致 → 422
        mockMvc.perform(post("/api/equipment/eq-d/readings/r1/witness")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"wit-wrongver","expectedVersion":3,"witnessId":"bob",
                                 "baseRevisionNo":2}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("REVISION_VERSION_MISMATCH"));

        // 失败分支：设备版本不匹配 → 409
        mockMvc.perform(post("/api/equipment/eq-d/readings/r1/witness")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"wit-badversion","expectedVersion":99,"witnessId":"bob",
                                 "baseRevisionNo":1}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
        mockMvc.perform(get("/api/equipment/eq-d/status"))
                .andExpect(jsonPath("$.version").value(3));

        // 另一名维护人 bob 见证同一读数版本（基准修订号 1）→ 112h 生效
        MvcResult witnessed = postJson("/api/equipment/eq-d/readings/r1/witness", """
                {"requestId":"wit-ok","expectedVersion":3,"witnessId":"bob","baseRevisionNo":1}
                """);
        org.junit.jupiter.api.Assertions.assertEquals(201, witnessed.getResponse().getStatus());
        mockMvc.perform(get("/api/equipment/eq-d/readings"))
                .andExpect(jsonPath("$[0].cumulativeMinutes").value(6720))
                .andExpect(jsonPath("$[0].revisionNo").value(2))
                .andExpect(jsonPath("$[0].pendingRequestId").value(nullValue()));

        // 见证后才参与保养周期：runMinutes=6720 ≥ 6500 → DUE
        mockMvc.perform(get("/api/equipment/eq-d/status"))
                .andExpect(jsonPath("$.version").value(4))
                .andExpect(jsonPath("$.latestCumulativeMinutes").value(6720))
                .andExpect(jsonPath("$.runMinutes").value(6720))
                .andExpect(jsonPath("$.status").value("DUE"));

        // 历史周期按当时生效读数解释：修订历史记录见证生效行的提交人与见证人
        mockMvc.perform(get("/api/equipment/eq-d/readings/r1/revisions"))
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[1].revisionNo").value(2))
                .andExpect(jsonPath("$[1].cumulativeMinutes").value(6720))
                .andExpect(jsonPath("$[1].submitterId").value("alice"))
                .andExpect(jsonPath("$[1].witnessId").value("bob"));
        mockMvc.perform(get("/api/equipment/eq-d/readings/r1/revision-requests"))
                .andExpect(jsonPath("$[0].status").value("EFFECTIVE"))
                .andExpect(jsonPath("$[0].witnessId").value("bob"));

        // 生效后以新值完成保养放行，锚点快照为 6720，周期重置为 0
        mockMvc.perform(post("/api/equipment/eq-d/maintenances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"mnt-ok","expectedVersion":4,"readingId":"r1",
                                 "anchorRevisionNo":2}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.anchorCumulativeMinutes").value(6720));
        mockMvc.perform(get("/api/equipment/eq-d/status"))
                .andExpect(jsonPath("$.runMinutes").value(0))
                .andExpect(jsonPath("$.status").value("OK"));
    }

    // ---------- 读数倒退 ----------

    @Test
    void revise_backward_returns422AndLeavesNoPending() throws Exception {
        registerDeviceD();
        addReading100h();

        // 低于当前有效值：无论差额是否超阈值都按读数倒退拒绝，且不产生待确认申请
        mockMvc.perform(post("/api/equipment/eq-d/readings/r1/revisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"rev-down1","expectedVersion":2,"cumulativeMinutes":5940,
                                 "submitterId":"alice"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("READING_BACKWARD"));
        mockMvc.perform(post("/api/equipment/eq-d/readings/r1/revisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"rev-down2","expectedVersion":2,"cumulativeMinutes":5000,
                                 "submitterId":"alice"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("READING_BACKWARD"));

        // 失败回滚：有效值仍 6000、无申请、修订历史仅 1 行、版本仍为 2
        mockMvc.perform(get("/api/equipment/eq-d/status"))
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.latestCumulativeMinutes").value(6000));
        mockMvc.perform(get("/api/equipment/eq-d/readings/r1/revision-requests"))
                .andExpect(jsonPath("$", hasSize(0)));
        mockMvc.perform(get("/api/equipment/eq-d/readings/r1/revisions"))
                .andExpect(jsonPath("$", hasSize(1)));
        Integer requestCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM reading_revision_request", Integer.class);
        org.junit.jupiter.api.Assertions.assertEquals(0, requestCount);
    }

    // ---------- 阈值边界：恰好相等不超阈值，直接生效 ----------

    @Test
    void revise_deltaEqualsThreshold_effectiveDirectly() throws Exception {
        registerDeviceD();
        addReading100h();
        // 6000 → 6600 恰好等于阈值 600，直接生效，无待确认字段
        mockMvc.perform(post("/api/equipment/eq-d/readings/r1/revisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"rev-eq","expectedVersion":2,"cumulativeMinutes":6600,
                                 "submitterId":"alice"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.cumulativeMinutes").value(6600))
                .andExpect(jsonPath("$.revisionNo").value(2))
                .andExpect(jsonPath("$.pendingRequestId").value(nullValue()));
        mockMvc.perform(get("/api/equipment/eq-d/readings/r1/revision-requests"))
                .andExpect(jsonPath("$", hasSize(0)));
    }

    // ---------- 待确认期间按有效值保养：锚定后申请作废，不能再见证 ----------

    @Test
    void pending_maintenanceOnEffectiveValue_supersedesRequest() throws Exception {
        registerDeviceD();
        addReading100h();
        postJson("/api/equipment/eq-d/readings/r1/revisions", """
                {"requestId":"rev-big2","expectedVersion":2,"cumulativeMinutes":6720,
                 "submitterId":"alice"}
                """);

        // 以当前有效值（修订号 1，6000）完成保养是允许的；待确认申请随之作废
        mockMvc.perform(post("/api/equipment/eq-d/maintenances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"mnt-old","expectedVersion":3,"readingId":"r1",
                                 "anchorRevisionNo":1}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.anchorCumulativeMinutes").value(6000))
                .andExpect(jsonPath("$.anchorRevisionNo").value(1));
        mockMvc.perform(get("/api/equipment/eq-d/readings/r1/revision-requests"))
                .andExpect(jsonPath("$[0].status").value("SUPERSEDED"));

        // 作废后见证 → 422；锚定读数也不可再修订 → 409
        mockMvc.perform(post("/api/equipment/eq-d/readings/r1/witness")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"wit-after","expectedVersion":4,"witnessId":"bob",
                                 "baseRevisionNo":1}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("REVISION_NOT_PENDING"));
        mockMvc.perform(post("/api/equipment/eq-d/readings/r1/revisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"rev-after","expectedVersion":4,"cumulativeMinutes":6100,
                                 "submitterId":"carol"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("READING_ANCHORED"));
    }

    // ---------- 见证前产生新生效修订：旧申请作废，旧见证不能批准新读数 ----------

    @Test
    void newRevision_supersedesPendingAndOldWitnessRejected() throws Exception {
        registerDeviceD();
        addReading100h();
        // alice: 6000 → 6720 待确认（基准版本 1）
        postJson("/api/equipment/eq-d/readings/r1/revisions", """
                {"requestId":"rev-a","expectedVersion":2,"cumulativeMinutes":6720,
                 "submitterId":"alice"}
                """);
        // carol 的阈值内修订 6000 → 6100 直接生效（修订号 2），旧申请被取代
        mockMvc.perform(post("/api/equipment/eq-d/readings/r1/revisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"rev-c","expectedVersion":3,"cumulativeMinutes":6100,
                                 "submitterId":"carol"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.revisionNo").value(2))
                .andExpect(jsonPath("$.cumulativeMinutes").value(6100))
                .andExpect(jsonPath("$.pendingRequestId").value(nullValue()));
        mockMvc.perform(get("/api/equipment/eq-d/readings/r1/revision-requests"))
                .andExpect(jsonPath("$[0].status").value("SUPERSEDED"));

        // 用新版本号“见证”旧申请：没有待确认申请 → 422
        mockMvc.perform(post("/api/equipment/eq-d/readings/r1/witness")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"wit-newver","expectedVersion":4,"witnessId":"bob",
                                 "baseRevisionNo":2}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("REVISION_NOT_PENDING"));

        // 新的大幅修订 6100 → 6800 以版本 2 为基准重新待确认，见证后生效为修订号 3
        postJson("/api/equipment/eq-d/readings/r1/revisions", """
                {"requestId":"rev-a2","expectedVersion":4,"cumulativeMinutes":6800,
                 "submitterId":"alice"}
                """);
        mockMvc.perform(post("/api/equipment/eq-d/readings/r1/witness")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"wit-ok2","expectedVersion":5,"witnessId":"bob",
                                 "baseRevisionNo":2}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.cumulativeMinutes").value(6800))
                .andExpect(jsonPath("$.revisionNo").value(3));

        // 历史：100h → 101h40m(直接生效) → 113h20m(见证生效)；申请记录保留全部状态
        mockMvc.perform(get("/api/equipment/eq-d/readings/r1/revisions"))
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[0].cumulativeMinutes").value(6000))
                .andExpect(jsonPath("$[1].cumulativeMinutes").value(6100))
                .andExpect(jsonPath("$[2].cumulativeMinutes").value(6800));
        mockMvc.perform(get("/api/equipment/eq-d/readings/r1/revision-requests"))
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].status").value("SUPERSEDED"))
                .andExpect(jsonPath("$[1].status").value("EFFECTIVE"))
                .andExpect(jsonPath("$[1].baseRevisionNo").value(2));
    }

    // ---------- 幂等：重放首次状态、换读数 409、失败不占键 ----------

    @Test
    void idempotency_reviseAndWitness() throws Exception {
        registerDeviceD();
        addReading100h();

        String reviseBody = """
                {"requestId":"rev-idem","expectedVersion":2,"cumulativeMinutes":6720,
                 "submitterId":"alice"}
                """;
        MvcResult first = postJson("/api/equipment/eq-d/readings/r1/revisions", reviseBody);
        org.junit.jupiter.api.Assertions.assertEquals(201, first.getResponse().getStatus());

        // 同键同参重放：返回首次的待确认结果，不重复建申请、版本不重复增加
        MvcResult replay = postJson("/api/equipment/eq-d/readings/r1/revisions", reviseBody);
        org.junit.jupiter.api.Assertions.assertEquals(201, replay.getResponse().getStatus());
        org.junit.jupiter.api.Assertions.assertEquals(first.getResponse().getContentAsString(),
                replay.getResponse().getContentAsString());
        mockMvc.perform(get("/api/equipment/eq-d/status"))
                .andExpect(jsonPath("$.version").value(3));
        Integer requestCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM reading_revision_request WHERE request_id = 'rev-idem'",
                Integer.class);
        org.junit.jupiter.api.Assertions.assertEquals(1, requestCount);

        // 同键换读数（新值不同）→ 409
        mockMvc.perform(post("/api/equipment/eq-d/readings/r1/revisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"rev-idem","expectedVersion":2,"cumulativeMinutes":6700,
                                 "submitterId":"alice"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));

        // 见证失败（同人）不占键：同一见证 requestId 换另一名维护人后成功
        mockMvc.perform(post("/api/equipment/eq-d/readings/r1/witness")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"wit-idem","expectedVersion":3,"witnessId":"alice",
                                 "baseRevisionNo":1}
                                """))
                .andExpect(status().isUnprocessableEntity());
        MvcResult witnessOk = postJson("/api/equipment/eq-d/readings/r1/witness", """
                {"requestId":"wit-idem","expectedVersion":3,"witnessId":"bob","baseRevisionNo":1}
                """);
        org.junit.jupiter.api.Assertions.assertEquals(201, witnessOk.getResponse().getStatus());

        // 生效后重放见证 requestId：返回首次生效结果，不重复追加修订或推进版本
        MvcResult witnessReplay = postJson("/api/equipment/eq-d/readings/r1/witness", """
                {"requestId":"wit-idem","expectedVersion":3,"witnessId":"bob","baseRevisionNo":1}
                """);
        org.junit.jupiter.api.Assertions.assertEquals(201, witnessReplay.getResponse().getStatus());
        org.junit.jupiter.api.Assertions.assertEquals(witnessOk.getResponse().getContentAsString(),
                witnessReplay.getResponse().getContentAsString());
        mockMvc.perform(get("/api/equipment/eq-d/status"))
                .andExpect(jsonPath("$.version").value(4));
        mockMvc.perform(get("/api/equipment/eq-d/readings/r1/revisions"))
                .andExpect(jsonPath("$", hasSize(2)));
    }

    // ---------- 不存在资源 ----------

    @Test
    void witness_missingResources_404() throws Exception {
        mockMvc.perform(post("/api/equipment/nope/readings/r1/witness")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"w1","expectedVersion":1,"witnessId":"bob",
                                 "baseRevisionNo":1}
                                """))
                .andExpect(status().isNotFound());
        registerDeviceD();
        mockMvc.perform(post("/api/equipment/eq-d/readings/missing/witness")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"w2","expectedVersion":1,"witnessId":"bob",
                                 "baseRevisionNo":1}
                                """))
                .andExpect(status().isNotFound());
    }
}
