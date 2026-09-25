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

import com.example.starter.baggage.BaggageDtos.LoadRequest;
import com.example.starter.baggage.BaggageDtos.RecoverRequest;
import com.example.starter.baggage.BaggageDtos.RegisterBagRequest;
import com.example.starter.baggage.BaggageDtos.RegisterLegRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 短卸差异登记与补到核对测试：覆盖差异到达子集校验、空到达、短卸冻结、补到恢复、
 * 未补到前批量装载整批回滚、补到与后续航段装载的并发一致顺序以及幂等边界。
 * 全部基于 H2 MySQL 兼容模式真实数据库，不使用 mock。
 */
@SpringBootTest
@AutoConfigureMockMvc
class BaggageShortUnloadTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-09-22T03:04:05Z");

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
        jdbcTemplate.update("DELETE FROM container_occupancy");
        jdbcTemplate.update("DELETE FROM cutoff_exception");
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

    @Test
    void differenceArrive_subsetMovesArrivedFreezesMissingAndRecoverCompletes() throws Exception {
        baggageService.setClock(() -> FIXED_NOW);
        registerLeg("LEG1", "PEK", "SHA");
        registerLeg("LEG2", "SHA", "CAN");
        registerBag("BAG1", List.of("LEG1"));
        registerBag("BAG2", List.of("LEG1", "LEG2"));
        load("LEG1", 1, List.of("BAG1", "BAG2"));
        seal("LEG1", 2);

        // 差异到达：仅 BAG1 实际到达，BAG2 短卸
        diffArrive("LEG1", 3, List.of("BAG1")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ARRIVED"))
                .andExpect(jsonPath("$.version").value(4))
                .andExpect(jsonPath("$.arrived", contains("BAG1")))
                .andExpect(jsonPath("$.shortUnloaded", contains("BAG2")));

        // 实际到达行李推进并交付
        mockMvc.perform(get("/api/bags/BAG1/trace"))
                .andExpect(jsonPath("$.currentLocation").value("SHA"))
                .andExpect(jsonPath("$.nextLegIndex").value(1))
                .andExpect(jsonPath("$.status").value("DELIVERED"))
                .andExpect(jsonPath("$.loadedLegId").doesNotExist());

        // 短卸行李冻结：位置不变、索引不推进、记录缺失航段/应到站/UTC 登记时刻
        mockMvc.perform(get("/api/bags/BAG2/trace"))
                .andExpect(jsonPath("$.currentLocation").value("PEK"))
                .andExpect(jsonPath("$.nextLegIndex").value(0))
                .andExpect(jsonPath("$.status").value("SHORT_UNLOADED"))
                .andExpect(jsonPath("$.shortLegId").value("LEG1"))
                .andExpect(jsonPath("$.shortDestination").value("SHA"))
                .andExpect(jsonPath("$.shortRegisteredAt").value(FIXED_NOW.toString()))
                .andExpect(jsonPath("$.loadedLegId").doesNotExist())
                .andExpect(jsonPath("$.events[*].eventType",
                        contains("REGISTERED", "LOADED", "SHORT_UNLOADED")));

        // 航段只读差异快照
        mockMvc.perform(get("/api/legs/LEG1/difference"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ARRIVED"))
                .andExpect(jsonPath("$.arrivalType").value("DIFF"))
                .andExpect(jsonPath("$.manifest", contains("BAG1", "BAG2")))
                .andExpect(jsonPath("$.actual", contains("BAG1")));

        // 未补到清单
        mockMvc.perform(get("/api/bags/short-unloaded"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shortUnloaded", hasSize(1)))
                .andExpect(jsonPath("$.shortUnloaded[0].bagTag").value("BAG2"))
                .andExpect(jsonPath("$.shortUnloaded[0].missingLegId").value("LEG1"))
                .andExpect(jsonPath("$.shortUnloaded[0].expectedStation").value("SHA"))
                .andExpect(jsonPath("$.shortUnloaded[0].registeredAt").value(FIXED_NOW.toString()));

        // 到达后 load_record 已清空，短卸行李不残留装载记录
        Integer loadRecords = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM load_record", Integer.class);
        assertThat(loadRecords).isZero();

        // 未补到前装载后续航段失败
        load("LEG2", 1, List.of("BAG2")).andExpect(status().isUnprocessableEntity());

        // 补到后恢复行程
        recover("BAG2", "LEG1", "SHA").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RECOVERED"))
                .andExpect(jsonPath("$.currentLocation").value("SHA"))
                .andExpect(jsonPath("$.nextLegIndex").value(1))
                .andExpect(jsonPath("$.recoveredLegId").value("LEG1"));

        // 补到后从未补到清单移除，短卸字段清空
        mockMvc.perform(get("/api/bags/short-unloaded"))
                .andExpect(jsonPath("$.shortUnloaded", hasSize(0)));
        mockMvc.perform(get("/api/bags/BAG2/trace"))
                .andExpect(jsonPath("$.shortLegId").doesNotExist())
                .andExpect(jsonPath("$.events[*].eventType",
                        contains("REGISTERED", "LOADED", "SHORT_UNLOADED", "RECOVERED")));

        // 补到先提交后才可参与后续航段装载，最终交付
        load("LEG2", 1, List.of("BAG2")).andExpect(status().isOk());
        seal("LEG2", 2);
        arriveExact("LEG2", List.of("BAG2")).andExpect(status().isOk());
        mockMvc.perform(get("/api/bags/BAG2/trace"))
                .andExpect(jsonPath("$.currentLocation").value("CAN"))
                .andExpect(jsonPath("$.status").value("DELIVERED"))
                .andExpect(jsonPath("$.events[*].eventType",
                        contains("REGISTERED", "LOADED", "SHORT_UNLOADED", "RECOVERED",
                                "LOADED", "UNLOADED", "DELIVERED")));
    }

    @Test
    void differenceArrive_emptySetIsLegalAndShortsAllBags() throws Exception {
        registerLeg("LEG1", "PEK", "SHA");
        registerBag("BAG1", List.of("LEG1"));
        registerBag("BAG2", List.of("LEG1"));
        load("LEG1", 1, List.of("BAG1", "BAG2"));
        seal("LEG1", 2);

        diffArrive("LEG1", 3, List.of()).andExpect(status().isOk())
                .andExpect(jsonPath("$.arrived", hasSize(0)))
                .andExpect(jsonPath("$.shortUnloaded", contains("BAG1", "BAG2")));

        mockMvc.perform(get("/api/legs/LEG1/difference"))
                .andExpect(jsonPath("$.arrivalType").value("DIFF"))
                .andExpect(jsonPath("$.actual", hasSize(0)));
        mockMvc.perform(get("/api/bags/BAG1/trace"))
                .andExpect(jsonPath("$.status").value("SHORT_UNLOADED"))
                .andExpect(jsonPath("$.nextLegIndex").value(0))
                .andExpect(jsonPath("$.currentLocation").value("PEK"));
    }

    @Test
    void differenceArrive_outOfManifestOrDuplicateRejectsWholeRequest() throws Exception {
        registerLeg("LEG1", "PEK", "SHA");
        registerLeg("LEG2", "SHA", "CAN");
        registerBag("BAG1", List.of("LEG1", "LEG2"));
        registerBag("BAG2", List.of("LEG1"));
        load("LEG1", 1, List.of("BAG1", "BAG2"));
        seal("LEG1", 2);

        // 清单外袋号 -> 422，航段与行李均不变化
        diffArrive("LEG1", 3, List.of("BAG1", "BAG_UNKNOWN"))
                .andExpect(status().isUnprocessableEntity());
        // 集合内重复 -> 422
        diffArrive("LEG1", 3, List.of("BAG1", "BAG1"))
                .andExpect(status().isUnprocessableEntity());
        // 空集之外的非法：多出来的袋号即便与缺失无关也整次拒绝
        diffArrive("LEG1", 3, List.of("BAG2", "BAG_UNKNOWN"))
                .andExpect(status().isUnprocessableEntity());

        mockMvc.perform(get("/api/legs/LEG1/manifest"))
                .andExpect(jsonPath("$.status").value("SEALED"))
                .andExpect(jsonPath("$.version").value(3));
        mockMvc.perform(get("/api/bags/BAG1/trace"))
                .andExpect(jsonPath("$.status").value("IN_TRANSIT"))
                .andExpect(jsonPath("$.loadedLegId").value("LEG1"))
                .andExpect(jsonPath("$.nextLegIndex").value(0));
        mockMvc.perform(get("/api/legs/LEG1/difference"))
                .andExpect(jsonPath("$.arrivalType").doesNotExist())
                .andExpect(jsonPath("$.actual").doesNotExist());
        Integer shortCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bag WHERE status = 'SHORT_UNLOADED'", Integer.class);
        assertThat(shortCount).isZero();
    }

    @Test
    void differenceArrive_rejectsWrongVersionAndNonSealed() throws Exception {
        registerLeg("LEG1", "PEK", "SHA");
        registerBag("BAG1", List.of("LEG1"));

        // OPEN 航段禁止差异到达
        diffArrive("LEG1", 1, List.of()).andExpect(status().isUnprocessableEntity());
        load("LEG1", 1, List.of("BAG1"));
        seal("LEG1", 2);
        // 版本冲突 -> 409
        diffArrive("LEG1", 2, List.of("BAG1")).andExpect(status().isConflict());
        // 航段不存在 -> 404
        diffArrive("LEG_MISSING", 1, List.of()).andExpect(status().isNotFound());
    }

    @Test
    void exactArrive_stillRequiresExactMatchAndArrivalKindsAreMutuallyExclusive() throws Exception {
        registerLeg("LEG1", "PEK", "SHA");
        registerBag("BAG1", List.of("LEG1"));
        registerBag("BAG2", List.of("LEG1"));
        load("LEG1", 1, List.of("BAG1", "BAG2"));
        seal("LEG1", 2);

        // 原精确到达入口行为保持：子集不被接受
        arriveExact("LEG1", List.of("BAG1")).andExpect(status().isUnprocessableEntity());
        // 差异到达先行
        diffArrive("LEG1", 3, List.of("BAG1")).andExpect(status().isOk());
        // 之后不能再执行另一种到达确认（精确）
        arriveExact("LEG1", List.of("BAG1", "BAG2")).andExpect(status().isUnprocessableEntity());
        // 也不能重复差异到达
        diffArrive("LEG1", 4, List.of("BAG1")).andExpect(status().isUnprocessableEntity());

        // 反向：精确到达后差异到达也被拒绝
        registerLeg("LEG2", "PEK", "SHA");
        registerBag("BAG3", List.of("LEG2"));
        load("LEG2", 1, List.of("BAG3"));
        seal("LEG2", 2);
        arriveExact("LEG2", List.of("BAG3")).andExpect(status().isOk());
        diffArrive("LEG2", 4, List.of()).andExpect(status().isUnprocessableEntity());
        // 精确到达快照：actual 等于完整清单
        mockMvc.perform(get("/api/legs/LEG2/difference"))
                .andExpect(jsonPath("$.arrivalType").value("EXACT"))
                .andExpect(jsonPath("$.actual", contains("BAG3")));
    }

    @Test
    void recover_singleLegShortBagBecomesDelivered() throws Exception {
        registerLeg("LEG1", "PEK", "SHA");
        registerBag("BAG1", List.of("LEG1"));
        load("LEG1", 1, List.of("BAG1"));
        seal("LEG1", 2);
        diffArrive("LEG1", 3, List.of()).andExpect(status().isOk());

        recover("BAG1", "LEG1", "SHA").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DELIVERED"))
                .andExpect(jsonPath("$.nextLegIndex").value(1));
        mockMvc.perform(get("/api/bags/BAG1/trace"))
                .andExpect(jsonPath("$.status").value("DELIVERED"))
                .andExpect(jsonPath("$.events[*].eventType",
                        contains("REGISTERED", "LOADED", "SHORT_UNLOADED", "RECOVERED", "DELIVERED")));
    }

    @Test
    void recover_rejectsWrongStationWrongLegUnknownAndDoubleRecover() throws Exception {
        registerLeg("LEG1", "PEK", "SHA");
        registerLeg("LEG2", "SHA", "CAN");
        registerBag("BAG1", List.of("LEG1", "LEG2"));
        load("LEG1", 1, List.of("BAG1"));
        seal("LEG1", 2);
        diffArrive("LEG1", 3, List.of()).andExpect(status().isOk());

        // 实际站与缺失航段到达站不一致 -> 422
        recover("BAG1", "LEG1", "CAN").andExpect(status().isUnprocessableEntity());
        // 缺失航段不匹配 -> 409（改航段）
        recover("BAG1", "LEG2", "SHA").andExpect(status().isConflict());
        // 行李不存在 -> 404
        recover("BAG_MISSING", "LEG1", "SHA").andExpect(status().isNotFound());

        // 非短卸行李不可补到
        registerBag("BAG2", List.of("LEG1"));
        recover("BAG2", "LEG1", "SHA").andExpect(status().isUnprocessableEntity());

        // 合法补到成功
        recover("BAG1", "LEG1", "SHA").andExpect(status().isOk());
        // 已补到不得再次推进 -> 409
        recover("BAG1", "LEG1", "SHA").andExpect(status().isConflict());
    }

    @Test
    void load_batchFullyRollsBackWhenOneBagIsShort() throws Exception {
        registerLeg("LEG1", "PEK", "SHA");
        registerLeg("LEG2", "SHA", "CAN");
        // BAG_SHORT 在 LEG1 短卸后滞留 PEK；BAG_OK 只有 LEG2 行程，登记即位于始发站 SHA
        registerBag("BAG_SHORT", List.of("LEG1", "LEG2"));
        registerBag("BAG_OK", List.of("LEG2"));

        load("LEG1", 1, List.of("BAG_SHORT"));
        seal("LEG1", 2);
        diffArrive("LEG1", 3, List.of()).andExpect(status().isOk());

        // 整批包含一个短卸行李：422 且合法行李也不移动
        load("LEG2", 1, List.of("BAG_OK", "BAG_SHORT"))
                .andExpect(status().isUnprocessableEntity());

        mockMvc.perform(get("/api/bags/BAG_OK/trace"))
                .andExpect(jsonPath("$.loadedLegId").doesNotExist())
                .andExpect(jsonPath("$.currentLocation").value("SHA"));
        mockMvc.perform(get("/api/legs/LEG2/manifest"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.status").value("OPEN"));
        Integer records = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM load_record", Integer.class);
        assertThat(records).isZero();

        // 补到后整批可成功
        recover("BAG_SHORT", "LEG1", "SHA").andExpect(status().isOk());
        load("LEG2", 1, List.of("BAG_OK", "BAG_SHORT")).andExpect(status().isOk())
                .andExpect(jsonPath("$.loaded", contains("BAG_OK", "BAG_SHORT")));
    }

    @Test
    void recover_idempotentReplayAndConflictAndFailureNotOccupyingKey() throws Exception {
        registerLeg("LEG1", "PEK", "SHA");
        registerBag("BAG1", List.of("LEG1"));
        load("LEG1", 1, List.of("BAG1"));
        seal("LEG1", 2);
        diffArrive("LEG1", 3, List.of()).andExpect(status().isOk());

        // 同参重放原结果
        String requestId = UUID.randomUUID().toString();
        postJson("/api/bags/recover", Map.of("requestId", requestId,
                "bagTag", "BAG1", "missingLegId", "LEG1", "actualStation", "SHA"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DELIVERED"));
        postJson("/api/bags/recover", Map.of("requestId", requestId,
                "bagTag", "BAG1", "missingLegId", "LEG1", "actualStation", "SHA"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DELIVERED"));
        // 事件只追加一次
        Integer eventCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bag_event WHERE bag_tag = 'BAG1' AND event_type = 'RECOVERED'",
                Integer.class);
        assertThat(eventCount).isEqualTo(1);
        Integer logs = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = ?", Integer.class, requestId);
        assertThat(logs).isEqualTo(1);

        // 同 requestId 改站 -> 409
        postJson("/api/bags/recover", Map.of("requestId", requestId,
                "bagTag", "BAG1", "missingLegId", "LEG1", "actualStation", "CAN"))
                .andExpect(status().isConflict());

        // 失败不占键：短卸行李先以错误站失败（422），再用同键正确参数成功
        registerLeg("LEG3", "PEK", "SHA");
        registerBag("BAG3", List.of("LEG3"));
        load("LEG3", 1, List.of("BAG3"));
        seal("LEG3", 2);
        diffArrive("LEG3", 3, List.of()).andExpect(status().isOk());

        String retryKey = UUID.randomUUID().toString();
        postJson("/api/bags/recover", Map.of("requestId", retryKey,
                "bagTag", "BAG3", "missingLegId", "LEG3", "actualStation", "CAN"))
                .andExpect(status().isUnprocessableEntity());
        postJson("/api/bags/recover", Map.of("requestId", retryKey,
                "bagTag", "BAG3", "missingLegId", "LEG3", "actualStation", "SHA"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DELIVERED"));
    }

    @Test
    void concurrentRecoverAndLoad_consistentOrdering() throws Exception {
        registerLeg("LEG1", "PEK", "SHA");
        registerLeg("LEG2", "SHA", "CAN");
        registerBag("BAG_SHORT", List.of("LEG1", "LEG2"));
        registerBag("BAG_OK", List.of("LEG2"));
        load("LEG1", 1, List.of("BAG_SHORT"));
        seal("LEG1", 2);
        diffArrive("LEG1", 3, List.of()).andExpect(status().isOk());

        // 补到与后续航段批量装载真实并发：顺序只能是“装载失败整批不移动”或“补到先提交后装载成功”
        List<Callable<Object>> tasks = List.of(
                () -> baggageService.recover(new RecoverRequest(
                        UUID.randomUUID().toString(), "BAG_SHORT", "LEG1", "SHA")),
                () -> baggageService.load("LEG2",
                        new LoadRequest(UUID.randomUUID().toString(), 1,
                                "tester", "ULD-LEG2", List.of("BAG_OK", "BAG_SHORT"))));
        List<Object> results = runConcurrently(tasks);

        boolean loadSucceeded = results.stream()
                .anyMatch(r -> r instanceof com.example.starter.baggage.BaggageDtos.LoadResponse);
        boolean loadFailed = results.stream()
                .filter(ApiException.class::isInstance)
                .map(ApiException.class::cast)
                .anyMatch(ex -> ex.getStatus().value() == 422);
        assertThat(loadSucceeded).isNotEqualTo(loadFailed);

        Integer okLoaded = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM load_record WHERE bag_tag = 'BAG_OK'", Integer.class);
        if (loadSucceeded) {
            // 补到先提交：整批两件都进入清单
            assertThat(okLoaded).isEqualTo(1);
            Integer shortLoaded = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM load_record WHERE bag_tag = 'BAG_SHORT'", Integer.class);
            assertThat(shortLoaded).isEqualTo(1);
            String shortStatus = jdbcTemplate.queryForObject(
                    "SELECT status FROM bag WHERE bag_tag = 'BAG_SHORT'", String.class);
            assertThat(shortStatus).isEqualTo("RECOVERED");
        } else {
            // 装载先尝试且失败：整批不移动，补到完成后再装载必然成功
            assertThat(okLoaded).isZero();
            recover("BAG_SHORT", "LEG1", "SHA").andExpect(status().isOk());
            load("LEG2", 1, List.of("BAG_OK", "BAG_SHORT")).andExpect(status().isOk());
        }
    }

    private void registerLeg(String legId, String origin, String destination) {
        baggageService.registerLeg(
                new RegisterLegRequest(UUID.randomUUID().toString(), legId, origin, destination,
                        Instant.parse("2099-01-01T00:00:00Z")));
    }

    private void registerBag(String bagTag, List<String> legIds) {
        baggageService.registerBag(new RegisterBagRequest(UUID.randomUUID().toString(), bagTag, legIds));
    }

    private ResultActions load(String legId, int expectedVersion, List<String> bagTags) throws Exception {
        return postJson("/api/legs/" + legId + "/load", Map.of(
                "requestId", UUID.randomUUID().toString(),
                "expectedVersion", expectedVersion,
                "operator", "tester", "containerId", "ULD-" + legId,
                "bagTags", bagTags));
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
