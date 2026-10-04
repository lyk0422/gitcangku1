package com.example.starter.baggage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
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

import com.example.starter.baggage.BaggageDtos.LoadRequest;
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
 * 旅客名单与安全卸载核对测试：登记行李绑定合成旅客、登机名单维护与递增版本、
 * 绑定旅客未登机禁止封舱、移除旅客须同次全部卸载其已装载行李（漏项 422 整单不变）、
 * 禁止夹带其他旅客行李、封舱冻结名单、版本裁决与幂等重放、并发一致性。
 * 全部基于 H2 MySQL 兼容模式真实数据库，不使用 mock。
 */
@SpringBootTest
@AutoConfigureMockMvc
class BaggagePassengerTest {

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
        jdbcTemplate.update("DELETE FROM leg_passenger");
        jdbcTemplate.update("DELETE FROM bag_itinerary");
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
    void seal_rejectsBoundBagWhenPassengerNotBoarded_andFreezesRoster() throws Exception {
        registerLeg("LEG1", "PEK", "SHA");
        registerBag("B1", List.of("LEG1"), "P1");
        registerBag("B2", List.of("LEG1"), null);
        load("LEG1", 1, List.of("B1", "B2")).andExpect(status().isOk());

        // P1 未登机：绑定旅客的行李禁止封舱
        seal("LEG1", 2).andExpect(status().isUnprocessableEntity());

        // 登记登机后封舱成功，名单版本递增
        updatePassengers("LEG1", 2, List.of("P1"), List.of()).andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.rosterVersion").value(2))
                .andExpect(jsonPath("$.boardedPassengers", contains("P1")))
                .andExpect(jsonPath("$.unloaded", hasSize(0)));
        seal("LEG1", 3).andExpect(status().isOk())
                .andExpect(jsonPath("$.manifest", contains("B1", "B2")));

        // 封舱冻结旅客版本与旅客集合
        mockMvc.perform(get("/api/legs/LEG1/passengers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SEALED"))
                .andExpect(jsonPath("$.rosterVersion").value(2))
                .andExpect(jsonPath("$.boardedPassengers", contains("P1")));
        Integer sealedRosterVersion = jdbcTemplate.queryForObject(
                "SELECT sealed_roster_version FROM leg WHERE leg_id = 'LEG1'", Integer.class);
        assertThat(sealedRosterVersion).isEqualTo(2);
        String sealedPassengers = jdbcTemplate.queryForObject(
                "SELECT sealed_passengers FROM leg WHERE leg_id = 'LEG1'", String.class);
        assertThat(sealedPassengers).isEqualTo("[\"P1\"]");

        // 封舱后名单不得修改
        updatePassengers("LEG1", 4, List.of("P1", "P2"), List.of())
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(get("/api/legs/LEG1/passengers"))
                .andExpect(jsonPath("$.boardedPassengers", contains("P1")));
    }

    @Test
    void updatePassengers_validatesRequestShape() throws Exception {
        registerLeg("LEG1", "PEK", "SHA");

        // 名单作为集合：重复编号 -> 400
        updatePassengers("LEG1", 1, List.of("P1", "P1"), List.of()).andExpect(status().isBadRequest());
        // 缺 operator -> 400
        postJson("/api/legs/LEG1/passengers", Map.of("requestId", UUID.randomUUID().toString(),
                "expectedLegVersion", 1, "boardedPassengers", List.of("P1"),
                "unloadBagTags", List.of())).andExpect(status().isBadRequest());
        // 缺 unloadBagTags -> 400
        postJson("/api/legs/LEG1/passengers", Map.of("requestId", UUID.randomUUID().toString(),
                "operator", "OP1", "expectedLegVersion", 1,
                "boardedPassengers", List.of("P1"))).andExpect(status().isBadRequest());
        // 航段不存在 -> 404
        updatePassengers("LEG_MISSING", 1, List.of("P1"), List.of()).andExpect(status().isNotFound());

        // 合法登记：名单与版本可查
        updatePassengers("LEG1", 1, List.of("P2", "P1"), List.of()).andExpect(status().isOk())
                .andExpect(jsonPath("$.rosterVersion").value(2))
                .andExpect(jsonPath("$.boardedPassengers", contains("P1", "P2")));
        mockMvc.perform(get("/api/legs/LEG1/passengers"))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.rosterVersion").value(2))
                .andExpect(jsonPath("$.boardedPassengers", contains("P1", "P2")));
        Integer legVersion = jdbcTemplate.queryForObject(
                "SELECT version FROM leg WHERE leg_id = 'LEG1'", Integer.class);
        assertThat(legVersion).isEqualTo(2);
    }

    @Test
    void removePassenger_missingUnloadItemFailsWholeRequest() throws Exception {
        // 合成场景：P1 有 B1、B2 已装载
        registerLeg("LEG1", "PEK", "SHA");
        registerBag("B1", List.of("LEG1"), "P1");
        registerBag("B2", List.of("LEG1"), "P1");
        updatePassengers("LEG1", 1, List.of("P1"), List.of()).andExpect(status().isOk());
        load("LEG1", 2, List.of("B1", "B2")).andExpect(status().isOk());

        // 移除 P1 只卸 B1：整单 422，名单与装载都不变
        updatePassengers("LEG1", 3, List.of(), List.of("B1"))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(get("/api/legs/LEG1/passengers"))
                .andExpect(jsonPath("$.boardedPassengers", contains("P1")))
                .andExpect(jsonPath("$.rosterVersion").value(2));
        mockMvc.perform(get("/api/bags/B1/trace"))
                .andExpect(jsonPath("$.loadedLegId").value("LEG1"));
        mockMvc.perform(get("/api/bags/B2/trace"))
                .andExpect(jsonPath("$.loadedLegId").value("LEG1"));
        Integer loadCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM load_record WHERE leg_id = 'LEG1'", Integer.class);
        assertThat(loadCount).isEqualTo(2);
        Integer legVersion = jdbcTemplate.queryForObject(
                "SELECT version FROM leg WHERE leg_id = 'LEG1'", Integer.class);
        assertThat(legVersion).isEqualTo(3);

        // 同次卸下两件后成功，清单不再含 B1 或 B2
        updatePassengers("LEG1", 3, List.of(), List.of("B1", "B2")).andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(4))
                .andExpect(jsonPath("$.rosterVersion").value(3))
                .andExpect(jsonPath("$.boardedPassengers", hasSize(0)))
                .andExpect(jsonPath("$.unloaded", contains("B1", "B2")));
        Integer remaining = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM load_record WHERE leg_id = 'LEG1'", Integer.class);
        assertThat(remaining).isZero();
        mockMvc.perform(get("/api/bags/B1/trace"))
                .andExpect(jsonPath("$.loadedLegId").doesNotExist())
                .andExpect(jsonPath("$.status").value("IN_TRANSIT"))
                .andExpect(jsonPath("$.nextLegIndex").value(0))
                .andExpect(jsonPath("$.events[*].eventType",
                        contains("REGISTERED", "LOADED", "SECURITY_UNLOADED")));

        // 原行程保留：可重新装载同一航段
        load("LEG1", 4, List.of("B1", "B2")).andExpect(status().isOk())
                .andExpect(jsonPath("$.loaded", contains("B1", "B2")));
    }
    @Test
    void removePassenger_rejectsStrayOrNotLoadedBags() throws Exception {
        registerLeg("LEG1", "PEK", "SHA");
        registerLeg("LEG2", "SHA", "CAN");
        registerBag("B1", List.of("LEG1"), "P1");
        registerBag("B2", List.of("LEG1"), "P2");
        registerBag("B3", List.of("LEG1"), null);
        registerBag("B4", List.of("LEG2"), "P1");
        updatePassengers("LEG1", 1, List.of("P1", "P2"), List.of()).andExpect(status().isOk());
        load("LEG1", 2, List.of("B1", "B2", "B3")).andExpect(status().isOk());
        load("LEG2", 1, List.of("B4")).andExpect(status().isOk());

        // 夹带其他旅客（P2 未被移除）的行李 -> 422
        updatePassengers("LEG1", 3, List.of("P2"), List.of("B1", "B2"))
                .andExpect(status().isUnprocessableEntity());
        // 夹带未绑定旅客的行李 -> 422
        updatePassengers("LEG1", 3, List.of("P2"), List.of("B1", "B3"))
                .andExpect(status().isUnprocessableEntity());
        // 卸载未装载在当前航段的行李 -> 422
        updatePassengers("LEG1", 3, List.of("P2"), List.of("B1", "B4"))
                .andExpect(status().isUnprocessableEntity());

        // 名单与装载均未变化
        mockMvc.perform(get("/api/legs/LEG1/passengers"))
                .andExpect(jsonPath("$.boardedPassengers", contains("P1", "P2")))
                .andExpect(jsonPath("$.rosterVersion").value(2));
        Integer loadCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM load_record WHERE leg_id = 'LEG1'", Integer.class);
        assertThat(loadCount).isEqualTo(3);
        Integer legVersion = jdbcTemplate.queryForObject(
                "SELECT version FROM leg WHERE leg_id = 'LEG1'", Integer.class);
        assertThat(legVersion).isEqualTo(3);
    }

    @Test
    void updatePassengers_versionConflictAndIdempotency() throws Exception {
        registerLeg("LEG1", "PEK", "SHA");

        // 版本冲突 -> 409
        updatePassengers("LEG1", 99, List.of("P1"), List.of()).andExpect(status().isConflict());

        // 同键同参重放首次完整响应，版本只推进一次
        String key = UUID.randomUUID().toString();
        Map<String, Object> body = Map.of("requestId", key, "operator", "OP1",
                "expectedLegVersion", 1, "boardedPassengers", List.of("P1"),
                "unloadBagTags", List.of());
        postJson("/api/legs/LEG1/passengers", body).andExpect(status().isOk())
                .andExpect(jsonPath("$.rosterVersion").value(2));
        postJson("/api/legs/LEG1/passengers", body).andExpect(status().isOk())
                .andExpect(jsonPath("$.rosterVersion").value(2));
        mockMvc.perform(get("/api/legs/LEG1/passengers"))
                .andExpect(jsonPath("$.rosterVersion").value(2));
        Integer logs = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = ?", Integer.class, key);
        assertThat(logs).isEqualTo(1);

        // 同键异参（expectedLegVersion 改变）-> 409
        postJson("/api/legs/LEG1/passengers", Map.of("requestId", key, "operator", "OP1",
                "expectedLegVersion", 2, "boardedPassengers", List.of("P1"),
                "unloadBagTags", List.of())).andExpect(status().isConflict());

        // 后续名单推进到 [P1, P2]
        updatePassengers("LEG1", 2, List.of("P1", "P2"), List.of()).andExpect(status().isOk())
                .andExpect(jsonPath("$.rosterVersion").value(3));
        // 旧名单更新重放不能带入后来名单：返回首次响应，当前名单保持 [P1, P2]
        postJson("/api/legs/LEG1/passengers", body).andExpect(status().isOk())
                .andExpect(jsonPath("$.rosterVersion").value(2))
                .andExpect(jsonPath("$.boardedPassengers", contains("P1")));
        mockMvc.perform(get("/api/legs/LEG1/passengers"))
                .andExpect(jsonPath("$.rosterVersion").value(3))
                .andExpect(jsonPath("$.boardedPassengers", contains("P1", "P2")));

        // 失败不占键：先 422（漏卸）后同键修正参数成功
        registerLeg("LEG2", "PEK", "SHA");
        registerBag("B9", List.of("LEG2"), "P9");
        updatePassengers("LEG2", 1, List.of("P9"), List.of()).andExpect(status().isOk());
        load("LEG2", 2, List.of("B9")).andExpect(status().isOk());
        String retryKey = UUID.randomUUID().toString();
        postJson("/api/legs/LEG2/passengers", Map.of("requestId", retryKey, "operator", "OP1",
                "expectedLegVersion", 3, "boardedPassengers", List.of(),
                "unloadBagTags", List.of())).andExpect(status().isUnprocessableEntity());
        postJson("/api/legs/LEG2/passengers", Map.of("requestId", retryKey, "operator", "OP1",
                "expectedLegVersion", 3, "boardedPassengers", List.of(),
                "unloadBagTags", List.of("B9"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.unloaded", contains("B9")));
    }

    @Test
    void unboundBagsFollowOriginalRules() throws Exception {
        registerLeg("LEG1", "PEK", "SHA");
        registerBag("B1", List.of("LEG1"), null);
        load("LEG1", 1, List.of("B1")).andExpect(status().isOk());

        // 无旅客绑定的旧行李：无名单也可封舱、到达
        seal("LEG1", 2).andExpect(status().isOk())
                .andExpect(jsonPath("$.manifest", contains("B1")));
        mockMvc.perform(get("/api/legs/LEG1/passengers"))
                .andExpect(jsonPath("$.boardedPassengers", hasSize(0)))
                .andExpect(jsonPath("$.rosterVersion").value(1));
        arrive("LEG1", List.of("B1")).andExpect(status().isOk());
        mockMvc.perform(get("/api/bags/B1/trace"))
                .andExpect(jsonPath("$.status").value("DELIVERED"));
    }
    @Test
    void concurrentUpdateAndSeal_versionDecidesSingleWinner() throws Exception {
        baggageService.registerLeg(
                new RegisterLegRequest(UUID.randomUUID().toString(), "LEG1", "PEK", "SHA"));
        baggageService.registerBag(
                new RegisterBagRequest(UUID.randomUUID().toString(), "B1", List.of("LEG1"), "P1"));
        baggageService.updatePassengers("LEG1", new UpdatePassengersRequest(
                UUID.randomUUID().toString(), "OP1", 1, List.of("P1"), List.of()));
        baggageService.load("LEG1", new LoadRequest(UUID.randomUUID().toString(), 2, List.of("B1")));

        // 名单更新与封舱真实并发：版本决定唯一先后
        List<Callable<Object>> tasks = List.of(
                () -> baggageService.updatePassengers("LEG1", new UpdatePassengersRequest(
                        UUID.randomUUID().toString(), "OP1", 3, List.of("P1", "P2"), List.of())),
                () -> baggageService.seal("LEG1",
                        new SealRequest(UUID.randomUUID().toString(), 3)));
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

        String legStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM leg WHERE leg_id = 'LEG1'", String.class);
        Integer version = jdbcTemplate.queryForObject(
                "SELECT version FROM leg WHERE leg_id = 'LEG1'", Integer.class);
        assertThat(version).isEqualTo(4);
        if ("OPEN".equals(legStatus)) {
            // 名单更新先成功：封舱以新版本完成并冻结新名单
            SealResponse sealed = baggageService.seal("LEG1",
                    new SealRequest(UUID.randomUUID().toString(), 4));
            assertThat(sealed.manifest()).containsExactly("B1");
            assertThat(baggageService.getPassengers("LEG1").boardedPassengers())
                    .containsExactly("P1", "P2");
            assertThat(baggageService.getPassengers("LEG1").rosterVersion()).isEqualTo(3);
        } else {
            // 封舱先成功：名单冻结，更新被拒 422
            assertThat(legStatus).isEqualTo("SEALED");
            assertThat(baggageService.getPassengers("LEG1").boardedPassengers())
                    .containsExactly("P1");
            assertThatThrownBy(() -> baggageService.updatePassengers("LEG1",
                    new UpdatePassengersRequest(UUID.randomUUID().toString(), "OP1", 4,
                            List.of("P1", "P2"), List.of())))
                    .isInstanceOf(ApiException.class)
                    .matches(ex -> ((ApiException) ex).getStatus().value() == 422);
        }
    }

    @Test
    void concurrentSameRequestId_replaysSingleEffect() throws Exception {
        baggageService.registerLeg(
                new RegisterLegRequest(UUID.randomUUID().toString(), "LEG1", "PEK", "SHA"));

        String requestId = UUID.randomUUID().toString();
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            tasks.add(() -> baggageService.updatePassengers("LEG1", new UpdatePassengersRequest(
                    requestId, "OP1", 1, List.of("P1"), List.of())));
        }
        List<Object> results = runConcurrently(tasks);

        assertThat(results).allSatisfy(result -> {
            assertThat(result).isInstanceOf(PassengerUpdateResponse.class);
            assertThat(((PassengerUpdateResponse) result).rosterVersion()).isEqualTo(2);
        });
        Integer version = jdbcTemplate.queryForObject(
                "SELECT version FROM leg WHERE leg_id = 'LEG1'", Integer.class);
        assertThat(version).isEqualTo(2);
        Integer rosterRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM leg_passenger WHERE leg_id = 'LEG1'", Integer.class);
        assertThat(rosterRows).isEqualTo(1);
        Integer logs = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = ?", Integer.class, requestId);
        assertThat(logs).isEqualTo(1);
    }

    private ResultActions registerLeg(String legId, String origin, String destination) throws Exception {
        return postJson("/api/legs", Map.of("requestId", UUID.randomUUID().toString(),
                "legId", legId, "origin", origin, "destination", destination));
    }

    private ResultActions registerBag(String bagTag, List<String> legIds, String passengerId)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", UUID.randomUUID().toString());
        body.put("bagTag", bagTag);
        body.put("legIds", legIds);
        if (passengerId != null) {
            body.put("passengerId", passengerId);
        }
        return postJson("/api/bags", body);
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

    private ResultActions updatePassengers(String legId, int expectedLegVersion,
                                           List<String> boarded, List<String> unload) throws Exception {
        return postJson("/api/legs/" + legId + "/passengers", Map.of(
                "requestId", UUID.randomUUID().toString(), "operator", "OP1",
                "expectedLegVersion", expectedLegVersion,
                "boardedPassengers", boarded, "unloadBagTags", unload));
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