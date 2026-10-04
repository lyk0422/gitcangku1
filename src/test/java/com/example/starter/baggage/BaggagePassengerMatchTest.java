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

import com.example.starter.baggage.BaggageDtos.PassengerUpdateResponse;
import com.example.starter.baggage.BaggageDtos.RegisterBagRequest;
import com.example.starter.baggage.BaggageDtos.RegisterLegRequest;
import com.example.starter.baggage.BaggageDtos.SealRequest;
import com.example.starter.baggage.BaggageDtos.SealResponse;
import com.example.starter.baggage.BaggageDtos.UpdatePassengersRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 旅客名单与安全卸载核对测试：登记绑定合成旅客、登机名单按航段版本裁决、
 * 封舱前旅客登机核对、移除旅客的原子卸载（漏项 422 整单不变）、封舱冻结快照、
 * 幂等重放与并发边界。全部基于 H2 MySQL 兼容模式真实数据库，事件时间用可控时钟。
 */
@SpringBootTest
@AutoConfigureMockMvc
class BaggagePassengerMatchTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-10-01T08:30:00Z");

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
    void setUp() {
        jdbcTemplate.update("DELETE FROM bag_event");
        jdbcTemplate.update("DELETE FROM load_record");
        jdbcTemplate.update("DELETE FROM leg_passenger");
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
    void mainFlow_boardedPassengerAllowsSealAndFreezesSnapshot() throws Exception {
        registerLeg("LEG1", "PEK", "SHA");
        registerBag("B1", "P1", List.of("LEG1"));
        registerBag("B2", "P1", List.of("LEG1"));
        registerBag("B3", null, List.of("LEG1"));

        // 登记时绑定的合成旅客编号随轨迹返回
        mockMvc.perform(get("/api/bags/B1/trace"))
                .andExpect(jsonPath("$.passengerId").value("P1"));
        mockMvc.perform(get("/api/bags/B3/trace"))
                .andExpect(jsonPath("$.passengerId").doesNotExist());

        load("LEG1", 1, List.of("B1", "B2", "B3")).andExpect(status().isOk());

        // P1 未登机：绑定旅客的行李在舱内，禁止封舱，航段与装载不变
        seal("LEG1", 2).andExpect(status().isUnprocessableEntity());
        mockMvc.perform(get("/api/legs/LEG1/manifest"))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.version").value(2));

        // 旅客登机：名单更新按航段当前版本裁决
        updatePassengers("LEG1", 2, List.of("P1"), List.of(), List.of())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.boarded", contains("P1")))
                .andExpect(jsonPath("$.unloaded", hasSize(0)));
        mockMvc.perform(get("/api/legs/LEG1/passengers"))
                .andExpect(jsonPath("$.passengers", contains("P1")))
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.sealedPassengerVersion").doesNotExist());

        // 旅客已登机后封舱成功，并冻结旅客版本、名单与旅客-行李关系
        seal("LEG1", 3).andExpect(status().isOk())
                .andExpect(jsonPath("$.manifest", contains("B1", "B2", "B3")))
                .andExpect(jsonPath("$.passengers", contains("P1")));
        mockMvc.perform(get("/api/legs/LEG1/passengers"))
                .andExpect(jsonPath("$.sealedPassengerVersion").value(3))
                .andExpect(jsonPath("$.sealedPassengers", contains("P1")))
                .andExpect(jsonPath("$.sealedPassengerBags.P1", contains("B1", "B2")));

        arriveExact("LEG1", List.of("B1", "B2", "B3")).andExpect(status().isOk());
        mockMvc.perform(get("/api/bags/B1/trace"))
                .andExpect(jsonPath("$.status").value("DELIVERED"));
    }

    @Test
    void removePassengerWithLoadedBags_requiresFullUnloadSetAtomically() throws Exception {
        registerLeg("LEG1", "PEK", "SHA");
        registerBag("B1", "P1", List.of("LEG1"));
        registerBag("B2", "P1", List.of("LEG1"));
        updatePassengers("LEG1", 1, List.of("P1"), List.of(), List.of())
                .andExpect(status().isOk());
        load("LEG1", 2, List.of("B1", "B2")).andExpect(status().isOk());

        // 移除 P1 只卸 B1：漏项 422，名单与装载都不变
        updatePassengers("LEG1", 3, List.of(), List.of("P1"), List.of("B1"))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(get("/api/legs/LEG1/passengers"))
                .andExpect(jsonPath("$.passengers", contains("P1")))
                .andExpect(jsonPath("$.version").value(3));
        assertThat(loadRecordCount("B1")).isEqualTo(1);
        assertThat(loadRecordCount("B2")).isEqualTo(1);

        // 同次提交全部行李卸载集合后成功，清单不再含 B1 或 B2
        updatePassengers("LEG1", 3, List.of(), List.of("P1"), List.of("B1", "B2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(4))
                .andExpect(jsonPath("$.boarded", hasSize(0)))
                .andExpect(jsonPath("$.unloaded", contains("B1", "B2")));
        Integer remaining = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM load_record WHERE leg_id = 'LEG1'", Integer.class);
        assertThat(remaining).isZero();

        // 原行程保留：位置、待乘索引不变，OFFLOAD 事件取可控时钟，可后续重新装载
        mockMvc.perform(get("/api/bags/B1/trace"))
                .andExpect(jsonPath("$.status").value("IN_TRANSIT"))
                .andExpect(jsonPath("$.currentLocation").value("PEK"))
                .andExpect(jsonPath("$.nextLegIndex").value(0))
                .andExpect(jsonPath("$.loadedLegId").doesNotExist())
                .andExpect(jsonPath("$.events[*].eventType",
                        contains("REGISTERED", "LOADED", "OFFLOAD")))
                .andExpect(jsonPath("$.events[2].eventTime").value(FIXED_NOW.toString()));

        load("LEG1", 4, List.of("B1", "B2")).andExpect(status().isOk());
        updatePassengers("LEG1", 5, List.of("P1"), List.of(), List.of())
                .andExpect(status().isOk());
        seal("LEG1", 6).andExpect(status().isOk())
                .andExpect(jsonPath("$.manifest", contains("B1", "B2")));
    }
    @Test
    void unloadRejectsSmuggledOrForeignBags() throws Exception {
        registerLeg("LEG1", "PEK", "SHA");
        registerLeg("LEG2", "PEK", "XIY");
        registerBag("B1", "P1", List.of("LEG1"));
        registerBag("B2", "P2", List.of("LEG1"));
        registerBag("B3", null, List.of("LEG1"));
        registerBag("B4", "P1", List.of("LEG2"));
        updatePassengers("LEG1", 1, List.of("P1", "P2"), List.of(), List.of())
                .andExpect(status().isOk());
        load("LEG1", 2, List.of("B1", "B2", "B3")).andExpect(status().isOk());
        load("LEG2", 1, List.of("B4")).andExpect(status().isOk());

        // 夹带其他旅客（P2）的行李 -> 422
        updatePassengers("LEG1", 3, List.of(), List.of("P1"), List.of("B1", "B2"))
                .andExpect(status().isUnprocessableEntity());
        // 夹带无旅客绑定的行李 -> 422
        updatePassengers("LEG1", 3, List.of(), List.of("P1"), List.of("B1", "B3"))
                .andExpect(status().isUnprocessableEntity());
        // 卸载不存在的袋号 -> 422
        updatePassengers("LEG1", 3, List.of(), List.of("P1"), List.of("B1", "B_UNKNOWN"))
                .andExpect(status().isUnprocessableEntity());
        // 夹带装载在其他航段的行李（即便同属 P1）-> 422
        updatePassengers("LEG1", 3, List.of(), List.of("P1"), List.of("B1", "B4"))
                .andExpect(status().isUnprocessableEntity());

        // 全部拒绝后名单与装载均不变
        mockMvc.perform(get("/api/legs/LEG1/passengers"))
                .andExpect(jsonPath("$.passengers", contains("P1", "P2")))
                .andExpect(jsonPath("$.version").value(3));
        assertThat(loadRecordCount("B1")).isEqualTo(1);
        assertThat(loadRecordCount("B2")).isEqualTo(1);
        assertThat(loadRecordCount("B3")).isEqualTo(1);
        assertThat(loadRecordCount("B4")).isEqualTo(1);

        // 精确卸载集合才放行
        updatePassengers("LEG1", 3, List.of(), List.of("P1"), List.of("B1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unloaded", contains("B1")));
        assertThat(loadRecordCount("B1")).isZero();
        assertThat(loadRecordCount("B4")).isEqualTo(1);
    }

    @Test
    void passengerUpdate_validationAndStateGuards() throws Exception {
        registerLeg("LEG1", "PEK", "SHA");
        registerBag("B1", "P1", List.of("LEG1"));

        // 名单作为集合：重复编号 400
        updatePassengers("LEG1", 1, List.of("P1", "P1"), List.of(), List.of())
                .andExpect(status().isBadRequest());
        updatePassengers("LEG1", 1, List.of(), List.of("P2", "P2"), List.of())
                .andExpect(status().isBadRequest());
        // 同一旅客同时出现在新增与移除集合 -> 400
        updatePassengers("LEG1", 1, List.of("P1"), List.of("P1"), List.of())
                .andExpect(status().isBadRequest());
        // 版本冲突 -> 409
        updatePassengers("LEG1", 99, List.of("P1"), List.of(), List.of())
                .andExpect(status().isConflict());
        // 航段不存在 -> 404
        updatePassengers("LEG_MISSING", 1, List.of("P1"), List.of(), List.of())
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/legs/LEG_MISSING/passengers"))
                .andExpect(status().isNotFound());

        // 封舱后名单冻结，安全变化须另行处理，不允许直接改冻结清单
        updatePassengers("LEG1", 1, List.of("P1"), List.of(), List.of())
                .andExpect(status().isOk());
        load("LEG1", 2, List.of("B1")).andExpect(status().isOk());
        seal("LEG1", 3).andExpect(status().isOk());
        updatePassengers("LEG1", 4, List.of("P2"), List.of(), List.of())
                .andExpect(status().isUnprocessableEntity());
        updatePassengers("LEG1", 4, List.of(), List.of("P1"), List.of())
                .andExpect(status().isUnprocessableEntity());

        // 到达后同样冻结
        arriveExact("LEG1", List.of("B1")).andExpect(status().isOk());
        updatePassengers("LEG1", 5, List.of("P2"), List.of(), List.of())
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(get("/api/legs/LEG1/passengers"))
                .andExpect(jsonPath("$.passengers", contains("P1")))
                .andExpect(jsonPath("$.sealedPassengers", contains("P1")));
    }

    @Test
    void seal_unboundBagsFollowOriginalRules() throws Exception {
        // 全无旅客绑定的航段：空名单即可封舱，沿用原规则
        registerLeg("LEG1", "PEK", "SHA");
        registerBag("B1", null, List.of("LEG1"));
        registerBag("B2", null, List.of("LEG1"));
        load("LEG1", 1, List.of("B1", "B2")).andExpect(status().isOk());
        seal("LEG1", 2).andExpect(status().isOk())
                .andExpect(jsonPath("$.manifest", contains("B1", "B2")))
                .andExpect(jsonPath("$.passengers", hasSize(0)));
        mockMvc.perform(get("/api/legs/LEG1/passengers"))
                .andExpect(jsonPath("$.sealedPassengerVersion").value(2))
                .andExpect(jsonPath("$.sealedPassengers", hasSize(0)));

        // 混合舱单：仅未登机旅客的绑定行李阻止封舱
        registerLeg("LEG2", "PEK", "XIY");
        registerBag("B3", null, List.of("LEG2"));
        registerBag("B4", "P9", List.of("LEG2"));
        load("LEG2", 1, List.of("B3", "B4")).andExpect(status().isOk());
        seal("LEG2", 2).andExpect(status().isUnprocessableEntity());
        updatePassengers("LEG2", 2, List.of("P9"), List.of(), List.of())
                .andExpect(status().isOk());
        seal("LEG2", 3).andExpect(status().isOk())
                .andExpect(jsonPath("$.passengers", contains("P9")));
    }
    @Test
    void passengerUpdate_idempotencyReplayConflictAndFailureNotOccupyingKey() throws Exception {
        registerLeg("LEG1", "PEK", "SHA");
        registerBag("B1", "P1", List.of("LEG1"));
        registerBag("B2", "P1", List.of("LEG1"));

        String requestId = UUID.randomUUID().toString();
        Map<String, Object> body = Map.of(
                "requestId", requestId, "operator", "OP1", "expectedLegVersion", 1,
                "add", List.of("P1"), "remove", List.of(), "unloadBagTags", List.of());
        postJson("/api/legs/LEG1/passengers", body).andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));
        // 同键同参重放首次完整响应，版本不再次推进
        postJson("/api/legs/LEG1/passengers", body).andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.boarded", contains("P1")));
        mockMvc.perform(get("/api/legs/LEG1/passengers"))
                .andExpect(jsonPath("$.version").value(2));
        Integer logs = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = ?", Integer.class, requestId);
        assertThat(logs).isEqualTo(1);

        // expectedLegVersion 改变属于异参 -> 409
        postJson("/api/legs/LEG1/passengers", Map.of(
                "requestId", requestId, "operator", "OP1", "expectedLegVersion", 2,
                "add", List.of("P1"), "remove", List.of(), "unloadBagTags", List.of()))
                .andExpect(status().isConflict());
        // 操作者改变属于异参 -> 409
        postJson("/api/legs/LEG1/passengers", Map.of(
                "requestId", requestId, "operator", "OP2", "expectedLegVersion", 1,
                "add", List.of("P1"), "remove", List.of(), "unloadBagTags", List.of()))
                .andExpect(status().isConflict());

        // 失败不占键：漏项卸载 422 后，同键以正确参数成功
        load("LEG1", 2, List.of("B1", "B2")).andExpect(status().isOk());
        String retryKey = UUID.randomUUID().toString();
        postJson("/api/legs/LEG1/passengers", Map.of(
                "requestId", retryKey, "operator", "OP1", "expectedLegVersion", 3,
                "add", List.of(), "remove", List.of("P1"), "unloadBagTags", List.of("B1")))
                .andExpect(status().isUnprocessableEntity());
        postJson("/api/legs/LEG1/passengers", Map.of(
                "requestId", retryKey, "operator", "OP1", "expectedLegVersion", 3,
                "add", List.of(), "remove", List.of("P1"), "unloadBagTags", List.of("B1", "B2")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unloaded", contains("B1", "B2")));
    }

    @Test
    void passengerUpdate_replayDoesNotReintroduceOldList() throws Exception {
        registerLeg("LEG1", "PEK", "SHA");

        String firstKey = UUID.randomUUID().toString();
        Map<String, Object> first = Map.of(
                "requestId", firstKey, "operator", "OP1", "expectedLegVersion", 1,
                "add", List.of("P1"), "remove", List.of(), "unloadBagTags", List.of());
        postJson("/api/legs/LEG1/passengers", first).andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));
        updatePassengers("LEG1", 2, List.of(), List.of("P1"), List.of())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.boarded", hasSize(0)));

        // 旧名单更新重放：返回首次完整响应，但不把后来已移除的旅客重新带入名单
        postJson("/api/legs/LEG1/passengers", first).andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.boarded", contains("P1")));
        mockMvc.perform(get("/api/legs/LEG1/passengers"))
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.passengers", hasSize(0)));
    }

    @Test
    void concurrentPassengerUpdates_versionDecidesSingleWinner() throws Exception {
        registerLeg("LEG1", "PEK", "SHA");

        List<Callable<Object>> tasks = List.of(
                () -> baggageService.updatePassengers("LEG1", new UpdatePassengersRequest(
                        UUID.randomUUID().toString(), "OP1", 1, List.of("P1"), List.of(), List.of())),
                () -> baggageService.updatePassengers("LEG1", new UpdatePassengersRequest(
                        UUID.randomUUID().toString(), "OP2", 1, List.of("P2"), List.of(), List.of())));
        List<Object> results = runConcurrently(tasks);

        long successes = results.stream().filter(PassengerUpdateResponse.class::isInstance).count();
        long conflicts = results.stream()
                .filter(ApiException.class::isInstance)
                .map(ApiException.class::cast)
                .filter(ex -> ex.getStatus().value() == 409)
                .count();
        assertThat(successes).isEqualTo(1);
        assertThat(conflicts).isEqualTo(1);
        Integer version = jdbcTemplate.queryForObject(
                "SELECT version FROM leg WHERE leg_id = 'LEG1'", Integer.class);
        assertThat(version).isEqualTo(2);
        Integer boarded = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM leg_passenger WHERE leg_id = 'LEG1'", Integer.class);
        assertThat(boarded).isEqualTo(1);
    }

    @Test
    void concurrentSameRequestId_replaysSingleEffect() throws Exception {
        registerLeg("LEG1", "PEK", "SHA");

        String requestId = UUID.randomUUID().toString();
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            tasks.add(() -> baggageService.updatePassengers("LEG1", new UpdatePassengersRequest(
                    requestId, "OP1", 1, List.of("P1"), List.of(), List.of())));
        }
        List<Object> results = runConcurrently(tasks);

        assertThat(results).allSatisfy(result -> {
            assertThat(result).isInstanceOf(PassengerUpdateResponse.class);
            assertThat(((PassengerUpdateResponse) result).version()).isEqualTo(2);
        });
        Integer version = jdbcTemplate.queryForObject(
                "SELECT version FROM leg WHERE leg_id = 'LEG1'", Integer.class);
        assertThat(version).isEqualTo(2);
        Integer boarded = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM leg_passenger WHERE leg_id = 'LEG1'", Integer.class);
        assertThat(boarded).isEqualTo(1);
        Integer logs = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = ?", Integer.class, requestId);
        assertThat(logs).isEqualTo(1);
    }

    @Test
    void concurrentUpdateAndSeal_versionDecidesSingleWinner() throws Exception {
        registerLeg("LEG1", "PEK", "SHA");
        registerBag("B1", null, List.of("LEG1"));
        load("LEG1", 1, List.of("B1")).andExpect(status().isOk());

        List<Callable<Object>> tasks = List.of(
                () -> baggageService.updatePassengers("LEG1", new UpdatePassengersRequest(
                        UUID.randomUUID().toString(), "OP1", 2, List.of("P1"), List.of(), List.of())),
                () -> baggageService.seal("LEG1",
                        new SealRequest(UUID.randomUUID().toString(), 2)));
        List<Object> results = runConcurrently(tasks);

        long successes = results.stream()
                .filter(r -> r instanceof PassengerUpdateResponse || r instanceof SealResponse)
                .count();
        long conflicts = results.stream()
                .filter(ApiException.class::isInstance)
                .map(ApiException.class::cast)
                .filter(ex -> ex.getStatus().value() == 409)
                .count();
        assertThat(successes).isEqualTo(1);
        assertThat(conflicts).isEqualTo(1);

        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM leg WHERE leg_id = 'LEG1'", String.class);
        Integer version = jdbcTemplate.queryForObject(
                "SELECT version FROM leg WHERE leg_id = 'LEG1'", Integer.class);
        assertThat(version).isEqualTo(3);
        if ("OPEN".equals(status)) {
            // 名单更新先成功：封舱随后以新版本完成并冻结新名单
            SealResponse sealed = baggageService.seal("LEG1",
                    new SealRequest(UUID.randomUUID().toString(), 3));
            assertThat(sealed.passengers()).containsExactly("P1");
        } else {
            // 封舱先成功：名单已冻结，安全变化不允许直接改冻结清单
            assertThat(status).isEqualTo("SEALED");
            assertThatThrownBy(() -> baggageService.updatePassengers("LEG1",
                    new UpdatePassengersRequest(UUID.randomUUID().toString(), "OP1", 3,
                            List.of("P1"), List.of(), List.of())))
                    .isInstanceOfSatisfying(ApiException.class,
                            ex -> assertThat(ex.getStatus().value()).isEqualTo(422));
            assertThat(baggageService.getPassengers("LEG1").sealedPassengers()).isEmpty();
        }
    }

    private void registerLeg(String legId, String origin, String destination) {
        baggageService.registerLeg(
                new RegisterLegRequest(UUID.randomUUID().toString(), legId, origin, destination));
    }

    private void registerBag(String bagTag, String passengerId, List<String> legIds) {
        baggageService.registerBag(new RegisterBagRequest(
                UUID.randomUUID().toString(), bagTag, legIds, passengerId));
    }

    private ResultActions updatePassengers(String legId, int expectedLegVersion, List<String> add,
                                           List<String> remove, List<String> unloadBagTags)
            throws Exception {
        return postJson("/api/legs/" + legId + "/passengers", Map.of(
                "requestId", UUID.randomUUID().toString(),
                "operator", "OP1",
                "expectedLegVersion", expectedLegVersion,
                "add", add, "remove", remove, "unloadBagTags", unloadBagTags));
    }

    private ResultActions load(String legId, int expectedVersion, List<String> bagTags)
            throws Exception {
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

    private ResultActions postJson(String url, Object body) throws Exception {
        return mockMvc.perform(post(url)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private int loadRecordCount(String bagTag) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM load_record WHERE bag_tag = ?", Integer.class, bagTag);
        return count == null ? 0 : count;
    }

    /** 同步起跑并发执行任务，结果按提交顺序返回（业务异常包装为返回值）。 */
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