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
import org.springframework.test.web.servlet.ResultActions;

/**
 * 工时读数认证 API 测试（真实 H2 内存库，MySQL 兼容模式）：
 * 双人认证、序列单调、批量回滚、修订链重新认证、退役保护、certKey 幂等。
 */
@SpringBootTest
@AutoConfigureMockMvc
class CertificationApiTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM idempotency_request");
        jdbc.update("DELETE FROM reading_certification");
        jdbc.update("DELETE FROM maintenance");
        jdbc.update("DELETE FROM reading_revision");
        jdbc.update("DELETE FROM reading");
        jdbc.update("DELETE FROM equipment");
    }

    private ResultActions postJson(String url, String body) throws Exception {
        return mockMvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private void register(String equipmentId, long periodMinutes) throws Exception {
        mockMvc.perform(post("/api/equipment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"reg-%s","equipmentId":"%s","maintenancePeriodMinutes":%d}
                                """.formatted(equipmentId, equipmentId, periodMinutes)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.retired").value(false));
    }

    private void addReading(String equipmentId, String requestId, long expectedVersion,
                            String readingId, String sampledAt, long cumulativeMinutes,
                            String recordedBy) throws Exception {
        mockMvc.perform(post("/api/equipment/" + equipmentId + "/readings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"%s","expectedVersion":%d,"readingId":"%s",
                                 "sampledAt":"%s","cumulativeMinutes":%d,"recordedBy":"%s"}
                                """.formatted(requestId, expectedVersion, readingId, sampledAt,
                                cumulativeMinutes, recordedBy)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    private ResultActions certify(String requestId, String certifier, String itemsJson) throws Exception {
        return postJson("/api/certifications", """
                {"requestId":"%s","certifier":"%s","items":[%s]}
                """.formatted(requestId, certifier, itemsJson));
    }

    private String item(String equipmentId, String readingId, int revisionNo) {
        return """
                {"equipmentId":"%s","readingId":"%s","expectedRevisionNo":%d}
                """.formatted(equipmentId, readingId, revisionNo);
    }

    // ---------- 双人认证主流程与快照 ----------

    @Test
    void certify_twoPersonRuleAndSnapshot() throws Exception {
        register("eq-c1", 100);
        addReading("eq-c1", "c1-add", 1, "r1", "2026-01-01T10:00:00Z", 50, "alice");

        // PENDING 不参与累计工时与保养判定
        mockMvc.perform(get("/api/equipment/eq-c1/status"))
                .andExpect(jsonPath("$.latestCumulativeMinutes").value(0))
                .andExpect(jsonPath("$.runMinutes").value(0))
                .andExpect(jsonPath("$.status").value("OK"));

        // 录入人不得认证自己的读数 → 403
        certify("c1-self", "alice", item("eq-c1", "r1", 1))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CERTIFIER_IS_RECORDER"));

        // 读数版本不符 → 422
        certify("c1-ver", "bob", item("eq-c1", "r1", 2))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("READING_VERSION_CONFLICT"));

        // 认证被拒绝不改变累计工时与版本，也不写快照
        mockMvc.perform(get("/api/equipment/eq-c1/status"))
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.runMinutes").value(0));
        mockMvc.perform(get("/api/equipment/eq-c1/certifications"))
                .andExpect(jsonPath("$", hasSize(0)));

        // 不同认证人认证成功：同事务转 CERTIFIED、重算并写快照
        certify("c1-cert", "bob", item("eq-c1", "r1", 1))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.certifier").value("bob"))
                .andExpect(jsonPath("$.readings", hasSize(1)))
                .andExpect(jsonPath("$.readings[0].readingId").value("r1"))
                .andExpect(jsonPath("$.readings[0].revisionNo").value(1))
                .andExpect(jsonPath("$.readings[0].cumulativeMinutes").value(50))
                .andExpect(jsonPath("$.equipments", hasSize(1)))
                .andExpect(jsonPath("$.equipments[0].equipmentId").value("eq-c1"))
                .andExpect(jsonPath("$.equipments[0].version").value(3))
                .andExpect(jsonPath("$.equipments[0].latestCertifiedCumulativeMinutes").value(50))
                .andExpect(jsonPath("$.equipments[0].runMinutes").value(50))
                .andExpect(jsonPath("$.equipments[0].maintenanceStatus").value("OK"));

        // 读数状态与累计工时更新
        mockMvc.perform(get("/api/equipment/eq-c1/readings"))
                .andExpect(jsonPath("$[0].status").value("CERTIFIED"))
                .andExpect(jsonPath("$[0].recordedBy").value("alice"))
                .andExpect(jsonPath("$[0].certifiedBy").value("bob"));
        mockMvc.perform(get("/api/equipment/eq-c1/status"))
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.runMinutes").value(50));

        // 不可变认证快照可查询
        mockMvc.perform(get("/api/equipment/eq-c1/readings/r1/certification"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").value("c1-cert"))
                .andExpect(jsonPath("$.certifiedBy").value("bob"))
                .andExpect(jsonPath("$.revisionNo").value(1))
                .andExpect(jsonPath("$.cumulativeMinutes").value(50))
                .andExpect(jsonPath("$.latestCertifiedCumulativeMinutes").value(50))
                .andExpect(jsonPath("$.runMinutes").value(50))
                .andExpect(jsonPath("$.maintenanceStatus").value("OK"))
                .andExpect(jsonPath("$.equipmentVersion").value(3));
        mockMvc.perform(get("/api/equipment/eq-c1/certifications"))
                .andExpect(jsonPath("$", hasSize(1)));
    }

    // ---------- 保养触发保护 ----------

    @Test
    void certify_pendingReadingNotTriggeringDue() throws Exception {
        register("eq-c2", 100);
        // 超过保养周期的 PENDING 读数不触发 DUE
        addReading("eq-c2", "c2-add", 1, "r1", "2026-01-01T10:00:00Z", 130, "alice");
        mockMvc.perform(get("/api/equipment/eq-c2/status"))
                .andExpect(jsonPath("$.runMinutes").value(0))
                .andExpect(jsonPath("$.status").value("OK"));

        // 认证后才参与阈值判定 → DUE
        certify("c2-cert", "bob", item("eq-c2", "r1", 1))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.equipments[0].maintenanceStatus").value("DUE"));
        mockMvc.perform(get("/api/equipment/eq-c2/status"))
                .andExpect(jsonPath("$.runMinutes").value(130))
                .andExpect(jsonPath("$.status").value("DUE"));
    }

    // ---------- 批量认证：跨设备、排序、快照 ----------

    @Test
    void certify_batchAcrossEquipmentSortedAndSnapshot() throws Exception {
        register("eq-b1", 1000);
        register("eq-b2", 1000);
        addReading("eq-b1", "b1-add1", 1, "r1", "2026-01-01T10:00:00Z", 100, "alice");
        addReading("eq-b1", "b1-add2", 2, "r2", "2026-01-01T11:00:00Z", 200, "alice");
        addReading("eq-b2", "b2-add1", 1, "s1", "2026-01-01T10:00:00Z", 50, "alice");

        // 批次内顺序无关：按设备与读数时刻排序验证并生效
        certify("b-cert", "bob",
                item("eq-b2", "s1", 1) + "," + item("eq-b1", "r2", 1) + "," + item("eq-b1", "r1", 1))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.readings", hasSize(3)))
                .andExpect(jsonPath("$.readings[0].readingId").value("r1"))
                .andExpect(jsonPath("$.readings[1].readingId").value("r2"))
                .andExpect(jsonPath("$.readings[2].readingId").value("s1"))
                .andExpect(jsonPath("$.equipments", hasSize(2)))
                .andExpect(jsonPath("$.equipments[0].equipmentId").value("eq-b1"))
                .andExpect(jsonPath("$.equipments[0].version").value(4))
                .andExpect(jsonPath("$.equipments[0].latestCertifiedCumulativeMinutes").value(200))
                .andExpect(jsonPath("$.equipments[1].equipmentId").value("eq-b2"))
                .andExpect(jsonPath("$.equipments[1].version").value(3))
                .andExpect(jsonPath("$.equipments[1].latestCertifiedCumulativeMinutes").value(50));

        mockMvc.perform(get("/api/equipment/eq-b1/certifications"))
                .andExpect(jsonPath("$", hasSize(2)));
        mockMvc.perform(get("/api/equipment/eq-b2/certifications"))
                .andExpect(jsonPath("$", hasSize(1)));
        mockMvc.perform(get("/api/equipment/eq-b1/status"))
                .andExpect(jsonPath("$.runMinutes").value(200));
    }

    // ---------- 批量回滚：任一失败整批回滚 ----------

    @Test
    void certify_batchRollbackOnAnyFailure() throws Exception {
        register("eq-b3", 1000);
        addReading("eq-b3", "b3-add1", 1, "r1", "2026-01-01T10:00:00Z", 100, "recorder-x");
        addReading("eq-b3", "b3-add2", 2, "r2", "2026-01-01T11:00:00Z", 200, "alice");

        // 批次中 r2 由 alice 录入、alice 认证 → 403，整批回滚：r1 也不得认证
        certify("b3-self", "alice",
                item("eq-b3", "r1", 1) + "," + item("eq-b3", "r2", 1))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CERTIFIER_IS_RECORDER"));
        mockMvc.perform(get("/api/equipment/eq-b3/readings"))
                .andExpect(jsonPath("$[0].status").value("PENDING"))
                .andExpect(jsonPath("$[1].status").value("PENDING"));
        mockMvc.perform(get("/api/equipment/eq-b3/certifications"))
                .andExpect(jsonPath("$", hasSize(0)));
        mockMvc.perform(get("/api/equipment/eq-b3/status"))
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.runMinutes").value(0));

        // 批次中一条读数版本不符 → 422，整批回滚
        certify("b3-ver", "bob",
                item("eq-b3", "r1", 1) + "," + item("eq-b3", "r2", 5))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("READING_VERSION_CONFLICT"));
        mockMvc.perform(get("/api/equipment/eq-b3/readings"))
                .andExpect(jsonPath("$[0].status").value("PENDING"))
                .andExpect(jsonPath("$[1].status").value("PENDING"));
        mockMvc.perform(get("/api/equipment/eq-b3/status"))
                .andExpect(jsonPath("$.version").value(3));

        // 修正后整批成功
        certify("b3-ok", "bob",
                item("eq-b3", "r1", 1) + "," + item("eq-b3", "r2", 1))
                .andExpect(status().isCreated());
        mockMvc.perform(get("/api/equipment/eq-b3/readings"))
                .andExpect(jsonPath("$[0].status").value("CERTIFIED"))
                .andExpect(jsonPath("$[1].status").value("CERTIFIED"));
        mockMvc.perform(get("/api/equipment/eq-b3/status"))
                .andExpect(jsonPath("$.version").value(4))
                .andExpect(jsonPath("$.runMinutes").value(200));
    }

    // ---------- 序列单调：认证不得使已认证序列倒退（含整批回滚） ----------

    @Test
    void certify_sequenceRegressionGuardAndRollback() throws Exception {
        register("eq-b4", 1000);
        addReading("eq-b4", "b4-add1", 1, "r1", "2026-01-01T10:00:00Z", 100, "alice");
        addReading("eq-b4", "b4-add2", 2, "r2", "2026-01-01T11:00:00Z", 200, "alice");
        certify("b4-cert", "bob", item("eq-b4", "r1", 1) + "," + item("eq-b4", "r2", 1))
                .andExpect(status().isCreated());
        addReading("eq-b4", "b4-add3", 4, "r3", "2026-01-01T12:00:00Z", 300, "alice");
        addReading("eq-b4", "b4-add4", 5, "r4", "2026-01-01T13:00:00Z", 400, "alice");

        // 模拟历史漂移数据：已认证读数 r2 的值被漂移到 350（直接修正库内数据）
        jdbc.update("UPDATE reading SET cumulative_minutes = 350"
                + " WHERE equipment_id = 'eq-b4' AND reading_id = 'r2'");

        // 单条认证：300 小于上一条已认证读数 350 → 422
        certify("b4-reg", "bob", item("eq-b4", "r3", 1))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CERTIFICATION_SEQUENCE_REGRESSION"));

        // 批量认证：r3 倒退 → 整批回滚，r4 也不得认证
        certify("b4-reg-batch", "bob", item("eq-b4", "r3", 1) + "," + item("eq-b4", "r4", 1))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CERTIFICATION_SEQUENCE_REGRESSION"));
        mockMvc.perform(get("/api/equipment/eq-b4/readings"))
                .andExpect(jsonPath("$[2].status").value("PENDING"))
                .andExpect(jsonPath("$[3].status").value("PENDING"));
        mockMvc.perform(get("/api/equipment/eq-b4/certifications"))
                .andExpect(jsonPath("$", hasSize(2)));
        mockMvc.perform(get("/api/equipment/eq-b4/status"))
                .andExpect(jsonPath("$.version").value(6))
                .andExpect(jsonPath("$.runMinutes").value(350));

        // 漂移修正后序列恢复单调，整批认证成功
        jdbc.update("UPDATE reading SET cumulative_minutes = 250"
                + " WHERE equipment_id = 'eq-b4' AND reading_id = 'r2'");
        certify("b4-fixed", "bob", item("eq-b4", "r3", 1) + "," + item("eq-b4", "r4", 1))
                .andExpect(status().isCreated());
        mockMvc.perform(get("/api/equipment/eq-b4/readings"))
                .andExpect(jsonPath("$[2].status").value("CERTIFIED"))
                .andExpect(jsonPath("$[3].status").value("CERTIFIED"));
        mockMvc.perform(get("/api/equipment/eq-b4/certifications"))
                .andExpect(jsonPath("$", hasSize(4)));
    }

    // ---------- 修订链：已认证读数只能沿修订链创建新版本并重新认证 ----------

    @Test
    void certify_revisionChainRequiresRecertification() throws Exception {
        register("eq-c3", 1000);
        addReading("eq-c3", "c3-add1", 1, "r1", "2026-01-01T10:00:00Z", 100, "alice");
        certify("c3-cert1", "bob", item("eq-c3", "r1", 1)).andExpect(status().isCreated());
        addReading("eq-c3", "c3-add2", 3, "r2", "2026-01-01T11:00:00Z", 200, "alice");
        certify("c3-cert2", "bob", item("eq-c3", "r2", 1)).andExpect(status().isCreated());
        mockMvc.perform(get("/api/equipment/eq-c3/status"))
                .andExpect(jsonPath("$.runMinutes").value(200));

        // 已认证读数不可直接修改：沿修订链创建新版本（rev2），回到 PENDING
        mockMvc.perform(post("/api/equipment/eq-c3/readings/r2/revisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"c3-rev","expectedVersion":5,"cumulativeMinutes":180,
                                 "recordedBy":"carol"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.revisionNo").value(2))
                .andExpect(jsonPath("$.status").value("PENDING"));

        // 最新读数回到 PENDING：累计工时回落到上一条已认证读数
        mockMvc.perform(get("/api/equipment/eq-c3/status"))
                .andExpect(jsonPath("$.latestCumulativeMinutes").value(100))
                .andExpect(jsonPath("$.runMinutes").value(100));

        // 旧修订号不可再认证 → 422
        certify("c3-old-rev", "bob", item("eq-c3", "r2", 1))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("READING_VERSION_CONFLICT"));

        // 新修订版本由不同认证人重新认证后重新计入
        certify("c3-cert3", "bob", item("eq-c3", "r2", 2))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.readings[0].revisionNo").value(2))
                .andExpect(jsonPath("$.equipments[0].latestCertifiedCumulativeMinutes").value(180));
        mockMvc.perform(get("/api/equipment/eq-c3/status"))
                .andExpect(jsonPath("$.runMinutes").value(180));

        // 认证快照不可变：rev1 与 rev2 的快照均保留，查询返回最近一次
        mockMvc.perform(get("/api/equipment/eq-c3/certifications"))
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[1].readingId").value("r2"))
                .andExpect(jsonPath("$[1].revisionNo").value(1))
                .andExpect(jsonPath("$[1].cumulativeMinutes").value(200))
                .andExpect(jsonPath("$[2].readingId").value("r2"))
                .andExpect(jsonPath("$[2].revisionNo").value(2))
                .andExpect(jsonPath("$[2].cumulativeMinutes").value(180));
        mockMvc.perform(get("/api/equipment/eq-c3/readings/r2/certification"))
                .andExpect(jsonPath("$.revisionNo").value(2))
                .andExpect(jsonPath("$.cumulativeMinutes").value(180));
    }

    // ---------- certKey 幂等：同键重放、异参冲突、失败不占键、批次规范化 ----------

    @Test
    void certify_idempotencyReplayAndFailureNotOccupying() throws Exception {
        register("eq-c4", 1000);
        addReading("eq-c4", "c4-add1", 1, "r1", "2026-01-01T10:00:00Z", 100, "alice");

        MvcResult first = certify("ck-1", "bob", item("eq-c4", "r1", 1)).andReturn();
        assert first.getResponse().getStatus() == 201;

        // 同键同参：完整重放重算结果，业务效果不重复
        MvcResult replay = certify("ck-1", "bob", item("eq-c4", "r1", 1)).andReturn();
        assert replay.getResponse().getStatus() == 201;
        assert replay.getResponse().getContentAsString()
                .equals(first.getResponse().getContentAsString());
        mockMvc.perform(get("/api/equipment/eq-c4/certifications"))
                .andExpect(jsonPath("$", hasSize(1)));
        mockMvc.perform(get("/api/equipment/eq-c4/status"))
                .andExpect(jsonPath("$.version").value(3));

        // 同键异参 → 409
        certify("ck-1", "bob", item("eq-c4", "r1", 2))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));

        // 失败不占键：422 后同 certKey 修正参数可成功
        addReading("eq-c4", "c4-add2", 3, "r2", "2026-01-01T11:00:00Z", 150, "alice");
        certify("ck-2", "bob", item("eq-c4", "r2", 7))
                .andExpect(status().isUnprocessableEntity());
        certify("ck-2", "bob", item("eq-c4", "r2", 1))
                .andExpect(status().isCreated());

        // 批次规范化：同集合不同顺序指纹相同，同键重放
        addReading("eq-c4", "c4-add3", 5, "r3", "2026-01-01T12:00:00Z", 200, "alice");
        addReading("eq-c4", "c4-add4", 6, "r4", "2026-01-01T13:00:00Z", 250, "alice");
        MvcResult batchFirst = certify("ck-3", "bob",
                item("eq-c4", "r4", 1) + "," + item("eq-c4", "r3", 1)).andReturn();
        assert batchFirst.getResponse().getStatus() == 201;
        MvcResult batchReplay = certify("ck-3", "bob",
                item("eq-c4", "r3", 1) + "," + item("eq-c4", "r4", 1)).andReturn();
        assert batchReplay.getResponse().getStatus() == 201;
        assert batchReplay.getResponse().getContentAsString()
                .equals(batchFirst.getResponse().getContentAsString());
        mockMvc.perform(get("/api/equipment/eq-c4/certifications"))
                .andExpect(jsonPath("$", hasSize(4)));
    }

    // ---------- 设备退役：退役后不可认证 ----------

    @Test
    void retire_blocksCertification() throws Exception {
        register("eq-r1", 100);
        addReading("eq-r1", "r1-add", 1, "r1", "2026-01-01T10:00:00Z", 50, "alice");

        // 版本冲突 → 409
        mockMvc.perform(post("/api/equipment/eq-r1/retirement")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"rt-0","expectedVersion":9}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));

        // 退役成功
        mockMvc.perform(post("/api/equipment/eq-r1/retirement")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"rt-1","expectedVersion":2}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.retired").value(true))
                .andExpect(jsonPath("$.version").value(3));

        // 重复退役 → 409
        mockMvc.perform(post("/api/equipment/eq-r1/retirement")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"rt-2","expectedVersion":3}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EQUIPMENT_ALREADY_RETIRED"));

        // 退役设备的读数不可认证 → 422，且不改变累计工时
        certify("rt-cert", "bob", item("eq-r1", "r1", 1))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("EQUIPMENT_RETIRED"));
        mockMvc.perform(get("/api/equipment/eq-r1/readings"))
                .andExpect(jsonPath("$[0].status").value("PENDING"));
        mockMvc.perform(get("/api/equipment/eq-r1/certifications"))
                .andExpect(jsonPath("$", hasSize(0)));

        // 退役请求幂等：同键重放
        mockMvc.perform(post("/api/equipment/eq-r1/retirement")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"rt-1","expectedVersion":2}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.retired").value(true));
    }

    // ---------- 认证失败分支：404 / 409 / 400 ----------

    @Test
    void certify_notFoundAndValidationBranches() throws Exception {
        register("eq-nf1", 100);
        addReading("eq-nf1", "nf-add", 1, "r1", "2026-01-01T10:00:00Z", 50, "alice");

        // 设备不存在 → 404
        certify("nf-e", "bob", item("eq-nope", "r1", 1))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EQUIPMENT_NOT_FOUND"));

        // 读数不存在 → 404
        certify("nf-r", "bob", item("eq-nf1", "nope", 1))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("READING_NOT_FOUND"));

        // 未认证读数查询认证快照 → 404
        mockMvc.perform(get("/api/equipment/eq-nf1/readings/r1/certification"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CERTIFICATION_NOT_FOUND"));

        // 已认证读数不可重复认证 → 409
        certify("nf-c", "bob", item("eq-nf1", "r1", 1)).andExpect(status().isCreated());
        certify("nf-c2", "carol", item("eq-nf1", "r1", 1))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("READING_ALREADY_CERTIFIED"));

        // 批次内读数重复 → 422
        certify("nf-dup", "bob", item("eq-nf1", "r1", 1) + "," + item("eq-nf1", "r1", 1))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BATCH_DUPLICATE_READING"));

        // 参数校验：空批次 / 缺认证人 → 400
        postJson("/api/certifications", """
                {"requestId":"nf-bad1","certifier":"bob","items":[]}
                """).andExpect(status().isBadRequest());
        postJson("/api/certifications", """
                {"requestId":"nf-bad2","items":[{"equipmentId":"eq-nf1","readingId":"r1",
                  "expectedRevisionNo":1}]}
                """).andExpect(status().isBadRequest());
    }
}
