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

import com.example.starter.baggage.BaggageDtos.ClaimHoldResponse;
import com.example.starter.baggage.BaggageDtos.HoldClaimRequest;
import com.example.starter.baggage.BaggageDtos.LoadRequest;
import com.example.starter.baggage.BaggageDtos.LoadResponse;
import com.example.starter.baggage.BaggageDtos.RegisterBagRequest;
import com.example.starter.baggage.BaggageDtos.RegisterLegRequest;
import com.example.starter.baggage.BaggageDtos.SealRequest;
import com.example.starter.baggage.BaggageDtos.SealResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 行李认领冻结测试：覆盖登记（OPEN 清单同事务移出）、双客服复核、二次确认解除、
 * 短卸/装载/补到/交付闸门、封舱与冻结提交顺序裁决、容量校验、幂等边界、
 * 误交付差异与不可变链稳定性。全部基于 H2 MySQL 兼容模式真实数据库，不使用 mock。
 */
@SpringBootTest
@AutoConfigureMockMvc
class BaggageClaimHoldTest {

    private static final Instant T_HELD = Instant.parse("2026-09-28T08:00:00Z");
    private static final Instant T_REVIEWED = Instant.parse("2026-09-28T08:03:00Z");
    private static final Instant T_RELEASED = Instant.parse("2026-09-28T08:05:30Z");

    private static final String AGENT_A = "agent-a";
    private static final String REVIEWER_B = "reviewer-b";
    private static final String DIGEST = "PAX|NAME=ZHANGSAN|DOC=E12345678|FLIGHT=CA1858";

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
        jdbcTemplate.update("DELETE FROM claim_event");
        jdbcTemplate.update("DELETE FROM claim_hold");
        jdbcTemplate.update("DELETE FROM bag_event");
        jdbcTemplate.update("DELETE FROM load_record");
        jdbcTemplate.update("DELETE FROM bag_itinerary");
        jdbcTemplate.update("DELETE FROM bag");
        jdbcTemplate.update("DELETE FROM leg");
        jdbcTemplate.update("DELETE FROM request_log");
        baggageService.setClock(Instant::now);
        executor = Executors.newFixedThreadPool(8);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        executor.shutdownNow();
        assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }

    // ---------------------------------------------------------------------
    // 主流程
    // ---------------------------------------------------------------------

    @Test
    void holdRemovesOpenManifestInSameTransactionAndReleaseRestoresHandlable() {
        baggageService.setClock(() -> T_HELD);
        registerLeg("LEG1", "PEK", "SHA", null);
        registerLeg("LEG2", "SHA", "CAN", null);
        registerBag("BAG1", List.of("LEG1", "LEG2"));
        baggageService.load("LEG1", new LoadRequest(rid(), 1, List.of("BAG1")));

        // 登记冻结：同从事务移出 OPEN 清单，航段版本推进
        ClaimHoldResponse held = hold("CLAIM-1", "BAG1", DIGEST, "旅客现场身份存疑", AGENT_A, "CS_AGENT");
        assertThat(held.status()).isEqualTo("ACTIVE");
        assertThat(held.priorStatus()).isEqualTo("IN_TRANSIT");
        assertThat(held.legId()).isEqualTo("LEG1");
        assertThat(held.heldBy()).isEqualTo(AGENT_A);
        assertThat(held.heldByRole()).isEqualTo("CS_AGENT");
        assertThat(held.heldAt()).isEqualTo(T_HELD.toString());
        assertThat(held.reviewedBy()).isNull();
        assertThat(held.releasedAt()).isNull();
        assertThat(held.heldDurationSeconds()).isZero();
        assertThat(held.events()).extracting(e -> e.eventType() + "|" + e.legId())
                .containsExactly("HELD|LEG1", "MANIFEST_REMOVED|LEG1");

        assertBagStatus("BAG1", "CLAIM_HOLD");
        assertThat(countLoadRecords("BAG1")).isZero();
        Integer versionAfterHold = legVersion("LEG1");
        assertThat(versionAfterHold).isEqualTo(3);
        String loadedLeg = jdbcTemplate.queryForObject(
                "SELECT loaded_leg_id FROM bag WHERE bag_tag = 'BAG1'", String.class);
        assertThat(loadedLeg).isNull();

        // 冻结期间装载/封舱后再加装均被拒
        ApiException loadEx = catchApi(() -> baggageService.load("LEG1",
                new LoadRequest(rid(), versionAfterHold, List.of("BAG1"))));
        assertThat(loadEx.getStatus().value()).isEqualTo(409);

        // 复核分支：错误角色、同人、错误摘要全部 422，且不改变状态
        baggageService.setClock(() -> T_REVIEWED);
        assertThat(catchApi(() -> review("CLAIM-1", DIGEST, AGENT_A, "CS_AGENT")).getStatus().value())
                .isEqualTo(422);
        assertThat(catchApi(() -> review("CLAIM-1", DIGEST, AGENT_A, "CS_REVIEWER")).getStatus().value())
                .isEqualTo(422);
        assertThat(catchApi(() -> review("CLAIM-1", "WRONG-DIGEST", REVIEWER_B, "CS_REVIEWER"))
                .getStatus().value()).isEqualTo(422);
        assertThat(getClaim("CLAIM-1").status()).isEqualTo("ACTIVE");
        assertThat(chainEventTypes("CLAIM-1")).containsExactly("HELD", "MANIFEST_REMOVED");

        // 未复核直接解除 -> 422
        assertThat(catchApi(() -> release("CLAIM-1", REVIEWER_B, "CS_REVIEWER")).getStatus().value())
                .isEqualTo(422);

        // 不同客服复核通过
        ClaimHoldResponse reviewed = review("CLAIM-1", DIGEST, REVIEWER_B, "CS_REVIEWER");
        assertThat(reviewed.status()).isEqualTo("REVIEWED");
        assertThat(reviewed.reviewedBy()).isEqualTo(REVIEWER_B);
        assertThat(reviewed.reviewedAt()).isEqualTo(T_REVIEWED.toString());
        // 重复复核 -> 409
        assertThat(catchApi(() -> review("CLAIM-1", DIGEST, REVIEWER_B, "CS_REVIEWER")).getStatus().value())
                .isEqualTo(409);
        // 二次确认必须是复核人本人
        assertThat(catchApi(() -> release("CLAIM-1", "reviewer-c", "CS_REVIEWER")).getStatus().value())
                .isEqualTo(422);

        // 第二次确认原子恢复
        baggageService.setClock(() -> T_RELEASED);
        ClaimHoldResponse released = release("CLAIM-1", REVIEWER_B, "CS_REVIEWER");
        assertThat(released.status()).isEqualTo("RELEASED");
        assertThat(released.restoredStatus()).isEqualTo("IN_TRANSIT");
        assertThat(released.releasedBy()).isEqualTo(REVIEWER_B);
        assertThat(released.releasedAt()).isEqualTo(T_RELEASED.toString());
        // 区间左闭右开时长（秒）：08:00:00 起，08:05:30 止（不含）
        assertThat(released.heldDurationSeconds()).isEqualTo(330L);
        assertBagStatus("BAG1", "IN_TRANSIT");

        // 旧装载请求不自动恢复：清单仍空，必须重提并按当前版本/容量校验
        assertThat(countLoadRecords("BAG1")).isZero();
        LoadResponse reload = baggageService.load("LEG1",
                new LoadRequest(rid(), versionAfterHold, List.of("BAG1")));
        assertThat(reload.version()).isEqualTo(4);
        assertThat(reload.loaded()).containsExactly("BAG1");
    }

    @Test
    void holdBlockedForSealedManifestDeliveredAndLostAndDuplicateActiveHold() {
        registerLeg("LEG1", "PEK", "SHA", null);
        registerBag("BAG1", List.of("LEG1"));
        registerBag("BAG2", List.of("LEG1"));
        baggageService.load("LEG1", new LoadRequest(rid(), 1, List.of("BAG1", "BAG2")));
        baggageService.seal("LEG1", new SealRequest(rid(), 2));

        // 已 SEALED：冻结 409
        ApiException sealedEx = catchApi(() -> hold("C1", "BAG1", DIGEST, "r", AGENT_A, "CS_AGENT"));
        assertThat(sealedEx.getStatus().value()).isEqualTo(409);

        // 精确到达交付后：409
        baggageService.arrive("LEG1", new com.example.starter.baggage.BaggageDtos.ArriveRequest(
                rid(), List.of("BAG1", "BAG2")));
        assertThat(catchApi(() -> hold("C2", "BAG1", DIGEST, "r", AGENT_A, "CS_AGENT"))
                .getStatus().value()).isEqualTo(409);

        // 已丢失（LOST 为既有状态机外的终态）：409
        registerLeg("LEG3", "PEK", "XIY", null);
        registerBag("BAG3", List.of("LEG3"));
        jdbcTemplate.update("UPDATE bag SET status = 'LOST' WHERE bag_tag = 'BAG3'");
        assertThat(catchApi(() -> hold("C3", "BAG3", DIGEST, "r", AGENT_A, "CS_AGENT"))
                .getStatus().value()).isEqualTo(409);

        // 同一行李同时只能一个生效冻结
        registerLeg("LEG4", "PEK", "KMG", null);
        registerBag("BAG4", List.of("LEG4"));
        hold("C4", "BAG4", DIGEST, "r", AGENT_A, "CS_AGENT");
        assertThat(catchApi(() -> hold("C4-DUP", "BAG4", DIGEST, "r", AGENT_A, "CS_AGENT"))
                .getStatus().value()).isEqualTo(409);
        Integer activeClaims = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM claim_hold WHERE bag_tag = 'BAG4' AND status IN ('ACTIVE','REVIEWED')",
                Integer.class);
        assertThat(activeClaims).isEqualTo(1);

        // 解除后允许再次冻结（新 claimKey，旧记录保留）
        review("C4", DIGEST, REVIEWER_B, "CS_REVIEWER");
        release("C4", REVIEWER_B, "CS_REVIEWER");
        ClaimHoldResponse second = hold("C4-2", "BAG4", DIGEST, "r2", AGENT_A, "CS_AGENT");
        assertThat(second.status()).isEqualTo("ACTIVE");
        Integer totalClaims = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM claim_hold WHERE bag_tag = 'BAG4'", Integer.class);
        assertThat(totalClaims).isEqualTo(2);
    }

    @Test
    void holdShortBagKeepsShortFieldsUntilReleaseAndBlocksRecover() {
        registerLeg("LEG1", "PEK", "SHA", null);
        registerLeg("LEG2", "SHA", "CAN", null);
        registerBag("BAG1", List.of("LEG1", "LEG2"));
        baggageService.load("LEG1", new LoadRequest(rid(), 1, List.of("BAG1")));
        baggageService.seal("LEG1", new SealRequest(rid(), 2));
        baggageService.arriveDifference("LEG1",
                new com.example.starter.baggage.BaggageDtos.DifferenceArriveRequest(
                        rid(), 3, List.of()));

        ClaimHoldResponse held = hold("SHORT-HOLD", "BAG1", DIGEST, "短卸后旅客主张错运", AGENT_A, "CS_AGENT");
        assertThat(held.priorStatus()).isEqualTo("SHORT_UNLOADED");
        // 不在任何 OPEN 清单：legId 为 NULL 且链中无 MANIFEST_REMOVED（null 含义稳定）
        assertThat(held.legId()).isNull();
        assertThat(held.events()).extracting("eventType").containsExactly("HELD");
        assertThat(held.events().get(0).legId()).isNull();

        assertBagStatus("BAG1", "CLAIM_HOLD");
        Map<String, Object> shortFields = jdbcTemplate.queryForMap(
                "SELECT short_leg_id, short_destination FROM bag WHERE bag_tag = 'BAG1'");
        assertThat(shortFields.get("short_leg_id")).isEqualTo("LEG1");
        assertThat(shortFields.get("short_destination")).isEqualTo("SHA");

        // 冻结期间补到确认被拒（409）
        ApiException recoverEx = catchApi(() -> baggageService.recover(
                new com.example.starter.baggage.BaggageDtos.RecoverRequest(
                        rid(), "BAG1", "LEG1", "SHA")));
        assertThat(recoverEx.getStatus().value()).isEqualTo(409);
        assertBagStatus("BAG1", "CLAIM_HOLD");

        review("SHORT-HOLD", DIGEST, REVIEWER_B, "CS_REVIEWER");
        ClaimHoldResponse released = release("SHORT-HOLD", REVIEWER_B, "CS_REVIEWER");
        assertThat(released.restoredStatus()).isEqualTo("SHORT_UNLOADED");
        assertBagStatus("BAG1", "SHORT_UNLOADED");
        // 解除后可正常补到
        baggageService.recover(new com.example.starter.baggage.BaggageDtos.RecoverRequest(
                rid(), "BAG1", "LEG1", "SHA"));
        assertBagStatus("BAG1", "RECOVERED");
    }

    @Test
    void heldBagOnSealedManifestCannotBeDelivered() {
        // 边界：正常流程下冻结会先移出 OPEN 清单，SEALED 舱单中出现 CLAIM_HOLD 行李只可能来自异常中间态。
        // 直接构造该中间态，验证到达交付闸门在真实 H2 上拒绝并整批回滚。
        registerLeg("LEG1", "PEK", "SHA", null);
        registerBag("BAG1", List.of("LEG1"));
        registerBag("BAG2", List.of("LEG1"));
        baggageService.load("LEG1", new LoadRequest(rid(), 1, List.of("BAG1", "BAG2")));
        hold("H1", "BAG1", DIGEST, "r", AGENT_A, "CS_AGENT");

        jdbcTemplate.update("INSERT INTO load_record (bag_tag, leg_id) VALUES ('BAG1', 'LEG1')");
        jdbcTemplate.update("UPDATE leg SET status = 'SEALED', version = 4,"
                + " sealed_manifest = '[\"BAG1\",\"BAG2\"]' WHERE leg_id = 'LEG1'");

        ApiException exactEx = catchApi(() -> baggageService.arrive("LEG1",
                new com.example.starter.baggage.BaggageDtos.ArriveRequest(
                        rid(), List.of("BAG1", "BAG2"))));
        assertThat(exactEx.getStatus().value()).isEqualTo(409);
        ApiException diffEx = catchApi(() -> baggageService.arriveDifference("LEG1",
                new com.example.starter.baggage.BaggageDtos.DifferenceArriveRequest(
                        rid(), 4, List.of("BAG1", "BAG2"))));
        assertThat(diffEx.getStatus().value()).isEqualTo(409);

        // 整批回滚：航段仍 SEALED，BAG2 未被推进交付
        assertThat(legStatus("LEG1")).isEqualTo("SEALED");
        assertBagStatus("BAG2", "IN_TRANSIT");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT next_leg_index FROM bag WHERE bag_tag = 'BAG2'", Integer.class)).isZero();
    }

    // ---------------------------------------------------------------------
    // 容量与诊断
    // ---------------------------------------------------------------------

    @Test
    void capacityCheckedOnLoadAndRecheckedAfterHoldReleaseResubmission() {
        registerLeg("LEG1", "PEK", "SHA", 1);
        registerBag("BAG1", List.of("LEG1"));
        registerBag("BAG2", List.of("LEG1"));

        LoadResponse first = baggageService.load("LEG1", new LoadRequest(rid(), 1, List.of("BAG1")));
        assertThat(first.capacity()).isEqualTo(1);
        assertThat(first.loadedCount()).isEqualTo(1);
        assertThat(first.remaining()).isZero();

        // 超容量：422 且消息给出实际值/要求值/差额
        ApiException over = catchApi(() -> baggageService.load("LEG1",
                new LoadRequest(rid(), 2, List.of("BAG2"))));
        assertThat(over.getStatus().value()).isEqualTo(422);
        assertThat(over.getMessage()).contains("容量上限 1").contains("合计要求 2").contains("差额 1");
        assertThat(countLoadRecords("BAG2")).isZero();

        // 冻结移出清单释放容量；解除后重提按当前容量通过
        hold("CAP-HOLD", "BAG1", DIGEST, "r", AGENT_A, "CS_AGENT");
        assertThat(countLoadRecords("BAG1")).isZero();
        review("CAP-HOLD", DIGEST, REVIEWER_B, "CS_REVIEWER");
        release("CAP-HOLD", REVIEWER_B, "CS_REVIEWER");
        LoadResponse again = baggageService.load("LEG1", new LoadRequest(rid(), 3, List.of("BAG1")));
        assertThat(again.loadedCount()).isEqualTo(1);
        assertThat(again.remaining()).isZero();
    }

    @Test
    void diagnosticsAndReadsAreReadOnlyAndUnlimitedCapacityShowsNulls() throws Exception {
        registerLeg("LEG1", "PEK", "SHA", null);
        registerLeg("LEG2", "SHA", "CAN", 2);
        registerBag("BAG1", List.of("LEG1", "LEG2"));

        mockMvc.perform(get("/api/bags/BAG1/diagnostics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_TRANSIT"))
                .andExpect(jsonPath("$.nextLegId").value("LEG1"))
                .andExpect(jsonPath("$.capacity").value(nullValue()))
                .andExpect(jsonPath("$.loadedCount").value(0))
                .andExpect(jsonPath("$.remaining").value(nullValue()))
                .andExpect(jsonPath("$.activeClaimKey").doesNotExist())
                .andExpect(jsonPath("$.handlable").value(true));

        hold("D-HOLD", "BAG1", DIGEST, "r", AGENT_A, "CS_AGENT");
        mockMvc.perform(get("/api/bags/BAG1/diagnostics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeClaimKey").value("D-HOLD"))
                .andExpect(jsonPath("$.handlable").value(false));

        // 连续读取不改变状态与链
        Integer eventsBefore = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM claim_event WHERE claim_key = 'D-HOLD'", Integer.class);
        mockMvc.perform(get("/api/bags/claims/D-HOLD")).andExpect(status().isOk());
        mockMvc.perform(get("/api/bags/BAG1/trace")).andExpect(status().isOk());
        mockMvc.perform(get("/api/bags/BAG1/diagnostics")).andExpect(status().isOk());
        Integer eventsAfter = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM claim_event WHERE claim_key = 'D-HOLD'", Integer.class);
        assertThat(eventsAfter).isEqualTo(eventsBefore);
        assertBagStatus("BAG1", "CLAIM_HOLD");

        mockMvc.perform(get("/api/bags/NOPE/diagnostics")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/bags/claims/NOPE")).andExpect(status().isNotFound());
    }

    // ---------------------------------------------------------------------
    // 误交付差异与不可变链
    // ---------------------------------------------------------------------

    @Test
    void misdeliveryRegistersDifferenceOnlyAndHistoryIsImmutable() {
        registerLeg("LEG1", "PEK", "SHA", null);
        registerLeg("LEG2", "SHA", "CAN", null);
        registerBag("BAG1", List.of("LEG1", "LEG2"));

        hold("IMM-1", "BAG1", DIGEST, "首次冻结", AGENT_A, "CS_AGENT");
        review("IMM-1", DIGEST, REVIEWER_B, "CS_REVIEWER");
        release("IMM-1", REVIEWER_B, "CS_REVIEWER");

        // 第二次冻结
        hold("IMM-2", "BAG1", DIGEST, "再次冻结", AGENT_A, "CS_AGENT");

        // 误交付只登记差异：行李仍 CLAIM_HOLD，认领记录不删除
        var diff1 = baggageService.registerDeliveryDifference(
                new com.example.starter.baggage.BaggageDtos.DeliveryDifferenceRequest(
                        rid(), "IMM-2", "LEG2", "冻结期间被误装上后续航段", REVIEWER_B, "CS_REVIEWER"));
        assertThat(diff1.discrepancyCount()).isEqualTo(1);
        var diff2 = baggageService.registerDeliveryDifference(
                new com.example.starter.baggage.BaggageDtos.DeliveryDifferenceRequest(
                        rid(), "IMM-2", "LEG2", "目的地误交付", REVIEWER_B, "CS_REVIEWER"));
        assertThat(diff2.discrepancyCount()).isEqualTo(2);
        assertBagStatus("BAG1", "CLAIM_HOLD");

        // 差异航段不存在 -> 422 且计数不增加（事务回滚）
        ApiException badLeg = catchApi(() -> baggageService.registerDeliveryDifference(
                new com.example.starter.baggage.BaggageDtos.DeliveryDifferenceRequest(
                        rid(), "IMM-2", "LEG_MISSING", "x", REVIEWER_B, "CS_REVIEWER")));
        assertThat(badLeg.getStatus().value()).isEqualTo(422);
        assertThat(getClaim("IMM-2").discrepancyCount()).isEqualTo(2);

        // 解除后不能再按冻结差异登记
        review("IMM-2", DIGEST, REVIEWER_B, "CS_REVIEWER");
        release("IMM-2", REVIEWER_B, "CS_REVIEWER");
        assertThat(catchApi(() -> baggageService.registerDeliveryDifference(
                new com.example.starter.baggage.BaggageDtos.DeliveryDifferenceRequest(
                        rid(), "IMM-2", "LEG2", "x", REVIEWER_B, "CS_REVIEWER")))
                .getStatus().value()).isEqualTo(422);

        // 旧链不被后续操作覆盖：IMM-1 链保持 HELD/REVIEWED/RELEASED，字段与顺序稳定
        ClaimHoldResponse first = getClaim("IMM-1");
        assertThat(first.status()).isEqualTo("RELEASED");
        assertThat(first.events()).extracting("eventType")
                .containsExactly("HELD", "REVIEWED", "RELEASED");
        assertThat(first.events().get(0).reason()).isEqualTo("首次冻结");
        assertThat(first.events().get(0).operatorId()).isEqualTo(AGENT_A);
        assertThat(first.events().get(1).operatorId()).isEqualTo(REVIEWER_B);
        assertThat(first.events().get(2).legId()).isNull();

        // IMM-2 链追加差异事件，seq 连续且时间精度为 UTC 字符串
        List<String> imm2Types = chainEventTypes("IMM-2");
        assertThat(imm2Types).containsExactly("HELD", "DELIVERY_DIFFERENCE", "DELIVERY_DIFFERENCE",
                "REVIEWED", "RELEASED");
        List<Map<String, Object>> diffRows = jdbcTemplate.queryForList(
                "SELECT seq, leg_id, reason FROM claim_event WHERE claim_key = 'IMM-2'"
                        + " AND event_type = 'DELIVERY_DIFFERENCE' ORDER BY seq");
        assertThat(diffRows).hasSize(2);
        assertThat(diffRows.get(0).get("leg_id")).isEqualTo("LEG2");
        assertThat(diffRows.get(0).get("reason")).isEqualTo("冻结期间被误装上后续航段");
        assertThat(diffRows.get(1).get("seq")).isEqualTo(2);

        // 行李事件流：冻结前后航段历史不改写，冻结事件成对保留
        List<String> bagEvents = jdbcTemplate.queryForList(
                "SELECT event_type FROM bag_event WHERE bag_tag = 'BAG1' ORDER BY seq", String.class);
        assertThat(bagEvents).containsExactly("REGISTERED", "CLAIM_HELD", "CLAIM_RELEASED",
                "CLAIM_HELD", "CLAIM_RELEASED");
    }

    // ---------------------------------------------------------------------
    // 幂等
    // ---------------------------------------------------------------------

    @Test
    void idempotency_holdReviewReplayDifferentParams409FailureNotOccupyingKey() {
        registerLeg("LEG1", "PEK", "SHA", null);
        registerBag("BAG1", List.of("LEG1"));
        registerBag("BAG2", List.of("LEG1"));

        // 同参重放：只产生一条冻结与一组链事件（BAG1 上的 IDEM-1 保持生效）
        String holdKey = rid();
        HoldClaimRequest holdRequest = new HoldClaimRequest(
                holdKey, "IDEM-1", "BAG1", DIGEST, "原因", AGENT_A, "CS_AGENT");
        ClaimHoldResponse first = baggageService.holdClaim(holdRequest);
        ClaimHoldResponse replay = baggageService.holdClaim(holdRequest);
        assertThat(replay.claimKey()).isEqualTo(first.claimKey());
        assertThat(replay.status()).isEqualTo("ACTIVE");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM claim_hold WHERE claim_key = 'IDEM-1'", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM claim_event WHERE claim_key = 'IDEM-1' AND event_type = 'HELD'",
                Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = ?", Integer.class, holdKey))
                .isEqualTo(1);

        // 异参 409
        assertThat(catchApi(() -> baggageService.holdClaim(new HoldClaimRequest(
                holdKey, "IDEM-1", "BAG1", DIGEST, "被改写的原因", AGENT_A, "CS_AGENT")))
                .getStatus().value()).isEqualTo(409);

        // 失败不占键：先错误角色 422，再同键正确角色成功（使用另一件无生效冻结的 BAG2）
        String failKey = rid();
        assertThat(catchApi(() -> baggageService.holdClaim(new HoldClaimRequest(
                failKey, "IDEM-2", "BAG2", DIGEST, "r", REVIEWER_B, "CS_REVIEWER")))
                .getStatus().value()).isEqualTo(422);
        ClaimHoldResponse afterRetry = baggageService.holdClaim(new HoldClaimRequest(
                failKey, "IDEM-2", "BAG2", DIGEST, "r", AGENT_A, "CS_AGENT"));
        assertThat(afterRetry.status()).isEqualTo("ACTIVE");

        // 复核：错误摘要失败不占键，同键正确摘要成功；同参重放返回 REVIEWED 原结果
        String reviewKey = rid();
        assertThat(catchApi(() -> baggageService.reviewClaim(
                new com.example.starter.baggage.BaggageDtos.ClaimReviewRequest(
                        reviewKey, "IDEM-2", "BAD", REVIEWER_B, "CS_REVIEWER")))
                .getStatus().value()).isEqualTo(422);
        var reviewOk = baggageService.reviewClaim(
                new com.example.starter.baggage.BaggageDtos.ClaimReviewRequest(
                        reviewKey, "IDEM-2", DIGEST, REVIEWER_B, "CS_REVIEWER"));
        assertThat(reviewOk.status()).isEqualTo("REVIEWED");
        var reviewReplay = baggageService.reviewClaim(
                new com.example.starter.baggage.BaggageDtos.ClaimReviewRequest(
                        reviewKey, "IDEM-2", DIGEST, REVIEWER_B, "CS_REVIEWER"));
        assertThat(reviewReplay.status()).isEqualTo("REVIEWED");

        // 解除同参重放为 RELEASED；新键重复解除 409
        String releaseKey = rid();
        var releaseReq = new com.example.starter.baggage.BaggageDtos.ClaimReleaseRequest(
                releaseKey, "IDEM-2", REVIEWER_B, "CS_REVIEWER");
        assertThat(baggageService.releaseClaim(releaseReq).status()).isEqualTo("RELEASED");
        assertThat(baggageService.releaseClaim(releaseReq).status()).isEqualTo("RELEASED");
        assertThat(catchApi(() -> baggageService.releaseClaim(
                new com.example.starter.baggage.BaggageDtos.ClaimReleaseRequest(
                        rid(), "IDEM-2", REVIEWER_B, "CS_REVIEWER")))
                .getStatus().value()).isEqualTo(409);
    }

    // ---------------------------------------------------------------------
    // 并发裁决
    // ---------------------------------------------------------------------

    @Test
    void concurrentSealAndHold_commitOrderDecides409() throws Exception {
        registerLeg("LEG1", "PEK", "SHA", null);
        registerBag("BAG1", List.of("LEG1"));
        baggageService.load("LEG1", new LoadRequest(rid(), 1, List.of("BAG1")));

        List<Callable<Object>> tasks = List.of(
                () -> baggageService.seal("LEG1", new SealRequest(rid(), 2)),
                () -> hold("RACE-1", "BAG1", DIGEST, "r", AGENT_A, "CS_AGENT"));
        List<Object> results = runConcurrently(tasks);

        long successes = results.stream()
                .filter(r -> r instanceof SealResponse || r instanceof ClaimHoldResponse)
                .count();
        assertThat(successes).isEqualTo(1);

        boolean sealedFirst = results.stream().anyMatch(SealResponse.class::isInstance);
        if (sealedFirst) {
            // 封舱先提交：冻结 409，行李仍在舱单
            assertApiStatus(results, 409, 1);
            assertThat(legStatus("LEG1")).isEqualTo("SEALED");
            assertBagStatus("BAG1", "IN_TRANSIT");
            assertThat(countLoadRecords("BAG1")).isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM claim_hold", Integer.class)).isZero();
        } else {
            // 冻结先提交：封舱版本过期 409，清单已移出且无半成品
            assertApiStatus(results, 409, 1);
            assertBagStatus("BAG1", "CLAIM_HOLD");
            assertThat(countLoadRecords("BAG1")).isZero();
            assertThat(getClaim("RACE-1").status()).isEqualTo("ACTIVE");
            // 航段仍是 OPEN（封舱失败未改写状态）
            assertThat(legStatus("LEG1")).isEqualTo("OPEN");
            assertThat(legVersion("LEG1")).isEqualTo(3);
        }
    }

    @Test
    void concurrentLoadAndHold_neverLeaveHeldBagInManifest() throws Exception {
        registerLeg("LEG1", "PEK", "SHA", null);
        registerBag("BAG1", List.of("LEG1"));

        List<Callable<Object>> tasks = List.of(
                () -> baggageService.load("LEG1", new LoadRequest(rid(), 1, List.of("BAG1"))),
                () -> hold("RACE-2", "BAG1", DIGEST, "r", AGENT_A, "CS_AGENT"));
        List<Object> results = runConcurrently(tasks);

        // 不变量：最终要么冻结失败（409）行李在清单，要么冻结生效且清单必然为空，不存在半成品
        String bagStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM bag WHERE bag_tag = 'BAG1'", String.class);
        Integer manifestRows = countLoadRecords("BAG1");
        if ("CLAIM_HOLD".equals(bagStatus)) {
            assertThat(manifestRows).isZero();
        } else {
            assertThat(bagStatus).isEqualTo("IN_TRANSIT");
            assertThat(manifestRows).isEqualTo(1);
        }
        // 装载若 409，必因冻结先提交（CLAIM_HOLD 闸门）
        results.stream().filter(ApiException.class::isInstance).map(ApiException.class::cast)
                .forEach(ex -> assertThat(ex.getStatus().value()).isEqualTo(409));
    }

    @Test
    void concurrentDoubleHold_onlyOneActive() throws Exception {
        registerLeg("LEG1", "PEK", "SHA", null);
        registerBag("BAG1", List.of("LEG1"));

        List<Callable<Object>> tasks = List.of(
                () -> hold("DUP-1", "BAG1", DIGEST, "r1", AGENT_A, "CS_AGENT"),
                () -> hold("DUP-2", "BAG1", DIGEST, "r2", AGENT_A, "CS_AGENT"));
        List<Object> results = runConcurrently(tasks);

        long active = results.stream().filter(ClaimHoldResponse.class::isInstance)
                .map(ClaimHoldResponse.class::cast).filter(c -> c.status().equals("ACTIVE")).count();
        assertThat(active).isEqualTo(1);
        assertApiStatus(results, 409, 1);
        Integer activeCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM claim_hold WHERE bag_tag = 'BAG1' AND status = 'ACTIVE'",
                Integer.class);
        assertThat(activeCount).isEqualTo(1);
    }

    // ---------------------------------------------------------------------
    // HTTP 层与响应结构抽查
    // ---------------------------------------------------------------------

    @Test
    void httpEndpoints_happyPathAndValidation400() throws Exception {
        registerLeg("LEG1", "PEK", "SHA", 1);
        registerBag("BAG1", List.of("LEG1"));
        baggageService.load("LEG1", new LoadRequest(rid(), 1, List.of("BAG1")));

        holdHttp("H-HTTP", "BAG1", DIGEST, "r", AGENT_A, "CS_AGENT")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.priorStatus").value("IN_TRANSIT"))
                .andExpect(jsonPath("$.events", hasSize(2)))
                .andExpect(jsonPath("$.events[0].eventType").value("HELD"))
                .andExpect(jsonPath("$.events[1].eventType").value("MANIFEST_REMOVED"))
                .andExpect(jsonPath("$.discrepancyCount").value(0));

        reviewHttp("H-HTTP", DIGEST, REVIEWER_B, "CS_REVIEWER")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVIEWED"));
        releaseHttp("H-HTTP", REVIEWER_B, "CS_REVIEWER")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.restoredStatus").value("IN_TRANSIT"))
                .andExpect(jsonPath("$.events[*].eventType",
                        contains("HELD", "MANIFEST_REMOVED", "REVIEWED", "RELEASED")));

        // 参数缺失 -> 400
        mockMvc.perform(post("/api/bags/claims/hold")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
        // 路径与请求体 claimKey 不一致 -> 422
        mockMvc.perform(post("/api/bags/claims/OTHER/review")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "requestId", rid(), "claimKey", "H-HTTP",
                                "passengerDigest", DIGEST, "operatorId", REVIEWER_B,
                                "operatorRole", "CS_REVIEWER"))))
                .andExpect(status().isUnprocessableEntity());
    }

    // ---------------------------------------------------------------------
    // 辅助
    // ---------------------------------------------------------------------

    private void registerLeg(String legId, String origin, String destination, Integer capacity) {
        baggageService.registerLeg(new RegisterLegRequest(rid(), legId, origin, destination, capacity));
    }

    private void registerBag(String bagTag, List<String> legIds) {
        baggageService.registerBag(new RegisterBagRequest(rid(), bagTag, legIds));
    }

    private ClaimHoldResponse hold(String claimKey, String bagTag, String digest, String reason,
                                   String operatorId, String role) {
        return baggageService.holdClaim(new HoldClaimRequest(
                rid(), claimKey, bagTag, digest, reason, operatorId, role));
    }

    private ClaimHoldResponse review(String claimKey, String digest, String operatorId, String role) {
        return baggageService.reviewClaim(new com.example.starter.baggage.BaggageDtos.ClaimReviewRequest(
                rid(), claimKey, digest, operatorId, role));
    }

    private ClaimHoldResponse release(String claimKey, String operatorId, String role) {
        return baggageService.releaseClaim(new com.example.starter.baggage.BaggageDtos.ClaimReleaseRequest(
                rid(), claimKey, operatorId, role));
    }

    private ClaimHoldResponse getClaim(String claimKey) {
        return baggageService.getClaimHold(claimKey);
    }

    private ResultActions holdHttp(String claimKey, String bagTag, String digest, String reason,
                                   String operatorId, String role) throws Exception {
        return postJson("/api/bags/claims/hold", Map.of(
                "requestId", rid(), "claimKey", claimKey, "bagTag", bagTag,
                "passengerDigest", digest, "reason", reason,
                "operatorId", operatorId, "operatorRole", role));
    }

    private ResultActions reviewHttp(String claimKey, String digest, String operatorId, String role)
            throws Exception {
        return postJson("/api/bags/claims/" + claimKey + "/review", Map.of(
                "requestId", rid(), "claimKey", claimKey, "passengerDigest", digest,
                "operatorId", operatorId, "operatorRole", role));
    }

    private ResultActions releaseHttp(String claimKey, String operatorId, String role) throws Exception {
        return postJson("/api/bags/claims/" + claimKey + "/release", Map.of(
                "requestId", rid(), "claimKey", claimKey,
                "operatorId", operatorId, "operatorRole", role));
    }

    private ResultActions postJson(String url, Object body) throws Exception {
        return mockMvc.perform(post(url)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private void assertBagStatus(String bagTag, String expected) {
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM bag WHERE bag_tag = ?", String.class, bagTag)).isEqualTo(expected);
    }

    private String legStatus(String legId) {
        return jdbcTemplate.queryForObject("SELECT status FROM leg WHERE leg_id = ?", String.class, legId);
    }

    private Integer legVersion(String legId) {
        return jdbcTemplate.queryForObject("SELECT version FROM leg WHERE leg_id = ?", Integer.class, legId);
    }

    private int countLoadRecords(String bagTag) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM load_record WHERE bag_tag = ?", Integer.class, bagTag);
        return count == null ? 0 : count;
    }

    private List<String> chainEventTypes(String claimKey) {
        return jdbcTemplate.queryForList(
                "SELECT event_type FROM claim_event WHERE claim_key = ? ORDER BY seq",
                String.class, claimKey);
    }

    private void assertApiStatus(List<Object> results, int status, int expectedCount) {
        long count = results.stream()
                .filter(ApiException.class::isInstance)
                .map(ApiException.class::cast)
                .filter(ex -> ex.getStatus().value() == status)
                .count();
        assertThat(count).isEqualTo(expectedCount);
    }

    private static ApiException catchApi(ThrowingRunnable action) {
        try {
            action.run();
        } catch (ApiException ex) {
            return ex;
        }
        throw new AssertionError("期望抛出 ApiException");
    }

    private static String rid() {
        return UUID.randomUUID().toString();
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

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run();
    }
}
