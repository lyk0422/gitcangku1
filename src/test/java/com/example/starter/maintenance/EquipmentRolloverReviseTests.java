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
 * 量程回绕：原始修订、保养锚点冻结、幂等与结构化指纹边界测试（真实 H2 内存库）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class EquipmentRolloverReviseTests {

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
                .andExpect(status().isCreated());
    }

    private void registerRange(String equipmentId, long expectedVersion, long rangeM) throws Exception {
        mockMvc.perform(post("/api/equipment/" + equipmentId + "/range")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"range-%s","expectedVersion":%d,"rangeM":%d}
                                """.formatted(equipmentId, expectedVersion, rangeM)))
                .andExpect(status().isCreated());
    }

    private void addRaw(String equipmentId, String requestId, long expectedVersion,
                        String readingId, String sampledAt, long raw, long wrapCount) throws Exception {
        mockMvc.perform(post("/api/equipment/" + equipmentId + "/raw-readings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"%s","expectedVersion":%d,"readingId":"%s",
                                 "sampledAt":"%s","raw":%d,"wrapCount":%d}
                                """.formatted(requestId, expectedVersion, readingId, sampledAt,
                                raw, wrapCount)))
                .andExpect(status().isCreated());
    }

    private MvcResult reviseRaw(String equipmentId, String readingId, String requestId,
                                long expectedVersion, int readingRevision,
                                long raw, long wrapCount) throws Exception {
        return postJson("/api/equipment/" + equipmentId + "/readings/" + readingId + "/raw-revisions",
                """
                {"requestId":"%s","expectedVersion":%d,"readingRevision":%d,
                 "raw":%d,"wrapCount":%d}
                """.formatted(requestId, expectedVersion, readingRevision, raw, wrapCount));
    }

    /** 题干合成场景：M=1000，前读数 raw990/wrap0，后读数 raw5/wrap1（归一 1005）。 */
    private void setupRolloverPair(String equipmentId) throws Exception {
        register(equipmentId, 100);
        registerRange(equipmentId, 1, 1000);
        addRaw(equipmentId, "setup-r1", 2, "r1", "2026-01-01T10:00:00Z", 990, 0);
        addRaw(equipmentId, "setup-r2", 3, "r2", "2026-01-01T11:00:00Z", 5, 1);
    }

    // ---------- 原始修订：回绕不可被改掉、单调重判、修订号校验 ----------

    @Test
    void reviseRaw_wrapBackToZeroRejected_cannotBecomeCumulative5() throws Exception {
        setupRolloverPair("eq-rev");

        // 把后读数 wrap 从 1 改为 0：归一值将变为 5 < 前邻 990，违反单调 → 422
        mockMvc.perform(post("/api/equipment/eq-rev/readings/r2/raw-revisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"rev-1","expectedVersion":4,"readingRevision":1,
                                 "raw":5,"wrapCount":0}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("READING_ORDER_VIOLATION"));

        // 拒绝后原值不变：归一仍为 1005，不会变成累计 5
        mockMvc.perform(get("/api/equipment/eq-rev/readings"))
                .andExpect(jsonPath("$[1].readingId").value("r2"))
                .andExpect(jsonPath("$[1].cumulativeMinutes").value(1005))
                .andExpect(jsonPath("$[1].wrapCount").value(1));
        mockMvc.perform(get("/api/equipment/eq-rev/status"))
                .andExpect(jsonPath("$.version").value(4))
                .andExpect(jsonPath("$.latestCumulativeMinutes").value(1005));

        // 合法修订：raw 5→10、wrap 保持 1，归一 1010，采样时刻不变，修订号 +1
        mockMvc.perform(post("/api/equipment/eq-rev/readings/r2/raw-revisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"rev-2","expectedVersion":4,"readingRevision":1,
                                 "raw":10,"wrapCount":1}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sampledAt").value("2026-01-01T11:00:00Z"))
                .andExpect(jsonPath("$.cumulativeMinutes").value(1010))
                .andExpect(jsonPath("$.raw").value(10))
                .andExpect(jsonPath("$.wrapCount").value(1))
                .andExpect(jsonPath("$.revisionNo").value(2))
                .andExpect(jsonPath("$.equipmentVersion").value(5));

        // 修订历史保留每次的 raw/wrap 与归一值
        mockMvc.perform(get("/api/equipment/eq-rev/readings/r2/revisions"))
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].revisionNo").value(1))
                .andExpect(jsonPath("$[0].raw").value(5))
                .andExpect(jsonPath("$[0].wrapCount").value(1))
                .andExpect(jsonPath("$[0].cumulativeMinutes").value(1005))
                .andExpect(jsonPath("$[1].revisionNo").value(2))
                .andExpect(jsonPath("$[1].raw").value(10))
                .andExpect(jsonPath("$[1].cumulativeMinutes").value(1010));

        // 携带过期读数修订号 → 409
        mockMvc.perform(post("/api/equipment/eq-rev/readings/r2/raw-revisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"rev-3","expectedVersion":5,"readingRevision":1,
                                 "raw":20,"wrapCount":1}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("READING_REVISION_CONFLICT"));
    }

    @Test
    void reviseRaw_invalidOrOverflow_badRequest() throws Exception {
        setupRolloverPair("eq-rbad");
        // raw 超量程 → 400
        MvcResult bad1 = reviseRaw("eq-rbad", "r2", "rb-1", 4, 1, 1000, 1);
        org.junit.jupiter.api.Assertions.assertEquals(400, bad1.getResponse().getStatus());
        // 归一溢出 → 400
        MvcResult bad2 = postJson("/api/equipment/eq-rbad/readings/r2/raw-revisions", """
                {"requestId":"rb-2","expectedVersion":4,"readingRevision":1,
                 "raw":0,"wrapCount":9223372036854775807}
                """);
        org.junit.jupiter.api.Assertions.assertEquals(400, bad2.getResponse().getStatus());
        org.junit.jupiter.api.Assertions.assertTrue(
                bad2.getResponse().getContentAsString().contains("NORMALIZED_OVERFLOW"));
        // 失败不占键：同 requestId 修正参数后成功
        MvcResult ok = reviseRaw("eq-rbad", "r2", "rb-1", 4, 1, 15, 1);
        org.junit.jupiter.api.Assertions.assertEquals(201, ok.getResponse().getStatus());
    }

    // ---------- 保养锚点冻结：raw/wrap/M 与归一值快照不随后续回绕变化 ----------

    @Test
    void maintenance_freezesAnchorEvidence_historyImmutable() throws Exception {
        setupRolloverPair("eq-frz");

        // 以 r2（raw5/wrap1，归一 1005）为锚点完成保养
        mockMvc.perform(post("/api/equipment/eq-frz/maintenances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"mnt-1","expectedVersion":4,"readingId":"r2","anchorRevisionNo":1}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.anchorCumulativeMinutes").value(1005))
                .andExpect(jsonPath("$.anchorRaw").value(5))
                .andExpect(jsonPath("$.anchorWrapCount").value(1))
                .andExpect(jsonPath("$.anchorRangeM").value(1000))
                .andExpect(jsonPath("$.equipmentVersion").value(5));

        // 锚点读数不可再修订 → 409
        MvcResult anchored = reviseRaw("eq-frz", "r2", "frz-1", 5, 1, 10, 1);
        org.junit.jupiter.api.Assertions.assertEquals(409, anchored.getResponse().getStatus());
        org.junit.jupiter.api.Assertions.assertTrue(
                anchored.getResponse().getContentAsString().contains("READING_ANCHORED"));

        // 后续继续回绕：raw10/wrap2 → 归一 2010
        addRaw("eq-frz", "frz-2", 5, "r3", "2026-01-01T12:00:00Z", 10, 2);

        // 状态按归一最新值减保养锚点：2010 - 1005 = 1005
        mockMvc.perform(get("/api/equipment/eq-frz/status"))
                .andExpect(jsonPath("$.latestCumulativeMinutes").value(2010))
                .andExpect(jsonPath("$.lastMaintenanceAnchorCumulativeMinutes").value(1005))
                .andExpect(jsonPath("$.runMinutes").value(1005))
                .andExpect(jsonPath("$.status").value("DUE"));

        // 历史保养锚点快照不随后续回绕变值
        mockMvc.perform(get("/api/equipment/eq-frz/maintenances"))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].anchorCumulativeMinutes").value(1005))
                .andExpect(jsonPath("$[0].anchorRaw").value(5))
                .andExpect(jsonPath("$[0].anchorWrapCount").value(1))
                .andExpect(jsonPath("$[0].anchorRangeM").value(1000));
    }

    // ---------- 幂等：重放、异参 409、失败不占键 ----------

    @Test
    void idempotency_rawEndpoints_replayMismatchFailure() throws Exception {
        setupRolloverPair("eq-idm");

        MvcResult first = postJson("/api/equipment/eq-idm/raw-readings", """
                {"requestId":"idm-1","expectedVersion":4,"readingId":"r3",
                 "sampledAt":"2026-01-01T12:00:00Z","raw":10,"wrapCount":2}
                """);
        org.junit.jupiter.api.Assertions.assertEquals(201, first.getResponse().getStatus());

        // 同键同参：重放首次完整响应，业务效果不重复
        MvcResult replay = postJson("/api/equipment/eq-idm/raw-readings", """
                {"requestId":"idm-1","expectedVersion":4,"readingId":"r3",
                 "sampledAt":"2026-01-01T12:00:00Z","raw":10,"wrapCount":2}
                """);
        org.junit.jupiter.api.Assertions.assertEquals(201, replay.getResponse().getStatus());
        org.junit.jupiter.api.Assertions.assertEquals(
                first.getResponse().getContentAsString(), replay.getResponse().getContentAsString());
        mockMvc.perform(get("/api/equipment/eq-idm/readings"))
                .andExpect(jsonPath("$", hasSize(3)));
        mockMvc.perform(get("/api/equipment/eq-idm/status"))
                .andExpect(jsonPath("$.version").value(5));

        // 同键异参 → 409
        mockMvc.perform(post("/api/equipment/eq-idm/raw-readings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"idm-1","expectedVersion":4,"readingId":"r3",
                                 "sampledAt":"2026-01-01T12:00:00Z","raw":11,"wrapCount":2}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));

        // 失败不占键：422 后同 requestId 修正参数可成功
        addRawExpect("eq-idm", "idm-2", 5, "r4", "2026-01-01T13:00:00Z", 5, 0, 422);
        addRawExpect("eq-idm", "idm-2", 5, "r4", "2026-01-01T13:00:00Z", 20, 2, 201);
    }

    private void addRawExpect(String equipmentId, String requestId, long expectedVersion,
                              String readingId, String sampledAt, long raw, long wrapCount,
                              int expectedStatus) throws Exception {
        mockMvc.perform(post("/api/equipment/" + equipmentId + "/raw-readings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"%s","expectedVersion":%d,"readingId":"%s",
                                 "sampledAt":"%s","raw":%d,"wrapCount":%d}
                                """.formatted(requestId, expectedVersion, readingId, sampledAt,
                                raw, wrapCount)))
                .andExpect(status().is(expectedStatus));
    }

    // ---------- 结构化指纹：参数含分隔符不发生误判 ----------

    @Test
    void fingerprint_structured_noDelimiterCollision() throws Exception {
        // 设备 "col-a" 的读数 "b|c" 与设备 "col-a|b" 的读数 "c"：
        // 竖线拼接指纹下两者相同（"col-a|b|c|..."），结构化指纹下不同。
        register("col-a", 1000);
        register("col-a|b", 1000);

        mockMvc.perform(post("/api/equipment/col-a/readings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"col-req","expectedVersion":1,"readingId":"b|c",
                                 "sampledAt":"2026-01-01T10:00:00Z","cumulativeMinutes":100}
                                """))
                .andExpect(status().isCreated());

        // 同 requestId 但参数不同：必须 409，不得被误判为同参重放
        mockMvc.perform(post("/api/equipment/col-a|b/readings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"col-req","expectedVersion":1,"readingId":"c",
                                 "sampledAt":"2026-01-01T10:00:00Z","cumulativeMinutes":100}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));

        // 第二台设备无任何读数写入（未发生错误重放）
        mockMvc.perform(get("/api/equipment/col-a|b/readings"))
                .andExpect(jsonPath("$", hasSize(0)));
        mockMvc.perform(get("/api/equipment/col-a/readings"))
                .andExpect(jsonPath("$", hasSize(1)));
    }
}
