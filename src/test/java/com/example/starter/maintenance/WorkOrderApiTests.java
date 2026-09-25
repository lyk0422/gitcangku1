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
 * 保养工单 API 主流程与失败分支测试（真实 H2 内存库，MySQL 兼容模式）：
 * 覆盖认证基线、建单冻结、登记窗口、批量预校验回滚、关闭快照不可变、取消/终止边界与幂等。
 */
@SpringBootTest
@AutoConfigureMockMvc
class WorkOrderApiTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM work_order_idempotency");
        jdbc.update("DELETE FROM maintenance_snapshot");
        jdbc.update("DELETE FROM work_order");
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

    private void addReading(String equipmentId, String requestId, long expectedVersion,
                            String readingId, String sampledAt, long minutes) throws Exception {
        mockMvc.perform(post("/api/equipment/" + equipmentId + "/readings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"%s","expectedVersion":%d,"readingId":"%s",
                                 "sampledAt":"%s","cumulativeMinutes":%d}
                                """.formatted(requestId, expectedVersion, readingId, sampledAt, minutes)))
                .andExpect(status().isCreated());
    }

    private void certify(String equipmentId, String requestId, long expectedVersion,
                         String readingId) throws Exception {
        mockMvc.perform(post("/api/equipment/" + equipmentId + "/readings/" + readingId + "/certification")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"%s","expectedVersion":%d}
                                """.formatted(requestId, expectedVersion)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.certified").value(true));
    }

    /** 登记设备 + 基线读数 r1(10:00=100) + 认证，设备版本推进到 3。 */
    private void setupCertifiedBaseline(String equipmentId) throws Exception {
        register(equipmentId, 10000);
        addReading(equipmentId, "add-" + equipmentId, 1, "r1", "2026-01-01T10:00:00Z", 100);
        certify(equipmentId, "cert-" + equipmentId, 2, "r1");
    }

    private void createWorkOrder(String equipmentId, String key, String workOrderId,
                                 String windowStart, String windowEnd) throws Exception {
        mockMvc.perform(post("/api/equipment/" + equipmentId + "/work-orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workOrderKey":"%s","expectedVersion":3,"workOrderId":"%s",
                                 "baselineReadingId":"r1","windowStart":"%s","windowEnd":"%s"}
                                """.formatted(key, workOrderId, windowStart, windowEnd)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.baselineReadingId").value("r1"))
                .andExpect(jsonPath("$.baselineRevisionNo").value(1))
                .andExpect(jsonPath("$.baselineCumulativeMinutes").value(100))
                .andExpect(jsonPath("$.windowStart").value(windowStart))
                .andExpect(jsonPath("$.windowEnd").value(windowEnd))
                .andExpect(jsonPath("$.lastValidReadingId").value("r1"))
                .andExpect(jsonPath("$.lastValidCumulativeMinutes").value(100))
                .andExpect(jsonPath("$.equipmentVersion").value(4));
    }

    // ---------- 认证基线 ----------

    @Test
    void certify_marksSingleCurrentCertifiedReading() throws Exception {
        register("eq-cert", 10000);
        addReading("eq-cert", "c1", 1, "r1", "2026-01-01T10:00:00Z", 100);
        addReading("eq-cert", "c2", 2, "r2", "2026-01-01T11:00:00Z", 200);
        certify("eq-cert", "cert-r2", 3, "r2");
        // 同设备同时仅一个已认证读数：认证 r1 后 r2 撤销
        certify("eq-cert", "cert-r1", 4, "r1");
        mockMvc.perform(get("/api/equipment/eq-cert/readings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].readingId").value("r1"))
                .andExpect(jsonPath("$[0].certified").value(true))
                .andExpect(jsonPath("$[1].readingId").value("r2"))
                .andExpect(jsonPath("$[1].certified").value(false));
    }

    // ---------- 建单主流程与冻结 ----------

    @Test
    void createWorkOrder_freezesBaselineAndWindow() throws Exception {
        setupCertifiedBaseline("eq-wo");
        createWorkOrder("eq-wo", "key-create", "wo-1",
                "2026-01-01T12:00:00Z", "2026-01-01T18:00:00Z");

        // 查询工单基线与窗口
        mockMvc.perform(get("/api/equipment/eq-wo/work-orders/wo-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andExpect(jsonPath("$.baselineSampledAt").value("2026-01-01T10:00:00Z"))
                .andExpect(jsonPath("$.windowStart").value("2026-01-01T12:00:00Z"))
                .andExpect(jsonPath("$.windowEnd").value("2026-01-01T18:00:00Z"));
    }

    @Test
    void createWorkOrder_invalidWindowAndBaselineRules() throws Exception {
        register("eq-bad", 10000);
        addReading("eq-bad", "b1", 1, "r1", "2026-01-01T10:00:00Z", 100);

        // 窗口结束不晚于开始 → 422
        mockMvc.perform(post("/api/equipment/eq-bad/work-orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workOrderKey":"k-bad-win","expectedVersion":2,"workOrderId":"w",
                                 "baselineReadingId":"r1",
                                 "windowStart":"2026-01-01T18:00:00Z",
                                 "windowEnd":"2026-01-01T12:00:00Z"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("WINDOW_INVALID"));

        // 基线读数未认证 → 422
        mockMvc.perform(post("/api/equipment/eq-bad/work-orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workOrderKey":"k-bad-base","expectedVersion":2,"workOrderId":"w",
                                 "baselineReadingId":"r1",
                                 "windowStart":"2026-01-01T12:00:00Z",
                                 "windowEnd":"2026-01-01T18:00:00Z"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BASELINE_NOT_CERTIFIED"));

        // 基线读数不存在 → 404
        mockMvc.perform(post("/api/equipment/eq-bad/work-orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workOrderKey":"k-bad-miss","expectedVersion":2,"workOrderId":"w",
                                 "baselineReadingId":"nope",
                                 "windowStart":"2026-01-01T12:00:00Z",
                                 "windowEnd":"2026-01-01T18:00:00Z"}
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("READING_NOT_FOUND"));

        // 全部失败未占键、未建工单、版本不变
        mockMvc.perform(get("/api/equipment/eq-bad/work-orders"))
                .andExpect(jsonPath("$", hasSize(0)));
        mockMvc.perform(get("/api/equipment/eq-bad/status"))
                .andExpect(jsonPath("$.version").value(2));
    }

    @Test
    void createWorkOrder_versionConflictAndOpenUniqueness() throws Exception {
        setupCertifiedBaseline("eq-open");
        createWorkOrder("eq-open", "k-o1", "wo-1",
                "2026-01-01T12:00:00Z", "2026-01-01T18:00:00Z");

        // 设备版本失配 → 409
        mockMvc.perform(post("/api/equipment/eq-open/work-orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workOrderKey":"k-o2","expectedVersion":3,"workOrderId":"wo-2",
                                 "baselineReadingId":"r1",
                                 "windowStart":"2026-01-01T12:00:00Z",
                                 "windowEnd":"2026-01-01T18:00:00Z"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));

        // 存在开放工单 → 409
        mockMvc.perform(post("/api/equipment/eq-open/work-orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workOrderKey":"k-o3","expectedVersion":4,"workOrderId":"wo-2",
                                 "baselineReadingId":"r1",
                                 "windowStart":"2026-01-01T12:00:00Z",
                                 "windowEnd":"2026-01-01T18:00:00Z"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WORK_ORDER_OPEN"));

        // 同标识工单重复 → 409
        mockMvc.perform(post("/api/equipment/eq-open/work-orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workOrderKey":"k-o4","expectedVersion":4,"workOrderId":"wo-1",
                                 "baselineReadingId":"r1",
                                 "windowStart":"2026-01-01T12:00:00Z",
                                 "windowEnd":"2026-01-01T18:00:00Z"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WORK_ORDER_EXISTS"));
    }

    // ---------- 开始 + 批量登记主流程 ----------

    @Test
    void startAndBatchRegister_windowAndBaselineEnforced() throws Exception {
        setupCertifiedBaseline("eq-flow");
        createWorkOrder("eq-flow", "k-f-create", "wo-1",
                "2026-01-01T12:00:00Z", "2026-01-01T18:00:00Z");

        // 开始：工单版本 1→2，设备版本 4→5
        mockMvc.perform(post("/api/equipment/eq-flow/work-orders/wo-1/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workOrderKey":"k-f-start","expectedVersion":4,"workOrderVersion":1}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.equipmentVersion").value(5));

        // 批量登记窗口内两条：13:00=200、14:00=260
        mockMvc.perform(post("/api/equipment/eq-flow/work-orders/wo-1/readings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workOrderKey":"k-f-reg","expectedVersion":5,"workOrderVersion":2,
                                 "readings":[
                                   {"readingId":"w-r1","sampledAt":"2026-01-01T13:00:00Z","cumulativeMinutes":200},
                                   {"readingId":"w-r2","sampledAt":"2026-01-01T14:00:00Z","cumulativeMinutes":260}
                                 ]}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.acceptedCount").value(2))
                .andExpect(jsonPath("$.readings", hasSize(2)))
                .andExpect(jsonPath("$.readings[0].readingId").value("w-r1"))
                .andExpect(jsonPath("$.readings[1].readingId").value("w-r2"))
                .andExpect(jsonPath("$.equipmentVersion").value(6))
                .andExpect(jsonPath("$.workOrderVersion").value(2));

        // 工单最近有效读数更新为批次最晚一条
        mockMvc.perform(get("/api/equipment/eq-flow/work-orders/wo-1"))
                .andExpect(jsonPath("$.lastValidReadingId").value("w-r2"))
                .andExpect(jsonPath("$.lastValidCumulativeMinutes").value(260))
                .andExpect(jsonPath("$.lastValidRevisionNo").value(1));

        // 读数诊断：全部 accepted
        mockMvc.perform(get("/api/equipment/eq-flow/work-orders/wo-1/reading-diagnostics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].accepted").value(true));
    }

    @Test
    void batchRegister_anyViolationRollsBackEverything() throws Exception {
        setupCertifiedBaseline("eq-roll");
        createWorkOrder("eq-roll", "k-r-create", "wo-1",
                "2026-01-01T12:00:00Z", "2026-01-01T18:00:00Z");
        mockMvc.perform(post("/api/equipment/eq-roll/work-orders/wo-1/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workOrderKey":"k-r-start","expectedVersion":4,"workOrderVersion":1}
                                """))
                .andExpect(status().isCreated());

        // 第二条越窗（19:00 不在 [12:00,18:00)）→ 422，全部回滚
        mockMvc.perform(post("/api/equipment/eq-roll/work-orders/wo-1/readings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workOrderKey":"k-r-bad1","expectedVersion":5,"workOrderVersion":2,
                                 "readings":[
                                   {"readingId":"bad-a","sampledAt":"2026-01-01T13:00:00Z","cumulativeMinutes":200},
                                   {"readingId":"bad-b","sampledAt":"2026-01-01T19:00:00Z","cumulativeMinutes":300}
                                 ]}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("OUT_OF_WINDOW"));

        // 低于基线（100）→ 422
        mockMvc.perform(post("/api/equipment/eq-roll/work-orders/wo-1/readings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workOrderKey":"k-r-bad2","expectedVersion":5,"workOrderVersion":2,
                                 "readings":[
                                   {"readingId":"bad-c","sampledAt":"2026-01-01T13:00:00Z","cumulativeMinutes":90}
                                 ]}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BELOW_BASELINE"));

        // 倒退：批次内合并最终序列 13:00=300 后 14:00=250 → 422
        mockMvc.perform(post("/api/equipment/eq-roll/work-orders/wo-1/readings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workOrderKey":"k-r-bad3","expectedVersion":5,"workOrderVersion":2,
                                 "readings":[
                                   {"readingId":"bad-d","sampledAt":"2026-01-01T13:00:00Z","cumulativeMinutes":300},
                                   {"readingId":"bad-e","sampledAt":"2026-01-01T14:00:00Z","cumulativeMinutes":250}
                                 ]}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("READING_ORDER_VIOLATION"));

        // 批次内重复标识 / 重复时刻 → 422
        mockMvc.perform(post("/api/equipment/eq-roll/work-orders/wo-1/readings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workOrderKey":"k-r-bad4","expectedVersion":5,"workOrderVersion":2,
                                 "readings":[
                                   {"readingId":"dup","sampledAt":"2026-01-01T13:00:00Z","cumulativeMinutes":200},
                                   {"readingId":"dup","sampledAt":"2026-01-01T14:00:00Z","cumulativeMinutes":260}
                                 ]}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("READING_ID_DUPLICATE"));

        // 设备/工单版本失配 → 422
        mockMvc.perform(post("/api/equipment/eq-roll/work-orders/wo-1/readings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workOrderKey":"k-r-bad5","expectedVersion":99,"workOrderVersion":2,
                                 "readings":[
                                   {"readingId":"bad-f","sampledAt":"2026-01-01T13:00:00Z","cumulativeMinutes":200}
                                 ]}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("WORK_ORDER_VERSION_CONFLICT"));

        // 回滚断言：无读数入库、设备版本仍为 5、工单仍 IN_PROGRESS 版本 2、失败未占键
        mockMvc.perform(get("/api/equipment/eq-roll/readings"))
                .andExpect(jsonPath("$", hasSize(1)));
        mockMvc.perform(get("/api/equipment/eq-roll/status"))
                .andExpect(jsonPath("$.version").value(5));
        mockMvc.perform(get("/api/equipment/eq-roll/work-orders/wo-1"))
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.lastValidReadingId").value("r1"));

        // 失败不占键：同 key 修正为合法批次可成功
        mockMvc.perform(post("/api/equipment/eq-roll/work-orders/wo-1/readings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workOrderKey":"k-r-bad1","expectedVersion":5,"workOrderVersion":2,
                                 "readings":[
                                   {"readingId":"ok-1","sampledAt":"2026-01-01T13:00:00Z","cumulativeMinutes":200}
                                 ]}
                                """))
                .andExpect(status().isCreated());
    }

    @Test
    void normalReadingDuringActiveWorkOrder_mustRespectWindowAndBaseline() throws Exception {
        setupCertifiedBaseline("eq-lock");
        createWorkOrder("eq-lock", "k-l-create", "wo-1",
                "2026-01-01T12:00:00Z", "2026-01-01T18:00:00Z");
        mockMvc.perform(post("/api/equipment/eq-lock/work-orders/wo-1/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workOrderKey":"k-l-start","expectedVersion":4,"workOrderVersion":1}
                                """))
                .andExpect(status().isCreated());

        // 普通读数端点在工单进行期间同样受限：越窗 422
        postJson("/api/equipment/eq-lock/readings", """
                {"requestId":"l-out","expectedVersion":5,"readingId":"x1",
                 "sampledAt":"2026-01-01T20:00:00Z","cumulativeMinutes":300}
                """).getResponse().getStatus();
        mockMvc.perform(post("/api/equipment/eq-lock/readings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"l-out","expectedVersion":5,"readingId":"x1",
                                 "sampledAt":"2026-01-01T20:00:00Z","cumulativeMinutes":300}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("OUT_OF_WINDOW"));

        // 低于基线 422
        mockMvc.perform(post("/api/equipment/eq-lock/readings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"l-low","expectedVersion":5,"readingId":"x2",
                                 "sampledAt":"2026-01-01T13:00:00Z","cumulativeMinutes":80}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BELOW_BASELINE"));
    }

    // ---------- 关闭快照 ----------

    @Test
    void close_writesImmutableSnapshot() throws Exception {
        setupCertifiedBaseline("eq-close");
        createWorkOrder("eq-close", "k-c-create", "wo-1",
                "2026-01-01T12:00:00Z", "2026-01-01T18:00:00Z");
        mockMvc.perform(post("/api/equipment/eq-close/work-orders/wo-1/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workOrderKey":"k-c-start","expectedVersion":4,"workOrderVersion":1}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/equipment/eq-close/work-orders/wo-1/readings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workOrderKey":"k-c-reg","expectedVersion":5,"workOrderVersion":2,
                                 "readings":[
                                   {"readingId":"c-r1","sampledAt":"2026-01-01T13:00:00Z","cumulativeMinutes":200}
                                 ]}
                                """))
                .andExpect(status().isCreated());

        // 关闭：工单版本 2→3，设备版本 6→7，快照最后有效读数为 c-r1
        MvcResult closed = postJson("/api/equipment/eq-close/work-orders/wo-1/close", """
                {"workOrderKey":"k-c-close","expectedVersion":6,"workOrderVersion":2}
                """);
        org.junit.jupiter.api.Assertions.assertEquals(201, closed.getResponse().getStatus());
        mockMvc.perform(get("/api/equipment/eq-close/work-orders/wo-1/snapshot"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workOrderId").value("wo-1"))
                .andExpect(jsonPath("$.baselineReadingId").value("r1"))
                .andExpect(jsonPath("$.baselineCumulativeMinutes").value(100))
                .andExpect(jsonPath("$.lastValidReadingId").value("c-r1"))
                .andExpect(jsonPath("$.lastValidCumulativeMinutes").value(200))
                .andExpect(jsonPath("$.closedAt").exists());

        // 关闭后的读数新增（此时无开放工单，普通登记不受窗口限制）不改写快照
        addReading("eq-close", "k-c-after", 7, "c-r2", "2026-01-02T09:00:00Z", 500);
        // 关闭后的读数修订不改写快照
        mockMvc.perform(post("/api/equipment/eq-close/readings/c-r1/revisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"k-c-rev","expectedVersion":8,"cumulativeMinutes":450}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(get("/api/equipment/eq-close/work-orders/wo-1/snapshot"))
                .andExpect(jsonPath("$.lastValidReadingId").value("c-r1"))
                .andExpect(jsonPath("$.lastValidCumulativeMinutes").value(200));

        // 重复关闭 → 409
        mockMvc.perform(post("/api/equipment/eq-close/work-orders/wo-1/close")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workOrderKey":"k-c-close2","expectedVersion":9,"workOrderVersion":3}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WORK_ORDER_ALREADY_CLOSED"));

        // 关闭后可对同设备再建工单
        certify("eq-close", "k-c-cert2", 9, "c-r2");
        mockMvc.perform(post("/api/equipment/eq-close/work-orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workOrderKey":"k-c-create2","expectedVersion":10,"workOrderId":"wo-2",
                                 "baselineReadingId":"c-r2",
                                 "windowStart":"2026-01-03T00:00:00Z",
                                 "windowEnd":"2026-01-03T12:00:00Z"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.baselineCumulativeMinutes").value(500));
    }

    @Test
    void closeWithoutReadings_snapshotFallsBackToBaseline() throws Exception {
        setupCertifiedBaseline("eq-cb");
        createWorkOrder("eq-cb", "k-cb-create", "wo-1",
                "2026-01-01T12:00:00Z", "2026-01-01T18:00:00Z");
        // 未开始工单也允许直接关闭：最后有效读数回退为基线
        mockMvc.perform(post("/api/equipment/eq-cb/work-orders/wo-1/close")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workOrderKey":"k-cb-close","expectedVersion":4,"workOrderVersion":1}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.lastValidReadingId").value("r1"))
                .andExpect(jsonPath("$.lastValidCumulativeMinutes").value(100));
        // 未关闭工单查询快照 → 404
        mockMvc.perform(get("/api/equipment/eq-cb/work-orders/wo-1/snapshot"))
                .andExpect(status().isOk());
    }

    // ---------- 取消边界 ----------

    @Test
    void cancel_onlyCreatedAllowed() throws Exception {
        setupCertifiedBaseline("eq-cancel");
        createWorkOrder("eq-cancel", "k-x-create", "wo-1",
                "2026-01-01T12:00:00Z", "2026-01-01T18:00:00Z");

        // 未开始：取消限制查询可取消
        mockMvc.perform(get("/api/equipment/eq-cancel/work-orders/wo-1/cancel-eligibility"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cancellable").value(true))
                .andExpect(jsonPath("$.status").value("CREATED"));

        // 开始后不可取消
        mockMvc.perform(post("/api/equipment/eq-cancel/work-orders/wo-1/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workOrderKey":"k-x-start","expectedVersion":4,"workOrderVersion":1}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(get("/api/equipment/eq-cancel/work-orders/wo-1/cancel-eligibility"))
                .andExpect(jsonPath("$.cancellable").value(false))
                .andExpect(jsonPath("$.reason").exists());
        mockMvc.perform(post("/api/equipment/eq-cancel/work-orders/wo-1/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workOrderKey":"k-x-cancel-bad","expectedVersion":5,"workOrderVersion":2}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WORK_ORDER_ALREADY_STARTED"));
    }

    @Test
    void cancel_createdThenCanCreateAgain() throws Exception {
        setupCertifiedBaseline("eq-cancel2");
        createWorkOrder("eq-cancel2", "k-y-create", "wo-1",
                "2026-01-01T12:00:00Z", "2026-01-01T18:00:00Z");
        mockMvc.perform(post("/api/equipment/eq-cancel2/work-orders/wo-1/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workOrderKey":"k-y-cancel","expectedVersion":4,"workOrderVersion":1}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.version").value(2));

        // 取消后可重新建单
        mockMvc.perform(post("/api/equipment/eq-cancel2/work-orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workOrderKey":"k-y-create2","expectedVersion":5,"workOrderId":"wo-2",
                                 "baselineReadingId":"r1",
                                 "windowStart":"2026-01-02T12:00:00Z",
                                 "windowEnd":"2026-01-02T18:00:00Z"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CREATED"));
    }

    // ---------- 终止 ----------

    @Test
    void terminate_onlyInProgressAndStateMachine() throws Exception {
        setupCertifiedBaseline("eq-term");
        createWorkOrder("eq-term", "k-t-create", "wo-1",
                "2026-01-01T12:00:00Z", "2026-01-01T18:00:00Z");

        // 未开始工单不可终止
        mockMvc.perform(post("/api/equipment/eq-term/work-orders/wo-1/terminate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workOrderKey":"k-t-bad","expectedVersion":4,"workOrderVersion":1,
                                 "reason":"early"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WORK_ORDER_NOT_TERMINABLE"));

        mockMvc.perform(post("/api/equipment/eq-term/work-orders/wo-1/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workOrderKey":"k-t-start","expectedVersion":4,"workOrderVersion":1}
                                """))
                .andExpect(status().isCreated());

        // 进行中可终止
        mockMvc.perform(post("/api/equipment/eq-term/work-orders/wo-1/terminate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workOrderKey":"k-t-ok","expectedVersion":5,"workOrderVersion":2,
                                 "reason":"设备故障"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("TERMINATED"))
                .andExpect(jsonPath("$.terminateReason").value("设备故障"))
                .andExpect(jsonPath("$.version").value(3));

        // 终止后不可再登记读数/取消/关闭
        mockMvc.perform(post("/api/equipment/eq-term/work-orders/wo-1/readings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workOrderKey":"k-t-reg","expectedVersion":6,"workOrderVersion":3,
                                 "readings":[
                                   {"readingId":"t-r","sampledAt":"2026-01-01T13:00:00Z","cumulativeMinutes":200}
                                 ]}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WORK_ORDER_NOT_IN_PROGRESS"));
        mockMvc.perform(post("/api/equipment/eq-term/work-orders/wo-1/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workOrderKey":"k-t-cancel","expectedVersion":6,"workOrderVersion":3}
                                """))
                .andExpect(status().isConflict());
        mockMvc.perform(post("/api/equipment/eq-term/work-orders/wo-1/close")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workOrderKey":"k-t-close","expectedVersion":6,"workOrderVersion":3}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WORK_ORDER_NOT_CLOSABLE"));
    }

    // ---------- workOrderKey 幂等 ----------

    @Test
    void workOrderKey_replaySameParams_conflictOnDifferentParams() throws Exception {
        setupCertifiedBaseline("eq-idem");
        createWorkOrder("eq-idem", "k-replay", "wo-1",
                "2026-01-01T12:00:00Z", "2026-01-01T18:00:00Z");

        // 同键同参重放：返回同一成功结果，不重复建单、版本不再前进
        MvcResult first = postJson("/api/equipment/eq-idem/work-orders", """
                {"workOrderKey":"k-replay","expectedVersion":3,"workOrderId":"wo-1",
                 "baselineReadingId":"r1",
                 "windowStart":"2026-01-01T12:00:00Z","windowEnd":"2026-01-01T18:00:00Z"}
                """);
        MvcResult second = postJson("/api/equipment/eq-idem/work-orders", """
                {"workOrderKey":"k-replay","expectedVersion":3,"workOrderId":"wo-1",
                 "baselineReadingId":"r1",
                 "windowStart":"2026-01-01T12:00:00Z","windowEnd":"2026-01-01T18:00:00Z"}
                """);
        org.junit.jupiter.api.Assertions.assertEquals(201, first.getResponse().getStatus());
        org.junit.jupiter.api.Assertions.assertEquals(201, second.getResponse().getStatus());
        org.junit.jupiter.api.Assertions.assertEquals(
                first.getResponse().getContentAsString(),
                second.getResponse().getContentAsString());
        mockMvc.perform(get("/api/equipment/eq-idem/work-orders"))
                .andExpect(jsonPath("$", hasSize(1)));
        mockMvc.perform(get("/api/equipment/eq-idem/status"))
                .andExpect(jsonPath("$.version").value(4));

        // 同键异参（不同窗口）→ 409
        mockMvc.perform(post("/api/equipment/eq-idem/work-orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workOrderKey":"k-replay","expectedVersion":3,"workOrderId":"wo-1",
                                 "baselineReadingId":"r1",
                                 "windowStart":"2026-01-01T13:00:00Z",
                                 "windowEnd":"2026-01-01T19:00:00Z"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WORK_ORDER_KEY_CONFLICT"));
    }

    // ---------- 基线冻结与窗口内修订 ----------

    @Test
    void baselineFrozenWhileWorkOrderOpen_revisionBlocked() throws Exception {
        setupCertifiedBaseline("eq-frozen");
        createWorkOrder("eq-frozen", "k-frz-create", "wo-1",
                "2026-01-01T12:00:00Z", "2026-01-01T18:00:00Z");

        // 未开始工单期间基线读数即冻结
        mockMvc.perform(post("/api/equipment/eq-frozen/readings/r1/revisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"frz-1","expectedVersion":4,"cumulativeMinutes":120}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WORK_ORDER_BASELINE_FROZEN"));

        // 开始后仍冻结
        mockMvc.perform(post("/api/equipment/eq-frozen/work-orders/wo-1/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workOrderKey":"k-frz-start","expectedVersion":4,"workOrderVersion":1}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/equipment/eq-frozen/readings/r1/revisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"frz-2","expectedVersion":5,"cumulativeMinutes":120}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WORK_ORDER_BASELINE_FROZEN"));

        // 基线修订号与工时快照不变
        mockMvc.perform(get("/api/equipment/eq-frozen/work-orders/wo-1"))
                .andExpect(jsonPath("$.baselineRevisionNo").value(1))
                .andExpect(jsonPath("$.baselineCumulativeMinutes").value(100));

        // 工单终止后基线可修订（不再有开放工单）
        mockMvc.perform(post("/api/equipment/eq-frozen/work-orders/wo-1/terminate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workOrderKey":"k-frz-term","expectedVersion":5,"workOrderVersion":2,
                                 "reason":"test"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/equipment/eq-frozen/readings/r1/revisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"frz-3","expectedVersion":6,"cumulativeMinutes":120}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.revisionNo").value(2));
    }

    @Test
    void reviseWindowReadingBelowBaseline_rejected() throws Exception {
        setupCertifiedBaseline("eq-rwin");
        createWorkOrder("eq-rwin", "k-rw-create", "wo-1",
                "2026-01-01T12:00:00Z", "2026-01-01T18:00:00Z");
        mockMvc.perform(post("/api/equipment/eq-rwin/work-orders/wo-1/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workOrderKey":"k-rw-start","expectedVersion":4,"workOrderVersion":1}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/equipment/eq-rwin/work-orders/wo-1/readings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workOrderKey":"k-rw-reg","expectedVersion":5,"workOrderVersion":2,
                                 "readings":[
                                   {"readingId":"w1","sampledAt":"2026-01-01T13:00:00Z","cumulativeMinutes":200}
                                 ]}
                                """))
                .andExpect(status().isCreated());
        // 窗口内读数修订到基线以下 → 422
        mockMvc.perform(post("/api/equipment/eq-rwin/readings/w1/revisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"rw-rev","expectedVersion":6,"cumulativeMinutes":90}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BELOW_BASELINE"));
    }

    // ---------- 批量登记幂等重放 ----------

    @Test
    void batchRegister_sameKeySameParamsReplaysOnce() throws Exception {
        setupCertifiedBaseline("eq-batch-idem");
        createWorkOrder("eq-batch-idem", "k-bi-create", "wo-1",
                "2026-01-01T12:00:00Z", "2026-01-01T18:00:00Z");
        mockMvc.perform(post("/api/equipment/eq-batch-idem/work-orders/wo-1/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workOrderKey":"k-bi-start","expectedVersion":4,"workOrderVersion":1}
                                """))
                .andExpect(status().isCreated());
        String body = """
                {"workOrderKey":"k-bi-reg","expectedVersion":5,"workOrderVersion":2,
                 "readings":[
                   {"readingId":"bi-1","sampledAt":"2026-01-01T13:00:00Z","cumulativeMinutes":200}
                 ]}
                """;
        MvcResult first = postJson("/api/equipment/eq-batch-idem/work-orders/wo-1/readings", body);
        MvcResult replay = postJson("/api/equipment/eq-batch-idem/work-orders/wo-1/readings", body);
        org.junit.jupiter.api.Assertions.assertEquals(201, first.getResponse().getStatus());
        org.junit.jupiter.api.Assertions.assertEquals(201, replay.getResponse().getStatus());
        org.junit.jupiter.api.Assertions.assertEquals(
                first.getResponse().getContentAsString(), replay.getResponse().getContentAsString());
        // 业务效果仅一次
        mockMvc.perform(get("/api/equipment/eq-batch-idem/readings"))
                .andExpect(jsonPath("$", hasSize(2)));
        mockMvc.perform(get("/api/equipment/eq-batch-idem/status"))
                .andExpect(jsonPath("$.version").value(6));
        mockMvc.perform(get("/api/equipment/eq-batch-idem/work-orders/wo-1/readings/w1/revisions"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/equipment/eq-batch-idem/readings/bi-1/revisions"))
                .andExpect(jsonPath("$.length()").value(1));

        // 同键异参（不同读数摘要）→ 409
        mockMvc.perform(post("/api/equipment/eq-batch-idem/work-orders/wo-1/readings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workOrderKey":"k-bi-reg","expectedVersion":5,"workOrderVersion":2,
                                 "readings":[
                                   {"readingId":"bi-1","sampledAt":"2026-01-01T13:00:00Z","cumulativeMinutes":201}
                                 ]}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WORK_ORDER_KEY_CONFLICT"));
    }

    @Test
    void workOrderNotFound_andEquipmentNotFound() throws Exception {        mockMvc.perform(get("/api/equipment/nope/work-orders"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EQUIPMENT_NOT_FOUND"));
        register("eq-nf", 1000);
        mockMvc.perform(get("/api/equipment/eq-nf/work-orders/ghost"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("WORK_ORDER_NOT_FOUND"));
        mockMvc.perform(get("/api/equipment/eq-nf/work-orders/ghost/snapshot"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("WORK_ORDER_NOT_FOUND"));
    }
}
