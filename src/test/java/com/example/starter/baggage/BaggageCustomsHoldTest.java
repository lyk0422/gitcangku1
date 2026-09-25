package com.example.starter.baggage;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.example.starter.baggage.BaggageDtos.CustomsHoldConfirmRequest;
import com.example.starter.baggage.BaggageDtos.CustomsHoldRequest;
import com.example.starter.baggage.BaggageDtos.LoadRequest;
import com.example.starter.baggage.BaggageDtos.RegisterBagRequest;
import com.example.starter.baggage.BaggageDtos.RegisterLegRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 海关暂扣与后续航段交接阻断测试：覆盖暂扣状态机、OPEN 清单原子移除、
 * 暂扣期间装载/补到阻断（409 带暂扣地点）、双人解除确认（重复确认 422、不可替换撤销）、
 * 解除后不自动恢复装载、暂扣历史/待第二人清单/阻断原因查询，以及真实 H2 上的并发与幂等边界。
 */
@SpringBootTest
@AutoConfigureMockMvc
class BaggageCustomsHoldTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-09-25T01:02:03Z");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private BaggageService baggageService;

    private ExecutorService executor;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM customs_hold_release");
        jdbcTemplate.update("DELETE FROM customs_hold_confirmation");
        jdbcTemplate.update("DELETE FROM customs_hold");
        jdbcTemplate.update("DELETE FROM bag_event");
        jdbcTemplate.update("DELETE FROM load_record");
        jdbcTemplate.update("DELETE FROM bag_itinerary");
        jdbcTemplate.update("DELETE FROM bag");
        jdbcTemplate.update("DELETE FROM leg");
        jdbcTemplate.update("DELETE FROM request_log");
        baggageService.setClock(() -> FIXED_NOW);
        executor = Executors.newFixedThreadPool(8);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        executor.shutdownNow();
        assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void hold_unloadedBagBecomesCustomsHoldAndQueriesExposeIt() throws Exception {
        registerLeg("LEG1", "PEK", "SHA");
        registerBag("BAG1", List.of("LEG1"));

        hold("HOLD1", "BAG1", "PEK_CUSTOMS", "查验异常").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.confirmations").value(0))
                .andExpect(jsonPath("$.previousStatus").value("IN_TRANSIT"))
                .andExpect(jsonPath("$.holdLocation").value("PEK_CUSTOMS"))
                .andExpect(jsonPath("$.heldAt").value(FIXED_NOW.toString()));

        mockMvc.perform(get("/api/bags/BAG1/trace"))
                .andExpect(jsonPath("$.status").value("CUSTOMS_HOLD"))
                .andExpect(jsonPath("$.activeHoldKey").value("HOLD1"))
                .andExpect(jsonPath("$.currentLocation").value("PEK_CUSTOMS"))
                .andExpect(jsonPath("$.loadedLegId").doesNotExist())
                .andExpect(jsonPath("$.events[*].eventType", contains("REGISTERED", "CUSTOMS_HELD")));

        mockMvc.perform(get("/api/bags/BAG1/blocking-reasons"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.blocked").value(true))
                .andExpect(jsonPath("$.reasons", hasSize(1)))
                .andExpect(jsonPath("$.reasons[0].reasonType").value("CUSTOMS_HOLD"))
                .andExpect(jsonPath("$.reasons[0].holdKey").value("HOLD1"))
                .andExpect(jsonPath("$.reasons[0].holdLocation").value("PEK_CUSTOMS"))
                .andExpect(jsonPath("$.reasons[0].message", containsString("PEK_CUSTOMS")));

        mockMvc.perform(get("/api/bags/BAG1/customs-holds"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.holds", hasSize(1)))
                .andExpect(jsonPath("$.holds[0].holdKey").value("HOLD1"))
                .andExpect(jsonPath("$.holds[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.holds[0].reason").value("查验异常"))
                .andExpect(jsonPath("$.holds[0].release").doesNotExist());

        // 待第二人清单：尚无确认，不出现
        mockMvc.perform(get("/api/customs-holds/pending-second-confirmation"))
                .andExpect(jsonPath("$.pending", hasSize(0)));

        // 暂扣记录不可变：无 status 可变列，且只插入一条
        Integer holdRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM customs_hold WHERE hold_key = 'HOLD1'", Integer.class);
        assertThat(holdRows).isEqualTo(1);
    }

    @Test
    void hold_bagInOpenManifestIsAtomicallyRemovedAndVersionBumps() throws Exception {
        registerLeg("LEG1", "PEK", "SHA");
        registerBag("BAG1", List.of("LEG1"));
        registerBag("BAG2", List.of("LEG1"));
        load("LEG1", 1, List.of("BAG1", "BAG2"));

        hold("HOLD1", "BAG1", "PEK_CUSTOMS", "单证不符").andExpect(status().isOk());

        // 行李已从 OPEN 清单原子移除，航段版本递增
        Integer records = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM load_record WHERE bag_tag = 'BAG1'", Integer.class);
        assertThat(records).isZero();
        mockMvc.perform(get("/api/legs/LEG1/manifest"))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.version").value(3));

        // 封舱后的只读清单不再包含被暂扣行李
        seal("LEG1", 3);
        mockMvc.perform(get("/api/legs/LEG1/manifest"))
                .andExpect(jsonPath("$.status").value("SEALED"))
                .andExpect(jsonPath("$.manifest", contains("BAG2")));
    }

    @Test
    void hold_rejectsDeliveredShortUnloadedSealedAndDoubleHold() throws Exception {
        registerLeg("LEG1", "PEK", "SHA");
        registerLeg("LEG2", "SHA", "CAN");
        registerBag("BAG_DELIVERED", List.of("LEG1"));
        registerBag("BAG_SHORT", List.of("LEG1", "LEG2"));
        load("LEG1", 1, List.of("BAG_DELIVERED", "BAG_SHORT"));
        seal("LEG1", 2);
        // 差异到达：BAG_DELIVERED 到达交付，BAG_SHORT 短卸（视为已丢失）
        diffArrive("LEG1", 3, List.of("BAG_DELIVERED"));

        // 已到达最终目的地 -> 409
        hold("HOLD_D", "BAG_DELIVERED", "SHA", "x").andExpect(status().isConflict());
        // 已丢失（短卸）-> 409
        hold("HOLD_S", "BAG_SHORT", "PEK", "x").andExpect(status().isConflict());

        // SEALED 航段上的行李 -> 409
        registerLeg("LEG3", "PEK", "SHA");
        registerBag("BAG_SEAL", List.of("LEG3"));
        load("LEG3", 1, List.of("BAG_SEAL"));
        seal("LEG3", 2);
        hold("HOLD_SEAL", "BAG_SEAL", "PEK_CUSTOMS", "x").andExpect(status().isConflict());

        // 已暂扣 -> 409 且带暂扣地点；holdKey 重复 -> 409；行李不存在 -> 404
        registerLeg("LEG4", "PEK", "SHA");
        registerBag("BAG_H", List.of("LEG4"));
        hold("HOLD_H", "BAG_H", "PEK_CUSTOMS", "x").andExpect(status().isOk());
        mockMvc.perform(post("/api/bags/customs-hold")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "requestId", UUID.randomUUID().toString(),
                                "holdKey", "HOLD_OTHER", "bagTag", "BAG_H",
                                "holdLocation", "PEK_CUSTOMS", "reason", "x"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", containsString("PEK_CUSTOMS")));
        hold("HOLD_H", "BAG_H", "PEK_CUSTOMS", "x").andExpect(status().isConflict());
        hold("HOLD_X", "BAG_MISSING", "PEK", "x").andExpect(status().isNotFound());
    }

    @Test
    void heldBag_loadAndRecoverAreBlockedWith409AndLocation() throws Exception {
        registerLeg("LEG1", "PEK", "SHA");
        registerLeg("LEG2", "SHA", "CAN");
        registerBag("BAG1", List.of("LEG1", "LEG2"));
        registerBag("BAG2", List.of("LEG1"));
        hold("HOLD1", "BAG1", "PEK_CUSTOMS", "查验").andExpect(status().isOk());

        // 暂扣后装载后续航段 -> 409，消息带暂扣地点；整批不移动，BAG2 也不进清单
        load("LEG1", 1, List.of("BAG2", "BAG1")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", containsString("PEK_CUSTOMS")));
        Integer records = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM load_record", Integer.class);
        assertThat(records).isZero();

        // 暂扣行李补到确认 -> 409
        mockMvc.perform(post("/api/bags/recover")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "requestId", UUID.randomUUID().toString(),
                                "bagTag", "BAG1", "missingLegId", "LEG1", "actualStation", "PEK"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", containsString("PEK_CUSTOMS")));
    }

    @Test
    void release_requiresTwoDifferentOperatorsAndRestoresBagAtomically() throws Exception {
        registerLeg("LEG1", "PEK", "SHA");
        registerLeg("LEG2", "SHA", "CAN");
        registerBag("BAG1", List.of("LEG1", "LEG2"));
        hold("HOLD1", "BAG1", "PEK_CUSTOMS", "查验").andExpect(status().isOk());

        // 第一人确认：仍 ACTIVE，进入待第二人清单
        confirm("HOLD1", "OFFICER_A").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.confirmations").value(1))
                .andExpect(jsonPath("$.operators[0].operatorId").value("OFFICER_A"))
                .andExpect(jsonPath("$.release").doesNotExist());
        mockMvc.perform(get("/api/customs-holds/pending-second-confirmation"))
                .andExpect(jsonPath("$.pending", hasSize(1)))
                .andExpect(jsonPath("$.pending[0].holdKey").value("HOLD1"))
                .andExpect(jsonPath("$.pending[0].bagTag").value("BAG1"))
                .andExpect(jsonPath("$.pending[0].firstOperatorId").value("OFFICER_A"));

        // 同一人重复确认 -> 422；已有确认不可替换或撤销：仍只有 1 人、行李仍暂扣
        confirm("HOLD1", "OFFICER_A").andExpect(status().isUnprocessableEntity());
        mockMvc.perform(get("/api/customs-holds/pending-second-confirmation"))
                .andExpect(jsonPath("$.pending", hasSize(1)));
        mockMvc.perform(get("/api/bags/BAG1/trace"))
                .andExpect(jsonPath("$.status").value("CUSTOMS_HOLD"));
        Integer confirmationCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM customs_hold_confirmation WHERE hold_key = 'HOLD1'", Integer.class);
        assertThat(confirmationCount).isEqualTo(1);

        // 第二人不同操作人确认：原子解除并固化两人及时刻
        confirm("HOLD1", "OFFICER_B").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RELEASED"))
                .andExpect(jsonPath("$.confirmations").value(2))
                .andExpect(jsonPath("$.release.firstOperatorId").value("OFFICER_A"))
                .andExpect(jsonPath("$.release.secondOperatorId").value("OFFICER_B"))
                .andExpect(jsonPath("$.release.releasedAt").value(FIXED_NOW.toString()));

        mockMvc.perform(get("/api/bags/BAG1/trace"))
                .andExpect(jsonPath("$.status").value("IN_TRANSIT"))
                .andExpect(jsonPath("$.activeHoldKey").doesNotExist())
                .andExpect(jsonPath("$.events[*].eventType",
                        contains("REGISTERED", "CUSTOMS_HELD", "CUSTOMS_HOLD_RELEASED")));
        mockMvc.perform(get("/api/bags/BAG1/customs-holds"))
                .andExpect(jsonPath("$.holds[0].status").value("RELEASED"))
                .andExpect(jsonPath("$.holds[0].confirmations", hasSize(2)))
                .andExpect(jsonPath("$.holds[0].release.firstOperatorId").value("OFFICER_A"))
                .andExpect(jsonPath("$.holds[0].release.secondOperatorId").value("OFFICER_B"));
        mockMvc.perform(get("/api/customs-holds/pending-second-confirmation"))
                .andExpect(jsonPath("$.pending", hasSize(0)));

        // 解除后再确认 -> 409
        confirm("HOLD1", "OFFICER_C").andExpect(status().isConflict());
        // 解除记录一暂扣仅一条
        Integer releaseRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM customs_hold_release WHERE hold_key = 'HOLD1'", Integer.class);
        assertThat(releaseRows).isEqualTo(1);
    }

    @Test
    void release_restoresRecoveredStatusAndUnknownHoldIs404() throws Exception {
        registerLeg("LEG1", "PEK", "SHA");
        registerLeg("LEG2", "SHA", "CAN");
        registerBag("BAG1", List.of("LEG1", "LEG2"));
        // 短卸后补到：行李在 SHA 待乘 LEG2，状态 RECOVERED
        load("LEG1", 1, List.of("BAG1"));
        seal("LEG1", 2);
        diffArrive("LEG1", 3, List.of());
        recover("BAG1", "LEG1", "SHA").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RECOVERED"));

        hold("HOLD1", "BAG1", "SHA_CUSTOMS", "查验").andExpect(status().isOk());
        confirm("HOLD1", "A");
        confirm("HOLD1", "B").andExpect(status().isOk());
        mockMvc.perform(get("/api/bags/BAG1/trace"))
                .andExpect(jsonPath("$.status").value("RECOVERED"))
                .andExpect(jsonPath("$.activeHoldKey").doesNotExist());

        confirm("HOLD_MISSING", "A").andExpect(status().isNotFound());
    }

    @Test
    void afterRelease_blockedOperationsAreNotAutoRestoredAndMustResubmit() throws Exception {
        registerLeg("LEG1", "PEK", "SHA");
        registerBag("BAG1", List.of("LEG1"));
        load("LEG1", 1, List.of("BAG1"));
        // 暂扣地点即始发站关内，解除后按当前航段规则仍可重新交接到同段
        hold("HOLD1", "BAG1", "PEK", "查验").andExpect(status().isOk());
        confirm("HOLD1", "A");
        confirm("HOLD1", "B").andExpect(status().isOk());

        // 解除不会自动恢复此前装载：清单仍为空，阻断原因清空
        Integer records = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM load_record WHERE bag_tag = 'BAG1'", Integer.class);
        assertThat(records).isZero();
        mockMvc.perform(get("/api/bags/BAG1/blocking-reasons"))
                .andExpect(jsonPath("$.blocked").value(false))
                .andExpect(jsonPath("$.reasons", hasSize(0)));

        // 须重新提交装载，并按当前版本（暂扣移除已推进版本）校验
        load("LEG1", 3, List.of("BAG1")).andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(4));

        // 再次暂扣后用旧版本号重新提交 -> 409
        hold("HOLD2", "BAG1", "PEK", "再次查验").andExpect(status().isOk());
        load("LEG1", 4, List.of("BAG1")).andExpect(status().isConflict());
    }

    @Test
    void idempotency_holdAndConfirmReplayConflictAndFailureNotOccupyingKey() throws Exception {
        registerLeg("LEG1", "PEK", "SHA");
        registerBag("BAG1", List.of("LEG1"));
        registerBag("BAG2", List.of("LEG1"));

        // 暂扣同键同参重放
        String holdRequestId = UUID.randomUUID().toString();
        Map<String, Object> holdBody = Map.of("requestId", holdRequestId, "holdKey", "HOLD1",
                "bagTag", "BAG1", "holdLocation", "PEK_CUSTOMS", "reason", "查验");
        postJson("/api/bags/customs-hold", holdBody).andExpect(status().isOk());
        postJson("/api/bags/customs-hold", holdBody).andExpect(status().isOk());
        Integer holdRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM customs_hold", Integer.class);
        assertThat(holdRows).isEqualTo(1);
        // 同 requestId 异参 -> 409
        postJson("/api/bags/customs-hold", Map.of("requestId", holdRequestId, "holdKey", "HOLD1",
                "bagTag", "BAG1", "holdLocation", "OTHER_PLACE", "reason", "查验"))
                .andExpect(status().isConflict());

        // 失败不占键：先用该 requestId 对不存在行李暂扣（404），再用于合法暂扣成功
        String retryHoldKey = UUID.randomUUID().toString();
        postJson("/api/bags/customs-hold", Map.of("requestId", retryHoldKey, "holdKey", "HOLD_BAD",
                "bagTag", "BAG_MISSING", "holdLocation", "PEK", "reason", "x"))
                .andExpect(status().isNotFound());
        postJson("/api/bags/customs-hold", Map.of("requestId", retryHoldKey, "holdKey", "HOLD2",
                "bagTag", "BAG2", "holdLocation", "PEK_CUSTOMS", "reason", "x"))
                .andExpect(status().isOk());

        // 确认同键同参重放第一人结果（ACTIVE/1 人），不重复插入确认
        String confirmRequestId = UUID.randomUUID().toString();
        Map<String, Object> confirmBody = Map.of("requestId", confirmRequestId,
                "holdKey", "HOLD1", "operatorId", "A");
        postJson("/api/bags/customs-hold/confirm", confirmBody).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        postJson("/api/bags/customs-hold/confirm", confirmBody).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.confirmations").value(1));
        Integer confirmationRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM customs_hold_confirmation WHERE hold_key = 'HOLD1'", Integer.class);
        assertThat(confirmationRows).isEqualTo(1);

        // 同 requestId 异参 -> 409（幂等层冲突，区别于业务 422）
        postJson("/api/bags/customs-hold/confirm", Map.of("requestId", confirmRequestId,
                "holdKey", "HOLD1", "operatorId", "B"))
                .andExpect(status().isConflict());

        // 失败不占键：新 requestId 先以已确认过的操作人 A 提交（业务 422），
        // 再以同一 requestId 换不同操作人 B 提交，即为第二人确认并解除
        String secondConfirmRequestId = UUID.randomUUID().toString();
        postJson("/api/bags/customs-hold/confirm", Map.of("requestId", secondConfirmRequestId,
                "holdKey", "HOLD1", "operatorId", "A"))
                .andExpect(status().isUnprocessableEntity());
        postJson("/api/bags/customs-hold/confirm", Map.of("requestId", secondConfirmRequestId,
                "holdKey", "HOLD1", "operatorId", "B"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RELEASED"));
        // 同键同参重放第二人确认：返回首次的 RELEASED 结果，不重复写解除记录
        postJson("/api/bags/customs-hold/confirm", Map.of("requestId", secondConfirmRequestId,
                "holdKey", "HOLD1", "operatorId", "B"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RELEASED"))
                .andExpect(jsonPath("$.release.secondOperatorId").value("B"));
        // 新 requestId 在解除后再确认 -> 409
        confirm("HOLD1", "C").andExpect(status().isConflict());
        Integer releaseRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM customs_hold_release WHERE hold_key = 'HOLD1'", Integer.class);
        assertThat(releaseRows).isEqualTo(1);
        Integer firstLogs = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = ?", Integer.class, confirmRequestId);
        assertThat(firstLogs).isEqualTo(1);
        Integer secondLogs = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = ?", Integer.class, secondConfirmRequestId);
        assertThat(secondLogs).isEqualTo(1);
    }

    @Test
    void concurrentTwoHoldsSameBag_onlyOneSucceeds() throws Exception {
        registerLeg("LEG1", "PEK", "SHA");
        registerBag("BAG1", List.of("LEG1"));

        List<Callable<Object>> tasks = List.of(
                () -> baggageService.customsHold(new CustomsHoldRequest(
                        UUID.randomUUID().toString(), "HOLD1", "BAG1", "PEK", "x")),
                () -> baggageService.customsHold(new CustomsHoldRequest(
                        UUID.randomUUID().toString(), "HOLD2", "BAG1", "PEK", "x")));
        List<Object> results = runConcurrently(tasks);

        long successes = results.stream()
                .filter(BaggageDtos.CustomsHoldResponse.class::isInstance).count();
        long conflicts = results.stream()
                .filter(ApiException.class::isInstance).map(ApiException.class::cast)
                .filter(ex -> ex.getStatus().value() == 409).count();
        assertThat(successes).isEqualTo(1);
        assertThat(conflicts).isEqualTo(1);
        Integer holdRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM customs_hold WHERE bag_tag = 'BAG1'", Integer.class);
        assertThat(holdRows).isEqualTo(1);
        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM bag WHERE bag_tag = 'BAG1'", String.class);
        assertThat(status).isEqualTo("CUSTOMS_HOLD");
    }

    @Test
    void concurrentSealAndHold_commitOrderIsArbitratedAndManifestNeverContainsHeldBag() throws Exception {
        registerLeg("LEG1", "PEK", "SHA");
        registerBag("BAG1", List.of("LEG1"));
        load("LEG1", 1, List.of("BAG1"));

        List<Callable<Object>> tasks = List.of(
                () -> baggageService.seal("LEG1",
                        new BaggageDtos.SealRequest(UUID.randomUUID().toString(), 2)),
                () -> baggageService.customsHold(new CustomsHoldRequest(
                        UUID.randomUUID().toString(), "HOLD1", "BAG1", "PEK_CUSTOMS", "x")));
        List<Object> results = runConcurrently(tasks);

        boolean sealedFirst = results.stream()
                .filter(ApiException.class::isInstance).map(ApiException.class::cast)
                .anyMatch(ex -> ex.getStatus().value() == 409
                        && ex.getMessage().contains("封舱"));
        String bagStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM bag WHERE bag_tag = 'BAG1'", String.class);
        String legStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM leg WHERE leg_id = 'LEG1'", String.class);
        if (sealedFirst) {
            // 封舱先提交：暂扣 409，行李仍在只读封舱清单
            assertThat(bagStatus).isEqualTo("IN_TRANSIT");
            assertThat(legStatus).isEqualTo("SEALED");
            assertThat(baggageService.getManifest("LEG1").manifest()).containsExactly("BAG1");
        } else {
            // 暂扣先提交：行李被移除并暂扣，封舱因版本冲突 409，清单不含该行李
            assertThat(bagStatus).isEqualTo("CUSTOMS_HOLD");
            assertThat(legStatus).isEqualTo("OPEN");
            Integer legVersion = jdbcTemplate.queryForObject(
                    "SELECT version FROM leg WHERE leg_id = 'LEG1'", Integer.class);
            assertThat(legVersion).isEqualTo(3);
            assertThat(baggageService.getManifest("LEG1").manifest()).doesNotContain("BAG1");
            Integer loadRows = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM load_record WHERE bag_tag = 'BAG1'", Integer.class);
            assertThat(loadRows).isZero();
            // 暂扣先提交后，任何装载尝试均 409
            assertLoadBlocked("LEG1", 3);
        }
    }

    @Test
    void concurrentLoadAndHold_holdFirstBlocksLoadOtherwiseRemovesFromOpenManifest() throws Exception {
        registerLeg("LEG1", "PEK", "SHA");
        registerBag("BAG1", List.of("LEG1"));

        List<Callable<Object>> tasks = List.of(
                () -> baggageService.load("LEG1",
                        new LoadRequest(UUID.randomUUID().toString(), 1, List.of("BAG1"))),
                () -> baggageService.customsHold(new CustomsHoldRequest(
                        UUID.randomUUID().toString(), "HOLD1", "BAG1", "PEK", "x")));
        List<Object> results = runConcurrently(tasks);

        boolean loadSucceeded = results.stream()
                .anyMatch(BaggageDtos.LoadResponse.class::isInstance);
        // 极小窗口下暂扣可能因装载刚提交而收到并发修改 409，允许重试后串行移除
        ApiException holdError = results.stream()
                .filter(ApiException.class::isInstance).map(ApiException.class::cast)
                .filter(ex -> ex.getMessage().contains("处理期间发生变化"))
                .findFirst().orElse(null);
        if (holdError != null) {
            baggageService.customsHold(new CustomsHoldRequest(
                    UUID.randomUUID().toString(), "HOLD1", "BAG1", "PEK", "x"));
        }

        // 裁决后稳定状态：行李暂扣，且不在任何装载清单
        String bagStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM bag WHERE bag_tag = 'BAG1'", String.class);
        assertThat(bagStatus).isEqualTo("CUSTOMS_HOLD");
        Integer loadRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM load_record WHERE bag_tag = 'BAG1'", Integer.class);
        assertThat(loadRows).isZero();
        if (!loadSucceeded) {
            // 暂扣先提交：装载必为 409
            long loadConflicts = results.stream()
                    .filter(ApiException.class::isInstance).map(ApiException.class::cast)
                    .filter(ex -> ex.getStatus().value() == 409).count();
            assertThat(loadConflicts).isGreaterThanOrEqualTo(1);
        }
    }

    @Test
    void concurrentThreeOperatorsConfirm_exactlyTwoSucceedAndThirdRejectedAfterRelease() throws Exception {
        registerLeg("LEG1", "PEK", "SHA");
        registerBag("BAG1", List.of("LEG1"));
        baggageService.customsHold(new CustomsHoldRequest(
                UUID.randomUUID().toString(), "HOLD1", "BAG1", "PEK", "x"));

        List<Callable<Object>> tasks = new ArrayList<>();
        for (String operator : List.of("A", "B", "C")) {
            tasks.add(() -> baggageService.customsHoldConfirm(new CustomsHoldConfirmRequest(
                    UUID.randomUUID().toString(), "HOLD1", operator)));
        }
        List<Object> results = runConcurrently(tasks);

        long firstConfirmations = results.stream()
                .filter(BaggageDtos.CustomsHoldConfirmResponse.class::isInstance)
                .map(BaggageDtos.CustomsHoldConfirmResponse.class::cast)
                .filter(r -> r.status().equals("ACTIVE") && r.confirmations() == 1).count();
        long releases = results.stream()
                .filter(BaggageDtos.CustomsHoldConfirmResponse.class::isInstance)
                .map(BaggageDtos.CustomsHoldConfirmResponse.class::cast)
                .filter(r -> r.status().equals("RELEASED")).count();
        long conflicts = results.stream()
                .filter(ApiException.class::isInstance).map(ApiException.class::cast)
                .filter(ex -> ex.getStatus().value() == 409).count();
        assertThat(firstConfirmations).isEqualTo(1);
        assertThat(releases).isEqualTo(1);
        assertThat(conflicts).isEqualTo(1);

        Integer releaseRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM customs_hold_release WHERE hold_key = 'HOLD1'", Integer.class);
        assertThat(releaseRows).isEqualTo(1);
        Integer confirmationRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM customs_hold_confirmation WHERE hold_key = 'HOLD1'", Integer.class);
        assertThat(confirmationRows).isEqualTo(2);
        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM bag WHERE bag_tag = 'BAG1'", String.class);
        assertThat(status).isEqualTo("IN_TRANSIT");
    }

    @Test
    void concurrentSameRequestIdConfirm_allReplaySingleEffect() throws Exception {
        registerLeg("LEG1", "PEK", "SHA");
        registerBag("BAG1", List.of("LEG1"));
        baggageService.customsHold(new CustomsHoldRequest(
                UUID.randomUUID().toString(), "HOLD1", "BAG1", "PEK", "x"));

        String requestId = UUID.randomUUID().toString();
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            tasks.add(() -> baggageService.customsHoldConfirm(
                    new CustomsHoldConfirmRequest(requestId, "HOLD1", "A")));
        }
        List<Object> results = runConcurrently(tasks);

        assertThat(results).allSatisfy(result -> {
            assertThat(result).isInstanceOf(BaggageDtos.CustomsHoldConfirmResponse.class);
            BaggageDtos.CustomsHoldConfirmResponse response =
                    (BaggageDtos.CustomsHoldConfirmResponse) result;
            assertThat(response.status()).isEqualTo("ACTIVE");
            assertThat(response.confirmations()).isEqualTo(1);
        });
        Integer rows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM customs_hold_confirmation WHERE hold_key = 'HOLD1'", Integer.class);
        assertThat(rows).isEqualTo(1);
        Integer logs = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = ?", Integer.class, requestId);
        assertThat(logs).isEqualTo(1);
    }

    private void assertLoadBlocked(String legId, int expectedVersion) {
        try {
            baggageService.load(legId,
                    new LoadRequest(UUID.randomUUID().toString(), expectedVersion, List.of("BAG1")));
            throw new AssertionError("暂扣先提交后装载必须 409");
        } catch (ApiException ex) {
            assertThat(ex.getStatus().value()).isEqualTo(409);
        }
    }

    private void registerLeg(String legId, String origin, String destination) {
        baggageService.registerLeg(
                new RegisterLegRequest(UUID.randomUUID().toString(), legId, origin, destination));
    }

    private void registerBag(String bagTag, List<String> legIds) {
        baggageService.registerBag(new RegisterBagRequest(UUID.randomUUID().toString(), bagTag, legIds));
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

    private ResultActions arriveExact(String legId, List<String> bagTags) throws Exception {
        return postJson("/api/legs/" + legId + "/arrive", Map.of(
                "requestId", UUID.randomUUID().toString(), "bagTags", bagTags));
    }

    private ResultActions diffArrive(String legId, int expectedVersion, List<String> bagTags) throws Exception {
        return postJson("/api/legs/" + legId + "/arrive-difference", Map.of(
                "requestId", UUID.randomUUID().toString(),
                "expectedVersion", expectedVersion, "bagTags", bagTags));
    }

    private ResultActions recover(String bagTag, String missingLegId, String actualStation) throws Exception {
        return postJson("/api/bags/recover", Map.of(
                "requestId", UUID.randomUUID().toString(),
                "bagTag", bagTag, "missingLegId", missingLegId, "actualStation", actualStation));
    }

    private ResultActions hold(String holdKey, String bagTag, String location, String reason) throws Exception {
        return postJson("/api/bags/customs-hold", Map.of(
                "requestId", UUID.randomUUID().toString(), "holdKey", holdKey,
                "bagTag", bagTag, "holdLocation", location, "reason", reason));
    }

    private ResultActions confirm(String holdKey, String operatorId) throws Exception {
        return postJson("/api/bags/customs-hold/confirm", Map.of(
                "requestId", UUID.randomUUID().toString(),
                "holdKey", holdKey, "operatorId", operatorId));
    }

    private ResultActions postJson(String url, Object body) throws Exception {
        return mockMvc.perform(post(url)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    /** 同步起跑并发执行任务，结果按提交顺序返回（异常包装为返回值）。 */
    private List<Object> runConcurrently(List<Callable<Object>> tasks) throws Exception {
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Object>> futures = new ArrayList<>();
        for (Callable<Object> task : tasks) {
            futures.add(executor.submit(() -> {
                ready.countDown();
                assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                try {
                    return task.call();
                } catch (ApiException ex) {
                    return ex;
                }
            }));
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        List<Object> results = new ArrayList<>();
        for (Future<Object> future : futures) {
            results.add(future.get(30, TimeUnit.SECONDS));
        }
        return results;
    }
}
