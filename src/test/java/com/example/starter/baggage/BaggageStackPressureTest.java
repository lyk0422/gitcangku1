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

import com.example.starter.baggage.BaggageDtos.ArrangeItem;
import com.example.starter.baggage.BaggageDtos.ArrangeRequest;
import com.example.starter.baggage.BaggageDtos.ArrangeResponse;
import com.example.starter.baggage.BaggageDtos.RegisterBagRequest;
import com.example.starter.baggage.BaggageDtos.RegisterLegRequest;
import com.example.starter.baggage.BaggageDtos.SealRequest;
import com.example.starter.baggage.BaggageDtos.SealResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 垂直堆叠承压规则测试：基于 H2 MySQL 兼容模式真实数据库。
 * 覆盖题干场景（底层承压 1000 克，600+500 整次拒绝，改 400 后恰好 1000 可封舱）、
 * 层号空洞/重复、零承压限顶层、完整后态卸载与换层、重量档案版本与冻结、
 * 幂等重放与并发竞争；全部核验业务结果与最终数据。
 */
@SpringBootTest
@AutoConfigureMockMvc
class BaggageStackPressureTest {

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
        jdbcTemplate.update("DELETE FROM bag_weight");
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
    void taskScenario_overPressureRejectsWholeRequestThenExactLimitSeals() throws Exception {
        registerLeg("LEG1", "PEK", "SHA");
        registerBag("A", List.of("LEG1"));
        registerBag("B", List.of("LEG1"));
        registerBag("C", List.of("LEG1"));
        // 底层 A 承压 1000 克；B 重 600、C 重 500
        createWeight("A", 100, 1000).andExpect(status().isCreated());
        createWeight("B", 600, 1000).andExpect(status().isCreated());
        createWeight("C", 500, 0).andExpect(status().isCreated());

        // A 上方 600+500=1100 > 1000：整次拒绝，原清单不变
        arrange("LEG1", 1, List.of(item("A", 1, 1), item("B", 1, 2), item("C", 1, 3)))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(get("/api/legs/LEG1/manifest"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.status").value("OPEN"));
        assertThat(countRows("load_record")).isZero();
        mockMvc.perform(get("/api/bags/A/trace"))
                .andExpect(jsonPath("$.loadedLegId").doesNotExist());

        // C 改 400 后恰好 600+400=1000，可排舱并封舱
        updateWeight("C", 400, 0, 1).andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));
        arrange("LEG1", 1, List.of(item("A", 1, 1), item("B", 1, 2), item("C", 1, 3)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.items[*].bagTag", contains("A", "B", "C")))
                .andExpect(jsonPath("$.items[*].layer", contains(1, 2, 3)));

        seal("LEG1", 2).andExpect(status().isOk())
                .andExpect(jsonPath("$.manifest", contains("A", "B", "C")));

        // 封舱冻结层序：堆叠视图返回冻结快照
        mockMvc.perform(get("/api/legs/LEG1/stacks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SEALED"))
                .andExpect(jsonPath("$.items[*].bagTag", contains("A", "B", "C")))
                .andExpect(jsonPath("$.items[*].stackNo", contains(1, 1, 1)))
                .andExpect(jsonPath("$.items[*].layer", contains(1, 2, 3)));

        // 已封舱禁止新排堆栈；封舱后修改已装行李档案 409，历史不漂移
        arrange("LEG1", 3, List.of(item("A", 1, 1), item("B", 1, 2)))
                .andExpect(status().isUnprocessableEntity());
        updateWeight("A", 100, 2000, 1).andExpect(status().isConflict());
        updateWeight("C", 999, 0, 2).andExpect(status().isConflict());
        mockMvc.perform(get("/api/bags/A/weight"))
                .andExpect(jsonPath("$.maxLoadGrams").value(1000))
                .andExpect(jsonPath("$.version").value(1));
        mockMvc.perform(get("/api/bags/C/weight"))
                .andExpect(jsonPath("$.weightGrams").value(400))
                .andExpect(jsonPath("$.version").value(2));
    }

    @Test
    void arrange_layerGapDuplicateLayerAndDuplicateBagRejected() throws Exception {
        registerLeg("LEG1", "PEK", "SHA");
        registerBag("A", List.of("LEG1"));
        registerBag("B", List.of("LEG1"));
        registerBag("C", List.of("LEG1"));
        createWeight("A", 100, 1000).andExpect(status().isCreated());
        createWeight("B", 100, 1000).andExpect(status().isCreated());
        createWeight("C", 100, 1000).andExpect(status().isCreated());

        // 层号空洞（缺第 2 层）-> 422
        arrange("LEG1", 1, List.of(item("A", 1, 1), item("B", 1, 3)))
                .andExpect(status().isUnprocessableEntity());
        // 同堆栈同层两件 -> 422
        arrange("LEG1", 1, List.of(item("A", 1, 1), item("B", 1, 1)))
                .andExpect(status().isUnprocessableEntity());
        // 同一件行李出现两次 -> 422
        arrange("LEG1", 1, List.of(item("A", 1, 1), item("A", 2, 1)))
                .andExpect(status().isUnprocessableEntity());
        // 层号不从 1 起 -> 422
        arrange("LEG1", 1, List.of(item("A", 1, 2)))
                .andExpect(status().isUnprocessableEntity());

        // 全部失败不落库：版本不变、无装载记录
        mockMvc.perform(get("/api/legs/LEG1/manifest"))
                .andExpect(jsonPath("$.version").value(1));
        assertThat(countRows("load_record")).isZero();
    }

    @Test
    void arrange_zeroPressureOnlyAllowedAtTop() throws Exception {
        registerLeg("LEG1", "PEK", "SHA");
        registerBag("TOP0", List.of("LEG1"));
        registerBag("BELOW", List.of("LEG1"));
        createWeight("TOP0", 50, 0).andExpect(status().isCreated());
        createWeight("BELOW", 800, 1000).andExpect(status().isCreated());

        // 零承压行李下方有位置、上方压一件 -> 422
        arrange("LEG1", 1, List.of(item("TOP0", 1, 1), item("BELOW", 1, 2)))
                .andExpect(status().isUnprocessableEntity());
        assertThat(countRows("load_record")).isZero();

        // 零承压行李位于顶层 -> 允许
        arrange("LEG1", 1, List.of(item("BELOW", 1, 1), item("TOP0", 1, 2)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].bagTag", contains("BELOW", "TOP0")));
    }

    @Test
    void arrange_postStateUnloadsAndRestacksAtomically() throws Exception {
        registerLeg("LEG1", "PEK", "SHA");
        registerBag("A", List.of("LEG1"));
        registerBag("B", List.of("LEG1"));
        registerBag("C", List.of("LEG1"));
        createWeight("A", 100, 1000).andExpect(status().isCreated());
        createWeight("B", 200, 1000).andExpect(status().isCreated());
        createWeight("C", 300, 1000).andExpect(status().isCreated());

        arrange("LEG1", 1, List.of(item("A", 1, 1), item("B", 1, 2), item("C", 1, 3)))
                .andExpect(status().isOk());

        // 完整后态：C 不在新态中视为卸载，B 换到独立堆栈 2
        arrange("LEG1", 2, List.of(item("A", 1, 1), item("B", 2, 1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.items[*].bagTag", contains("A", "B")))
                .andExpect(jsonPath("$.items[*].stackNo", contains(1, 2)));

        mockMvc.perform(get("/api/bags/C/trace"))
                .andExpect(jsonPath("$.loadedLegId").doesNotExist())
                .andExpect(jsonPath("$.currentLocation").value("PEK"));
        Integer cRecords = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM load_record WHERE bag_tag = 'C'", Integer.class);
        assertThat(cRecords).isZero();
        mockMvc.perform(get("/api/legs/LEG1/stacks"))
                .andExpect(jsonPath("$.items", hasSize(2)));

        // 卸载后的行李可重新排入；空后态清空整舱
        arrange("LEG1", 3, List.of(item("C", 1, 1))).andExpect(status().isOk());
        arrange("LEG1", 4, List.of()).andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(0)));
        assertThat(countRows("load_record")).isZero();
    }

    @Test
    void arrange_rejectsWrongVersionUnknownAndIneligibleBags() throws Exception {
        registerLeg("LEG1", "PEK", "SHA");
        registerLeg("LEG2", "SHA", "CAN");
        registerBag("A", List.of("LEG1"));
        registerBag("NO_PROFILE", List.of("LEG1"));
        registerBag("WRONG_LEG", List.of("LEG2"));
        createWeight("A", 100, 1000).andExpect(status().isCreated());
        createWeight("WRONG_LEG", 100, 1000).andExpect(status().isCreated());

        // 版本冲突 -> 409
        arrange("LEG1", 99, List.of(item("A", 1, 1))).andExpect(status().isConflict());
        // 航段不存在 -> 404
        arrange("LEG_MISSING", 1, List.of(item("A", 1, 1))).andExpect(status().isNotFound());
        // 行李不存在 -> 422
        arrange("LEG1", 1, List.of(item("BAG_MISSING", 1, 1)))
                .andExpect(status().isUnprocessableEntity());
        // 缺少重量档案 -> 422
        arrange("LEG1", 1, List.of(item("NO_PROFILE", 1, 1)))
                .andExpect(status().isUnprocessableEntity());
        // 待乘航段不符 -> 422
        arrange("LEG1", 1, List.of(item("WRONG_LEG", 1, 1)))
                .andExpect(status().isUnprocessableEntity());

        mockMvc.perform(get("/api/legs/LEG1/manifest"))
                .andExpect(jsonPath("$.version").value(1));
        assertThat(countRows("load_record")).isZero();
    }

    @Test
    void weightProfile_versionsValidationAndIdempotentCreate() throws Exception {
        registerLeg("LEG1", "PEK", "SHA");
        registerBag("A", List.of("LEG1"));

        // 行李不存在 -> 404
        createWeight("BAG_MISSING", 100, 100).andExpect(status().isNotFound());
        // 自重非正、承压为负 -> 400
        createWeight("A", 0, 100).andExpect(status().isBadRequest());
        createWeight("A", 100, -1).andExpect(status().isBadRequest());

        // 同 requestId 同参重放首次完整响应，只占一键
        String requestId = UUID.randomUUID().toString();
        Map<String, Object> body = Map.of("requestId", requestId, "weightGrams", 100, "maxLoadGrams", 500);
        postJson("/api/bags/A/weight", body).andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(1));
        postJson("/api/bags/A/weight", body).andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(1));
        assertThat(countRows("bag_weight")).isEqualTo(1);
        // 同键异参 -> 409
        postJson("/api/bags/A/weight", Map.of("requestId", requestId, "weightGrams", 101,
                "maxLoadGrams", 500)).andExpect(status().isConflict());
        // 重复建档（新键）-> 409
        createWeight("A", 100, 500).andExpect(status().isConflict());

        // 修改带 expectedVersion：不符 409，符合则递增
        updateWeight("A", 120, 600, 99).andExpect(status().isConflict());
        updateWeight("A", 120, 600, 1).andExpect(status().isOk())
                .andExpect(jsonPath("$.weightGrams").value(120))
                .andExpect(jsonPath("$.maxLoadGrams").value(600))
                .andExpect(jsonPath("$.version").value(2));
        mockMvc.perform(get("/api/bags/A/weight"))
                .andExpect(jsonPath("$.weightGrams").value(120))
                .andExpect(jsonPath("$.version").value(2));

        // 未建档行李修改/查询 -> 404
        registerBag("B", List.of("LEG1"));
        updateWeight("B", 1, 1, 1).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/bags/B/weight")).andExpect(status().isNotFound());
    }

    @Test
    void weightProfile_updateLoadedBagCausingOverPressureConflict() throws Exception {
        registerLeg("LEG1", "PEK", "SHA");
        registerBag("A", List.of("LEG1"));
        registerBag("B", List.of("LEG1"));
        registerBag("FREE", List.of("LEG1"));
        createWeight("A", 100, 1000).andExpect(status().isCreated());
        createWeight("B", 600, 500).andExpect(status().isCreated());
        createWeight("FREE", 50, 50).andExpect(status().isCreated());
        arrange("LEG1", 1, List.of(item("A", 1, 1), item("B", 1, 2))).andExpect(status().isOk());

        // B 加重到 1500：A 上方 1500 > 1000 -> 409，档案不变
        updateWeight("B", 1500, 500, 1).andExpect(status().isConflict());
        // A 承压降到 500：上方 600 > 500 -> 409
        updateWeight("A", 100, 500, 1).andExpect(status().isConflict());
        mockMvc.perform(get("/api/bags/B/weight"))
                .andExpect(jsonPath("$.weightGrams").value(600))
                .andExpect(jsonPath("$.version").value(1));

        // B 加重到 700：A 上方 700 <= 1000 -> 允许
        updateWeight("B", 700, 500, 1).andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));
        // 未装载行李自由修改
        updateWeight("FREE", 60, 60, 1).andExpect(status().isOk());
    }

    @Test
    void arrange_idempotentReplayConflictAndFailureNotOccupyingKey() throws Exception {
        registerLeg("LEG1", "PEK", "SHA");
        registerBag("A", List.of("LEG1"));
        registerBag("B", List.of("LEG1"));
        createWeight("A", 100, 1000).andExpect(status().isCreated());
        createWeight("B", 200, 0).andExpect(status().isCreated());

        // 同键同参重放：版本只推进一次，响应一致
        String requestId = UUID.randomUUID().toString();
        Map<String, Object> body = Map.of("requestId", requestId, "expectedVersion", 1,
                "items", List.of(item("A", 1, 1), item("B", 1, 2)));
        postJson("/api/legs/LEG1/arrange", body).andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));
        postJson("/api/legs/LEG1/arrange", body).andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));
        mockMvc.perform(get("/api/legs/LEG1/manifest"))
                .andExpect(jsonPath("$.version").value(2));
        Integer logs = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = ?", Integer.class, requestId);
        assertThat(logs).isEqualTo(1);

        // 同键异参 -> 409
        postJson("/api/legs/LEG1/arrange", Map.of("requestId", requestId, "expectedVersion", 1,
                "items", List.of(item("A", 1, 1)))).andExpect(status().isConflict());

        // 失败不占键：先以层号空洞失败（422），再同键正确参数成功
        String retryKey = UUID.randomUUID().toString();
        postJson("/api/legs/LEG1/arrange", Map.of("requestId", retryKey, "expectedVersion", 2,
                "items", List.of(item("A", 1, 2)))).andExpect(status().isUnprocessableEntity());
        postJson("/api/legs/LEG1/arrange", Map.of("requestId", retryKey, "expectedVersion", 2,
                "items", List.of(item("A", 1, 1)))).andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(3));
    }

    @Test
    void concurrentArrangeAndSeal_versionDecidesSingleWinner() throws Exception {
        registerLeg("LEG1", "PEK", "SHA");
        registerBag("A", List.of("LEG1"));
        registerBag("B", List.of("LEG1"));
        createWeight("A", 100, 1000).andExpect(status().isCreated());
        createWeight("B", 200, 0).andExpect(status().isCreated());
        arrange("LEG1", 1, List.of(item("A", 1, 1))).andExpect(status().isOk());

        List<Callable<Object>> tasks = List.of(
                () -> baggageService.arrange("LEG1", new ArrangeRequest(
                        UUID.randomUUID().toString(), 2,
                        List.of(new ArrangeItem("A", 1, 1), new ArrangeItem("B", 1, 2)))),
                () -> baggageService.seal("LEG1",
                        new SealRequest(UUID.randomUUID().toString(), 2)));
        List<Object> results = runConcurrently(tasks);

        long successes = results.stream()
                .filter(r -> r instanceof ArrangeResponse || r instanceof SealResponse).count();
        long conflicts = results.stream()
                .filter(ApiException.class::isInstance)
                .map(ApiException.class::cast)
                .filter(ex -> ex.getStatus().value() == 409).count();
        assertThat(successes).isEqualTo(1);
        assertThat(conflicts).isEqualTo(1);

        Integer version = jdbcTemplate.queryForObject(
                "SELECT version FROM leg WHERE leg_id = 'LEG1'", Integer.class);
        assertThat(version).isEqualTo(3);
        String legStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM leg WHERE leg_id = 'LEG1'", String.class);
        if ("SEALED".equals(legStatus)) {
            // 封舱先成功：层序冻结为旧态（仅 A），禁止新排堆栈
            mockMvc.perform(get("/api/legs/LEG1/stacks"))
                    .andExpect(jsonPath("$.items[*].bagTag", contains("A")));
            arrange("LEG1", 3, List.of(item("A", 1, 1), item("B", 1, 2)))
                    .andExpect(status().isUnprocessableEntity());
        } else {
            // 重排先成功：封舱可随后完成并冻结新层序
            seal("LEG1", 3).andExpect(status().isOk());
            mockMvc.perform(get("/api/legs/LEG1/stacks"))
                    .andExpect(jsonPath("$.items[*].bagTag", contains("A", "B")));
        }
    }

    @Test
    void concurrentSameRequestIdArrange_replaysSingleEffect() throws Exception {
        registerLeg("LEG1", "PEK", "SHA");
        registerBag("A", List.of("LEG1"));
        registerBag("B", List.of("LEG1"));
        createWeight("A", 100, 1000).andExpect(status().isCreated());
        createWeight("B", 200, 0).andExpect(status().isCreated());

        String requestId = UUID.randomUUID().toString();
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            tasks.add(() -> baggageService.arrange("LEG1", new ArrangeRequest(requestId, 1,
                    List.of(new ArrangeItem("A", 1, 1), new ArrangeItem("B", 1, 2)))));
        }
        List<Object> results = runConcurrently(tasks);

        assertThat(results).allSatisfy(result -> {
            assertThat(result).isInstanceOf(ArrangeResponse.class);
            assertThat(((ArrangeResponse) result).version()).isEqualTo(2);
        });
        assertThat(countRows("load_record")).isEqualTo(2);
        Integer logs = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = ?", Integer.class, requestId);
        assertThat(logs).isEqualTo(1);
        Integer version = jdbcTemplate.queryForObject(
                "SELECT version FROM leg WHERE leg_id = 'LEG1'", Integer.class);
        assertThat(version).isEqualTo(2);
    }

    private void registerLeg(String legId, String origin, String destination) {
        baggageService.registerLeg(
                new RegisterLegRequest(UUID.randomUUID().toString(), legId, origin, destination));
    }

    private void registerBag(String bagTag, List<String> legIds) {
        baggageService.registerBag(new RegisterBagRequest(UUID.randomUUID().toString(), bagTag, legIds));
    }

    private ResultActions createWeight(String bagTag, int weightGrams, int maxLoadGrams) throws Exception {
        return postJson("/api/bags/" + bagTag + "/weight", Map.of(
                "requestId", UUID.randomUUID().toString(),
                "weightGrams", weightGrams, "maxLoadGrams", maxLoadGrams));
    }

    private ResultActions updateWeight(String bagTag, int weightGrams, int maxLoadGrams,
                                       int expectedVersion) throws Exception {
        return mockMvc.perform(put("/api/bags/" + bagTag + "/weight")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "requestId", UUID.randomUUID().toString(),
                        "weightGrams", weightGrams, "maxLoadGrams", maxLoadGrams,
                        "expectedVersion", expectedVersion))));
    }

    private ResultActions arrange(String legId, int expectedVersion,
                                  List<Map<String, Object>> items) throws Exception {
        return postJson("/api/legs/" + legId + "/arrange", Map.of(
                "requestId", UUID.randomUUID().toString(),
                "expectedVersion", expectedVersion, "items", items));
    }

    private ResultActions seal(String legId, int expectedVersion) throws Exception {
        return postJson("/api/legs/" + legId + "/seal", Map.of(
                "requestId", UUID.randomUUID().toString(), "expectedVersion", expectedVersion));
    }

    private static Map<String, Object> item(String bagTag, int stackNo, int layer) {
        return Map.of("bagTag", bagTag, "stackNo", stackNo, "layer", layer);
    }

    private Integer countRows(String table) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
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
