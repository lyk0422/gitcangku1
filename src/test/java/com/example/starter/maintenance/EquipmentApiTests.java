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
 * 读数需经双人认证（CERTIFIED）后才参与累计工时与保养阈值判定。
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
        jdbc.update("DELETE FROM reading_certification");
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
                .andExpect(jsonPath("$.retired").value(false));
    }

    private void addReading(String equipmentId, String requestId, long expectedVersion,
                            String readingId, String sampledAt, long cumulativeMinutes,
                            int expectedStatus) throws Exception {
        mockMvc.perform(post("/api/equipment/" + equipmentId + "/readings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"%s","expectedVersion":%d,"readingId":"%s",
                                 "sampledAt":"%s","cumulativeMinutes":%d,"recordedBy":"recorder-a"}
                                """.formatted(requestId, expectedVersion, readingId, sampledAt,
                                cumulativeMinutes)))
                .andExpect(status().is(expectedStatus));
    }

    private void certify(String requestId, String equipmentId, String readingId,
                         int revisionNo, int expectedStatus) throws Exception {
        mockMvc.perform(post("/api/certifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"%s","certifier":"certifier-b","items":[
                                  {"equipmentId":"%s","readingId":"%s","expectedRevisionNo":%d}]}
                                """.formatted(requestId, equipmentId, readingId, revisionNo)))
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
                .andExpect(jsonPath("$.status").value("OK"));

        // PENDING 读数不参与累计工时与保养判定
        addReading("eq-main", "add-r1", 1, "r1", "2026-01-01T10:00:00Z", 50, 201);
        mockMvc.perform(get("/api/equipment/eq-main/status"))
                .andExpect(jsonPath("$.runMinutes").value(0))
                .andExpect(jsonPath("$.status").value("OK"));

        // 认证后计入累计工时
        certify("cert-r1", "eq-main", "r1", 1, 201);
        mockMvc.perform(get("/api/equipment/eq-main/status"))
                .andExpect(jsonPath("$.runMinutes").value(50))
                .andExpect(jsonPath("$.status").value("OK"));

        // 达到保养周期 → DUE
        addReading("eq-main", "add-r2", 3, "r2", "2026-01-01T11:00:00Z", 130, 201);
        certify("cert-r2", "eq-main", "r2", 1, 201);
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
        certify("cert-r3", "eq-main", "r3", 1, 201);
        mockMvc.perform(get("/api/equipment/eq-main/status"))
                .andExpect(jsonPath("$.runMinutes").value(50))
                .andExpect(jsonPath("$.status").value("OK"));

        // 读数历史：按采样时刻升序，r2 已锚定，三条均已认证
        mockMvc.perform(get("/api/equipment/eq-main/readings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[0].readingId").value("r1"))
                .andExpect(jsonPath("$[0].status").value("CERTIFIED"))
                .andExpect(jsonPath("$[0].recordedBy").value("recorder-a"))
                .andExpect(jsonPath("$[0].certifiedBy").value("certifier-b"))
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
                .andExpect(jsonPath("$[0].cumulativeMinutes").value(130))
                .andExpect(jsonPath("$[0].recordedBy").value("recorder-a"));
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
                                {"requestId":"rev-v1","expectedVersion":1,"cumulativeMinutes":20,
                                 "recordedBy":"recorder-a"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
    }

    // ---------- 单调性与补录 ----------

    @Test
    void addReading_backfillMustSatisfyBothNeighbors() throws Exception {
        register("eq-mono", 1000);
        addReading("eq-mono", "m1", 1, "r1", "2026-01-01T10:00:00Z", 100, 201);
        addReading("eq-mono", "m2", 2, "r3", "2026-01-01T12:00:00Z", 300, 201);

        // 合法补录：100 <= 200 <= 300
        addReading("eq-mono", "m3", 3, "r2", "2026-01-01T11:00:00Z", 200, 201);

        // 补录超过后邻（300）：即使不小于前邻也必须 422，不得仅与最新值比较
        addReading("eq-mono", "m4", 4, "r2x", "2026-01-01T11:30:00Z", 350, 422);
        // 补录低于前邻（100）
        addReading("eq-mono", "m5", 4, "r2y", "2026-01-01T10:30:00Z", 50, 422);
        // 末尾追加低于最新值
        addReading("eq-mono", "m6", 4, "r4", "2026-01-01T13:00:00Z", 250, 422);
        // 边界相等允许（单调不减）
        addReading("eq-mono", "m7", 4, "r4", "2026-01-01T13:00:00Z", 300, 201);

        // 失败分支均未落库：共 4 条读数
        mockMvc.perform(get("/api/equipment/eq-mono/readings"))
                .andExpect(jsonPath("$", hasSize(4)));
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
    void revise_keepsHistoryAndValidatesNeighbors() throws Exception {
        register("eq-rev", 1000);
        addReading("eq-rev", "rv1", 1, "r1", "2026-01-01T10:00:00Z", 100, 201);
        addReading("eq-rev", "rv2", 2, "r2", "2026-01-01T11:00:00Z", 200, 201);

        // 修订只改累计分钟，不改采样时刻；修订号 +1
        mockMvc.perform(post("/api/equipment/eq-rev/readings/r1/revisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"rv3","expectedVersion":3,"cumulativeMinutes":150,
                                 "recordedBy":"recorder-b"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sampledAt").value("2026-01-01T10:00:00Z"))
                .andExpect(jsonPath("$.cumulativeMinutes").value(150))
                .andExpect(jsonPath("$.revisionNo").value(2))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.recordedBy").value("recorder-b"))
                .andExpect(jsonPath("$.equipmentVersion").value(4));

        // 历史保留：修订 1（初始值 100）与修订 2（150）
        mockMvc.perform(get("/api/equipment/eq-rev/readings/r1/revisions"))
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].revisionNo").value(1))
                .andExpect(jsonPath("$[0].cumulativeMinutes").value(100))
                .andExpect(jsonPath("$[1].revisionNo").value(2))
                .andExpect(jsonPath("$[1].cumulativeMinutes").value(150));

        // 修订须同时符合前后相邻值
        mockMvc.perform(post("/api/equipment/eq-rev/readings/r1/revisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"rv4","expectedVersion":4,"cumulativeMinutes":250,
                                 "recordedBy":"recorder-b"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("READING_ORDER_VIOLATION"));
        mockMvc.perform(post("/api/equipment/eq-rev/readings/r2/revisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"rv5","expectedVersion":4,"cumulativeMinutes":50,
                                 "recordedBy":"recorder-b"}
                                """))
                .andExpect(status().isUnprocessableEntity());
    }

    // ---------- 保养锚点规则 ----------

    @Test
    void maintenance_anchorRules() throws Exception {
        register("eq-mnt", 1000);
        addReading("eq-mnt", "a1", 1, "r1", "2026-01-01T10:00:00Z", 100, 201);
        addReading("eq-mnt", "a2", 2, "r2", "2026-01-01T11:00:00Z", 200, 201);

        // 未认证读数不能作为保养锚点 → 422
        mockMvc.perform(post("/api/equipment/eq-mnt/maintenances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"mn-p","expectedVersion":3,"readingId":"r1","anchorRevisionNo":1}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ANCHOR_NOT_CERTIFIED"));

        // 批量认证 r1、r2
        mockMvc.perform(post("/api/certifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"cert-batch","certifier":"certifier-b","items":[
                                  {"equipmentId":"eq-mnt","readingId":"r1","expectedRevisionNo":1},
                                  {"equipmentId":"eq-mnt","readingId":"r2","expectedRevisionNo":1}]}
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
                                {"requestId":"mn-3","expectedVersion":5,"cumulativeMinutes":120,
                                 "recordedBy":"recorder-a"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("READING_ANCHORED"));

        // 非锚点读数可正常修订，新修订版本回到 PENDING 须重新认证
        mockMvc.perform(post("/api/equipment/eq-mnt/readings/r2/revisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"mn-4","expectedVersion":5,"cumulativeMinutes":210,
                                 "recordedBy":"recorder-a"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.revisionNo").value(2))
                .andExpect(jsonPath("$.status").value("PENDING"));

        // 修订后未重新认证不能作为锚点
        mockMvc.perform(post("/api/equipment/eq-mnt/maintenances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"mn-5","expectedVersion":6,"readingId":"r2","anchorRevisionNo":2}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ANCHOR_NOT_CERTIFIED"));

        // 重新认证后以其新修订号完成第二次保养
        certify("cert-r2-v2", "eq-mnt", "r2", 2, 201);
        mockMvc.perform(post("/api/equipment/eq-mnt/maintenances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"mn-6","expectedVersion":7,"readingId":"r2","anchorRevisionNo":2}
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
                 "sampledAt":"2026-01-01T10:00:00Z","cumulativeMinutes":100,"recordedBy":"recorder-a"}
                """);
        assert first.getResponse().getStatus() == 201;

        // 同键同参：重放原成功结果，业务效果不重复
        MvcResult replay = postJson("/api/equipment/eq-idem/readings", """
                {"requestId":"idem-1","expectedVersion":1,"readingId":"r1",
                 "sampledAt":"2026-01-01T10:00:00Z","cumulativeMinutes":100,"recordedBy":"recorder-a"}
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
                                 "sampledAt":"2026-01-01T10:00:00Z","cumulativeMinutes":101,
                                 "recordedBy":"recorder-a"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));

        // 失败不占键：422 后同 requestId 修正参数可成功
        addReading("eq-idem", "idem-2", 2, "r2", "2026-01-01T11:00:00Z", 50, 422);
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
                                {"requestId":"nf-2","expectedVersion":1,"cumulativeMinutes":10,
                                 "recordedBy":"recorder-a"}
                                """))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/equipment/eq-nf/readings/nope/revisions"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/equipment/eq-nf/readings/nope/certification"))
                .andExpect(status().isNotFound());
    }
}
