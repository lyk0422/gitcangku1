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
 * 保养工单锁定 API 测试：基线冻结、读数窗口、批量回滚、关闭快照、取消边界与幂等（真实 H2，MySQL 兼容模式）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class WorkOrderApiTests {

    private static final String WINDOW_START = "2026-01-01T12:00:00Z";
    private static final String WINDOW_END = "2026-01-01T14:00:00Z";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM idempotency_request");
        jdbc.update("DELETE FROM work_order");
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

    private String createBody(String workOrderKey, long expectedVersion, String start, String end) {
        return """
                {"workOrderKey":"%s","expectedVersion":%d,"windowStart":"%s","windowEnd":"%s"}
                """.formatted(workOrderKey, expectedVersion, start, end);
    }

    private MvcResult createOrder(String equipmentId, String workOrderKey, long expectedVersion,
                                  String start, String end) throws Exception {
        return postJson("/api/equipment/" + equipmentId + "/work-orders",
                createBody(workOrderKey, expectedVersion, start, end));
    }

    private MvcResult operate(String equipmentId, String workOrderKey, String operation,
                              long expectedWorkOrderVersion) throws Exception {
        return postJson("/api/equipment/" + equipmentId + "/work-orders/" + workOrderKey + "/" + operation,
                """
                {"expectedWorkOrderVersion":%d}
                """.formatted(expectedWorkOrderVersion));
    }

    private MvcResult batchReadings(String equipmentId, String workOrderKey, long expectedVersion,
                                    long expectedWorkOrderVersion, String itemsJson) throws Exception {
        return postJson("/api/equipment/" + equipmentId + "/work-orders/" + workOrderKey + "/readings",
                """
                {"expectedVersion":%d,"expectedWorkOrderVersion":%d,"readings":[%s]}
                """.formatted(expectedVersion, expectedWorkOrderVersion, itemsJson));
    }

    private String item(String readingId, String sampledAt, long cumulativeMinutes) {
        return """
                {"readingId":"%s","sampledAt":"%s","cumulativeMinutes":%d}
                """.formatted(readingId, sampledAt, cumulativeMinutes);
    }

    // ---------- 主流程：建单 → 开始 → 批量登记 → 关闭快照 → 快照不可变 ----------

    @Test
    void mainFlow_baselineWindowSnapshotAndImmutability() throws Exception {
        register("eq-wo", 1000);
        addReading("eq-wo", "wo-r1", 1, "r1", "2026-01-01T10:00:00Z", 100, 201);
        addReading("eq-wo", "wo-r2", 2, "r2", "2026-01-01T11:00:00Z", 200, 201);

        // 建单：冻结当前已认证读数 r2（最新生效读数）为基线，锁定左闭右开窗口
        mockMvc.perform(post("/api/equipment/eq-wo/work-orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("wo-1", 3, WINDOW_START, WINDOW_END)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andExpect(jsonPath("$.workOrderVersion").value(1))
                .andExpect(jsonPath("$.baselineReadingId").value("r2"))
                .andExpect(jsonPath("$.baselineSampledAt").value("2026-01-01T11:00:00Z"))
                .andExpect(jsonPath("$.baselineCumulativeMinutes").value(200))
                .andExpect(jsonPath("$.windowStart").value(WINDOW_START))
                .andExpect(jsonPath("$.windowEnd").value(WINDOW_END))
                .andExpect(jsonPath("$.cancellable").value(true))
                .andExpect(jsonPath("$.cancelRestriction").value("CANCEL_ALLOWED"))
                .andExpect(jsonPath("$.equipmentVersion").value(3));

        // 开始
        operate("eq-wo", "wo-1", "start", 1);
        mockMvc.perform(get("/api/equipment/eq-wo/work-orders/wo-1"))
                .andExpect(jsonPath("$.status").value("STARTED"))
                .andExpect(jsonPath("$.workOrderVersion").value(2))
                .andExpect(jsonPath("$.startedAt").isNotEmpty())
                .andExpect(jsonPath("$.cancellable").value(false))
                .andExpect(jsonPath("$.cancelRestriction").value("ALREADY_STARTED"));

        // 批量登记：窗口内、不低于基线、序列单调
        mockMvc.perform(post("/api/equipment/eq-wo/work-orders/wo-1/readings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion":3,"expectedWorkOrderVersion":2,"readings":[
                                 %s, %s]}
                                """.formatted(item("r3", "2026-01-01T12:30:00Z", 250),
                                item("r4", "2026-01-01T13:00:00Z", 320))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.workOrderVersion").value(3))
                .andExpect(jsonPath("$.equipmentVersion").value(4))
                .andExpect(jsonPath("$.readingsInWindow").value(2))
                .andExpect(jsonPath("$.lastValidReadingId").value("r4"))
                .andExpect(jsonPath("$.lastValidCumulativeMinutes").value(320));

        // 工单期间单条读数同样受窗口约束（窗口内、不低于基线 → 允许）
        addReading("eq-wo", "wo-r5", 4, "r5", "2026-01-01T13:30:00Z", 360, 201);

        // 关闭：写入不可变快照（基线 + 最后有效读数 r5 + 关闭时刻）
        mockMvc.perform(post("/api/equipment/eq-wo/work-orders/wo-1/close")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedWorkOrderVersion":3}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.workOrderVersion").value(4))
                .andExpect(jsonPath("$.closedAt").isNotEmpty())
                .andExpect(jsonPath("$.snapshotLastReadingId").value("r5"))
                .andExpect(jsonPath("$.snapshotLastSampledAt").value("2026-01-01T13:30:00Z"))
                .andExpect(jsonPath("$.snapshotLastCumulativeMinutes").value(360))
                .andExpect(jsonPath("$.baselineCumulativeMinutes").value(200))
                .andExpect(jsonPath("$.cancelRestriction").value("ALREADY_CLOSED"));

        // 关闭后：新读数不再受窗口约束，且不得改写快照
        addReading("eq-wo", "wo-r6", 5, "r6", "2026-01-01T15:00:00Z", 400, 201);
        // 关闭后修订窗口内读数：快照保持不变（诊断反映当前值，快照冻结）
        mockMvc.perform(post("/api/equipment/eq-wo/readings/r5/revisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"wo-rev-r5","expectedVersion":6,"cumulativeMinutes":370}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(get("/api/equipment/eq-wo/work-orders/wo-1"))
                .andExpect(jsonPath("$.snapshotLastReadingId").value("r5"))
                .andExpect(jsonPath("$.snapshotLastCumulativeMinutes").value(360))
                .andExpect(jsonPath("$.baselineCumulativeMinutes").value(200))
                .andExpect(jsonPath("$.lastValidCumulativeMinutes").value(370));

        // 工单列表
        mockMvc.perform(get("/api/equipment/eq-wo/work-orders"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].workOrderKey").value("wo-1"));
    }

    // ---------- 建单校验 ----------

    @Test
    void create_validationBranches() throws Exception {
        register("eq-cv", 1000);
        // 无已认证读数 → 422
        mockMvc.perform(post("/api/equipment/eq-cv/work-orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("wo-cv-0", 1, WINDOW_START, WINDOW_END)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("NO_CERTIFIED_READING"));

        addReading("eq-cv", "cv-r1", 1, "r1", "2026-01-01T10:00:00Z", 100, 201);

        // 窗口结束等于开始 → 422；结束早于开始 → 422
        mockMvc.perform(post("/api/equipment/eq-cv/work-orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("wo-cv-1", 2, WINDOW_START, WINDOW_START)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_WINDOW"));
        mockMvc.perform(post("/api/equipment/eq-cv/work-orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("wo-cv-1", 2, WINDOW_END, WINDOW_START)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_WINDOW"));

        // 设备版本失配 → 409
        mockMvc.perform(post("/api/equipment/eq-cv/work-orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("wo-cv-1", 99, WINDOW_START, WINDOW_END)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));

        // 失败不占键：修正参数后同键可成功
        mockMvc.perform(post("/api/equipment/eq-cv/work-orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("wo-cv-1", 2, WINDOW_START, WINDOW_END)))
                .andExpect(status().isCreated());

        // 存在未完结工单 → 409
        mockMvc.perform(post("/api/equipment/eq-cv/work-orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("wo-cv-2", 2, WINDOW_START, WINDOW_END)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WORK_ORDER_ACTIVE_EXISTS"));

        // 设备不存在 → 404
        mockMvc.perform(post("/api/equipment/nope/work-orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("wo-cv-9", 1, WINDOW_START, WINDOW_END)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EQUIPMENT_NOT_FOUND"));
    }

    // ---------- 取消边界与终止 ----------

    @Test
    void cancelAndTerminate_boundaries() throws Exception {
        register("eq-cancel", 1000);
        addReading("eq-cancel", "cc-r1", 1, "r1", "2026-01-01T10:00:00Z", 100, 201);

        // 未开始工单可取消
        mockMvc.perform(post("/api/equipment/eq-cancel/work-orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("wo-c1", 2, WINDOW_START, WINDOW_END)))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/equipment/eq-cancel/work-orders/wo-c1/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedWorkOrderVersion":1}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancelledAt").isNotEmpty())
                .andExpect(jsonPath("$.cancelRestriction").value("ALREADY_CANCELLED"));
        // 终态不可再取消/开始/关闭/终止
        mockMvc.perform(post("/api/equipment/eq-cancel/work-orders/wo-c1/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedWorkOrderVersion":2}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WORK_ORDER_TERMINAL_STATE"));

        // 已开始工单不可取消，必须关闭或终止
        mockMvc.perform(post("/api/equipment/eq-cancel/work-orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("wo-c2", 2, WINDOW_START, WINDOW_END)))
                .andExpect(status().isCreated());
        operate("eq-cancel", "wo-c2", "start", 1);
        mockMvc.perform(post("/api/equipment/eq-cancel/work-orders/wo-c2/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedWorkOrderVersion":2}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WORK_ORDER_ALREADY_STARTED"));
        // 已开始工单可终止
        mockMvc.perform(post("/api/equipment/eq-cancel/work-orders/wo-c2/terminate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedWorkOrderVersion":2}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("TERMINATED"))
                .andExpect(jsonPath("$.terminatedAt").isNotEmpty())
                .andExpect(jsonPath("$.cancelRestriction").value("ALREADY_TERMINATED"));

        // 未开始工单可直接终止；终止后设备可再建单
        mockMvc.perform(post("/api/equipment/eq-cancel/work-orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("wo-c3", 2, WINDOW_START, WINDOW_END)))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/equipment/eq-cancel/work-orders/wo-c3/terminate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedWorkOrderVersion":1}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("TERMINATED"));
        mockMvc.perform(post("/api/equipment/eq-cancel/work-orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("wo-c4", 2, WINDOW_START, WINDOW_END)))
                .andExpect(status().isCreated());

        // 未开始不能关闭；未开始不能登记读数
        mockMvc.perform(post("/api/equipment/eq-cancel/work-orders/wo-c4/close")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedWorkOrderVersion":1}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WORK_ORDER_NOT_STARTED"));
        mockMvc.perform(post("/api/equipment/eq-cancel/work-orders/wo-c4/readings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion":2,"expectedWorkOrderVersion":1,"readings":[%s]}
                                """.formatted(item("rx", "2026-01-01T12:30:00Z", 150))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WORK_ORDER_NOT_STARTED"));
    }

    // ---------- 批量登记：预校验与整体回滚 ----------

    @Test
    void batchReadings_prevalidationAndRollback() throws Exception {
        register("eq-batch", 1000);
        addReading("eq-batch", "b-r1", 1, "r1", "2026-01-01T10:00:00Z", 100, 201);
        createOrder("eq-batch", "wo-b1", 2, WINDOW_START, WINDOW_END);
        operate("eq-batch", "wo-b1", "start", 1);

        // 越窗（窗口开始前）→ 422
        mockMvc.perform(post("/api/equipment/eq-batch/work-orders/wo-b1/readings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion":2,"expectedWorkOrderVersion":2,"readings":[%s]}
                                """.formatted(item("r2", "2026-01-01T11:00:00Z", 150))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("READING_OUT_OF_WINDOW"));
        // 越窗（窗口结束时刻，右开）→ 422
        mockMvc.perform(post("/api/equipment/eq-batch/work-orders/wo-b1/readings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion":2,"expectedWorkOrderVersion":2,"readings":[%s]}
                                """.formatted(item("r2", WINDOW_END, 150))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("READING_OUT_OF_WINDOW"));
        // 低于基线（倒退）→ 422
        mockMvc.perform(post("/api/equipment/eq-batch/work-orders/wo-b1/readings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion":2,"expectedWorkOrderVersion":2,"readings":[%s]}
                                """.formatted(item("r2", "2026-01-01T12:30:00Z", 50))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("READING_BELOW_BASELINE"));
        // 批内序列倒退 → 422
        mockMvc.perform(post("/api/equipment/eq-batch/work-orders/wo-b1/readings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion":2,"expectedWorkOrderVersion":2,"readings":[%s, %s]}
                                """.formatted(item("r2", "2026-01-01T12:30:00Z", 150),
                                item("r3", "2026-01-01T13:00:00Z", 120))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("READING_ORDER_VIOLATION"));
        // 设备版本失配 → 422
        mockMvc.perform(post("/api/equipment/eq-batch/work-orders/wo-b1/readings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion":99,"expectedWorkOrderVersion":2,"readings":[%s]}
                                """.formatted(item("r2", "2026-01-01T12:30:00Z", 150))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BATCH_VERSION_MISMATCH"));
        // 工单版本失配 → 422
        mockMvc.perform(post("/api/equipment/eq-batch/work-orders/wo-b1/readings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion":2,"expectedWorkOrderVersion":99,"readings":[%s]}
                                """.formatted(item("r2", "2026-01-01T12:30:00Z", 150))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BATCH_VERSION_MISMATCH"));

        // 全部失败整体回滚：无新读数、工单版本与设备版本均未推进
        mockMvc.perform(get("/api/equipment/eq-batch/readings"))
                .andExpect(jsonPath("$", hasSize(1)));
        mockMvc.perform(get("/api/equipment/eq-batch/work-orders/wo-b1"))
                .andExpect(jsonPath("$.workOrderVersion").value(2))
                .andExpect(jsonPath("$.status").value("STARTED"));
        mockMvc.perform(get("/api/equipment/eq-batch/status"))
                .andExpect(jsonPath("$.version").value(2));

        // 失败不占键：同键修正参数后成功，且可顺序登记第二批（版本推进）
        mockMvc.perform(post("/api/equipment/eq-batch/work-orders/wo-b1/readings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion":2,"expectedWorkOrderVersion":2,"readings":[%s, %s]}
                                """.formatted(item("r2", "2026-01-01T12:30:00Z", 150),
                                item("r3", "2026-01-01T13:00:00Z", 180))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.workOrderVersion").value(3))
                .andExpect(jsonPath("$.equipmentVersion").value(3));
        mockMvc.perform(post("/api/equipment/eq-batch/work-orders/wo-b1/readings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion":3,"expectedWorkOrderVersion":3,"readings":[%s]}
                                """.formatted(item("r4", "2026-01-01T13:30:00Z", 200))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.workOrderVersion").value(4));
        mockMvc.perform(get("/api/equipment/eq-batch/readings"))
                .andExpect(jsonPath("$", hasSize(4)));
    }

    // ---------- 工单进行期间的单条读数约束 ----------

    @Test
    void singleReading_duringActiveOrder_windowAndBaselineEnforced() throws Exception {
        register("eq-single", 1000);
        addReading("eq-single", "s-r1", 1, "r1", "2026-01-01T10:00:00Z", 100, 201);
        createOrder("eq-single", "wo-s1", 2, WINDOW_START, WINDOW_END);
        operate("eq-single", "wo-s1", "start", 1);

        // 窗口前 → 422；窗口结束时刻（右开）→ 422；低于基线 → 422
        addReading("eq-single", "s-r2", 2, "r2", "2026-01-01T11:00:00Z", 150, 422);
        addReading("eq-single", "s-r3", 2, "r3", WINDOW_END, 150, 422);
        addReading("eq-single", "s-r4", 2, "r4", "2026-01-01T12:30:00Z", 50, 422);
        // 窗口内且不低于基线 → 允许
        addReading("eq-single", "s-r5", 2, "r5", "2026-01-01T12:30:00Z", 150, 201);
        mockMvc.perform(get("/api/equipment/eq-single/readings"))
                .andExpect(jsonPath("$", hasSize(2)));

        // 关闭后不再受窗口约束
        operate("eq-single", "wo-s1", "close", 2);
        addReading("eq-single", "s-r6", 3, "r6", "2026-01-01T15:00:00Z", 200, 201);
    }

    // ---------- 幂等：同键同参重放、异参 409、失败不占键 ----------

    @Test
    void idempotency_replayMismatchAndFailureNotOccupying() throws Exception {
        register("eq-widem", 1000);
        addReading("eq-widem", "i-r1", 1, "r1", "2026-01-01T10:00:00Z", 100, 201);

        // 建单同键同参重放：响应完全一致，业务效果只发生一次
        MvcResult first = createOrder("eq-widem", "wo-i1", 2, WINDOW_START, WINDOW_END);
        assert first.getResponse().getStatus() == 201;
        MvcResult replay = createOrder("eq-widem", "wo-i1", 2, WINDOW_START, WINDOW_END);
        assert replay.getResponse().getStatus() == 201;
        assert replay.getResponse().getContentAsString()
                .equals(first.getResponse().getContentAsString());
        mockMvc.perform(get("/api/equipment/eq-widem/work-orders"))
                .andExpect(jsonPath("$", hasSize(1)));

        // 同键异参（不同窗口）→ 409
        mockMvc.perform(post("/api/equipment/eq-widem/work-orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("wo-i1", 2, WINDOW_START, "2026-01-01T15:00:00Z")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));

        // 状态变更同键同参重放
        MvcResult startFirst = operate("eq-widem", "wo-i1", "start", 1);
        assert startFirst.getResponse().getStatus() == 200;
        MvcResult startReplay = operate("eq-widem", "wo-i1", "start", 1);
        assert startReplay.getResponse().getStatus() == 200;
        assert startReplay.getResponse().getContentAsString()
                .equals(startFirst.getResponse().getContentAsString());
        mockMvc.perform(get("/api/equipment/eq-widem/work-orders/wo-i1"))
                .andExpect(jsonPath("$.workOrderVersion").value(2));

        // 批量登记同键同参重放；同版本异参（读数摘要不同）→ 409
        String batch = """
                {"expectedVersion":2,"expectedWorkOrderVersion":2,"readings":[%s]}
                """.formatted(item("r2", "2026-01-01T12:30:00Z", 150));
        MvcResult batchFirst = postJson("/api/equipment/eq-widem/work-orders/wo-i1/readings", batch);
        assert batchFirst.getResponse().getStatus() == 201;
        MvcResult batchReplay = postJson("/api/equipment/eq-widem/work-orders/wo-i1/readings", batch);
        assert batchReplay.getResponse().getStatus() == 201;
        assert batchReplay.getResponse().getContentAsString()
                .equals(batchFirst.getResponse().getContentAsString());
        mockMvc.perform(get("/api/equipment/eq-widem/readings"))
                .andExpect(jsonPath("$", hasSize(2)));
        mockMvc.perform(post("/api/equipment/eq-widem/work-orders/wo-i1/readings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion":2,"expectedWorkOrderVersion":2,"readings":[%s]}
                                """.formatted(item("r2", "2026-01-01T12:30:00Z", 160))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));

        // 关闭同键同参重放
        MvcResult closeFirst = operate("eq-widem", "wo-i1", "close", 3);
        assert closeFirst.getResponse().getStatus() == 200;
        MvcResult closeReplay = operate("eq-widem", "wo-i1", "close", 3);
        assert closeReplay.getResponse().getStatus() == 200;
        assert closeReplay.getResponse().getContentAsString()
                .equals(closeFirst.getResponse().getContentAsString());
        mockMvc.perform(get("/api/equipment/eq-widem/work-orders/wo-i1"))
                .andExpect(jsonPath("$.workOrderVersion").value(4))
                .andExpect(jsonPath("$.status").value("CLOSED"));
    }

    // ---------- 资源不存在 ----------

    @Test
    void notFound_branches() throws Exception {
        mockMvc.perform(get("/api/equipment/nope/work-orders/wo-x"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EQUIPMENT_NOT_FOUND"));
        mockMvc.perform(get("/api/equipment/nope/work-orders"))
                .andExpect(status().isNotFound());

        register("eq-wnf", 1000);
        mockMvc.perform(get("/api/equipment/eq-wnf/work-orders/wo-x"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("WORK_ORDER_NOT_FOUND"));
        mockMvc.perform(post("/api/equipment/eq-wnf/work-orders/wo-x/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedWorkOrderVersion":1}
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("WORK_ORDER_NOT_FOUND"));
        // 工单属于其他设备 → 404
        addReading("eq-wnf", "nf-r1", 1, "r1", "2026-01-01T10:00:00Z", 100, 201);
        createOrder("eq-wnf", "wo-nf", 2, WINDOW_START, WINDOW_END);
        register("eq-wnf2", 1000);
        mockMvc.perform(get("/api/equipment/eq-wnf2/work-orders/wo-nf"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("WORK_ORDER_NOT_FOUND"));
    }

    // ---------- 查询：基线、窗口、读数诊断、快照与取消限制 ----------

    @Test
    void query_diagnosticsAndSnapshot() throws Exception {
        register("eq-diag", 1000);
        addReading("eq-diag", "d-r1", 1, "r1", "2026-01-01T10:00:00Z", 100, 201);
        addReading("eq-diag", "d-r2", 2, "r2", "2026-01-01T11:00:00Z", 200, 201);
        createOrder("eq-diag", "wo-d1", 3, WINDOW_START, WINDOW_END);
        operate("eq-diag", "wo-d1", "start", 1);

        // 诊断：窗口内暂无读数，最后有效读数为 null
        mockMvc.perform(get("/api/equipment/eq-diag/work-orders/wo-d1"))
                .andExpect(jsonPath("$.baselineReadingId").value("r2"))
                .andExpect(jsonPath("$.baselineCumulativeMinutes").value(200))
                .andExpect(jsonPath("$.readingsInWindow").value(0))
                .andExpect(jsonPath("$.lastValidReadingId").value(nullValue()))
                .andExpect(jsonPath("$.snapshotLastReadingId").value(nullValue()))
                .andExpect(jsonPath("$.cancellable").value(false));

        batchReadings("eq-diag", "wo-d1", 3, 2,
                item("r3", "2026-01-01T12:30:00Z", 250) + ","
                        + item("r4", "2026-01-01T13:00:00Z", 300));
        mockMvc.perform(get("/api/equipment/eq-diag/work-orders/wo-d1"))
                .andExpect(jsonPath("$.readingsInWindow").value(2))
                .andExpect(jsonPath("$.lastValidReadingId").value("r4"))
                .andExpect(jsonPath("$.lastValidCumulativeMinutes").value(300));

        // 关闭后快照保留基线与最后有效读数
        operate("eq-diag", "wo-d1", "close", 3);
        mockMvc.perform(get("/api/equipment/eq-diag/work-orders/wo-d1"))
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.baselineReadingId").value("r2"))
                .andExpect(jsonPath("$.snapshotLastReadingId").value("r4"))
                .andExpect(jsonPath("$.snapshotLastSampledAt").value("2026-01-01T13:00:00Z"))
                .andExpect(jsonPath("$.snapshotLastCumulativeMinutes").value(300))
                .andExpect(jsonPath("$.closedAt").isNotEmpty());
    }

    // ---------- 无窗口内有效读数时关闭：快照最后有效读数取基线 ----------

    @Test
    void close_withoutWindowReadings_snapshotFallsBackToBaseline() throws Exception {
        register("eq-empty", 1000);
        addReading("eq-empty", "e-r1", 1, "r1", "2026-01-01T10:00:00Z", 100, 201);
        createOrder("eq-empty", "wo-e1", 2, WINDOW_START, WINDOW_END);
        operate("eq-empty", "wo-e1", "start", 1);
        mockMvc.perform(post("/api/equipment/eq-empty/work-orders/wo-e1/close")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedWorkOrderVersion":2}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.snapshotLastReadingId").value("r1"))
                .andExpect(jsonPath("$.snapshotLastSampledAt").value("2026-01-01T10:00:00Z"))
                .andExpect(jsonPath("$.snapshotLastCumulativeMinutes").value(100));
    }
}
