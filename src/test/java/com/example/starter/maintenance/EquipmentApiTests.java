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
 * 读数默认录入人 "rec"，认证人 "cert"（双人认证）；PENDING 读数不参与累计工时与保养判定。
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
        jdbc.update("DELETE FROM certification_snapshot");
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
                .andExpect(jsonPath("$.version").value(1));
    }

    private void addReading(String equipmentId, String requestId, long expectedVersion,
                            String readingId, String sampledAt, long cumulativeMinutes,
                            int expectedStatus) throws Exception {
        mockMvc.perform(post("/api/equipment/" + equipmentId + "/readings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"%s","expectedVersion":%d,"readingId":"%s",
                                 "sampledAt":"%s","cumulativeMinutes":%d,"recordedBy":"rec"}
                                """.formatted(requestId, expectedVersion, readingId, sampledAt,
                                cumulativeMinutes)))
                .andExpect(status().is(expectedStatus));
    }

    /** 单条认证：认证人 "cert" 与录入人 "rec" 不同。 */
    private void certify(String equipmentId, String certKey, String readingId, int revisionNo,
                         int expectedStatus) throws Exception {
        mockMvc.perform(post("/api/certifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"certKey":"%s","certifiedBy":"cert",
                                 "items":[{"equipmentId":"%s","readingId":"%s","revisionNo":%d}]}
                                """.formatted(certKey, equipmentId, readingId, revisionNo)))
                .andExpect(status().is(expectedStatus));
    }

    // ---------- 主流程：登记 → 读数 → 认证 → DUE → 保养 → OK → 历史 ----------

    @Test
    void mainFlow_statusTransitionsAndHistory() throws Exception {
        register("eq-main", 100);

        // 初始状态：累计工时 0，OK
        mockMvc.perform(get("/api/equipment/eq-main/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.latestCumulativeMinutes").value(0))
                .andExpect(jsonPath("$.runMinutes").value(0))
                .andExpect(jsonPath("$.status").value("OK"))
                .andExpect(jsonPath("$.retired").value(false));

        // PENDING 读数不参与累计工时与保养判定
        addReading("eq-main", "add-r1", 1, "r1", "2026-01-01T10:00:00Z", 50, 201);
        mockMvc.perform(get("/api/equipment/eq-main/status"))
                .andExpect(jsonPath("$.runMinutes").value(0))
                .andExpect(jsonPath("$.status").value("OK"));
        // 认证后参与判定
        certify("eq-main", "cert-1", "r1", 1, 201);
        mockMvc.perform(get("/api/equipment/eq-main/status"))
                .andExpect(jsonPath("$.runMinutes").value(50))
                .andExpect(jsonPath("$.status").value("OK"));

        // 达到保养周期 → DUE
        addReading("eq-main", "add-r2", 3, "r2", "2026-01-01T11:00:00Z", 130, 201);
        certify("eq-main", "cert-2", "r2", 1, 201);
        mockMvc.perform(get("/api/equipment/eq-main/status"))
                .andExpect(jsonPath("$.runMinutes").value(130))
                .andExpect(jsonPath("$.status").value("DUE"));

        // 完成保养：锚点 r2 修订号 1
        mockMvc.perform(post("/api/equipment/eq-main/maintenances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"mnt-1","expectedVersion":5,"readingId":"r2","anchorRevisionNo":1}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.anchorSampledAt").value("2026-01-01T11:00:00Z"))
                .andExpect(jsonPath("$.anchorCumulativeMinutes").value(130))
                .andExpect(jsonPath("$.equipmentVersion").value(6));

        // 保养后本轮运行分钟从锚点重算
        mockMvc.perform(get("/api/equipment/eq-main/status"))
                .andExpect(jsonPath("$.version").value(6))
                .andExpect(jsonPath("$.lastMaintenanceAnchorCumulativeMinutes").value(130))
                .andExpect(jsonPath("$.runMinutes").value(0))
                .andExpect(jsonPath("$.status").value("OK"));

        addReading("eq-main", "add-r3", 6, "r3", "2026-01-01T12:00:00Z", 180, 201);
        certify("eq-main", "cert-3", "r3", 1, 201);
        mockMvc.perform(get("/api/equipment/eq-main/status"))
                .andExpect(jsonPath("$.runMinutes").value(50))
                .andExpect(jsonPath("$.status").value("OK"));

        // 读数历史：按采样时刻升序，r2 已锚定，全部已认证
        mockMvc.perform(get("/api/equipment/eq-main/readings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[0].readingId").value("r1"))
                .andExpect(jsonPath("$[0].certStatus").value("CERTIFIED"))
                .andExpect(jsonPath("$[0].recordedBy").value("rec"))
                .andExpect(jsonPath("$[0].certifiedBy").value("cert"))
                .andExpect(jsonPath("$[1].readingId").value("r2"))
                .andExpect(jsonPath("$[1].anchored").value(true))
                .andExpect(jsonPath("$[2].readingId").value("r3"));

        // 保养历史：锚点快照保留
        mockMvc.perform(get("/api/equipment/eq-main/maintenances"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].readingId").value("r2"))
                .andExpect(jsonPath("$[0].anchorRevisionNo").value(1))
                .andExpect(jsonPath("$[0].anchorCumulativeMinutes").value(130));

        // 修订历史：初始登记为修订 1
        mockMvc.perform(get("/api/equipment/eq-main/readings/r2/revisions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].revisionNo").value(1))
                .andExpect(jsonPath("$[0].cumulativeMinutes").value(130));

        // 认证快照：三条，不可变，含重算结果
        mockMvc.perform(get("/api/equipment/eq-main/certifications"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[0].readingId").value("r1"))
                .andExpect(jsonPath("$[0].certifiedBy").value("cert"))
                .andExpect(jsonPath("$[0].recordedBy").value("rec"))
                .andExpect(jsonPath("$[0].latestCumulativeMinutes").value(50))
                .andExpect(jsonPath("$[1].readingId").value("r2"))
                .andExpect(jsonPath("$[1].dueStatus").value("DUE"))
                .andExpect(jsonPath("$[2].readingId").value("r3"))
                .andExpect(jsonPath("$[2].runMinutes").value(50));
    }

    // ---------- 设备登记 ----------

    @Test
    void register_duplicateEquipment_conflict() throws Exception {
        register("eq-dup", 100);
        mockMvc.perform(post("/api/equipment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"reg-dup-2","equipmentId":"eq-dup","maintenancePeriodMinutes":200}
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
                                {"equipmentId":"eq-bad","maintenancePeriodMinutes":100}
                                """))
                .andExpect(status().isBadRequest());
    }

    // ---------- 版本校验 ----------

    @Test
    void write_versionConflict_returns409() throws Exception {
        register("eq-ver", 100);
        addReading("eq-ver", "add-v1", 99, "r1", "2026-01-01T10:00:00Z", 10, 409);
        // 失败的写操作不改变版本
        mockMvc.perform(get("/api/equipment/eq-ver/status"))
                .andExpect(jsonPath("$.version").value(1));
        addReading("eq-ver", "add-v2", 1, "r1", "2026-01-01T10:00:00Z", 10, 201);
        // 修订同样校验版本
        mockMvc.perform(post("/api/equipment/eq-ver/readings/r1/revisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"rev-v1","expectedVersion":1,"cumulativeMinutes":20,"recordedBy":"rec"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
    }

    // ---------- 单调性：PENDING 为草稿，认证闸门校验已认证序列单调不减 ----------

    @Test
    void certify_monotonicSequenceEnforcedAtCertification() throws Exception {
        register("eq-mono", 1000);
        // PENDING 读数为草稿：录入期不做单调拦截，可含任意非负值与补录
        addReading("eq-mono", "m1", 1, "r1", "2026-01-01T10:00:00Z", 100, 201);
        addReading("eq-mono", "m2", 2, "r3", "2026-01-01T12:00:00Z", 300, 201);
        addReading("eq-mono", "m3", 3, "r2", "2026-01-01T11:00:00Z", 200, 201);
        addReading("eq-mono", "m4", 4, "r4", "2026-01-01T13:00:00Z", 250, 201);
        addReading("eq-mono", "m5", 5, "r0", "2026-01-01T09:00:00Z", 50, 201);

        // 合法序列批量认证（含补录 r0）：50,100,200,300 单调不减
        mockMvc.perform(post("/api/certifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"certKey":"c-mono-1","certifiedBy":"cert",
                                 "items":[{"equipmentId":"eq-mono","readingId":"r0","revisionNo":1},
                                          {"equipmentId":"eq-mono","readingId":"r1","revisionNo":1},
                                          {"equipmentId":"eq-mono","readingId":"r2","revisionNo":1},
                                          {"equipmentId":"eq-mono","readingId":"r3","revisionNo":1}]}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.certifiedCount").value(4));

        // r4=250 小于上一个已认证读数（300）→ 认证 422，累计工时不变
        certify("eq-mono", "c-mono-2", "r4", 1, 422);
        mockMvc.perform(get("/api/equipment/eq-mono/status"))
                .andExpect(jsonPath("$.latestCumulativeMinutes").value(300))
                .andExpect(jsonPath("$.version").value(7));

        // 边界相等允许（单调不减）：修订 r4 到 300 后认证成功
        mockMvc.perform(post("/api/equipment/eq-mono/readings/r4/revisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"m6","expectedVersion":7,"cumulativeMinutes":300,"recordedBy":"rec"}
                                """))
                .andExpect(status().isCreated());
        certify("eq-mono", "c-mono-3", "r4", 2, 201);
        mockMvc.perform(get("/api/equipment/eq-mono/status"))
                .andExpect(jsonPath("$.latestCumulativeMinutes").value(300));

        // 全部读数落库（草稿也算），已认证 5 条
        mockMvc.perform(get("/api/equipment/eq-mono/readings"))
                .andExpect(jsonPath("$", hasSize(5)));
        mockMvc.perform(get("/api/equipment/eq-mono/certifications"))
                .andExpect(jsonPath("$", hasSize(5)));
    }

    @Test
    void addReading_sameTimestampAndDuplicateId_rejected() throws Exception {
        register("eq-same", 1000);
        addReading("eq-same", "s1", 1, "r1", "2026-01-01T10:00:00Z", 100, 201);
        // 同一设备同一时刻仅一条
        addReading("eq-same", "s2", 2, "r2", "2026-01-01T10:00:00Z", 150, 422);
        // readingId 设备内唯一
        addReading("eq-same", "s3", 2, "r1", "2026-01-01T11:00:00Z", 150, 409);
    }

    // ---------- 修订 ----------

    @Test
    void revise_keepsHistoryAndCertificationValidatesSequence() throws Exception {
        register("eq-rev", 1000);
        addReading("eq-rev", "rv1", 1, "r1", "2026-01-01T10:00:00Z", 100, 201);
        addReading("eq-rev", "rv2", 2, "r2", "2026-01-01T11:00:00Z", 200, 201);
        certify("eq-rev", "rv-c1", "r1", 1, 201);
        certify("eq-rev", "rv-c2", "r2", 1, 201);

        // 修订只改累计分钟，不改采样时刻；修订号 +1，新版本回到 PENDING
        mockMvc.perform(post("/api/equipment/eq-rev/readings/r1/revisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"rv3","expectedVersion":5,"cumulativeMinutes":150,"recordedBy":"rec"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sampledAt").value("2026-01-01T10:00:00Z"))
                .andExpect(jsonPath("$.cumulativeMinutes").value(150))
                .andExpect(jsonPath("$.revisionNo").value(2))
                .andExpect(jsonPath("$.certStatus").value("PENDING"))
                .andExpect(jsonPath("$.equipmentVersion").value(6));

        // 历史保留：修订 1（初始值 100）与修订 2（150）
        mockMvc.perform(get("/api/equipment/eq-rev/readings/r1/revisions"))
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].revisionNo").value(1))
                .andExpect(jsonPath("$[0].cumulativeMinutes").value(100))
                .andExpect(jsonPath("$[1].revisionNo").value(2))
                .andExpect(jsonPath("$[1].cumulativeMinutes").value(150));

        // 重新认证 r1 v2=150：已认证序列 150,200 单调 → 成功
        certify("eq-rev", "rv-c3", "r1", 2, 201);

        // 修订 r1 到 250（草稿允许），认证时超过后邻已认证 r2=200 → 422
        mockMvc.perform(post("/api/equipment/eq-rev/readings/r1/revisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"rv4","expectedVersion":7,"cumulativeMinutes":250,"recordedBy":"rec"}
                                """))
                .andExpect(status().isCreated());
        certify("eq-rev", "rv-c4", "r1", 3, 422);

        // 修回 150 并重新认证成功（失败认证不改变状态）
        mockMvc.perform(post("/api/equipment/eq-rev/readings/r1/revisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"rv4b","expectedVersion":8,"cumulativeMinutes":150,"recordedBy":"rec"}
                                """))
                .andExpect(status().isCreated());
        certify("eq-rev", "rv-c4b", "r1", 4, 201);

        // 修订 r2 到 50，认证时小于前邻已认证 r1=150 → 422；累计工时不变
        mockMvc.perform(post("/api/equipment/eq-rev/readings/r2/revisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"rv5","expectedVersion":10,"cumulativeMinutes":50,"recordedBy":"rec"}
                                """))
                .andExpect(status().isCreated());
        certify("eq-rev", "rv-c5", "r2", 2, 422);
        mockMvc.perform(get("/api/equipment/eq-rev/status"))
                .andExpect(jsonPath("$.latestCumulativeMinutes").value(150));
    }

    // ---------- 保养锚点规则 ----------

    @Test
    void maintenance_anchorRules() throws Exception {
        register("eq-mnt", 1000);
        addReading("eq-mnt", "a1", 1, "r1", "2026-01-01T10:00:00Z", 100, 201);
        addReading("eq-mnt", "a2", 2, "r2", "2026-01-01T11:00:00Z", 200, 201);

        // 未认证读数不可作为保养锚点 → 422
        mockMvc.perform(post("/api/equipment/eq-mnt/maintenances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"mn-0","expectedVersion":3,"readingId":"r1","anchorRevisionNo":1}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("READING_NOT_CERTIFIED"));

        // 批次认证 r1、r2
        mockMvc.perform(post("/api/certifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"certKey":"cert-mn","certifiedBy":"cert",
                                 "items":[{"equipmentId":"eq-mnt","readingId":"r1","revisionNo":1},
                                          {"equipmentId":"eq-mnt","readingId":"r2","revisionNo":1}]}
                                """))
                .andExpect(status().isCreated());

        // 锚点读数不存在 → 404
        mockMvc.perform(post("/api/equipment/eq-mnt/maintenances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"mn-x","expectedVersion":4,"readingId":"nope","anchorRevisionNo":1}
                                """))
                .andExpect(status().isNotFound());

        // 锚点修订号与当前修订号不一致 → 409
        mockMvc.perform(post("/api/equipment/eq-mnt/maintenances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"mn-x2","expectedVersion":4,"readingId":"r1","anchorRevisionNo":7}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ANCHOR_REVISION_CONFLICT"));

        // 第一次保养可选任意已认证读数
        mockMvc.perform(post("/api/equipment/eq-mnt/maintenances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"mn-1","expectedVersion":4,"readingId":"r1","anchorRevisionNo":1}
                                """))
                .andExpect(status().isCreated());

        // 锚点时间必须严格晚于上次保养锚点：同读数/更早读数均 422
        mockMvc.perform(post("/api/equipment/eq-mnt/maintenances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"mn-2","expectedVersion":5,"readingId":"r1","anchorRevisionNo":1}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ANCHOR_TIME_NOT_LATER"));

        // 作为历史保养锚点的读数不可修订 → 409
        mockMvc.perform(post("/api/equipment/eq-mnt/readings/r1/revisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"mn-3","expectedVersion":5,"cumulativeMinutes":120,"recordedBy":"rec"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("READING_ANCHORED"));

        // 非锚点读数可正常修订；修订后回到 PENDING，须重新认证才能作为锚点
        mockMvc.perform(post("/api/equipment/eq-mnt/readings/r2/revisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"mn-4","expectedVersion":5,"cumulativeMinutes":210,"recordedBy":"rec"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.revisionNo").value(2))
                .andExpect(jsonPath("$.certStatus").value("PENDING"));
        mockMvc.perform(post("/api/equipment/eq-mnt/maintenances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"mn-4b","expectedVersion":6,"readingId":"r2","anchorRevisionNo":2}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("READING_NOT_CERTIFIED"));
        certify("eq-mnt", "cert-mn-2", "r2", 2, 201);
        mockMvc.perform(post("/api/equipment/eq-mnt/maintenances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"mn-5","expectedVersion":7,"readingId":"r2","anchorRevisionNo":2}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.anchorCumulativeMinutes").value(210));

        // 保养记录保存锚点快照且不允许删除（无删除接口），历史完整
        mockMvc.perform(get("/api/equipment/eq-mnt/maintenances"))
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].anchorCumulativeMinutes").value(100))
                .andExpect(jsonPath("$[1].anchorCumulativeMinutes").value(210));
    }

    // ---------- 幂等 ----------

    @Test
    void idempotency_replayMismatchAndFailureNotOccupying() throws Exception {
        register("eq-idem", 1000);

        MvcResult first = postJson("/api/equipment/eq-idem/readings", """
                {"requestId":"idem-1","expectedVersion":1,"readingId":"r1",
                 "sampledAt":"2026-01-01T10:00:00Z","cumulativeMinutes":100,"recordedBy":"rec"}
                """);
        assert first.getResponse().getStatus() == 201;

        // 同键同参：重放原成功结果，业务效果不重复
        MvcResult replay = postJson("/api/equipment/eq-idem/readings", """
                {"requestId":"idem-1","expectedVersion":1,"readingId":"r1",
                 "sampledAt":"2026-01-01T10:00:00Z","cumulativeMinutes":100,"recordedBy":"rec"}
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
                                 "sampledAt":"2026-01-01T10:00:00Z","cumulativeMinutes":101,"recordedBy":"rec"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));

        // 失败不占键：422（同一采样时刻重复）后同 requestId 修正参数可成功
        addReading("eq-idem", "idem-2", 2, "r2", "2026-01-01T10:00:00Z", 150, 422);
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
        mockMvc.perform(get("/api/equipment/nope/certifications"))
                .andExpect(status().isNotFound());
        addReading("nope", "nf-1", 1, "r1", "2026-01-01T10:00:00Z", 1, 404);

        register("eq-nf", 100);
        mockMvc.perform(post("/api/equipment/eq-nf/readings/nope/revisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"nf-2","expectedVersion":1,"cumulativeMinutes":10,"recordedBy":"rec"}
                                """))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/equipment/eq-nf/readings/nope/revisions"))
                .andExpect(status().isNotFound());
    }
}
