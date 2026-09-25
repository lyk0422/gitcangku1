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
 * 工时读数认证测试：双人认证、序列单调、批量回滚、修订链重新认证、退役校验与 certKey 幂等。
 * 真实 H2 内存库（MySQL 兼容模式），每个用例前清空业务表。
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
        jdbc.update("DELETE FROM certification_snapshot");
        jdbc.update("DELETE FROM maintenance");
        jdbc.update("DELETE FROM reading_revision");
        jdbc.update("DELETE FROM reading");
        jdbc.update("DELETE FROM equipment");
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
                            String recordedBy) throws Exception {
        mockMvc.perform(post("/api/equipment/" + equipmentId + "/readings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"%s","expectedVersion":%d,"readingId":"%s",
                                 "sampledAt":"%s","cumulativeMinutes":%d,"recordedBy":"%s"}
                                """.formatted(requestId, expectedVersion, readingId, sampledAt,
                                cumulativeMinutes, recordedBy)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.certStatus").value("PENDING"))
                .andExpect(jsonPath("$.recordedBy").value(recordedBy));
    }

    private ResultActions certify(String certKey, String certifiedBy, String itemsJson) throws Exception {
        return mockMvc.perform(post("/api/certifications")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"certKey":"%s","certifiedBy":"%s","items":%s}
                        """.formatted(certKey, certifiedBy, itemsJson)));
    }

    private static String item(String equipmentId, String readingId, int revisionNo) {
        return "{\"equipmentId\":\"%s\",\"readingId\":\"%s\",\"revisionNo\":%d}"
                .formatted(equipmentId, readingId, revisionNo);
    }

    // ---------- 双人认证主流程 ----------

    @Test
    void certify_dualPerson_successAndSnapshot() throws Exception {
        register("eq-c1", 100);
        addReading("eq-c1", "c1-a1", 1, "r1", "2026-01-01T10:00:00Z", 80, "alice");

        // PENDING 不参与判定
        mockMvc.perform(get("/api/equipment/eq-c1/status"))
                .andExpect(jsonPath("$.latestCumulativeMinutes").value(0))
                .andExpect(jsonPath("$.runMinutes").value(0))
                .andExpect(jsonPath("$.status").value("OK"));

        // 不同于录入人的认证人认证成功，响应为完整重算结果
        certify("ck-1", "bob", "[" + item("eq-c1", "r1", 1) + "]")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.certKey").value("ck-1"))
                .andExpect(jsonPath("$.certifiedBy").value("bob"))
                .andExpect(jsonPath("$.certifiedCount").value(1))
                .andExpect(jsonPath("$.results", hasSize(1)))
                .andExpect(jsonPath("$.results[0].equipmentId").value("eq-c1"))
                .andExpect(jsonPath("$.results[0].certifiedReadings[0].readingId").value("r1"))
                .andExpect(jsonPath("$.results[0].certifiedReadings[0].revisionNo").value(1))
                .andExpect(jsonPath("$.results[0].latestCumulativeMinutes").value(80))
                .andExpect(jsonPath("$.results[0].runMinutes").value(80))
                .andExpect(jsonPath("$.results[0].status").value("OK"))
                .andExpect(jsonPath("$.results[0].equipmentVersion").value(3));

        // 认证后参与判定
        mockMvc.perform(get("/api/equipment/eq-c1/status"))
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.latestCumulativeMinutes").value(80))
                .andExpect(jsonPath("$.runMinutes").value(80));

        // 读数状态与认证快照可查询
        mockMvc.perform(get("/api/equipment/eq-c1/readings"))
                .andExpect(jsonPath("$[0].certStatus").value("CERTIFIED"))
                .andExpect(jsonPath("$[0].certifiedBy").value("bob"));
        mockMvc.perform(get("/api/equipment/eq-c1/certifications"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].certKey").value("ck-1"))
                .andExpect(jsonPath("$[0].readingId").value("r1"))
                .andExpect(jsonPath("$[0].revisionNo").value(1))
                .andExpect(jsonPath("$[0].recordedBy").value("alice"))
                .andExpect(jsonPath("$[0].certifiedBy").value("bob"))
                .andExpect(jsonPath("$[0].cumulativeMinutes").value(80))
                .andExpect(jsonPath("$[0].batchSeq").value(1))
                .andExpect(jsonPath("$[0].latestCumulativeMinutes").value(80))
                .andExpect(jsonPath("$[0].runMinutes").value(80))
                .andExpect(jsonPath("$[0].dueStatus").value("OK"));
    }

    @Test
    void certify_recorderCannotCertifyOwn_forbidden() throws Exception {
        register("eq-c2", 100);
        addReading("eq-c2", "c2-a1", 1, "r1", "2026-01-01T10:00:00Z", 50, "alice");

        // 录入人认证自己的读数 → 403
        certify("ck-2", "alice", "[" + item("eq-c2", "r1", 1) + "]")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CERTIFIER_IS_RECORDER"));

        // 403 不改变状态：读数仍 PENDING，累计工时不变
        mockMvc.perform(get("/api/equipment/eq-c2/readings"))
                .andExpect(jsonPath("$[0].certStatus").value("PENDING"));
        mockMvc.perform(get("/api/equipment/eq-c2/status"))
                .andExpect(jsonPath("$.latestCumulativeMinutes").value(0))
                .andExpect(jsonPath("$.version").value(2));

        // 另一认证人可认证（403 失败不占键，同 certKey 可重试）
        certify("ck-2", "bob", "[" + item("eq-c2", "r1", 1) + "]")
                .andExpect(status().isCreated());
    }

    // ---------- 认证校验：版本 / 退役 / 已认证 ----------

    @Test
    void certify_versionMismatchAndRetired_unprocessable() throws Exception {
        register("eq-c3", 100);
        addReading("eq-c3", "c3-a1", 1, "r1", "2026-01-01T10:00:00Z", 50, "alice");

        // 读数版本不一致 → 422
        certify("ck-3", "bob", "[" + item("eq-c3", "r1", 7) + "]")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("READING_VERSION_CONFLICT"));

        // 读数不存在 → 404
        certify("ck-3b", "bob", "[" + item("eq-c3", "nope", 1) + "]")
                .andExpect(status().isNotFound());

        // 设备退役后认证 → 422
        mockMvc.perform(post("/api/equipment/eq-c3/retire")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"retire-1","expectedVersion":2}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(3));
        mockMvc.perform(get("/api/equipment/eq-c3/status"))
                .andExpect(jsonPath("$.retired").value(true));
        certify("ck-3c", "bob", "[" + item("eq-c3", "r1", 1) + "]")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("EQUIPMENT_RETIRED"));

        // 重复退役 → 409
        mockMvc.perform(post("/api/equipment/eq-c3/retire")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"retire-2","expectedVersion":3}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EQUIPMENT_ALREADY_RETIRED"));
    }

    @Test
    void certify_alreadyCertified_unprocessable() throws Exception {
        register("eq-c4", 100);
        addReading("eq-c4", "c4-a1", 1, "r1", "2026-01-01T10:00:00Z", 50, "alice");
        certify("ck-4", "bob", "[" + item("eq-c4", "r1", 1) + "]")
                .andExpect(status().isCreated());

        // 已认证读数不可重复认证（另一 certKey、另一认证人）→ 422
        certify("ck-4b", "carol", "[" + item("eq-c4", "r1", 1) + "]")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("READING_ALREADY_CERTIFIED"));
    }

    // ---------- 批量认证：跨设备、序列单调、整批回滚 ----------

    @Test
    void certifyBatch_multiEquipment_success() throws Exception {
        register("eq-b1", 1000);
        register("eq-b2", 1000);
        addReading("eq-b1", "b1-a1", 1, "r1", "2026-01-01T10:00:00Z", 100, "alice");
        addReading("eq-b1", "b1-a2", 2, "r2", "2026-01-01T11:00:00Z", 200, "alice");
        addReading("eq-b2", "b2-a1", 1, "r9", "2026-01-01T09:00:00Z", 30, "carol");

        // 一个批次跨设备认证多条：按设备和读数时刻排序验证
        certify("ck-b1", "bob",
                        "[" + item("eq-b2", "r9", 1) + ","
                                + item("eq-b1", "r2", 1) + ","
                                + item("eq-b1", "r1", 1) + "]")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.certifiedCount").value(3))
                .andExpect(jsonPath("$.results", hasSize(2)))
                .andExpect(jsonPath("$.results[0].equipmentId").value("eq-b1"))
                .andExpect(jsonPath("$.results[0].certifiedReadings", hasSize(2)))
                .andExpect(jsonPath("$.results[0].certifiedReadings[0].readingId").value("r1"))
                .andExpect(jsonPath("$.results[0].certifiedReadings[1].readingId").value("r2"))
                .andExpect(jsonPath("$.results[0].latestCumulativeMinutes").value(200))
                .andExpect(jsonPath("$.results[1].equipmentId").value("eq-b2"))
                .andExpect(jsonPath("$.results[1].latestCumulativeMinutes").value(30));

        // 批次内序号按设备、采样时刻规范化排序
        mockMvc.perform(get("/api/equipment/eq-b1/certifications"))
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].batchSeq").value(1))
                .andExpect(jsonPath("$[1].batchSeq").value(2));
        mockMvc.perform(get("/api/equipment/eq-b2/certifications"))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].batchSeq").value(3));
    }

    @Test
    void certifyBatch_sequenceRegression_rollsBackEntireBatch() throws Exception {
        register("eq-rb", 1000);
        addReading("eq-rb", "rb-a1", 1, "r1", "2026-01-01T10:00:00Z", 100, "alice");
        addReading("eq-rb", "rb-a2", 2, "r2", "2026-01-01T11:00:00Z", 200, "alice");
        // 草稿读数 r3=150：与 r2=200 合并后最终序列倒退
        addReading("eq-rb", "rb-a3", 3, "r3", "2026-01-01T12:00:00Z", 150, "alice");

        // 整批认证：100,200,150 出现倒退 → 422，整批回滚
        certify("ck-rb-1", "bob",
                        "[" + item("eq-rb", "r1", 1) + ","
                                + item("eq-rb", "r2", 1) + ","
                                + item("eq-rb", "r3", 1) + "]")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CERTIFICATION_ORDER_VIOLATION"));

        // 整批回滚：全部仍 PENDING、无快照、版本与累计工时不变
        mockMvc.perform(get("/api/equipment/eq-rb/readings"))
                .andExpect(jsonPath("$[0].certStatus").value("PENDING"))
                .andExpect(jsonPath("$[1].certStatus").value("PENDING"))
                .andExpect(jsonPath("$[2].certStatus").value("PENDING"));
        mockMvc.perform(get("/api/equipment/eq-rb/certifications"))
                .andExpect(jsonPath("$", hasSize(0)));
        mockMvc.perform(get("/api/equipment/eq-rb/status"))
                .andExpect(jsonPath("$.version").value(4))
                .andExpect(jsonPath("$.latestCumulativeMinutes").value(0));

        // 子集 100,200 单调 → 成功；r3=150 小于上一个已认证读数（200）→ 仍 422
        certify("ck-rb-2", "bob",
                        "[" + item("eq-rb", "r1", 1) + "," + item("eq-rb", "r2", 1) + "]")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.results[0].latestCumulativeMinutes").value(200));
        certify("ck-rb-3", "bob", "[" + item("eq-rb", "r3", 1) + "]")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CERTIFICATION_ORDER_VIOLATION"));
        mockMvc.perform(get("/api/equipment/eq-rb/status"))
                .andExpect(jsonPath("$.latestCumulativeMinutes").value(200));
    }

    @Test
    void certify_belowLastCertified_rejectedAndStateUnchanged() throws Exception {
        register("eq-bl", 100);
        addReading("eq-bl", "bl-a1", 1, "p1", "2026-01-01T10:00:00Z", 150, "alice");
        certify("ck-bl-1", "bob", "[" + item("eq-bl", "p1", 1) + "]")
                .andExpect(status().isCreated());
        // 已触发 DUE（周期 100）
        mockMvc.perform(get("/api/equipment/eq-bl/status"))
                .andExpect(jsonPath("$.status").value("DUE"))
                .andExpect(jsonPath("$.latestCumulativeMinutes").value(150));

        // 草稿 p2=120 小于上一个已认证读数（150）→ 认证 422
        addReading("eq-bl", "bl-a2", 3, "p2", "2026-01-01T11:00:00Z", 120, "alice");
        certify("ck-bl-2", "bob", "[" + item("eq-bl", "p2", 1) + "]")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CERTIFICATION_ORDER_VIOLATION"));

        // 认证被拒绝不改变累计工时和已触发保养状态
        mockMvc.perform(get("/api/equipment/eq-bl/status"))
                .andExpect(jsonPath("$.status").value("DUE"))
                .andExpect(jsonPath("$.latestCumulativeMinutes").value(150))
                .andExpect(jsonPath("$.version").value(4));
        mockMvc.perform(get("/api/equipment/eq-bl/certifications"))
                .andExpect(jsonPath("$", hasSize(1)));
    }

    // ---------- 修订链：已认证读数沿修订链创建新版本并重新认证 ----------

    @Test
    void certify_revisionChain_reCertification() throws Exception {
        register("eq-rc", 1000);
        addReading("eq-rc", "rc-a1", 1, "r1", "2026-01-01T10:00:00Z", 100, "alice");
        addReading("eq-rc", "rc-a2", 2, "r2", "2026-01-01T11:00:00Z", 200, "alice");
        certify("ck-rc-1", "bob",
                        "[" + item("eq-rc", "r1", 1) + "," + item("eq-rc", "r2", 1) + "]")
                .andExpect(status().isCreated());
        mockMvc.perform(get("/api/equipment/eq-rc/status"))
                .andExpect(jsonPath("$.latestCumulativeMinutes").value(200))
                .andExpect(jsonPath("$.version").value(4));

        // 已认证读数不可直接修改：沿修订链创建新版本（r2 修订为 150），新版本回到 PENDING
        mockMvc.perform(post("/api/equipment/eq-rc/readings/r2/revisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"rc-rev","expectedVersion":4,"cumulativeMinutes":150,"recordedBy":"alice"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.revisionNo").value(2))
                .andExpect(jsonPath("$.certStatus").value("PENDING"));

        // 修订后 PENDING 不参与判定：最新已认证回退到 r1=100；原快照不变
        mockMvc.perform(get("/api/equipment/eq-rc/status"))
                .andExpect(jsonPath("$.latestCumulativeMinutes").value(100));
        mockMvc.perform(get("/api/equipment/eq-rc/certifications"))
                .andExpect(jsonPath("$", hasSize(2)));

        // 旧版本（revisionNo=1）不可再认证 → 422 版本不一致
        certify("ck-rc-2", "bob", "[" + item("eq-rc", "r2", 1) + "]")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("READING_VERSION_CONFLICT"));

        // 新版本的录入人不得认证 → 403；另一认证人认证成功
        certify("ck-rc-3", "alice", "[" + item("eq-rc", "r2", 2) + "]")
                .andExpect(status().isForbidden());
        certify("ck-rc-3", "bob", "[" + item("eq-rc", "r2", 2) + "]")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.results[0].latestCumulativeMinutes").value(150));

        // 快照追加新版本记录（不可变，历史保留）
        mockMvc.perform(get("/api/equipment/eq-rc/certifications"))
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[2].readingId").value("r2"))
                .andExpect(jsonPath("$[2].revisionNo").value(2))
                .andExpect(jsonPath("$[2].cumulativeMinutes").value(150))
                .andExpect(jsonPath("$[2].latestCumulativeMinutes").value(150));
        mockMvc.perform(get("/api/equipment/eq-rc/status"))
                .andExpect(jsonPath("$.latestCumulativeMinutes").value(150));
    }

    // ---------- certKey 幂等 ----------

    @Test
    void certify_certKeyReplayAndMismatch() throws Exception {
        register("eq-k1", 1000);
        addReading("eq-k1", "k1-a1", 1, "r1", "2026-01-01T10:00:00Z", 100, "alice");
        addReading("eq-k1", "k1-a2", 2, "r2", "2026-01-01T11:00:00Z", 200, "alice");

        MvcResult first = certify("ck-k1", "bob",
                "[" + item("eq-k1", "r1", 1) + "," + item("eq-k1", "r2", 1) + "]").andReturn();
        assert first.getResponse().getStatus() == 201;

        // 同键同参（条目顺序不同，规范化后指纹相同）：重放完整重算结果，业务效果不重复
        MvcResult replay = certify("ck-k1", "bob",
                "[" + item("eq-k1", "r2", 1) + "," + item("eq-k1", "r1", 1) + "]").andReturn();
        assert replay.getResponse().getStatus() == 201;
        assert replay.getResponse().getContentAsString()
                .equals(first.getResponse().getContentAsString());
        mockMvc.perform(get("/api/equipment/eq-k1/certifications"))
                .andExpect(jsonPath("$", hasSize(2)));
        mockMvc.perform(get("/api/equipment/eq-k1/status"))
                .andExpect(jsonPath("$.version").value(4));

        // 同键异参（认证人不同 / 读数版本不同）→ 409
        certify("ck-k1", "carol", "[" + item("eq-k1", "r1", 1) + "]")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));

        // 失败不占键：422 后同 certKey 修正参数可成功
        register("eq-k2", 1000);
        addReading("eq-k2", "k2-a1", 1, "r1", "2026-01-01T10:00:00Z", 100, "alice");
        certify("ck-k2", "bob", "[" + item("eq-k2", "r1", 5) + "]")
                .andExpect(status().isUnprocessableEntity());
        certify("ck-k2", "bob", "[" + item("eq-k2", "r1", 1) + "]")
                .andExpect(status().isCreated());
    }

    // ---------- 批次参数校验 ----------

    @Test
    void certify_validationBranches() throws Exception {
        register("eq-v1", 1000);
        addReading("eq-v1", "v1-a1", 1, "r1", "2026-01-01T10:00:00Z", 100, "alice");

        // 空批次 → 400
        mockMvc.perform(post("/api/certifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"certKey":"ck-v1","certifiedBy":"bob","items":[]}
                                """))
                .andExpect(status().isBadRequest());
        // 批次内同一读数重复 → 422
        certify("ck-v2", "bob", "[" + item("eq-v1", "r1", 1) + "," + item("eq-v1", "r1", 1) + "]")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CERT_BATCH_DUPLICATE"));
        // 设备不存在 → 404
        certify("ck-v3", "bob", "[" + item("eq-nope", "r1", 1) + "]")
                .andExpect(status().isNotFound());
    }
}
