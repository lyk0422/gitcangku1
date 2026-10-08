package com.example.starter.baggage;

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

import com.example.starter.baggage.BaggageDtos.ArriveRequest;
import com.example.starter.baggage.BaggageDtos.ClaimGroupRequest;
import com.example.starter.baggage.BaggageDtos.ClaimMember;
import com.example.starter.baggage.BaggageDtos.DifferenceArriveRequest;
import com.example.starter.baggage.BaggageDtos.FormGroupRequest;
import com.example.starter.baggage.BaggageDtos.LoadRequest;
import com.example.starter.baggage.BaggageDtos.RecoverRequest;
import com.example.starter.baggage.BaggageDtos.RegisterBagRequest;
import com.example.starter.baggage.BaggageDtos.RegisterLegRequest;
import com.example.starter.baggage.BaggageDtos.SealRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 整组认领模式测试：覆盖建组校验、末段待认领、短卸/补到后整组交付、
 * 400/409/422 失败原子性、单件入口不交付组成员、幂等重放与并发唯一成功。
 * 全部基于 H2 MySQL 兼容模式真实数据库与真实事务，不使用 mock。
 */
@SpringBootTest
@AutoConfigureMockMvc
class BaggageGroupClaimTest {

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
        jdbcTemplate.update("DELETE FROM bag_event");
        jdbcTemplate.update("DELETE FROM load_record");
        jdbcTemplate.update("DELETE FROM bag_itinerary");
        jdbcTemplate.update("DELETE FROM bag_group_member");
        jdbcTemplate.update("DELETE FROM bag_group");
        jdbcTemplate.update("DELETE FROM bag");
        jdbcTemplate.update("DELETE FROM leg");
        jdbcTemplate.update("DELETE FROM request_log");
        executor = Executors.newFixedThreadPool(8);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        executor.shutdownNow();
        assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void groupScenario_aArrivesBShort_claim422ThenRecoverAndDeliverBoth_replayArriveNoDoubleDelivery()
            throws Exception {
        setupTwoLegRoute();
        registerBag("A", List.of("L1", "L2"));
        registerBag("B", List.of("L1", "L2"));

        // 装载前建组
        formGroup(UUID.randomUUID().toString(), "G1", "agent-1", List.of("B", "A"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("FORMED"))
                .andExpect(jsonPath("$.finalLegId").value("L2"))
                .andExpect(jsonPath("$.finalStation").value("CAN"))
                .andExpect(jsonPath("$.members", contains("A", "B")))
                .andExpect(jsonPath("$.itinerary", hasSize(2)));

        // 组查询：成员冻结在组上、尚未齐
        mockMvc.perform(get("/api/groups/G1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allReady").value(false))
                .andExpect(jsonPath("$.notReadyReason").value("尚有成员未进入待认领状态"))
                .andExpect(jsonPath("$.members[0].bagTag").value("B"))
                .andExpect(jsonPath("$.members[0].notReadyReason").value("尚有后续航段未完成"))
                .andExpect(jsonPath("$.members[1].bagTag").value("A"))
                .andExpect(jsonPath("$.members[1].notReadyReason").value("尚有后续航段未完成"));

        // 第一段两件一起走完
        load("L1", 1, List.of("A", "B"));
        seal("L1", 2);
        arriveExact("L1", List.of("A", "B"));

        // 末段：A 实际到达，B 短卸（差异到达）
        load("L2", 1, List.of("A", "B"));
        seal("L2", 2);
        diffArrive("L2", 3, List.of("A"));

        // A 只进入待认领，未被交付；B 短卸
        assertThat(bagStatus("A")).isEqualTo("READY_FOR_CLAIM");
        assertThat(bagStatus("B")).isEqualTo("SHORT_UNLOADED");
        mockMvc.perform(get("/api/bags/A/trace"))
                .andExpect(jsonPath("$.groupKey").value("G1"))
                .andExpect(jsonPath("$.status").value("READY_FOR_CLAIM"))
                .andExpect(jsonPath("$.currentLocation").value("CAN"))
                .andExpect(jsonPath("$.events[*].eventType",
                        contains("REGISTERED", "LOADED", "UNLOADED", "LOADED",
                                "UNLOADED", "READY_FOR_CLAIM")));

        // 整组认领 422，A 保持待认领，无一件交付
        claim("G1", UUID.randomUUID().toString(), "agent-1", currentClaims("A", "B"))
                .andExpect(status().isUnprocessableEntity());
        assertThat(bagStatus("A")).isEqualTo("READY_FOR_CLAIM");
        assertThat(bagStatus("B")).isEqualTo("SHORT_UNLOADED");
        mockMvc.perform(get("/api/groups/G1"))
                .andExpect(jsonPath("$.allReady").value(false))
                .andExpect(jsonPath("$.members[?(@.bagTag=='B')].notReadyReason",
                        contains("仍短卸，缺失航段 L2 应到站 CAN")));

        // B 补到后两件都待认领，原补到入口不自动交付组成员
        recover("B", "L2", "CAN");
        assertThat(bagStatus("B")).isEqualTo("READY_FOR_CLAIM");
        assertThat(bagStatus("A")).isEqualTo("READY_FOR_CLAIM");
        mockMvc.perform(get("/api/bags/B/trace"))
                .andExpect(jsonPath("$.events[*].eventType",
                        contains("REGISTERED", "LOADED", "UNLOADED", "LOADED",
                                "SHORT_UNLOADED", "RECOVERED", "READY_FOR_CLAIM")));

        // 显式认领：一次双件交付
        claim("G1", UUID.randomUUID().toString(), "agent-2", currentClaims("A", "B"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLAIMED"))
                .andExpect(jsonPath("$.claimedMembers", contains("A", "B")))
                .andExpect(jsonPath("$.claimedBy").value("agent-2"));
        assertThat(bagStatus("A")).isEqualTo("DELIVERED");
        assertThat(bagStatus("B")).isEqualTo("DELIVERED");
        assertThat(eventCount("A", "CLAIMED")).isEqualTo(1);
        assertThat(eventCount("B", "CLAIMED")).isEqualTo(1);
        // 同组重试不重复追加交付轨迹
        claim("G1", UUID.randomUUID().toString(), "agent-2", currentClaims("A", "B"))
                .andExpect(status().isUnprocessableEntity());
        assertThat(eventCount("A", "CLAIMED")).isEqualTo(1);
        assertThat(eventCount("B", "CLAIMED")).isEqualTo(1);

        // 再重放末段到达（原到达入口）：航段早已 ARRIVED，422，不产生第二次交付
        postArrive("L2", UUID.randomUUID().toString(), List.of("A", "B"))
                .andExpect(status().isUnprocessableEntity());
        assertThat(eventCount("A", "DELIVERED")).isZero();
        Integer deliveredEventsA = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bag_event WHERE bag_tag = 'A' AND event_type = 'CLAIMED'",
                Integer.class);
        assertThat(deliveredEventsA).isEqualTo(1);
    }

    private ResultActions postArrive(String legId, String requestId, List<String> bagTags) throws Exception {
        return postJson("/api/legs/" + legId + "/arrive",
                Map.of("requestId", requestId, "bagTags", bagTags));
    }

    @Test
    void formGroup_rejectsSizeRangeDuplicateAndBlankOperator() throws Exception {
        setupTwoLegRoute();
        registerBag("A", List.of("L1", "L2"));
        registerBag("B", List.of("L1", "L2"));

        // 成员数 2~5：仅 1 件 -> 400
        formGroup(UUID.randomUUID().toString(), "G1", "agent", List.of("A"))
                .andExpect(status().isBadRequest());
        // operator 为空 -> 400
        formGroup(UUID.randomUUID().toString(), "G1", " ", List.of("A", "B"))
                .andExpect(status().isBadRequest());

        // 合法建组
        formGroup(UUID.randomUUID().toString(), "G1", "agent", List.of("A", "B"))
                .andExpect(status().isCreated());
        // groupKey 重复 -> 409
        formGroup(UUID.randomUUID().toString(), "G1", "agent", List.of("A", "B"))
                .andExpect(status().isConflict());
        // 成员重复集合（实际唯一）提交 [A,A] -> 业务校验拒绝且不建组
        formGroup(UUID.randomUUID().toString(), "G2", "agent", List.of("A", "A"))
                .andExpect(status().isUnprocessableEntity());
        // 组不存在查询 -> 404
        mockMvc.perform(get("/api/groups/NOPE")).andExpect(status().isNotFound());
    }

    @Test
    void formGroup_requiresSameItinerarySameStationAndBeforeAnyLoad() throws Exception {
        setupTwoLegRoute();
        registerLeg("L3", "CAN", "SZX");
        registerBag("A", List.of("L1", "L2"));
        registerBag("B", List.of("L1"));
        registerBag("C", List.of("L1", "L2", "L3"));

        // 行程不一致（长度不同）-> 422
        formGroup(UUID.randomUUID().toString(), "G1", "agent", List.of("A", "B"))
                .andExpect(status().isUnprocessableEntity());
        // 行程不一致（末段不同）-> 422
        formGroup(UUID.randomUUID().toString(), "G2", "agent", List.of("A", "C"))
                .andExpect(status().isUnprocessableEntity());
        // 行李不存在 -> 422
        formGroup(UUID.randomUUID().toString(), "G3", "agent", List.of("A", "X"))
                .andExpect(status().isUnprocessableEntity());

        // 任一成员装载后禁止建组
        load("L1", 1, List.of("A"));
        formGroup(UUID.randomUUID().toString(), "G4", "agent", List.of("A", "B"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void nonGroupBag_stillAutoDeliveredOnFinalArrival() throws Exception {
        setupTwoLegRoute();
        registerBag("A", List.of("L1", "L2"));
        registerBag("B", List.of("L1", "L2"));
        // 只把 A 与另一件同行行李组不起来——用单件非组 B 验证：B 不在任何组
        load("L1", 1, List.of("A", "B"));
        seal("L1", 2);
        arriveExact("L1", List.of("A", "B"));
        load("L2", 1, List.of("A", "B"));
        seal("L2", 2);
        arriveExact("L2", List.of("A", "B"));
        // 非组行李末段自动交付，轨迹为 DELIVERED 而非 READY_FOR_CLAIM
        assertThat(bagStatus("A")).isEqualTo("DELIVERED");
        assertThat(bagStatus("B")).isEqualTo("DELIVERED");
        assertThat(eventCount("A", "DELIVERED")).isEqualTo(1);
        assertThat(eventCount("A", "READY_FOR_CLAIM")).isZero();
    }

    @Test
    void groupMember_cannotBeDeliveredViaSingleBagEntries() throws Exception {
        setupTwoLegRoute();
        registerBag("A", List.of("L1", "L2"));
        registerBag("B", List.of("L1", "L2"));
        formGroup(UUID.randomUUID().toString(), "G1", "agent", List.of("A", "B"))
                .andExpect(status().isCreated());
        load("L1", 1, List.of("A", "B"));
        seal("L1", 2);
        arriveExact("L1", List.of("A", "B"));
        load("L2", 1, List.of("A", "B"));
        seal("L2", 2);
        // 末段精确到达：组成员也只到 READY_FOR_CLAIM，原到达入口不交付
        arriveExact("L2", List.of("A", "B"));
        assertThat(bagStatus("A")).isEqualTo("READY_FOR_CLAIM");
        assertThat(bagStatus("B")).isEqualTo("READY_FOR_CLAIM");
        // 补到入口对非短卸的待认领成员无效 -> 422
        postRecover("A", "L2", "CAN").andExpect(status().isUnprocessableEntity());
        assertThat(bagStatus("A")).isEqualTo("READY_FOR_CLAIM");
    }

    private ResultActions postRecover(String bagTag, String missingLegId, String station) throws Exception {
        return postJson("/api/bags/recover", Map.of(
                "requestId", UUID.randomUUID().toString(),
                "bagTag", bagTag, "missingLegId", missingLegId, "actualStation", station));
    }

    /** 让两件组成员走完末段并全部 READY_FOR_CLAIM。 */
    private void prepareReadyGroup(String groupKey) {
        load("L1", 1, List.of("A", "B"));
        seal("L1", 2);
        arriveExact("L1", List.of("A", "B"));
        load("L2", 1, List.of("A", "B"));
        seal("L2", 2);
        arriveExact("L2", List.of("A", "B"));
        assertThat(bagStatus("A")).isEqualTo("READY_FOR_CLAIM");
        assertThat(bagStatus("B")).isEqualTo("READY_FOR_CLAIM");
    }

    @Test
    void claim_missingMember400AndNothingDelivered() throws Exception {
        setupTwoLegRoute();
        registerBag("A", List.of("L1", "L2"));
        registerBag("B", List.of("L1", "L2"));
        formGroup(UUID.randomUUID().toString(), "G1", "agent", List.of("A", "B"))
                .andExpect(status().isCreated());
        prepareReadyGroup("G1");

        // 缺 B -> 400
        claim("G1", UUID.randomUUID().toString(), "agent",
                List.of(new ClaimMember("A", bagVersion("A"))))
                .andExpect(status().isBadRequest());
        // 夹带组外成员 C -> 400
        registerBag("C", List.of("L1", "L2"));
        claim("G1", UUID.randomUUID().toString(), "agent",
                List.of(new ClaimMember("A", bagVersion("A")),
                        new ClaimMember("B", bagVersion("B")),
                        new ClaimMember("C", bagVersion("C"))))
                .andExpect(status().isBadRequest());
        // 成员重复 -> 400
        claim("G1", UUID.randomUUID().toString(), "agent",
                List.of(new ClaimMember("A", bagVersion("A")),
                        new ClaimMember("A", bagVersion("A"))))
                .andExpect(status().isBadRequest());

        assertThat(bagStatus("A")).isEqualTo("READY_FOR_CLAIM");
        assertThat(bagStatus("B")).isEqualTo("READY_FOR_CLAIM");
        assertThat(eventCount("A", "CLAIMED")).isZero();
    }

    @Test
    void claim_staleVersion409AndNothingDelivered() throws Exception {
        setupTwoLegRoute();
        registerBag("A", List.of("L1", "L2"));
        registerBag("B", List.of("L1", "L2"));
        formGroup(UUID.randomUUID().toString(), "G1", "agent", List.of("A", "B"))
                .andExpect(status().isCreated());
        prepareReadyGroup("G1");
        int staleA = bagVersion("A") - 1;

        claim("G1", UUID.randomUUID().toString(), "agent",
                List.of(new ClaimMember("A", staleA), new ClaimMember("B", bagVersion("B"))))
                .andExpect(status().isConflict());
        assertThat(bagStatus("A")).isEqualTo("READY_FOR_CLAIM");
        assertThat(bagStatus("B")).isEqualTo("READY_FOR_CLAIM");
        assertThat(eventCount("B", "CLAIMED")).isZero();

        // 组不存在 -> 404
        claim("NOPE", UUID.randomUUID().toString(), "agent",
                List.of(new ClaimMember("A", bagVersion("A")), new ClaimMember("B", bagVersion("B"))))
                .andExpect(status().isNotFound());
    }

    @Test
    void claim_whenMemberStillHasLaterLeg422() throws Exception {
        setupTwoLegRoute();
        registerBag("A", List.of("L1", "L2"));
        registerBag("B", List.of("L1", "L2"));
        formGroup(UUID.randomUUID().toString(), "G1", "agent", List.of("A", "B"))
                .andExpect(status().isCreated());
        // 仅走完第一段：两件都有后续段
        load("L1", 1, List.of("A", "B"));
        seal("L1", 2);
        arriveExact("L1", List.of("A", "B"));

        claim("G1", UUID.randomUUID().toString(), "agent", currentClaims("A", "B"))
                .andExpect(status().isUnprocessableEntity());
        assertThat(bagStatus("A")).isEqualTo("IN_TRANSIT");
        assertThat(eventCount("A", "CLAIMED")).isZero();
    }

    @Test
    void claim_idempotentReplayAndDifferentParams409() throws Exception {
        setupTwoLegRoute();
        registerBag("A", List.of("L1", "L2"));
        registerBag("B", List.of("L1", "L2"));
        formGroup(UUID.randomUUID().toString(), "G1", "agent", List.of("A", "B"))
                .andExpect(status().isCreated());
        prepareReadyGroup("G1");

        String requestId = UUID.randomUUID().toString();
        List<ClaimMember> members = currentClaims("A", "B");
        // 同参回首次完整响应
        claim("G1", requestId, "agent-1", members)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLAIMED"))
                .andExpect(jsonPath("$.claimedBy").value("agent-1"));
        claim("G1", requestId, "agent-1", members)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.claimedBy").value("agent-1"));
        // 交付轨迹只追加一次
        assertThat(eventCount("A", "CLAIMED")).isEqualTo(1);
        assertThat(eventCount("B", "CLAIMED")).isEqualTo(1);
        Integer logs = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = ?", Integer.class, requestId);
        assertThat(logs).isEqualTo(1);

        // 同键异参（改期望版本）-> 409
        claim("G1", requestId, "agent-1",
                List.of(new ClaimMember("A", members.get(0).expectedVersion() - 1),
                        new ClaimMember("B", members.get(1).expectedVersion())))
                .andExpect(status().isConflict());
    }

    @Test
    void claim_failureDoesNotOccupyKey() throws Exception {
        setupTwoLegRoute();
        registerBag("A", List.of("L1", "L2"));
        registerBag("B", List.of("L1", "L2"));
        formGroup(UUID.randomUUID().toString(), "G1", "agent", List.of("A", "B"))
                .andExpect(status().isCreated());

        // 未齐时同键先 422，不占键；补齐后同键成功
        String requestId = UUID.randomUUID().toString();
        claim("G1", requestId, "agent", currentClaims("A", "B"))
                .andExpect(status().isUnprocessableEntity());
        prepareReadyGroup("G1");
        claim("G1", requestId, "agent", currentClaims("A", "B"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLAIMED"));
        Integer logs = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = ?", Integer.class, requestId);
        assertThat(logs).isEqualTo(1);
    }

    @Test
    void concurrentDistinctClaims_onlyOneSucceedsAndNoDoubleEvents() throws Exception {
        setupTwoLegRoute();
        registerBag("A", List.of("L1", "L2"));
        registerBag("B", List.of("L1", "L2"));
        baggageService.formGroup(new FormGroupRequest(
                UUID.randomUUID().toString(), "G1", "agent", List.of("A", "B")));
        prepareReadyGroup("G1");

        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            tasks.add(() -> baggageService.claimGroup("G1", new ClaimGroupRequest(
                    UUID.randomUUID().toString(), "agent",
                    List.of(new ClaimMember("A", bagVersion("A")),
                            new ClaimMember("B", bagVersion("B"))))));
        }
        List<Object> results = runConcurrently(tasks);

        long successes = results.stream()
                .filter(BaggageDtos.ClaimGroupResponse.class::isInstance)
                .count();
        long rejected = results.stream()
                .filter(ApiException.class::isInstance)
                .map(ApiException.class::cast)
                .filter(ex -> ex.getStatus().value() == 422)
                .count();
        assertThat(successes).isEqualTo(1);
        assertThat(rejected).isEqualTo(3);
        assertThat(bagStatus("A")).isEqualTo("DELIVERED");
        assertThat(bagStatus("B")).isEqualTo("DELIVERED");
        assertThat(eventCount("A", "CLAIMED")).isEqualTo(1);
        assertThat(eventCount("B", "CLAIMED")).isEqualTo(1);
        String groupStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM bag_group WHERE group_key = 'G1'", String.class);
        assertThat(groupStatus).isEqualTo("CLAIMED");
    }

    @Test
    void concurrentSameRequestIdClaim_replaysSingleEffect() throws Exception {
        setupTwoLegRoute();
        registerBag("A", List.of("L1", "L2"));
        registerBag("B", List.of("L1", "L2"));
        baggageService.formGroup(new FormGroupRequest(
                UUID.randomUUID().toString(), "G1", "agent", List.of("A", "B")));
        prepareReadyGroup("G1");

        String requestId = UUID.randomUUID().toString();
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            tasks.add(() -> baggageService.claimGroup("G1", new ClaimGroupRequest(
                    requestId, "agent",
                    List.of(new ClaimMember("A", bagVersion("A")),
                            new ClaimMember("B", bagVersion("B"))))));
        }
        List<Object> results = runConcurrently(tasks);

        assertThat(results).allSatisfy(result ->
                assertThat(result).isInstanceOf(BaggageDtos.ClaimGroupResponse.class));
        assertThat(eventCount("A", "CLAIMED")).isEqualTo(1);
        Integer logs = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = ?", Integer.class, requestId);
        assertThat(logs).isEqualTo(1);
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

    // ---- 场景装配：两段联程 PEK -> SHA -> CAN ----

    private void setupTwoLegRoute() {
        registerLeg("L1", "PEK", "SHA");
        registerLeg("L2", "SHA", "CAN");
    }

    private void registerLeg(String legId, String origin, String destination) {
        baggageService.registerLeg(
                new RegisterLegRequest(UUID.randomUUID().toString(), legId, origin, destination));
    }

    private void registerBag(String bagTag, List<String> legIds) {
        baggageService.registerBag(
                new RegisterBagRequest(UUID.randomUUID().toString(), bagTag, legIds));
    }

    private void load(String legId, int expectedVersion, List<String> bagTags) {
        baggageService.load(legId,
                new LoadRequest(UUID.randomUUID().toString(), expectedVersion, bagTags));
    }

    private void seal(String legId, int expectedVersion) {
        baggageService.seal(legId, new SealRequest(UUID.randomUUID().toString(), expectedVersion));
    }

    private void arriveExact(String legId, List<String> bagTags) {
        baggageService.arrive(legId, new ArriveRequest(UUID.randomUUID().toString(), bagTags));
    }

    private void arriveExact(String legId, String requestId, List<String> bagTags) {
        baggageService.arrive(legId, new ArriveRequest(requestId, bagTags));
    }

    private void diffArrive(String legId, int expectedVersion, List<String> bagTags) {
        baggageService.arriveDifference(legId,
                new DifferenceArriveRequest(UUID.randomUUID().toString(), expectedVersion, bagTags));
    }

    private void diffArrive(String legId, String requestId, int expectedVersion, List<String> bagTags) {
        baggageService.arriveDifference(legId,
                new DifferenceArriveRequest(requestId, expectedVersion, bagTags));
    }

    private void recover(String bagTag, String missingLegId, String station) {
        baggageService.recover(new RecoverRequest(
                UUID.randomUUID().toString(), bagTag, missingLegId, station));
    }

    private int bagVersion(String bagTag) {
        Integer version = jdbcTemplate.queryForObject(
                "SELECT version FROM bag WHERE bag_tag = ?", Integer.class, bagTag);
        assertThat(version).isNotNull();
        return version;
    }

    private String bagStatus(String bagTag) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM bag WHERE bag_tag = ?", String.class, bagTag);
    }

    private int eventCount(String bagTag, String eventType) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bag_event WHERE bag_tag = ? AND event_type = ?",
                Integer.class, bagTag, eventType);
        return count == null ? 0 : count;
    }

    // ---- HTTP 辅助 ----

    private ResultActions formGroup(String requestId, String groupKey, String operator,
                                    List<String> bagTags) throws Exception {
        return postJson("/api/groups", new FormGroupRequest(requestId, groupKey, operator, bagTags));
    }

    private ResultActions claim(String groupKey, String requestId, String operator,
                                List<ClaimMember> members) throws Exception {
        return postJson("/api/groups/" + groupKey + "/claim",
                new ClaimGroupRequest(requestId, operator, members));
    }

    private ResultActions postJson(String url, Object body) throws Exception {
        return mockMvc.perform(post(url)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private List<ClaimMember> currentClaims(String... bagTags) {
        return java.util.Arrays.stream(bagTags)
                .map(tag -> new ClaimMember(tag, bagVersion(tag)))
                .toList();
    }
}
