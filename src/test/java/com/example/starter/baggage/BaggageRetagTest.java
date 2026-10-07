package com.example.starter.baggage;

import java.util.ArrayList;
import java.util.HashMap;
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
import com.example.starter.baggage.BaggageDtos.RegisterBagRequest;
import com.example.starter.baggage.BaggageDtos.RegisterLegRequest;
import com.example.starter.baggage.BaggageDtos.RetagRequest;
import com.example.starter.baggage.BaggageDtos.RetagResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 行李标签代次（换标）测试：覆盖换标主流程（B1->B2->B3）、当前号门禁、
 * 旧标签别名追溯、封舱清单冻结、拒绝分支、幂等重放与并发边界。
 * 全部基于 H2 MySQL 兼容模式真实数据库，不使用 mock。
 */
@SpringBootTest
@AutoConfigureMockMvc
class BaggageRetagTest {

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
        jdbcTemplate.update("DELETE FROM bag_tag_alias");
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
    void retag_mainFlowSynthesis_b1ToB2ThenB3WithFrozenManifest() throws Exception {
        registerLeg("LEG1", "PEK", "SHA");
        registerLeg("LEG2", "SHA", "CAN");

        // 登记：分配稳定 bagId，当前标签 B1，代次 1
        registerBag("B1", List.of("LEG1", "LEG2")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.bagTag").value("B1"))
                .andExpect(jsonPath("$.tagGeneration").value(1))
                .andExpect(jsonPath("$.bagId").isNotEmpty());
        String bagId = bagIdOf("B1");

        // B1 换为 B2：代次加一
        retag(bagId, 1, "B2").andExpect(status().isOk())
                .andExpect(jsonPath("$.bagId").value(bagId))
                .andExpect(jsonPath("$.bagTag").value("B2"))
                .andExpect(jsonPath("$.previousBagTag").value("B1"))
                .andExpect(jsonPath("$.tagGeneration").value(2));

        // 按 B1 新装载 409，按 B2 可装
        load("LEG1", 1, List.of("B1")).andExpect(status().isConflict());
        load("LEG1", 1, List.of("B2")).andExpect(status().isOk())
                .andExpect(jsonPath("$.loaded", contains("B2")));
        seal("LEG1", 2).andExpect(status().isOk())
                .andExpect(jsonPath("$.manifest", contains("B2")));

        // 到达中转站 SHA，尚有后续航段 LEG2
        arriveExact("LEG1", List.of("B2")).andExpect(status().isOk());
        mockMvc.perform(get("/api/bags/B2/trace"))
                .andExpect(jsonPath("$.currentLocation").value("SHA"))
                .andExpect(jsonPath("$.nextLegIndex").value(1))
                .andExpect(jsonPath("$.status").value("IN_TRANSIT"));

        // 中转站换 B3：旧封舱清单仍列 B2，既有行程不重置
        retag(bagId, 2, "B3").andExpect(status().isOk())
                .andExpect(jsonPath("$.tagGeneration").value(3));
        mockMvc.perform(get("/api/legs/LEG1/manifest"))
                .andExpect(jsonPath("$.manifest", contains("B2")));

        // B1/B2 查询均追溯同一 bagId，当前号 B3、代次 3
        for (String alias : List.of("B1", "B2", "B3")) {
            mockMvc.perform(get("/api/bags/" + alias + "/trace"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.bagId").value(bagId))
                    .andExpect(jsonPath("$.bagTag").value("B3"))
                    .andExpect(jsonPath("$.tagGeneration").value(3))
                    .andExpect(jsonPath("$.nextLegIndex").value(1));
        }

        // 旧标签永久保留别名，不分配给另一实物
        registerBag("B1", List.of("LEG2")).andExpect(status().isConflict());
        registerBag("B2", List.of("LEG2")).andExpect(status().isConflict());

        // 后续航段按当前号 B3 装载并最终交付
        load("LEG2", 1, List.of("B3")).andExpect(status().isOk());
        seal("LEG2", 2).andExpect(status().isOk())
                .andExpect(jsonPath("$.manifest", contains("B3")));
        arriveExact("LEG2", List.of("B3")).andExpect(status().isOk());
        mockMvc.perform(get("/api/bags/B1/trace"))
                .andExpect(jsonPath("$.currentLocation").value("CAN"))
                .andExpect(jsonPath("$.status").value("DELIVERED"))
                .andExpect(jsonPath("$.events[*].eventType",
                        contains("REGISTERED", "RETAGGED", "LOADED", "UNLOADED",
                                "RETAGGED", "LOADED", "UNLOADED", "DELIVERED")));
    }

    @Test
    void retag_rejectsUnknownBagIdWrongGenerationAndUsedNewTag() throws Exception {
        registerLeg("LEG1", "PEK", "SHA");
        registerBag("B1", List.of("LEG1"));
        registerBag("B9", List.of("LEG1"));
        String bagId = bagIdOf("B1");
        String otherBagId = bagIdOf("B9");

        // 未知 bagId -> 404
        retag("BAG-MISSING", 1, "B2").andExpect(status().isNotFound());
        // 期望代次不符 -> 409
        retag(bagId, 2, "B2").andExpect(status().isConflict());
        // 新号为自己的当前号 -> 409
        retag(bagId, 1, "B1").andExpect(status().isConflict());
        // 新号已被其他实物占用 -> 409
        retag(bagId, 1, "B9").andExpect(status().isConflict());
        // 参数校验：operator 为空 -> 400，expectedTagGeneration 缺失 -> 400
        postJson("/api/bags/retag", Map.of("requestId", UUID.randomUUID().toString(),
                "operator", "", "bagId", bagId, "expectedTagGeneration", 1, "newBagTag", "B2"))
                .andExpect(status().isBadRequest());
        Map<String, Object> noGeneration = new HashMap<>();
        noGeneration.put("requestId", UUID.randomUUID().toString());
        noGeneration.put("operator", "op");
        noGeneration.put("bagId", bagId);
        noGeneration.put("newBagTag", "B2");
        postJson("/api/bags/retag", noGeneration).andExpect(status().isBadRequest());

        // 失败均不改变状态
        mockMvc.perform(get("/api/bags/B1/trace"))
                .andExpect(jsonPath("$.bagTag").value("B1"))
                .andExpect(jsonPath("$.tagGeneration").value(1));
        assertThat(bagIdOf("B1")).isEqualTo(bagId);
        assertThat(bagIdOf("B9")).isEqualTo(otherBagId);
    }

    @Test
    void retag_rejectsLoadedShortUnloadedAndDelivered() throws Exception {
        registerLeg("LEG1", "PEK", "SHA");
        registerLeg("LEG2", "SHA", "CAN");
        registerBag("B_LOADED", List.of("LEG1"));
        registerBag("B_SHORT", List.of("LEG1", "LEG2"));
        registerBag("B_DONE", List.of("LEG1"));

        // 已装舱 -> 422
        load("LEG1", 1, List.of("B_LOADED", "B_SHORT", "B_DONE")).andExpect(status().isOk());
        retag(bagIdOf("B_LOADED"), 1, "B_NEW1").andExpect(status().isUnprocessableEntity());

        // 短卸未补到 -> 422
        seal("LEG1", 2);
        diffArrive("LEG1", 3, List.of("B_LOADED", "B_DONE")).andExpect(status().isOk());
        retag(bagIdOf("B_SHORT"), 1, "B_NEW2").andExpect(status().isUnprocessableEntity());

        // 已交付 -> 422（B_DONE 行程仅 LEG1，精确到达子集不接受，用差异到达送达）
        mockMvc.perform(get("/api/bags/B_DONE/trace"))
                .andExpect(jsonPath("$.status").value("DELIVERED"));
        retag(bagIdOf("B_DONE"), 1, "B_NEW3").andExpect(status().isUnprocessableEntity());

        // 短卸行李补到后（RECOVERED 在途）允许换标
        recover("B_SHORT", "LEG1", "SHA").andExpect(status().isOk());
        retag(bagIdOf("B_SHORT"), 1, "B_NEW2").andExpect(status().isOk())
                .andExpect(jsonPath("$.tagGeneration").value(2));
        // 补到恢复后可按新号装载后续航段
        load("LEG2", 1, List.of("B_NEW2")).andExpect(status().isOk());
    }

    @Test
    void trace_eventsFreezeTagAtEventTime() throws Exception {
        registerLeg("LEG1", "PEK", "SHA");
        registerBag("B1", List.of("LEG1"));
        String bagId = bagIdOf("B1");
        retag(bagId, 1, "B2").andExpect(status().isOk());
        retag(bagId, 2, "B3").andExpect(status().isOk());

        // 任一旧标签追溯同一 bagId；事件中的标签为发生时刻的当前号，不随换标倒改
        mockMvc.perform(get("/api/bags/B1/trace"))
                .andExpect(jsonPath("$.bagId").value(bagId))
                .andExpect(jsonPath("$.bagTag").value("B3"))
                .andExpect(jsonPath("$.events[*].eventType",
                        contains("REGISTERED", "RETAGGED", "RETAGGED")))
                .andExpect(jsonPath("$.events[*].bagTag", contains("B1", "B2", "B3")));
        mockMvc.perform(get("/api/bags/UNKNOWN_TAG/trace")).andExpect(status().isNotFound());
    }

    @Test
    void retag_idempotencyReplayConflictAndFailureNotOccupyingKey() throws Exception {
        registerLeg("LEG1", "PEK", "SHA");
        registerBag("B1", List.of("LEG1"));
        String bagId = bagIdOf("B1");

        // 同键同参重放：返回首次完整响应，代次不重复推进
        String requestId = UUID.randomUUID().toString();
        Map<String, Object> body = Map.of("requestId", requestId, "operator", "agent-1",
                "bagId", bagId, "expectedTagGeneration", 1, "newBagTag", "B2");
        postJson("/api/bags/retag", body).andExpect(status().isOk())
                .andExpect(jsonPath("$.bagTag").value("B2"))
                .andExpect(jsonPath("$.tagGeneration").value(2));
        postJson("/api/bags/retag", body).andExpect(status().isOk())
                .andExpect(jsonPath("$.bagTag").value("B2"))
                .andExpect(jsonPath("$.tagGeneration").value(2));
        mockMvc.perform(get("/api/bags/B2/trace"))
                .andExpect(jsonPath("$.tagGeneration").value(2));
        Integer aliases = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bag_tag_alias WHERE bag_id = ?", Integer.class, bagId);
        assertThat(aliases).isEqualTo(2);

        // 同键异参 -> 409
        postJson("/api/bags/retag", Map.of("requestId", requestId, "operator", "agent-1",
                "bagId", bagId, "expectedTagGeneration", 1, "newBagTag", "B3"))
                .andExpect(status().isConflict());

        // 失败不占键：先以已用新号失败（409），再同键换新号成功
        String retryKey = UUID.randomUUID().toString();
        postJson("/api/bags/retag", Map.of("requestId", retryKey, "operator", "agent-2",
                "bagId", bagId, "expectedTagGeneration", 2, "newBagTag", "B2"))
                .andExpect(status().isConflict());
        postJson("/api/bags/retag", Map.of("requestId", retryKey, "operator", "agent-2",
                "bagId", bagId, "expectedTagGeneration", 2, "newBagTag", "B3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tagGeneration").value(3));

        // 成功重放旧换标响应：不把当前标签退回、不重新占号
        postJson("/api/bags/retag", body).andExpect(status().isOk())
                .andExpect(jsonPath("$.bagTag").value("B2"))
                .andExpect(jsonPath("$.tagGeneration").value(2));
        mockMvc.perform(get("/api/bags/B3/trace"))
                .andExpect(jsonPath("$.bagTag").value("B3"))
                .andExpect(jsonPath("$.tagGeneration").value(3));
        aliases = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bag_tag_alias WHERE bag_id = ?", Integer.class, bagId);
        assertThat(aliases).isEqualTo(3);
    }

    @Test
    void loadAndRecover_enforceCurrentTagAndGenerationGate() throws Exception {
        registerLeg("LEG1", "PEK", "SHA");
        registerLeg("LEG2", "SHA", "CAN");
        registerBag("B1", List.of("LEG1", "LEG2"));
        String bagId = bagIdOf("B1");
        retag(bagId, 1, "B2").andExpect(status().isOk());

        // 装载门禁：旧标签 409；代次不符 409；当前号+当前代次可装
        load("LEG1", 1, List.of("B1")).andExpect(status().isConflict());
        load("LEG1", 1, List.of("B2"), 1).andExpect(status().isConflict());
        load("LEG1", 1, List.of("B2"), 2).andExpect(status().isOk());

        seal("LEG1", 2);
        diffArrive("LEG1", 3, List.of()).andExpect(status().isOk());

        // 补到门禁：旧标签 409；代次不符 409；当前号+当前代次成功
        recover("B1", "LEG1", "SHA").andExpect(status().isConflict());
        recover("B2", "LEG1", "SHA", 1).andExpect(status().isConflict());
        recover("B2", "LEG1", "SHA", 2).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RECOVERED"));
    }

    @Test
    void concurrentRetag_sameGenerationSingleWinner() throws Exception {
        registerLeg("LEG1", "PEK", "SHA");
        registerBag("B1", List.of("LEG1"));
        String bagId = bagIdOf("B1");

        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            String newTag = "B_NEW_" + i;
            tasks.add(() -> baggageService.retag(
                    new RetagRequest(UUID.randomUUID().toString(), "op", bagId, 1, newTag)));
        }
        List<Object> results = runConcurrently(tasks);

        long successes = results.stream().filter(RetagResponse.class::isInstance).count();
        long conflicts = results.stream()
                .filter(ApiException.class::isInstance)
                .map(ApiException.class::cast)
                .filter(ex -> ex.getStatus().value() == 409)
                .count();
        assertThat(successes).isEqualTo(1);
        assertThat(conflicts).isEqualTo(3);

        // 一个 bagId 只有一个当前标签，代次只推进一次
        String winnerTag = ((RetagResponse) results.stream()
                .filter(RetagResponse.class::isInstance).findFirst().orElseThrow()).bagTag();
        String currentTag = jdbcTemplate.queryForObject(
                "SELECT bag_tag FROM bag WHERE bag_id = ?", String.class, bagId);
        Integer generation = jdbcTemplate.queryForObject(
                "SELECT tag_generation FROM bag WHERE bag_id = ?", Integer.class, bagId);
        assertThat(currentTag).isEqualTo(winnerTag);
        assertThat(generation).isEqualTo(2);
        Integer aliases = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bag_tag_alias WHERE bag_id = ?", Integer.class, bagId);
        assertThat(aliases).isEqualTo(2);
    }

    @Test
    void concurrentRetagAndLoad_serializedByBagLock() throws Exception {
        registerLeg("LEG1", "PEK", "SHA");
        registerBag("B1", List.of("LEG1"));
        String bagId = bagIdOf("B1");

        List<Callable<Object>> tasks = List.of(
                () -> baggageService.retag(
                        new RetagRequest(UUID.randomUUID().toString(), "op", bagId, 1, "B2")),
                () -> baggageService.load("LEG1",
                        new LoadRequest(UUID.randomUUID().toString(), 1, List.of("B1"))));
        List<Object> results = runConcurrently(tasks);

        boolean retagOk = results.stream().anyMatch(RetagResponse.class::isInstance);
        boolean retagRejected = results.stream()
                .filter(ApiException.class::isInstance).map(ApiException.class::cast)
                .anyMatch(ex -> ex.getStatus().value() == 422);
        boolean loadOk = results.stream()
                .anyMatch(com.example.starter.baggage.BaggageDtos.LoadResponse.class::isInstance);
        boolean loadConflicted = results.stream()
                .filter(ApiException.class::isInstance).map(ApiException.class::cast)
                .anyMatch(ex -> ex.getStatus().value() == 409);

        // 两种合法时序：装载先成功则换标 422（已装舱）；换标先成功则装载 409（旧标签门禁）
        if (loadOk) {
            assertThat(retagRejected).isTrue();
            assertThat(loadConflicted).isFalse();
            String tag = jdbcTemplate.queryForObject(
                    "SELECT bag_tag FROM bag WHERE bag_id = ?", String.class, bagId);
            assertThat(tag).isEqualTo("B1");
            Integer records = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM load_record WHERE bag_id = ?", Integer.class, bagId);
            assertThat(records).isEqualTo(1);
        } else {
            assertThat(retagOk).isTrue();
            assertThat(loadConflicted).isTrue();
            Integer generation = jdbcTemplate.queryForObject(
                    "SELECT tag_generation FROM bag WHERE bag_id = ?", Integer.class, bagId);
            assertThat(generation).isEqualTo(2);
            Integer records = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM load_record WHERE bag_id = ?", Integer.class, bagId);
            assertThat(records).isZero();
        }
    }

    private void registerLeg(String legId, String origin, String destination) {
        baggageService.registerLeg(
                new RegisterLegRequest(UUID.randomUUID().toString(), legId, origin, destination));
    }

    private ResultActions registerBag(String bagTag, List<String> legIds) throws Exception {
        return postJson("/api/bags", Map.of("requestId", UUID.randomUUID().toString(),
                "bagTag", bagTag, "legIds", legIds));
    }

    private String bagIdOf(String bagTag) {
        return jdbcTemplate.queryForObject(
                "SELECT bag_id FROM bag WHERE bag_tag = ?", String.class, bagTag);
    }

    private ResultActions retag(String bagId, int expectedTagGeneration, String newBagTag) throws Exception {
        return postJson("/api/bags/retag", Map.of("requestId", UUID.randomUUID().toString(),
                "operator", "agent", "bagId", bagId,
                "expectedTagGeneration", expectedTagGeneration, "newBagTag", newBagTag));
    }

    private ResultActions load(String legId, int expectedVersion, List<String> bagTags) throws Exception {
        return postJson("/api/legs/" + legId + "/load", Map.of(
                "requestId", UUID.randomUUID().toString(),
                "expectedVersion", expectedVersion, "bagTags", bagTags));
    }

    private ResultActions load(String legId, int expectedVersion, List<String> bagTags,
                               int expectedTagGeneration) throws Exception {
        return postJson("/api/legs/" + legId + "/load", Map.of(
                "requestId", UUID.randomUUID().toString(),
                "expectedVersion", expectedVersion, "bagTags", bagTags,
                "expectedTagGeneration", expectedTagGeneration));
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

    private ResultActions recover(String bagTag, String missingLegId, String actualStation,
                                  int expectedTagGeneration) throws Exception {
        return postJson("/api/bags/recover", Map.of(
                "requestId", UUID.randomUUID().toString(),
                "bagTag", bagTag, "missingLegId", missingLegId, "actualStation", actualStation,
                "expectedTagGeneration", expectedTagGeneration));
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
