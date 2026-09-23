package com.example.starter.baggage;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 错装批次追回与剩余路径原子改派 API 测试：覆盖登记/预览/确认/查询主流程、
 * 整单回滚失败分支、封舱竞争、路径形状校验、原路径不可再装载、幂等边界（集合换序同参、
 * 路径换序异参、失败不占键）与只读血缘。全部基于 H2 MySQL 兼容模式真实数据库。
 */
@SpringBootTest
@AutoConfigureMockMvc
class MisloadApiTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-09-23T03:04:05Z");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private BaggageService baggageService;

    @Autowired
    private MisloadService misloadService;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM bag_path_snapshot");
        jdbcTemplate.update("DELETE FROM misload_proposal");
        jdbcTemplate.update("DELETE FROM misload_item");
        jdbcTemplate.update("DELETE FROM misload_incident");
        jdbcTemplate.update("DELETE FROM bag_event");
        jdbcTemplate.update("DELETE FROM load_record");
        jdbcTemplate.update("DELETE FROM bag_itinerary");
        jdbcTemplate.update("DELETE FROM bag");
        jdbcTemplate.update("DELETE FROM leg");
        jdbcTemplate.update("DELETE FROM request_log");
        misloadService.setClock(Instant::now);
    }

    @Test
    void misloadMainFlow_registerPreviewConfirmAndDeliverOnNewPath() throws Exception {
        misloadService.setClock(() -> FIXED_NOW);
        // 原始行程：PEK -> SHA -> CAN，两件行李已乘 LEG_O1 到达 SHA，原剩余 LEG_O2(SHA->CAN)
        registerLeg("LEG_O1", "PEK", "SHA", null);
        registerLeg("LEG_O2", "SHA", "CAN", null);
        registerBag("BAG1", List.of("LEG_O1", "LEG_O2"));
        registerBag("BAG2", List.of("LEG_O1", "LEG_O2"));
        loadSealArrive("LEG_O1", 1, List.of("BAG1", "BAG2"));

        // 实际错装航段：CTU -> SHA（不属于任何行李行程）
        registerLeg("LEG_M", "CTU", "SHA", "2026-09-22T06:00:00Z");
        // 恢复航段 SHA -> KMG -> CAN，出发时间严格递增
        registerLeg("LEG_R1", "SHA", "KMG", "2026-10-01T08:00:00Z");
        registerLeg("LEG_R2", "KMG", "CAN", "2026-10-01T12:00:00Z");

        // 登记错装（当前版本 3：登记1/装载2/到达3），袋号换序提交
        registerMisload("INC1", "LEG_M", List.of(
                misloadBag("BAG2", 3, "SHA"),
                misloadBag("BAG1", 3, "SHA")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.incidentKey").value("INC1"))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.scanStation").value("SHA"))
                .andExpect(jsonPath("$.registeredAt").value(FIXED_NOW.toString()))
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[0].bagTag").value("BAG1"))
                .andExpect(jsonPath("$.items[0].frozenVersion").value(4))
                .andExpect(jsonPath("$.items[0].currentStation").value("SHA"))
                .andExpect(jsonPath("$.items[0].finalDestination").value("CAN"))
                .andExpect(jsonPath("$.items[0].originalRemaining[0].legId").value("LEG_O2"))
                .andExpect(jsonPath("$.items[0].recoveryPath", hasSize(0)));

        mockMvc.perform(get("/api/bags/BAG1/trace"))
                .andExpect(jsonPath("$.status").value("MISLOADED"))
                .andExpect(jsonPath("$.version").value(4))
                .andExpect(jsonPath("$.currentLocation").value("SHA"))
                .andExpect(jsonPath("$.events[*].eventType",
                        contains("REGISTERED", "LOADED", "UNLOADED", "MISLOADED")));

        // 预览：BAG1 两段恢复，BAG2 一段直飞（同样 SHA->CAN）
        registerLeg("LEG_RD", "SHA", "CAN", "2026-10-02T08:00:00Z");
        preview("INC1", Map.of(
                "BAG1", List.of("LEG_R1", "LEG_R2"),
                "BAG2", List.of("LEG_RD")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.items[0].recoveryPath", hasSize(2)))
                .andExpect(jsonPath("$.items[0].recoveryPath[0].legId").value("LEG_R1"))
                .andExpect(jsonPath("$.items[0].recoveryPath[0].departureTime")
                        .value("2026-10-01T08:00:00Z"))
                .andExpect(jsonPath("$.items[1].recoveryPath[0].legId").value("LEG_RD"));

        // 确认：原子关闭事件、替换路径、推进代次、保存双向快照
        confirm("INC1").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.generation").value(1))
                .andExpect(jsonPath("$.bags", hasSize(2)))
                .andExpect(jsonPath("$.bags[0].bagTag").value("BAG1"))
                .andExpect(jsonPath("$.bags[0].pathGeneration").value(1))
                .andExpect(jsonPath("$.bags[0].nextLegIndex").value(0))
                .andExpect(jsonPath("$.bags[0].newPath[0].legId").value("LEG_R1"));

        mockMvc.perform(get("/api/bags/BAG1/trace"))
                .andExpect(jsonPath("$.status").value("IN_TRANSIT"))
                .andExpect(jsonPath("$.pathGeneration").value(1))
                .andExpect(jsonPath("$.nextLegIndex").value(0))
                .andExpect(jsonPath("$.itinerary", hasSize(2)))
                .andExpect(jsonPath("$.itinerary[0].legId").value("LEG_R1"))
                .andExpect(jsonPath("$.events[*].eventType",
                        contains("REGISTERED", "LOADED", "UNLOADED", "MISLOADED", "REROUTED")));

        // 原剩余路径不得再装载
        load("LEG_O2", 1, List.of("BAG2")).andExpect(status().isUnprocessableEntity());
        Integer oldLoadRecords = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM load_record", Integer.class);
        assertThat(oldLoadRecords).isZero();

        // 血缘查询只读：原剩余（gen0）与新路径（gen1）不可变快照齐全
        mockMvc.perform(get("/api/misloads/INC1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.generation").value(1))
                .andExpect(jsonPath("$.items[0].originalRemaining[0].legId").value("LEG_O2"))
                .andExpect(jsonPath("$.items[0].recoveryPath[0].legId").value("LEG_R1"))
                .andExpect(jsonPath("$.snapshots", hasSize(4)))
                .andExpect(jsonPath("$.snapshots[0].bagTag").value("BAG1"))
                .andExpect(jsonPath("$.snapshots[0].kind").value("ORIGINAL"))
                .andExpect(jsonPath("$.snapshots[0].generation").value(0));

        // 新路径走完交付：BAG2 直飞，BAG1 经 KMG 中转
        load("LEG_RD", 1, List.of("BAG2")).andExpect(status().isOk());
        seal("LEG_RD", 2).andExpect(status().isOk());
        arrive("LEG_RD", List.of("BAG2")).andExpect(status().isOk());
        mockMvc.perform(get("/api/bags/BAG2/trace"))
                .andExpect(jsonPath("$.status").value("DELIVERED"))
                .andExpect(jsonPath("$.currentLocation").value("CAN"));

        load("LEG_R1", 1, List.of("BAG1")).andExpect(status().isOk());
        seal("LEG_R1", 2).andExpect(status().isOk());
        arrive("LEG_R1", List.of("BAG1")).andExpect(status().isOk());
        load("LEG_R2", 1, List.of("BAG1")).andExpect(status().isOk());
        seal("LEG_R2", 2).andExpect(status().isOk());
        arrive("LEG_R2", List.of("BAG1")).andExpect(status().isOk());
        mockMvc.perform(get("/api/bags/BAG1/trace"))
                .andExpect(jsonPath("$.status").value("DELIVERED"))
                .andExpect(jsonPath("$.currentLocation").value("CAN"));
    }

    @Test
    void register_wholeBatchRollsBackWhenAnyBagInvalid() throws Exception {
        registerLeg("LEG_O1", "PEK", "SHA", null);
        registerLeg("LEG_O2", "SHA", "CAN", null);
        registerLeg("LEG_M", "CTU", "SHA", "2026-09-22T06:00:00Z");
        registerBag("BAG1", List.of("LEG_O1", "LEG_O2"));
        registerBag("BAG2", List.of("LEG_O1", "LEG_O2"));
        loadSealArrive("LEG_O1", 1, List.of("BAG1", "BAG2"));

        // 扫描站点不符（BAG1）-> 整单 422，BAG2 也不改态
        registerMisload("INC_BAD_STATION", "LEG_M", List.of(
                misloadBag("BAG1", 3, "CAN"),
                misloadBag("BAG2", 3, "SHA")))
                .andExpect(status().isUnprocessableEntity());
        // 版本不符（BAG2 传 99）-> 整单 409
        registerMisload("INC_BAD_VERSION", "LEG_M", List.of(
                misloadBag("BAG1", 3, "SHA"),
                misloadBag("BAG2", 99, "SHA")))
                .andExpect(status().isConflict());
        // 实际航段属于行程 -> 422
        registerMisload("INC_IN_ITINERARY", "LEG_O2", List.of(
                misloadBag("BAG1", 3, "SHA"),
                misloadBag("BAG2", 3, "SHA")))
                .andExpect(status().isUnprocessableEntity());
        // 批内袋号重复 -> 422
        registerMisload("INC_DUP", "LEG_M", List.of(
                misloadBag("BAG1", 3, "SHA"),
                misloadBag("BAG1", 3, "SHA")))
                .andExpect(status().isUnprocessableEntity());
        // 不存在的行李 -> 422
        registerMisload("INC_UNKNOWN", "LEG_M", List.of(
                misloadBag("BAG1", 3, "SHA"),
                misloadBag("BAG_X", 3, "SHA")))
                .andExpect(status().isUnprocessableEntity());

        // 无任何行李改态、无事件残留
        for (String bagTag : List.of("BAG1", "BAG2")) {
            mockMvc.perform(get("/api/bags/" + bagTag + "/trace"))
                    .andExpect(jsonPath("$.status").value("IN_TRANSIT"))
                    .andExpect(jsonPath("$.version").value(3))
                    .andExpect(jsonPath("$.currentLocation").value("SHA"));
        }
        Integer incidents = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM misload_incident", Integer.class);
        assertThat(incidents).isZero();
        Integer events = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bag_event WHERE event_type = 'MISLOADED'", Integer.class);
        assertThat(events).isZero();

        // 件数越界 2~50 -> 400
        postJson("/api/misloads", Map.of(
                "requestId", UUID.randomUUID().toString(),
                "incidentKey", "INC_SIZE",
                "actualLegId", "LEG_M",
                "bags", List.of(misloadBag("BAG1", 3, "SHA"))))
                .andExpect(status().isBadRequest());

        // incidentKey 唯一 -> 重复登记 409
        registerMisload("INC1", "LEG_M", List.of(
                misloadBag("BAG1", 3, "SHA"),
                misloadBag("BAG2", 3, "SHA"))).andExpect(status().isCreated());
        registerMisload("INC1", "LEG_M", List.of(
                misloadBag("BAG1", 4, "SHA"),
                misloadBag("BAG2", 4, "SHA"))).andExpect(status().isConflict());
    }

    @Test
    void register_rejectsDeliveredInTransitAndOpenIncidentBags() throws Exception {
        // 两件两段行程行李到 SHA 后在途待乘 LEG_O2
        registerLeg("LEG_O1", "PEK", "SHA", null);
        registerLeg("LEG_O2", "SHA", "CAN", null);
        registerLeg("LEG_M", "CTU", "SHA", "2026-09-22T06:00:00Z");
        registerBag("BAG_A", List.of("LEG_O1", "LEG_O2"));
        registerBag("BAG_B", List.of("LEG_O1", "LEG_O2"));
        loadSealArrive("LEG_O1", 1, List.of("BAG_A", "BAG_B"));

        // 已交付行李：单段行程走完
        registerLeg("LEG_S1", "PEK", "SHA", null);
        registerBag("BAG_D", List.of("LEG_S1"));
        loadSealArrive("LEG_S1", 1, List.of("BAG_D"));
        registerMisload("INC_D", "LEG_M", List.of(
                misloadBag("BAG_D", 3, "SHA"),
                misloadBag("BAG_A", 3, "SHA"))).andExpect(status().isConflict());

        // BAG_A 先装载到原剩余航段（已在途）-> 整单 422
        load("LEG_O2", 1, List.of("BAG_A")).andExpect(status().isOk());
        registerMisload("INC_T", "LEG_M", List.of(
                misloadBag("BAG_A", 4, "SHA"),
                misloadBag("BAG_B", 3, "SHA"))).andExpect(status().isUnprocessableEntity());

        // 正常登记 BAG_B 与另一件未走行李 BAG_X（系统位置 PEK，实际错到 SHA）
        registerBag("BAG_X", List.of("LEG_O1", "LEG_O2"));
        registerMisload("INC_OPEN", "LEG_M", List.of(
                misloadBag("BAG_B", 3, "SHA"),
                misloadBag("BAG_X", 1, "SHA"))).andExpect(status().isCreated());

        // BAG_B、BAG_X 均已有未结错装 -> 整单 409
        registerMisload("INC_AGAIN", "LEG_M", List.of(
                misloadBag("BAG_B", 4, "SHA"),
                misloadBag("BAG_X", 2, "SHA"))).andExpect(status().isConflict());
    }

    @Test
    void preview_validatesShapeAndCoverageWithoutChangingBags() throws Exception {
        prepareTwoMisloadedBags();

        // 未覆盖全部行李 -> 422
        preview("INC1", Map.of("BAG1", List.of("LEG_RD")))
                .andExpect(status().isUnprocessableEntity());
        // 出现批次外袋号 -> 422
        preview("INC1", Map.of(
                "BAG1", List.of("LEG_RD"),
                "BAG2", List.of("LEG_RD"),
                "BAG9", List.of("LEG_RD")))
                .andExpect(status().isUnprocessableEntity());
        // 首段起点与当前站不符（PEK -> ...）
        registerLeg("LEG_X1", "PEK", "CAN", "2026-10-03T08:00:00Z");
        preview("INC1", Map.of(
                "BAG1", List.of("LEG_X1"),
                "BAG2", List.of("LEG_RD")))
                .andExpect(status().isUnprocessableEntity());
        // 末段终点不是原最终目的地 CAN
        registerLeg("LEG_X2", "SHA", "KMG", "2026-10-03T08:00:00Z");
        preview("INC1", Map.of(
                "BAG1", List.of("LEG_X2"),
                "BAG2", List.of("LEG_RD")))
                .andExpect(status().isUnprocessableEntity());
        // 段间站点不连续
        registerLeg("LEG_Y1", "SHA", "CTU", "2026-10-03T08:00:00Z");
        preview("INC1", Map.of(
                "BAG1", List.of("LEG_Y1", "LEG_R2"),
                "BAG2", List.of("LEG_RD")))
                .andExpect(status().isUnprocessableEntity());
        // 出发时间未严格递增
        registerLeg("LEG_Z1", "SHA", "KMG", "2026-10-05T08:00:00Z");
        preview("INC1", Map.of(
                "BAG1", List.of("LEG_Z1", "LEG_R2"),
                "BAG2", List.of("LEG_RD")))
                .andExpect(status().isUnprocessableEntity());
        // 航段未登记出发时刻 -> 422
        registerLeg("LEG_NOTIME", "SHA", "CAN", null);
        preview("INC1", Map.of(
                "BAG1", List.of("LEG_NOTIME"),
                "BAG2", List.of("LEG_RD")))
                .andExpect(status().isUnprocessableEntity());
        // 恢复航段不存在 -> 422
        preview("INC1", Map.of(
                "BAG1", List.of("LEG_MISSING"),
                "BAG2", List.of("LEG_RD")))
                .andExpect(status().isUnprocessableEntity());
        // 路径内航段重复 -> 422
        preview("INC1", Map.of(
                "BAG1", List.of("LEG_R1", "LEG_R1"),
                "BAG2", List.of("LEG_RD")))
                .andExpect(status().isUnprocessableEntity());
        // 段数越界 -> 400
        mockMvc.perform(post("/api/misloads/INC1/preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "requestId", UUID.randomUUID().toString(),
                                "items", List.of(Map.of("bagTag", "BAG1", "segments", List.of()))))))
                .andExpect(status().isBadRequest());
        // 不存在的事件 -> 404
        preview("INC_MISSING", Map.of(
                "BAG1", List.of("LEG_RD"),
                "BAG2", List.of("LEG_RD")))
                .andExpect(status().isNotFound());

        // 全部失败后行李仍为 MISLOADED、无提案、无快照
        for (String bagTag : List.of("BAG1", "BAG2")) {
            mockMvc.perform(get("/api/bags/" + bagTag + "/trace"))
                    .andExpect(jsonPath("$.status").value("MISLOADED"))
                    .andExpect(jsonPath("$.pathGeneration").value(0));
        }
        Integer proposals = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM misload_proposal", Integer.class);
        assertThat(proposals).isZero();
    }

    @Test
    void confirm_sealedRecoveryLegRollsBackWholeReroute() throws Exception {
        prepareTwoMisloadedBags();
        preview("INC1", Map.of(
                "BAG1", List.of("LEG_R1", "LEG_R2"),
                "BAG2", List.of("LEG_RD"))).andExpect(status().isOk());

        // 预览后、确认前把恢复航段 LEG_R1 空舱封舱：确认整单 422
        seal("LEG_R1", 1).andExpect(status().isOk());
        confirm("INC1").andExpect(status().isUnprocessableEntity());

        // 整单回滚：事件仍 OPEN、行李仍 MISLOADED、无快照、无新代次行程
        mockMvc.perform(get("/api/misloads/INC1"))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.snapshots", hasSize(0)));
        for (String bagTag : List.of("BAG1", "BAG2")) {
            mockMvc.perform(get("/api/bags/" + bagTag + "/trace"))
                    .andExpect(jsonPath("$.status").value("MISLOADED"))
                    .andExpect(jsonPath("$.pathGeneration").value(0));
        }
        Integer newGenRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bag_itinerary WHERE generation > 0", Integer.class);
        assertThat(newGenRows).isZero();

        // 解封后（航段一旦封舱不可逆，这里用另一组航段重新预览）仍可成功
        registerLeg("LEG_Q1", "SHA", "KMG", "2026-11-01T08:00:00Z");
        registerLeg("LEG_Q2", "KMG", "CAN", "2026-11-01T12:00:00Z");
        preview("INC1", Map.of(
                "BAG1", List.of("LEG_Q1", "LEG_Q2"),
                "BAG2", List.of("LEG_RD"))).andExpect(status().isOk());
        confirm("INC1").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));
        // 再次确认 -> 409
        confirm("INC1").andExpect(status().isConflict());
        // 确认后再预览 -> 409
        preview("INC1", Map.of(
                "BAG1", List.of("LEG_Q1", "LEG_Q2"),
                "BAG2", List.of("LEG_RD")))
                .andExpect(status().isConflict());
    }

    @Test
    void confirm_withoutPreviewRejected() throws Exception {
        prepareTwoMisloadedBags();
        confirm("INC1").andExpect(status().isUnprocessableEntity());
        mockMvc.perform(get("/api/misloads/INC1"))
                .andExpect(jsonPath("$.status").value("OPEN"));
    }

    @Test
    void idempotency_registerReorderSameParamsPreviewOrderMattersAndFailureNotOccupying() throws Exception {
        prepareTwoMisloadedBags();

        // 预览幂等：同键重复提交重放冻结结果
        String previewRequestId = UUID.randomUUID().toString();
        previewWithId(previewRequestId, "INC1", Map.of(
                "BAG1", List.of("LEG_R1", "LEG_R2"),
                "BAG2", List.of("LEG_RD")))
                .andExpect(status().isOk());
        previewWithId(previewRequestId, "INC1", Map.of(
                "BAG2", List.of("LEG_RD"),
                "BAG1", List.of("LEG_R1", "LEG_R2")))
                .andExpect(status().isOk());
        Integer proposalLogs = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = ?", Integer.class, previewRequestId);
        assertThat(proposalLogs).isEqualTo(1);

        // 同键但 BAG1 路径段换序：路径顺序有意义 -> 409
        previewWithId(previewRequestId, "INC1", Map.of(
                "BAG1", List.of("LEG_R2", "LEG_R1"),
                "BAG2", List.of("LEG_RD")))
                .andExpect(status().isConflict());

        // 确认幂等：同键重放，事件只关闭一次
        String confirmRequestId = UUID.randomUUID().toString();
        confirmWithId(confirmRequestId, "INC1").andExpect(status().isOk());
        confirmWithId(confirmRequestId, "INC1").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));
        Integer confirmLogs = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = ?", Integer.class, confirmRequestId);
        assertThat(confirmLogs).isEqualTo(1);
        Integer snapshots = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bag_path_snapshot WHERE incident_id = 'INC1'", Integer.class);
        assertThat(snapshots).isEqualTo(5);

        // 登记失败不占键：新批次先 422 失败，再用同键成功。
        // 两件两段行程行李分别在各自航段链到达 SHA 中转（未交付），LEG_E1/LEG_F1 均已 ARRIVED
        registerLeg("LEG_NM", "CTU", "SHA", "2026-09-22T09:00:00Z");
        registerLeg("LEG_E1", "PEK", "SHA", null);
        registerLeg("LEG_E2", "SHA", "CAN", null);
        registerLeg("LEG_F1", "PEK", "SHA", null);
        registerLeg("LEG_F2", "SHA", "CAN", null);
        registerBag("BAG_E", List.of("LEG_E1", "LEG_E2"));
        registerBag("BAG_F", List.of("LEG_F1", "LEG_F2"));
        loadSealArrive("LEG_E1", 1, List.of("BAG_E"));
        loadSealArrive("LEG_F1", 1, List.of("BAG_F"));

        String registerRequestId = UUID.randomUUID().toString();
        registerMisloadWithId(registerRequestId, "INC_RETRY", "LEG_NM", List.of(
                misloadBag("BAG_E", 3, "CAN"),
                misloadBag("BAG_F", 3, "SHA")))
                .andExpect(status().isUnprocessableEntity());
        registerMisloadWithId(registerRequestId, "INC_RETRY", "LEG_NM", List.of(
                misloadBag("BAG_F", 3, "SHA"),
                misloadBag("BAG_E", 3, "SHA")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("OPEN"));
        Integer registerLogs = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = ?", Integer.class, registerRequestId);
        assertThat(registerLogs).isEqualTo(1);
    }

    @Test
    void queries_areReadOnlyAndUnknownIncident404() throws Exception {
        prepareTwoMisloadedBags();
        mockMvc.perform(get("/api/misloads/NOPE")).andExpect(status().isNotFound());

        mockMvc.perform(get("/api/misloads/INC1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.actualLegId").value("LEG_M"))
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.confirmedAt").doesNotExist());
    }

    // ---- 场景装配与请求辅助 ----

    /** 两件行李到达 SHA 后以 INC1/LEG_M 登记错装，并备好恢复航段。 */
    private void prepareTwoMisloadedBags() throws Exception {
        registerLeg("LEG_O1", "PEK", "SHA", null);
        registerLeg("LEG_O2", "SHA", "CAN", null);
        registerLeg("LEG_M", "CTU", "SHA", "2026-09-22T06:00:00Z");
        registerBag("BAG1", List.of("LEG_O1", "LEG_O2"));
        registerBag("BAG2", List.of("LEG_O1", "LEG_O2"));
        loadSealArrive("LEG_O1", 1, List.of("BAG1", "BAG2"));
        registerLeg("LEG_R1", "SHA", "KMG", "2026-10-01T08:00:00Z");
        registerLeg("LEG_R2", "KMG", "CAN", "2026-10-01T12:00:00Z");
        registerLeg("LEG_RD", "SHA", "CAN", "2026-10-02T08:00:00Z");
        registerMisload("INC1", "LEG_M", List.of(
                misloadBag("BAG1", 3, "SHA"),
                misloadBag("BAG2", 3, "SHA"))).andExpect(status().isCreated());
    }

    private Map<String, Object> misloadBag(String bagTag, int version, String scanStation) {
        return Map.of("bagTag", bagTag, "expectedVersion", version, "scanStation", scanStation);
    }

    private ResultActions registerLeg(String legId, String origin, String destination,
                                      String departureTime) throws Exception {
        java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("requestId", UUID.randomUUID().toString());
        body.put("legId", legId);
        body.put("origin", origin);
        body.put("destination", destination);
        if (departureTime != null) {
            body.put("departureTime", departureTime);
        }
        return postJson("/api/legs", body);
    }

    private ResultActions registerBag(String bagTag, List<String> legIds) throws Exception {
        return postJson("/api/bags", Map.of("requestId", UUID.randomUUID().toString(),
                "bagTag", bagTag, "legIds", legIds));
    }

    private void loadSealArrive(String legId, int loadVersion, List<String> bagTags) throws Exception {
        load(legId, loadVersion, bagTags).andExpect(status().isOk());
        seal(legId, loadVersion + 1).andExpect(status().isOk());
        arrive(legId, bagTags).andExpect(status().isOk());
    }

    private ResultActions load(String legId, int expectedVersion, List<String> bagTags) throws Exception {
        return postJson("/api/legs/" + legId + "/load", Map.of(
                "requestId", UUID.randomUUID().toString(),
                "expectedVersion", expectedVersion, "bagTags", bagTags));
    }

    private ResultActions seal(String legId, int expectedVersion) throws Exception {
        return postJson("/api/legs/" + legId + "/seal", Map.of(
                "requestId", UUID.randomUUID().toString(), "expectedVersion", expectedVersion));
    }

    private ResultActions arrive(String legId, List<String> bagTags) throws Exception {
        return postJson("/api/legs/" + legId + "/arrive", Map.of(
                "requestId", UUID.randomUUID().toString(), "bagTags", bagTags));
    }

    private ResultActions registerMisload(String incidentKey, String actualLegId,
                                          List<Map<String, Object>> bags) throws Exception {
        return registerMisloadWithId(UUID.randomUUID().toString(), incidentKey, actualLegId, bags);
    }

    private ResultActions registerMisloadWithId(String requestId, String incidentKey,
                                                String actualLegId,
                                                List<Map<String, Object>> bags) throws Exception {
        return postJson("/api/misloads", Map.of(
                "requestId", requestId, "incidentKey", incidentKey,
                "actualLegId", actualLegId, "bags", bags));
    }

    private ResultActions preview(String incidentKey, Map<String, List<String>> items) throws Exception {
        return previewWithId(UUID.randomUUID().toString(), incidentKey, items);
    }

    private ResultActions previewWithId(String requestId, String incidentKey,
                                        Map<String, List<String>> items) throws Exception {
        List<Map<String, Object>> itemList = items.entrySet().stream()
                .map(entry -> Map.of("bagTag", entry.getKey(), "segments",
                        (Object) entry.getValue().stream().map(legId -> Map.of("legId", legId)).toList()))
                .toList();
        return postJson("/api/misloads/" + incidentKey + "/preview",
                Map.of("requestId", requestId, "items", itemList));
    }

    private ResultActions confirm(String incidentKey) throws Exception {
        return confirmWithId(UUID.randomUUID().toString(), incidentKey);
    }

    private ResultActions confirmWithId(String requestId, String incidentKey) throws Exception {
        return postJson("/api/misloads/" + incidentKey + "/confirm",
                Map.of("requestId", requestId));
    }

    private ResultActions postJson(String url, Object body) throws Exception {
        return mockMvc.perform(post(url)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }
}
