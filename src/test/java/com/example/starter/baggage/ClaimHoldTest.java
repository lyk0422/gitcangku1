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

import com.example.starter.baggage.BaggageDtos.ClaimHoldRequest;
import com.example.starter.baggage.BaggageDtos.ClaimHoldResponse;
import com.example.starter.baggage.BaggageDtos.ClaimReleaseRequest;
import com.example.starter.baggage.BaggageDtos.ClaimReviewRequest;
import com.example.starter.baggage.BaggageDtos.LoadRequest;
import com.example.starter.baggage.BaggageDtos.RecoverRequest;
import com.example.starter.baggage.BaggageDtos.RegisterBagRequest;
import com.example.starter.baggage.BaggageDtos.RegisterLegRequest;
import com.example.starter.baggage.BaggageDtos.SealRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 行李认领冻结测试：覆盖冻结/复核/解除主流程、OPEN 清单同事务移出、终态与封舱 409、
 * 复核角色与摘要 422、解除恢复与旧装载不自动恢复、容量重校验、证据链不可变、
 * 幂等重放/异参/失败不占键、冻结与封舱/装载/重复冻结的真实并发裁决以及只读诊断。
 * 全部基于 H2 MySQL 兼容模式真实数据库，不使用 mock。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ClaimHoldTest {

    private static final Instant T0 = Instant.parse("2026-09-28T08:00:00Z");
    private static final String SUMMARY = "PAX-DOC-X9F2-NAME-MATCH";
    private static final String AGENT_A = "agent-a";
    private static final String ROLE_DESK = "CLAIM_DESK";
    private static final String AGENT_B = "agent-b";
    private static final String ROLE_SUPERVISOR = "CLAIM_SUPERVISOR";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private BaggageService baggageService;

    private Instant now;
    private ExecutorService executor;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM delivery_difference");
        jdbcTemplate.update("DELETE FROM claim_chain");
        jdbcTemplate.update("DELETE FROM claim_hold");
        jdbcTemplate.update("DELETE FROM bag_event");
        jdbcTemplate.update("DELETE FROM load_record");
        jdbcTemplate.update("DELETE FROM bag_itinerary");
        jdbcTemplate.update("DELETE FROM bag");
        jdbcTemplate.update("DELETE FROM leg");
        jdbcTemplate.update("DELETE FROM request_log");
        now = T0;
        baggageService.setClock(() -> now);
        executor = Executors.newFixedThreadPool(8);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        executor.shutdownNow();
        assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        baggageService.setClock(Instant::now);
    }

    @Test
    void holdOnOpenList_removesListAndBumpsVersion_thenReviewAndReleaseRestoreHandover() {
        setupTwoLegBagLoadedOnSecondLeg();

        // 冻结：同事务移出 OPEN 清单，航段版本推进（装载后 v2，移出后 v3）
        ClaimHoldResponse held = baggageService.claimHold(holdRequest("CLAIM-1", "BAG1"));
        assertThat(held.status()).isEqualTo("ACTIVE");
        assertThat(held.removedFromLeg()).isEqualTo("LEG2");
        assertThat(held.statusBeforeHold()).isEqualTo("IN_TRANSIT");
        assertThat(held.holdAt()).isEqualTo(T0.toString());
        assertBag("BAG1", "CLAIM_HOLD", "SHA", 1, null);
        assertThat(count("SELECT COUNT(*) FROM load_record WHERE bag_tag = 'BAG1'")).isZero();
        assertThat(legVersion("LEG2")).isEqualTo(3);

        // 冻结期间：装载 409、补到 409
        expectConflict(() -> baggageService.load("LEG2",
                new LoadRequest(uuid(), 3, List.of("BAG1"))));
        expectConflict(() -> baggageService.recover(
                new RecoverRequest(uuid(), "BAG1", "LEG1", "SHA")));

        // 明细与证据链：HOLD + LIST_REMOVAL，第二位操作人字段为 null
        var detail = baggageService.getClaimDetail("CLAIM-1");
        assertThat(detail.status()).isEqualTo("ACTIVE");
        assertThat(detail.frozenLegId()).isEqualTo("LEG2");
        assertThat(detail.chain()).extracting(c -> c.eventType() + ":" + c.seq())
                .containsExactly("HOLD:0", "LIST_REMOVAL:1");
        assertThat(detail.chain()).allSatisfy(c -> {
            assertThat(c.reason()).isEqualTo("旅客在 SHA 转盘未见行李，申请认领冻结");
            assertThat(c.holdAgentId()).isEqualTo(AGENT_A);
            assertThat(c.reviewAgentId()).isNull();
            assertThat(c.reviewedAt()).isNull();
            assertThat(c.releasedAt()).isNull();
            assertThat(c.holdAt()).isEqualTo(T0.toString());
            assertThat(c.legId()).isEqualTo("LEG2");
        });

        // 复核摘要不匹配 -> 422，状态与证据链不变
        expectUnprocessable(() -> baggageService.claimReview(
                reviewRequest("CLAIM-1", "WRONG-SUMMARY")));
        assertThat(baggageService.getClaimDetail("CLAIM-1").chain()).hasSize(2);

        // 同角色复核 -> 422
        expectUnprocessable(() -> baggageService.claimReview(new ClaimReviewRequest(
                uuid(), "CLAIM-1", SUMMARY, AGENT_B, ROLE_DESK)));
        // 同一人复核 -> 422
        expectUnprocessable(() -> baggageService.claimReview(new ClaimReviewRequest(
                uuid(), "CLAIM-1", SUMMARY, AGENT_A, ROLE_SUPERVISOR)));

        now = T0.plusSeconds(90);
        var reviewed = baggageService.claimReview(reviewRequest("CLAIM-1", SUMMARY));
        assertThat(reviewed.status()).isEqualTo("REVIEWED");
        assertThat(reviewed.holdDurationSeconds()).isEqualTo(90L);
        assertBag("BAG1", "CLAIM_HOLD", "SHA", 1, null);

        // 未二次确认前解除尝试：错误摘要 422、非复核人 422、直接解除已由 REVIEWED 状态保护
        expectUnprocessable(() -> baggageService.claimRelease(new ClaimReleaseRequest(
                uuid(), "CLAIM-1", "WRONG", AGENT_B, ROLE_SUPERVISOR)));
        expectUnprocessable(() -> baggageService.claimRelease(new ClaimReleaseRequest(
                uuid(), "CLAIM-1", SUMMARY, AGENT_A, ROLE_SUPERVISOR)));

        now = T0.plusSeconds(200);
        var released = baggageService.claimRelease(new ClaimReleaseRequest(
                uuid(), "CLAIM-1", SUMMARY, AGENT_B, ROLE_SUPERVISOR));
        assertThat(released.status()).isEqualTo("RELEASED");
        assertThat(released.restoredTo()).isEqualTo("IN_TRANSIT");
        assertThat(released.holdDurationSeconds()).isEqualTo(200L);
        assertBag("BAG1", "IN_TRANSIT", "SHA", 1, null);

        // 旧装载请求不自动恢复：清单仍为空，必须重提
        assertThat(count("SELECT COUNT(*) FROM load_record WHERE bag_tag = 'BAG1'")).isZero();
        // 重提按当前版本（v3）与容量校验后成功到 v4
        var reload = baggageService.load("LEG2", new LoadRequest(uuid(), 3, List.of("BAG1")));
        assertThat(reload.version()).isEqualTo(4);
        assertBag("BAG1", "IN_TRANSIT", "SHA", 1, "LEG2");

        // 证据链最终四行，字段、排序与 null 含义稳定
        var finalDetail = baggageService.getClaimDetail("CLAIM-1");
        assertThat(finalDetail.chain()).extracting(c -> c.eventType() + ":" + c.seq())
                .containsExactly("HOLD:0", "LIST_REMOVAL:1", "REVIEW:2", "RELEASE:3");
        var reviewRow = finalDetail.chain().get(2);
        assertThat(reviewRow.reviewAgentId()).isEqualTo(AGENT_B);
        assertThat(reviewRow.reviewAgentRole()).isEqualTo(ROLE_SUPERVISOR);
        assertThat(reviewRow.reviewedAt()).isEqualTo(T0.plusSeconds(90).toString());
        assertThat(reviewRow.releasedAt()).isNull();
        var releaseRow = finalDetail.chain().get(3);
        assertThat(releaseRow.releasedAt()).isEqualTo(T0.plusSeconds(200).toString());
        assertThat(releaseRow.reviewedAt()).isEqualTo(T0.plusSeconds(90).toString());
        // 冻结前后航段历史不改写：HOLD/LIST_REMOVAL 行的时刻仍为 T0
        assertThat(finalDetail.chain().get(0).eventTime()).isEqualTo(T0.toString());
        assertThat(finalDetail.chain().get(1).eventTime()).isEqualTo(T0.toString());
    }

    @Test
    void holdWhenNotOnOpenList_chainHasOnlyHoldAndLegVersionUnchanged() {
        baggageService.registerLeg(new RegisterLegRequest(uuid(), "LEG1", "PEK", "SHA", null));
        baggageService.registerBag(new RegisterBagRequest(uuid(), "BAG1", List.of("LEG1")));

        var held = baggageService.claimHold(holdRequest("CLAIM-2", "BAG1"));
        assertThat(held.removedFromLeg()).isNull();
        assertBag("BAG1", "CLAIM_HOLD", "PEK", 0, null);
        assertThat(legVersion("LEG1")).isEqualTo(1);
        var detail = baggageService.getClaimDetail("CLAIM-2");
        assertThat(detail.chain()).extracting(c -> c.eventType() + ":" + c.seq())
                .containsExactly("HOLD:0");
        assertThat(detail.chain().get(0).legId()).isNull();

        now = T0.plusSeconds(30);
        baggageService.claimReview(reviewRequest("CLAIM-2", SUMMARY));
        now = T0.plusSeconds(60);
        var released = baggageService.claimRelease(new ClaimReleaseRequest(
                uuid(), "CLAIM-2", SUMMARY, AGENT_B, ROLE_SUPERVISOR));
        assertThat(released.restoredTo()).isEqualTo("IN_TRANSIT");
        assertBag("BAG1", "IN_TRANSIT", "PEK", 0, null);
    }

    @Test
    void hold_rejectsDeliveredLostSealedAndDuplicateActive() throws Exception {
        // 已最终交付 -> 409
        baggageService.registerLeg(new RegisterLegRequest(uuid(), "LEG1", "PEK", "SHA", null));
        baggageService.registerBag(new RegisterBagRequest(uuid(), "BAG_DONE", List.of("LEG1")));
        jdbcTemplate.update("UPDATE bag SET status = 'DELIVERED' WHERE bag_tag = 'BAG_DONE'");
        expectConflict(() -> baggageService.claimHold(holdRequest("C-DONE", "BAG_DONE")));

        // 已丢失 -> 409
        baggageService.registerBag(new RegisterBagRequest(uuid(), "BAG_LOST", List.of("LEG1")));
        jdbcTemplate.update("UPDATE bag SET status = 'LOST' WHERE bag_tag = 'BAG_LOST'");
        expectConflict(() -> baggageService.claimHold(holdRequest("C-LOST", "BAG_LOST")));

        // 封舱先提交：行李固化在 SEALED 舱单中 -> 409
        baggageService.registerLeg(new RegisterLegRequest(uuid(), "LEG2", "PEK", "SHA", null));
        baggageService.registerBag(new RegisterBagRequest(uuid(), "BAG_SEAL", List.of("LEG2")));
        baggageService.load("LEG2", new LoadRequest(uuid(), 1, List.of("BAG_SEAL")));
        baggageService.seal("LEG2", new SealRequest(uuid(), 2));
        expectConflict(() -> baggageService.claimHold(holdRequest("C-SEAL", "BAG_SEAL")));
        // 封舱 409 不产生半成品：无冻结、无证据链、行李仍在舱单
        assertThat(count("SELECT COUNT(*) FROM claim_hold")).isZero();
        assertBag("BAG_SEAL", "IN_TRANSIT", "PEK", 0, "LEG2");

        // 行李不存在 -> 404（HTTP 层核对）
        holdHttp("C-NOPE", "BAG_NOPE").andExpect(status().isNotFound());

        // 生效中重复冻结 -> 409，且库内只有一个未解除冻结
        baggageService.registerLeg(new RegisterLegRequest(uuid(), "LEG3", "SHA", "CAN", null));
        baggageService.registerBag(new RegisterBagRequest(uuid(), "BAG2", List.of("LEG3")));
        baggageService.claimHold(holdRequest("C-ACTIVE", "BAG2"));
        expectConflict(() -> baggageService.claimHold(holdRequest("C-ACTIVE-2", "BAG2")));
        assertThat(count("SELECT COUNT(*) FROM claim_hold WHERE bag_tag = 'BAG2'"
                + " AND status <> 'RELEASED'")).isEqualTo(1);

        // claimKey 重复 -> 409
        expectConflict(() -> baggageService.claimHold(holdRequest("C-ACTIVE", "BAG2")));
    }

    @Test
    void release_restoresShortUnloadedStateAndShortFields() {
        baggageService.registerLeg(new RegisterLegRequest(uuid(), "LEG1", "PEK", "SHA", null));
        baggageService.registerBag(new RegisterBagRequest(uuid(), "BAG1", List.of("LEG1")));
        baggageService.load("LEG1", new LoadRequest(uuid(), 1, List.of("BAG1")));
        baggageService.seal("LEG1", new SealRequest(uuid(), 2));
        jdbcTemplate.update(
                "UPDATE bag SET status = 'SHORT_UNLOADED', loaded_leg_id = NULL, short_leg_id = 'LEG1',"
                        + " short_destination = 'SHA', short_registered_at = ? WHERE bag_tag = 'BAG1'",
                java.time.OffsetDateTime.ofInstant(T0.minusSeconds(500), java.time.ZoneOffset.UTC));

        // 短卸但未到最终目的地的行李可冻结
        var held = baggageService.claimHold(holdRequest("C-SHORT", "BAG1"));
        assertThat(held.statusBeforeHold()).isEqualTo("SHORT_UNLOADED");
        assertThat(held.removedFromLeg()).isNull();
        assertBag("BAG1", "CLAIM_HOLD", "PEK", 0, null);
        // 冻结期间补到 409
        expectConflict(() -> baggageService.recover(
                new RecoverRequest(uuid(), "BAG1", "LEG1", "SHA")));

        baggageService.claimReview(reviewRequest("C-SHORT", SUMMARY));
        var released = baggageService.claimRelease(new ClaimReleaseRequest(
                uuid(), "C-SHORT", SUMMARY, AGENT_B, ROLE_SUPERVISOR));
        assertThat(released.restoredTo()).isEqualTo("SHORT_UNLOADED");
        // 短卸字段原样保留
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT status, short_leg_id, short_destination FROM bag WHERE bag_tag = 'BAG1'");
        assertThat(row.get("STATUS")).isEqualTo("SHORT_UNLOADED");
        assertThat(row.get("SHORT_LEG_ID")).isEqualTo("LEG1");
        assertThat(row.get("SHORT_DESTINATION")).isEqualTo("SHA");
        // 解除后补到成功
        var recover = baggageService.recover(new RecoverRequest(uuid(), "BAG1", "LEG1", "SHA"));
        assertThat(recover.status()).isEqualTo("DELIVERED");
    }

    @Test
    void staleLoadNotRestored_resubmitValidatedAgainstCurrentCapacity() {
        // LEG2 容量 2：三件行李的待乘航段都是 LEG2
        baggageService.registerLeg(new RegisterLegRequest(uuid(), "LEG1", "PEK", "SHA", null));
        baggageService.registerLeg(new RegisterLegRequest(uuid(), "LEG2", "SHA", "CAN", 2));
        baggageService.registerBag(new RegisterBagRequest(uuid(), "BAG_A", List.of("LEG1", "LEG2")));
        baggageService.registerBag(new RegisterBagRequest(uuid(), "BAG_B", List.of("LEG1", "LEG2")));
        baggageService.registerBag(new RegisterBagRequest(uuid(), "BAG_C", List.of("LEG1", "LEG2")));
        baggageService.load("LEG1", new LoadRequest(uuid(), 1, List.of("BAG_A", "BAG_B", "BAG_C")));
        baggageService.seal("LEG1", new SealRequest(uuid(), 2));
        arriveExact("LEG1", List.of("BAG_A", "BAG_B", "BAG_C"));

        // 旧请求：B 在冻结前已提交到 LEG2
        baggageService.load("LEG2", new LoadRequest(uuid(), 1, List.of("BAG_B")));
        assertThat(legVersion("LEG2")).isEqualTo(2);

        // B 冻结移出：容量立即释放
        baggageService.claimHold(holdRequest("C-CAP", "BAG_B"));
        assertThat(count("SELECT COUNT(*) FROM load_record WHERE leg_id = 'LEG2'")).isZero();
        assertThat(legVersion("LEG2")).isEqualTo(3);

        // 冻结期间 A 占用一个容量位
        baggageService.load("LEG2", new LoadRequest(uuid(), 3, List.of("BAG_A")));
        assertThat(legVersion("LEG2")).isEqualTo(4);

        baggageService.claimReview(reviewRequest("C-CAP", SUMMARY));
        baggageService.claimRelease(new ClaimReleaseRequest(
                uuid(), "C-CAP", SUMMARY, AGENT_B, ROLE_SUPERVISOR));
        // 旧装载不自动恢复，必须重提
        assertThat(count("SELECT COUNT(*) FROM load_record WHERE bag_tag = 'BAG_B'")).isZero();
        // 重提按当前容量校验：实际 1 + 要求 1 <= 上限 2，成功
        var reload = baggageService.load("LEG2", new LoadRequest(uuid(), 4, List.of("BAG_B")));
        assertThat(reload.version()).isEqualTo(5);
        assertBag("BAG_B", "IN_TRANSIT", "SHA", 1, "LEG2");

        // 容量已满后 C 再重提：实际 2、要求 1、上限 2，差额 1 -> 422，且无半成品
        ApiException ex = expectUnprocessable(() -> baggageService.load("LEG2",
                new LoadRequest(uuid(), 5, List.of("BAG_C"))));
        assertThat(ex.getMessage()).contains("实际值 2").contains("要求 1").contains("差额 1");
        assertBag("BAG_C", "IN_TRANSIT", "SHA", 1, null);
        assertThat(count("SELECT COUNT(*) FROM load_record")).isEqualTo(2);
        assertThat(legVersion("LEG2")).isEqualTo(5);
    }

    @Test
    void capacityRejection_leavesNoHalfLoadedRecords() {
        baggageService.registerLeg(new RegisterLegRequest(uuid(), "LEG1", "PEK", "SHA", 1));
        baggageService.registerBag(new RegisterBagRequest(uuid(), "BAG1", List.of("LEG1")));
        baggageService.registerBag(new RegisterBagRequest(uuid(), "BAG2", List.of("LEG1")));
        baggageService.load("LEG1", new LoadRequest(uuid(), 1, List.of("BAG1")));
        // 超容量整批 422，BAG2 不进入清单、版本不变
        ApiException ex = expectUnprocessable(() -> baggageService.load("LEG1",
                new LoadRequest(uuid(), 2, List.of("BAG2"))));
        assertThat(ex.getMessage()).contains("实际值 1").contains("要求 1").contains("差额 1");
        assertThat(count("SELECT COUNT(*) FROM load_record")).isEqualTo(1);
        assertThat(legVersion("LEG1")).isEqualTo(2);
        assertBag("BAG2", "IN_TRANSIT", "PEK", 0, null);
    }

    @Test
    void idempotency_holdReviewReplay_conflictAndFailureNotOccupyingKey() {
        baggageService.registerLeg(new RegisterLegRequest(uuid(), "LEG1", "PEK", "SHA", null));
        baggageService.registerBag(new RegisterBagRequest(uuid(), "BAG1", List.of("LEG1")));
        baggageService.registerBag(new RegisterBagRequest(uuid(), "BAG2", List.of("LEG1")));
        jdbcTemplate.update("UPDATE bag SET status = 'DELIVERED' WHERE bag_tag = 'BAG2'");

        // 失败不占键：先对 DELIVERED 行李冻结失败（409），同键改为合法行李后成功
        String holdKey = uuid();
        expectConflict(() -> baggageService.claimHold(new ClaimHoldRequest(
                holdKey, "C1", "BAG2", SUMMARY, "原因", AGENT_A, ROLE_DESK)));
        var hold = baggageService.claimHold(new ClaimHoldRequest(
                holdKey, "C1", "BAG1", SUMMARY, "原因", AGENT_A, ROLE_DESK));
        assertThat(hold.bagTag()).isEqualTo("BAG1");
        // 同参重放：不产生第二条冻结、证据链不重复
        var replay = baggageService.claimHold(new ClaimHoldRequest(
                holdKey, "C1", "BAG1", SUMMARY, "原因", AGENT_A, ROLE_DESK));
        assertThat(replay).usingRecursiveComparison().isEqualTo(hold);
        assertThat(count("SELECT COUNT(*) FROM claim_hold WHERE claim_key = 'C1'")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM claim_chain WHERE claim_key = 'C1'")).isEqualTo(1);
        // 同键异参 -> 409
        expectConflict(() -> baggageService.claimHold(new ClaimHoldRequest(
                holdKey, "C1", "BAG1", "OTHER", "原因", AGENT_A, ROLE_DESK)));

        // 复核同参重放
        String reviewKey = uuid();
        var review = baggageService.claimReview(new ClaimReviewRequest(
                reviewKey, "C1", SUMMARY, AGENT_B, ROLE_SUPERVISOR));
        var reviewReplay = baggageService.claimReview(new ClaimReviewRequest(
                reviewKey, "C1", SUMMARY, AGENT_B, ROLE_SUPERVISOR));
        assertThat(reviewReplay).usingRecursiveComparison().isEqualTo(review);
        assertThat(count("SELECT COUNT(*) FROM claim_chain WHERE claim_key = 'C1'"
                + " AND event_type = 'REVIEW'")).isEqualTo(1);

        // 解除同参重放与异参 409
        String releaseKey = uuid();
        var release = baggageService.claimRelease(new ClaimReleaseRequest(
                releaseKey, "C1", SUMMARY, AGENT_B, ROLE_SUPERVISOR));
        var releaseReplay = baggageService.claimRelease(new ClaimReleaseRequest(
                releaseKey, "C1", SUMMARY, AGENT_B, ROLE_SUPERVISOR));
        assertThat(releaseReplay).usingRecursiveComparison().isEqualTo(release);
        expectConflict(() -> baggageService.claimRelease(new ClaimReleaseRequest(
                releaseKey, "C1", "WRONG", AGENT_B, ROLE_SUPERVISOR)));
        assertThat(count("SELECT COUNT(*) FROM claim_chain WHERE claim_key = 'C1'"
                + " AND event_type = 'RELEASE'")).isEqualTo(1);
    }

    @Test
    void deliveryDifference_afterReleaseOnly_appendsWithoutDeletingClaimOrChain() {
        baggageService.registerLeg(new RegisterLegRequest(uuid(), "LEG1", "PEK", "SHA", null));
        baggageService.registerBag(new RegisterBagRequest(uuid(), "BAG1", List.of("LEG1")));
        baggageService.claimHold(holdRequest("C-DD", "BAG1"));
        // 生效中登记误交付差异 -> 409
        expectConflict(() -> baggageService.registerDeliveryDifference(
                differenceRequest("C-DD", "BAG1")));
        assertThat(count("SELECT COUNT(*) FROM delivery_difference")).isZero();

        baggageService.claimReview(reviewRequest("C-DD", SUMMARY));
        baggageService.claimRelease(new ClaimReleaseRequest(
                uuid(), "C-DD", SUMMARY, AGENT_B, ROLE_SUPERVISOR));
        var diff1 = baggageService.registerDeliveryDifference(
                differenceRequest("C-DD", "BAG1"));
        var diff2 = baggageService.registerDeliveryDifference(
                differenceRequest("C-DD", "BAG1"));
        assertThat(diff2.differenceId()).isEqualTo(diff1.differenceId() + 1);
        assertThat(count("SELECT COUNT(*) FROM delivery_difference WHERE claim_key = 'C-DD'"))
                .isEqualTo(2);
        // 认领记录与证据链原样保留，不被差异流程删除或改写
        assertThat(count("SELECT COUNT(*) FROM claim_hold WHERE claim_key = 'C-DD'"
                + " AND status = 'RELEASED'")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM claim_chain WHERE claim_key = 'C-DD'"))
                .isEqualTo(3);
        // 解除后允许就同一件行李再次冻结，产生独立证据链
        var second = baggageService.claimHold(holdRequest("C-DD-2", "BAG1"));
        assertThat(second.status()).isEqualTo("ACTIVE");
        assertThat(count("SELECT COUNT(*) FROM claim_hold WHERE bag_tag = 'BAG1'")).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM claim_chain WHERE claim_key = 'C-DD-2'"))
                .isEqualTo(1);
    }

    @Test
    void concurrentSealAndHold_exactlyOneWinner() throws Exception {
        registerLegLoadBagOnOpenLeg();

        List<Object> results = runConcurrently(List.of(
                () -> baggageService.seal("LEG1", new SealRequest(uuid(), 2)),
                () -> baggageService.claimHold(holdRequest("C-RACE", "BAG1"))));

        long sealOk = results.stream().filter(r -> r instanceof BaggageDtos.SealResponse).count();
        long holdOk = results.stream().filter(r -> r instanceof ClaimHoldResponse).count();
        long conflicts = results.stream()
                .filter(ApiException.class::isInstance).map(ApiException.class::cast)
                .filter(ex -> ex.getStatus().value() == 409).count();
        assertThat(sealOk + holdOk).isEqualTo(1);
        assertThat(conflicts).isEqualTo(1);

        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM leg WHERE leg_id = 'LEG1'", String.class);
        if (sealOk == 1) {
            // 封舱先提交：冻结 409，行李固化在舱单
            assertThat(status).isEqualTo("SEALED");
            assertBag("BAG1", "IN_TRANSIT", "PEK", 0, "LEG1");
            assertThat(count("SELECT COUNT(*) FROM claim_hold")).isZero();
        } else {
            // 冻结先提交：封舱版本冲突 409，行李 CLAIM_HOLD、清单为空、航段仍 OPEN 且版本推进
            assertThat(status).isEqualTo("OPEN");
            assertThat(legVersion("LEG1")).isEqualTo(3);
            assertBag("BAG1", "CLAIM_HOLD", "PEK", 0, null);
            assertThat(count("SELECT COUNT(*) FROM load_record")).isZero();
            assertThat(count("SELECT COUNT(*) FROM claim_chain WHERE event_type = 'LIST_REMOVAL'"))
                    .isEqualTo(1);
        }
    }

    @Test
    void concurrentHoldAndLoad_neverLeavesHeldBagOnList() throws Exception {
        baggageService.registerLeg(new RegisterLegRequest(uuid(), "LEG1", "PEK", "SHA", null));
        baggageService.registerBag(new RegisterBagRequest(uuid(), "BAG1", List.of("LEG1")));

        List<Object> results = runConcurrently(List.of(
                () -> baggageService.load("LEG1", new LoadRequest(uuid(), 1, List.of("BAG1"))),
                () -> baggageService.claimHold(holdRequest("C-RACE2", "BAG1"))));

        long holdOk = results.stream().filter(ClaimHoldResponse.class::isInstance).count();
        assertThat(holdOk).isEqualTo(1);
        // 冻结必最终生效；装载要么 409，要么先成功后被同事务移出清单
        boolean loadOk = results.stream().anyMatch(r -> r instanceof BaggageDtos.LoadResponse);
        boolean loadConflict = results.stream()
                .filter(ApiException.class::isInstance).map(ApiException.class::cast)
                .anyMatch(ex -> ex.getStatus().value() == 409);
        assertThat(loadOk).isNotEqualTo(loadConflict);

        assertBag("BAG1", "CLAIM_HOLD", "PEK", 0, null);
        assertThat(count("SELECT COUNT(*) FROM load_record")).isZero();
        Integer version = legVersion("LEG1");
        // 装载先提交（1->2）则冻结移出再推进到 3；冻结先提交则装载 409、版本保持 1
        assertThat(version).isEqualTo(loadOk ? 3 : 1);
    }

    @Test
    void concurrentDoubleHold_singleActiveClaimViaDatabaseConstraint() throws Exception {
        baggageService.registerLeg(new RegisterLegRequest(uuid(), "LEG1", "PEK", "SHA", null));
        baggageService.registerBag(new RegisterBagRequest(uuid(), "BAG1", List.of("LEG1")));

        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            final int idx = i;
            tasks.add(() -> baggageService.claimHold(
                    holdRequest("C-PAR-" + idx, "BAG1")));
        }
        List<Object> results = runConcurrently(tasks);

        long ok = results.stream().filter(ClaimHoldResponse.class::isInstance).count();
        long conflicts = results.stream()
                .filter(ApiException.class::isInstance).map(ApiException.class::cast)
                .filter(ex -> ex.getStatus().value() == 409).count();
        assertThat(ok).isEqualTo(1);
        assertThat(conflicts).isEqualTo(3);
        assertThat(count("SELECT COUNT(*) FROM claim_hold WHERE bag_tag = 'BAG1'"
                + " AND status <> 'RELEASED'")).isEqualTo(1);
        assertBag("BAG1", "CLAIM_HOLD", "PEK", 0, null);
    }

    @Test
    void reviewAndRelease_failuresAre404Or409WithoutSideEffects() {
        baggageService.registerLeg(new RegisterLegRequest(uuid(), "LEG1", "PEK", "SHA", null));
        baggageService.registerBag(new RegisterBagRequest(uuid(), "BAG1", List.of("LEG1")));

        // 不存在的冻结：复核/解除 404
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                baggageService.claimReview(reviewRequest("MISSING", SUMMARY)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus().value()).isEqualTo(404));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> baggageService.claimRelease(
                new ClaimReleaseRequest(uuid(), "MISSING", SUMMARY, AGENT_B, ROLE_SUPERVISOR)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).getStatus().value()).isEqualTo(404));

        baggageService.claimHold(holdRequest("C-FAIL", "BAG1"));
        // 未复核直接第二次确认 -> 409
        expectConflict(() -> baggageService.claimRelease(new ClaimReleaseRequest(
                uuid(), "C-FAIL", SUMMARY, AGENT_B, ROLE_SUPERVISOR)));
        // 合法复核
        baggageService.claimReview(reviewRequest("C-FAIL", SUMMARY));
        // 重复复核 -> 409
        expectConflict(() -> baggageService.claimReview(reviewRequest("C-FAIL", SUMMARY)));
        // 合法解除
        baggageService.claimRelease(new ClaimReleaseRequest(
                uuid(), "C-FAIL", SUMMARY, AGENT_B, ROLE_SUPERVISOR));
        // 重复解除 -> 409，且证据链 RELEASE 只有一行
        expectConflict(() -> baggageService.claimRelease(new ClaimReleaseRequest(
                uuid(), "C-FAIL", SUMMARY, AGENT_B, ROLE_SUPERVISOR)));
        assertThat(count("SELECT COUNT(*) FROM claim_chain WHERE claim_key = 'C-FAIL'"
                + " AND event_type = 'RELEASE'")).isEqualTo(1);
    }

    @Test
    void reads_doNotChangeState_andHttpStatusCodesAreExposed() throws Exception {
        baggageService.registerLeg(new RegisterLegRequest(uuid(), "LEG1", "PEK", "SHA", null));
        baggageService.registerBag(new RegisterBagRequest(uuid(), "BAG1", List.of("LEG1")));
        holdHttp("C-HTTP", "BAG1").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.removedFromLeg").value(nullValue()));

        // 冻结期间装载经 HTTP 返回 409
        postJson("/api/legs/LEG1/load", Map.of(
                "requestId", uuid(), "expectedVersion", 1, "bagTags", List.of("BAG1")))
                .andExpect(status().isConflict());
        // 摘要不匹配经 HTTP 返回 422
        postJson("/api/claims/C-HTTP/review", Map.of(
                "requestId", uuid(), "claimKey", "C-HTTP",
                "passengerVerificationSummary", "BAD",
                "reviewerId", AGENT_B, "reviewerRole", ROLE_SUPERVISOR))
                .andExpect(status().isUnprocessableEntity());

        long chainsBefore = count("SELECT COUNT(*) FROM claim_chain");
        int versionBefore = legVersion("LEG1");
        mockMvc.perform(get("/api/claims/C-HTTP"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.chain", hasSize(1)))
                .andExpect(jsonPath("$.chain[0].eventType").value("HOLD"))
                .andExpect(jsonPath("$.passengerVerificationSummary").value(SUMMARY));
        mockMvc.perform(get("/api/bags/BAG1/claim-diagnostic"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bagStatus").value("CLAIM_HOLD"))
                .andExpect(jsonPath("$.activeClaimKey").value("C-HTTP"));
        mockMvc.perform(get("/api/bags/BAG1/trace")).andExpect(status().isOk())
                .andExpect(jsonPath("$.events[*].eventType", contains("REGISTERED", "CLAIM_HELD")));
        // 读取后状态无任何变化
        assertThat(count("SELECT COUNT(*) FROM claim_chain")).isEqualTo(chainsBefore);
        assertThat(legVersion("LEG1")).isEqualTo(versionBefore);
        assertBag("BAG1", "CLAIM_HOLD", "PEK", 0, null);
    }

    // ---------- 辅助方法 ----------

    private void setupTwoLegBagLoadedOnSecondLeg() {
        baggageService.registerLeg(new RegisterLegRequest(uuid(), "LEG1", "PEK", "SHA", null));
        baggageService.registerLeg(new RegisterLegRequest(uuid(), "LEG2", "SHA", "CAN", null));
        baggageService.registerBag(new RegisterBagRequest(uuid(), "BAG1", List.of("LEG1", "LEG2")));
        baggageService.load("LEG1", new LoadRequest(uuid(), 1, List.of("BAG1")));
        baggageService.seal("LEG1", new SealRequest(uuid(), 2));
        arriveExact("LEG1", List.of("BAG1"));
        baggageService.load("LEG2", new LoadRequest(uuid(), 1, List.of("BAG1")));
    }

    private void registerLegLoadBagOnOpenLeg() {
        baggageService.registerLeg(new RegisterLegRequest(uuid(), "LEG1", "PEK", "SHA", null));
        baggageService.registerBag(new RegisterBagRequest(uuid(), "BAG1", List.of("LEG1")));
        baggageService.load("LEG1", new LoadRequest(uuid(), 1, List.of("BAG1")));
    }

    private ClaimHoldRequest holdRequest(String claimKey, String bagTag) {
        return new ClaimHoldRequest(uuid(), claimKey, bagTag, SUMMARY,
                "旅客在 SHA 转盘未见行李，申请认领冻结", AGENT_A, ROLE_DESK);
    }

    private ClaimReviewRequest reviewRequest(String claimKey, String summary) {
        return new ClaimReviewRequest(uuid(), claimKey, summary, AGENT_B, ROLE_SUPERVISOR);
    }

    private BaggageDtos.DeliveryDifferenceRequest differenceRequest(String claimKey, String bagTag) {
        return new BaggageDtos.DeliveryDifferenceRequest(uuid(), bagTag, claimKey,
                "CAN", "SHA", AGENT_A, "解除冻结后行李被错误交付至 CAN");
    }

    private ResultActions holdHttp(String claimKey, String bagTag) throws Exception {
        return postJson("/api/claims", Map.of(
                "requestId", uuid(), "claimKey", claimKey, "bagTag", bagTag,
                "passengerVerificationSummary", SUMMARY,
                "reason", "旅客在 SHA 转盘未见行李，申请认领冻结",
                "holdAgentId", AGENT_A, "holdAgentRole", ROLE_DESK));
    }

    private void arriveExact(String legId, List<String> bagTags) {
        baggageService.arrive(legId, new BaggageDtos.ArriveRequest(uuid(), bagTags));
    }

    private ResultActions postJson(String url, Object body) throws Exception {
        return mockMvc.perform(post(url)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private void assertBag(String bagTag, String status, String location,
                           int nextLegIndex, String loadedLegId) {
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT status, current_location, next_leg_index, loaded_leg_id"
                        + " FROM bag WHERE bag_tag = ?", bagTag);
        assertThat(row.get("STATUS")).isEqualTo(status);
        assertThat(row.get("CURRENT_LOCATION")).isEqualTo(location);
        assertThat(((Number) row.get("NEXT_LEG_INDEX")).intValue()).isEqualTo(nextLegIndex);
        assertThat(row.get("LOADED_LEG_ID")).isEqualTo(loadedLegId);
    }

    private int count(String sql) {
        Integer value = jdbcTemplate.queryForObject(sql, Integer.class);
        return value == null ? 0 : value;
    }

    private int legVersion(String legId) {
        return jdbcTemplate.queryForObject(
                "SELECT version FROM leg WHERE leg_id = ?", Integer.class, legId);
    }

    private ApiException expectConflict(Callable<?> action) {
        return expect(409, action);
    }

    private ApiException expectUnprocessable(Callable<?> action) {
        return expect(422, action);
    }

    private ApiException expect(int status, Callable<?> action) {
        try {
            action.call();
        } catch (ApiException ex) {
            assertThat(ex.getStatus().value()).isEqualTo(status);
            return ex;
        } catch (Exception ex) {
            throw new AssertionError("预期 ApiException " + status + "，实际: " + ex, ex);
        }
        throw new AssertionError("预期 ApiException " + status + "，但操作成功");
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

    private static String uuid() {
        return UUID.randomUUID().toString();
    }
}
