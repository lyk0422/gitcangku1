package com.example.starter.baggage;

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

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 短卸差异登记与补到核对 API 测试：覆盖子集校验、空到达、差异快照、补到续运、
 * 未补到清单、批量装载回滚、原精确到达入口兼容与 requestId 幂等边界。
 */
@SpringBootTest
@AutoConfigureMockMvc
class BaggageShortUnloadTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM load_record");
        jdbcTemplate.update("DELETE FROM bag_trace_event");
        jdbcTemplate.update("DELETE FROM bag_itinerary");
        jdbcTemplate.update("DELETE FROM bag");
        jdbcTemplate.update("DELETE FROM leg");
        jdbcTemplate.update("DELETE FROM request_log");
    }

    /** LEG1 PEK->SHA、LEG2 SHA->CAN；BAG1 仅 LEG1，BAG2/BAG3 经 LEG1、LEG2，并在 LEG1 封舱。 */
    private void setupThreeBagsSealed() throws Exception {
        registerLeg("LEG1", "PEK", "SHA");
        registerLeg("LEG2", "SHA", "CAN");
        registerBag("BAG1", List.of("LEG1"));
        registerBag("BAG2", List.of("LEG1", "LEG2"));
        registerBag("BAG3", List.of("LEG1", "LEG2"));
        load("LEG1", 1, List.of("BAG1", "BAG2", "BAG3"));
        seal("LEG1", 2);
    }

    @Test
    void discrepancyArrive_partialArrivalMovesArrivedAndRegistersShort() throws Exception {
        setupThreeBagsSealed();

        discrepancyArrive("LEG1", 3, List.of("BAG3", "BAG1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ARRIVED"))
                .andExpect(jsonPath("$.version").value(4))
                .andExpect(jsonPath("$.arrivalMode").value("DISCREPANCY"))
                .andExpect(jsonPath("$.arrived", contains("BAG1", "BAG3")))
                .andExpect(jsonPath("$.shortUnloaded", contains("BAG2")))
                .andExpect(jsonPath("$.registeredAt", endsWith("Z")));

        // BAG1 实际到达且完成行程 -> DELIVERED
        mockMvc.perform(get("/api/bags/BAG1/trace"))
                .andExpect(jsonPath("$.currentLocation").value("SHA"))
                .andExpect(jsonPath("$.status").value("DELIVERED"))
                .andExpect(jsonPath("$.nextLegIndex").value(1))
                .andExpect(jsonPath("$.loadedLegId").doesNotExist());
        // BAG3 实际到达、未完成行程 -> IN_TRANSIT，可续运下一航段
        mockMvc.perform(get("/api/bags/BAG3/trace"))
                .andExpect(jsonPath("$.currentLocation").value("SHA"))
                .andExpect(jsonPath("$.status").value("IN_TRANSIT"))
                .andExpect(jsonPath("$.nextLegIndex").value(1))
                .andExpect(jsonPath("$.loadedLegId").doesNotExist());
        // BAG2 短卸：停留 PEK、待乘索引不推进
        mockMvc.perform(get("/api/bags/BAG2/trace"))
                .andExpect(jsonPath("$.currentLocation").value("PEK"))
                .andExpect(jsonPath("$.status").value("SHORT_UNLOADED"))
                .andExpect(jsonPath("$.nextLegIndex").value(0))
                .andExpect(jsonPath("$.loadedLegId").doesNotExist())
                .andExpect(jsonPath("$.trace[*].eventType",
                        contains("REGISTERED", "LOADED", "SHORT_UNLOADED")));

        // 航段只读差异快照
        mockMvc.perform(get("/api/legs/LEG1/discrepancy"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ARRIVED"))
                .andExpect(jsonPath("$.arrivalMode").value("DISCREPANCY"))
                .andExpect(jsonPath("$.manifest", contains("BAG1", "BAG2", "BAG3")))
                .andExpect(jsonPath("$.arrived", contains("BAG1", "BAG3")))
                .andExpect(jsonPath("$.shortUnloaded", contains("BAG2")));

        // 未补到清单
        mockMvc.perform(get("/api/short-unloaded"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].bagTag").value("BAG2"))
                .andExpect(jsonPath("$[0].missingLegId").value("LEG1"))
                .andExpect(jsonPath("$[0].expectedDestination").value("SHA"))
                .andExpect(jsonPath("$[0].registeredAt", endsWith("Z")));

        // 短卸行李不得出现在任何装载清单中
        Integer loadRecords = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM load_record WHERE bag_tag = 'BAG2'", Integer.class);
        org.assertj.core.api.Assertions.assertThat(loadRecords).isZero();
    }

    @Test
    void discrepancyArrive_emptySetIsLegalAndAllBagsShort() throws Exception {
        setupThreeBagsSealed();

        discrepancyArrive("LEG1", 3, List.of())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.arrivalMode").value("DISCREPANCY"))
                .andExpect(jsonPath("$.arrived", hasSize(0)))
                .andExpect(jsonPath("$.shortUnloaded", contains("BAG1", "BAG2", "BAG3")));

        mockMvc.perform(get("/api/bags/BAG2/trace"))
                .andExpect(jsonPath("$.status").value("SHORT_UNLOADED"))
                .andExpect(jsonPath("$.currentLocation").value("PEK"))
                .andExpect(jsonPath("$.nextLegIndex").value(0));
        mockMvc.perform(get("/api/short-unloaded"))
                .andExpect(jsonPath("$", hasSize(3)));
    }

    @Test
    void discrepancyArrive_rejectsOutOfManifestDuplicatesAndWrongState() throws Exception {
        setupThreeBagsSealed();

        // 清单外袋号 -> 422
        discrepancyArrive("LEG1", 3, List.of("BAG1", "BAG9"))
                .andExpect(status().isUnprocessableEntity());
        // 袋号重复 -> 422
        discrepancyArrive("LEG1", 3, List.of("BAG1", "BAG1"))
                .andExpect(status().isUnprocessableEntity());
        // 版本不匹配 -> 409
        discrepancyArrive("LEG1", 99, List.of("BAG1"))
                .andExpect(status().isConflict());

        // 航段仍为 SEALED、版本不变，行李均未变化
        mockMvc.perform(get("/api/legs/LEG1/discrepancy"))
                .andExpect(jsonPath("$.status").value("SEALED"))
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.arrivalMode").value(nullValue()));
        mockMvc.perform(get("/api/bags/BAG2/trace"))
                .andExpect(jsonPath("$.status").value("IN_TRANSIT"))
                .andExpect(jsonPath("$.loadedLegId").value("LEG1"))
                .andExpect(jsonPath("$.nextLegIndex").value(0));

        // OPEN 航段禁止差异到达
        registerLeg("LEG9", "PEK", "SHA");
        discrepancyArrive("LEG9", 1, List.of()).andExpect(status().isUnprocessableEntity());

        // 差异到达成功
        discrepancyArrive("LEG1", 3, List.of("BAG1", "BAG3")).andExpect(status().isOk());

        // 已 ARRIVED：不能再执行另一种到达确认，也不能重复差异到达
        arrive("LEG1", List.of("BAG1", "BAG2", "BAG3")).andExpect(status().isUnprocessableEntity());
        discrepancyArrive("LEG1", 4, List.of("BAG2")).andExpect(status().isUnprocessableEntity());
    }

    @Test
    void exactArrive_stillWorksAndSnapshotMarksExact() throws Exception {
        setupThreeBagsSealed();

        arrive("LEG1", List.of("BAG3", "BAG2", "BAG1")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ARRIVED"));
        mockMvc.perform(get("/api/legs/LEG1/discrepancy"))
                .andExpect(jsonPath("$.arrivalMode").value("EXACT"))
                .andExpect(jsonPath("$.arrived", contains("BAG1", "BAG2", "BAG3")))
                .andExpect(jsonPath("$.shortUnloaded", hasSize(0)));
        mockMvc.perform(get("/api/short-unloaded")).andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void recover_movesBagAdvancesIndexAndOpensNextLoad() throws Exception {
        setupThreeBagsSealed();
        discrepancyArrive("LEG1", 3, List.of("BAG1", "BAG3")).andExpect(status().isOk());

        // 补到 BAG2：应到站 SHA，推进待乘索引 -> RECOVERED
        recover("BAG2", "LEG1", "SHA").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RECOVERED"))
                .andExpect(jsonPath("$.missingLegId").value("LEG1"))
                .andExpect(jsonPath("$.currentLocation").value("SHA"))
                .andExpect(jsonPath("$.nextLegIndex").value(1))
                .andExpect(jsonPath("$.recoveredAt", endsWith("Z")));

        mockMvc.perform(get("/api/bags/BAG2/trace"))
                .andExpect(jsonPath("$.status").value("RECOVERED"))
                .andExpect(jsonPath("$.currentLocation").value("SHA"))
                .andExpect(jsonPath("$.trace[*].eventType",
                        contains("REGISTERED", "LOADED", "SHORT_UNLOADED", "RECOVERED")));
        mockMvc.perform(get("/api/short-unloaded")).andExpect(jsonPath("$", hasSize(0)));

        // 补到后可装载下一航段并完成交付
        load("LEG2", 1, List.of("BAG2", "BAG3")).andExpect(status().isOk());
        mockMvc.perform(get("/api/bags/BAG2/trace"))
                .andExpect(jsonPath("$.status").value("IN_TRANSIT"))
                .andExpect(jsonPath("$.loadedLegId").value("LEG2"));
        seal("LEG2", 2).andExpect(status().isOk());
        arrive("LEG2", List.of("BAG2", "BAG3")).andExpect(status().isOk());
        mockMvc.perform(get("/api/bags/BAG2/trace"))
                .andExpect(jsonPath("$.currentLocation").value("CAN"))
                .andExpect(jsonPath("$.status").value("DELIVERED"))
                .andExpect(jsonPath("$.nextLegIndex").value(2));
    }

    @Test
    void recover_finishingItineraryDeliversImmediately() throws Exception {
        setupThreeBagsSealed();
        // BAG1 行程只有 LEG1，差异到达时空集 -> BAG1 短卸
        discrepancyArrive("LEG1", 3, List.of("BAG2", "BAG3")).andExpect(status().isOk());

        recover("BAG1", "LEG1", "SHA").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DELIVERED"))
                .andExpect(jsonPath("$.nextLegIndex").value(1));
        mockMvc.perform(get("/api/bags/BAG1/trace"))
                .andExpect(jsonPath("$.status").value("DELIVERED"))
                .andExpect(jsonPath("$.trace[*].eventType",
                        contains("REGISTERED", "LOADED", "SHORT_UNLOADED", "RECOVERED", "DELIVERED")));
    }

    @Test
    void recover_rejectsWrongStationLegAndState() throws Exception {
        setupThreeBagsSealed();
        discrepancyArrive("LEG1", 3, List.of("BAG1", "BAG3")).andExpect(status().isOk());

        // 实际站不等于缺失航段到达站 -> 422
        recover("BAG2", "LEG1", "CAN").andExpect(status().isUnprocessableEntity());
        // 缺失航段不匹配 -> 409
        recover("BAG2", "LEG2", "SHA").andExpect(status().isConflict());
        // 非短卸状态（BAG3 已正常到达）-> 422
        recover("BAG3", "LEG1", "SHA").andExpect(status().isUnprocessableEntity());
        // 未知行李 -> 404
        recover("BAG_MISSING", "LEG1", "SHA").andExpect(status().isNotFound());

        // 失败均未改变 BAG2
        mockMvc.perform(get("/api/bags/BAG2/trace"))
                .andExpect(jsonPath("$.status").value("SHORT_UNLOADED"))
                .andExpect(jsonPath("$.currentLocation").value("PEK"))
                .andExpect(jsonPath("$.nextLegIndex").value(0));

        // 正确补到后再次补到 -> 422，不得重复推进
        recover("BAG2", "LEG1", "SHA").andExpect(status().isOk());
        recover("BAG2", "LEG1", "SHA").andExpect(status().isUnprocessableEntity());
        mockMvc.perform(get("/api/bags/BAG2/trace"))
                .andExpect(jsonPath("$.nextLegIndex").value(1));
    }

    @Test
    void load_nextLegBatchRollsBackWhileAnyBagShort() throws Exception {
        setupThreeBagsSealed();
        discrepancyArrive("LEG1", 3, List.of("BAG3")).andExpect(status().isOk());
        // BAG1 已交付、BAG2 短卸；尝试把 BAG2 与已到达的 BAG3 同批装入 LEG2
        load("LEG2", 1, List.of("BAG3", "BAG2")).andExpect(status().isUnprocessableEntity());

        // 整批不移动：BAG3 也未装载
        mockMvc.perform(get("/api/bags/BAG3/trace"))
                .andExpect(jsonPath("$.loadedLegId").doesNotExist())
                .andExpect(jsonPath("$.currentLocation").value("SHA"));
        Integer loadCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM load_record", Integer.class);
        org.assertj.core.api.Assertions.assertThat(loadCount).isZero();

        // 补到后整批可装载
        recover("BAG2", "LEG1", "SHA").andExpect(status().isOk());
        load("LEG2", 1, List.of("BAG2", "BAG3")).andExpect(status().isOk())
                .andExpect(jsonPath("$.loaded", contains("BAG2", "BAG3")));
    }

    @Test
    void idempotency_discrepancyAndRecoverReplayAndConflict() throws Exception {
        setupThreeBagsSealed();

        String discrepancyId = UUID.randomUUID().toString();
        Map<String, Object> discrepancyBody = Map.of(
                "requestId", discrepancyId, "expectedVersion", 3,
                "actualBagTags", List.of("BAG1", "BAG3"));
        postJson("/api/legs/LEG1/arrive-discrepancy", discrepancyBody).andExpect(status().isOk())
                .andExpect(jsonPath("$.shortUnloaded", contains("BAG2")));
        // 同键同参重放：原结果，航段版本不重复推进
        postJson("/api/legs/LEG1/arrive-discrepancy", discrepancyBody).andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(4));
        mockMvc.perform(get("/api/legs/LEG1/manifest"))
                .andExpect(jsonPath("$.version").value(4));

        // 同键改实际集合 -> 409
        postJson("/api/legs/LEG1/arrive-discrepancy", Map.of(
                "requestId", discrepancyId, "expectedVersion", 3,
                "actualBagTags", List.of("BAG1")))
                .andExpect(status().isConflict());

        String recoverId = UUID.randomUUID().toString();
        Map<String, Object> recoverBody = Map.of(
                "requestId", recoverId, "missingLegId", "LEG1", "actualStation", "SHA");
        postJson("/api/bags/BAG2/recover", recoverBody).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RECOVERED"))
                .andExpect(jsonPath("$.nextLegIndex").value(1));
        // 同键同参重放原结果，不重复推进
        postJson("/api/bags/BAG2/recover", recoverBody).andExpect(status().isOk())
                .andExpect(jsonPath("$.nextLegIndex").value(1));
        // 同键改站 -> 409
        postJson("/api/bags/BAG2/recover", Map.of(
                "requestId", recoverId, "missingLegId", "LEG1", "actualStation", "CAN"))
                .andExpect(status().isConflict());
        // 同键改航段 -> 409
        postJson("/api/bags/BAG2/recover", Map.of(
                "requestId", recoverId, "missingLegId", "LEG2", "actualStation", "SHA"))
                .andExpect(status().isConflict());

        Integer traceCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bag_trace_event WHERE bag_tag = 'BAG2' AND event_type = 'RECOVERED'",
                Integer.class);
        org.assertj.core.api.Assertions.assertThat(traceCount).isEqualTo(1);
        Integer logs = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id IN (?, ?)",
                Integer.class, discrepancyId, recoverId);
        org.assertj.core.api.Assertions.assertThat(logs).isEqualTo(2);
    }

    @Test
    void idempotency_failedDiscrepancyDoesNotOccupyKey() throws Exception {
        setupThreeBagsSealed();
        String requestId = UUID.randomUUID().toString();
        // 先以清单外袋号失败（422），不占键
        postJson("/api/legs/LEG1/arrive-discrepancy", Map.of(
                "requestId", requestId, "expectedVersion", 3,
                "actualBagTags", List.of("BAG9")))
                .andExpect(status().isUnprocessableEntity());
        // 同键修正为合法子集后成功
        postJson("/api/legs/LEG1/arrive-discrepancy", Map.of(
                "requestId", requestId, "expectedVersion", 3,
                "actualBagTags", List.of("BAG1", "BAG2", "BAG3")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shortUnloaded", hasSize(0)));
    }

    @Test
    void snapshot_returnsNullModeBeforeArrivalAnd404ForUnknownLeg() throws Exception {
        registerLeg("LEG1", "PEK", "SHA");
        mockMvc.perform(get("/api/legs/LEG1/discrepancy"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.arrivalMode").value(nullValue()))
                .andExpect(jsonPath("$.arrived").value(nullValue()));
        mockMvc.perform(get("/api/legs/NOPE/discrepancy")).andExpect(status().isNotFound());
    }

    private ResultActions registerLeg(String legId, String origin, String destination) throws Exception {
        return postJson("/api/legs", Map.of("requestId", UUID.randomUUID().toString(),
                "legId", legId, "origin", origin, "destination", destination));
    }

    private ResultActions registerBag(String bagTag, List<String> legIds) throws Exception {
        return postJson("/api/bags", Map.of("requestId", UUID.randomUUID().toString(),
                "bagTag", bagTag, "legIds", legIds));
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

    private ResultActions discrepancyArrive(String legId, int expectedVersion, List<String> actualBagTags)
            throws Exception {
        return postJson("/api/legs/" + legId + "/arrive-discrepancy", Map.of(
                "requestId", UUID.randomUUID().toString(),
                "expectedVersion", expectedVersion, "actualBagTags", actualBagTags));
    }

    private ResultActions recover(String bagTag, String missingLegId, String actualStation) throws Exception {
        return postJson("/api/bags/" + bagTag + "/recover", Map.of(
                "requestId", UUID.randomUUID().toString(),
                "missingLegId", missingLegId, "actualStation", actualStation));
    }

    private ResultActions postJson(String url, Object body) throws Exception {
        return mockMvc.perform(post(url)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }
}
