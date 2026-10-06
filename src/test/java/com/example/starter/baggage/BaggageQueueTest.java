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

import com.example.starter.baggage.BaggageDtos.DequeueRequest;
import com.example.starter.baggage.BaggageDtos.DequeueResponse;
import com.example.starter.baggage.BaggageDtos.EnqueueRequest;
import com.example.starter.baggage.BaggageDtos.RegisterBagRequest;
import com.example.starter.baggage.BaggageDtos.RegisterLegRequest;
import com.example.starter.baggage.BaggageDtos.WithdrawRequest;
import com.example.starter.baggage.BaggageDtos.WithdrawResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 单道输送线待装队列测试：覆盖合成队列 A/B/C 主流程（出队 2 只装 A、B，旧版本 409，
 * 撤 C 后可封舱，重放首次出队不再移动行李）、入队/出队/撤队拒绝分支、
 * 直接装载 409、封舱 422、序号历史保留、幂等边界与并发一成一拒。
 * 全部基于 H2 MySQL 兼容模式真实数据库，不使用 mock。
 */
@SpringBootTest
@AutoConfigureMockMvc
class BaggageQueueTest {

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
        jdbcTemplate.update("DELETE FROM load_queue_entry");
        jdbcTemplate.update("DELETE FROM load_record");
        jdbcTemplate.update("DELETE FROM bag_itinerary");
        jdbcTemplate.update("DELETE FROM bag");
        jdbcTemplate.update("DELETE FROM leg");
        jdbcTemplate.update("DELETE FROM request_log");
        executor = Executors.newFixedThreadPool(4);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        executor.shutdownNow();
        assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void syntheticQueue_dequeueFrontTwoOldVersion409WithdrawCThenSealAndReplay() throws Exception {
        registerLeg("LEG1", "PEK", "SHA").andExpect(status().isCreated());
        registerBag("A", List.of("LEG1")).andExpect(status().isCreated());
        registerBag("B", List.of("LEG1")).andExpect(status().isCreated());
        registerBag("C", List.of("LEG1")).andExpect(status().isCreated());

        // 入队 A、B、C：按请求顺序分配序号 1、2、3，队列版本 1 -> 2
        enqueue("LEG1", "ops-1", List.of("A", "B", "C")).andExpect(status().isOk())
                .andExpect(jsonPath("$.queueVersion").value(2))
                .andExpect(jsonPath("$.entries[*].seq", contains(1, 2, 3)))
                .andExpect(jsonPath("$.entries[*].bagTag", contains("A", "B", "C")))
                .andExpect(jsonPath("$.entries[*].status", contains("WAITING", "WAITING", "WAITING")));

        // 出队 2：只装最前面的 A、B，冻结序号与完整装载响应
        String dequeueRequestId = UUID.randomUUID().toString();
        Map<String, Object> dequeueBody = Map.of(
                "requestId", dequeueRequestId, "operator", "ops-2",
                "expectedQueueVersion", 2, "count", 2);
        postJson("/api/legs/LEG1/queue/dequeue", dequeueBody).andExpect(status().isOk())
                .andExpect(jsonPath("$.queueVersion").value(3))
                .andExpect(jsonPath("$.dequeued[*].seq", contains(1, 2)))
                .andExpect(jsonPath("$.dequeued[*].bagTag", contains("A", "B")))
                .andExpect(jsonPath("$.dequeued[*].status", contains("DEQUEUED", "DEQUEUED")))
                .andExpect(jsonPath("$.load.version").value(2))
                .andExpect(jsonPath("$.load.loaded", contains("A", "B")));

        mockMvc.perform(get("/api/bags/A/trace"))
                .andExpect(jsonPath("$.loadedLegId").value("LEG1"));
        mockMvc.perform(get("/api/bags/B/trace"))
                .andExpect(jsonPath("$.loadedLegId").value("LEG1"));
        mockMvc.perform(get("/api/bags/C/trace"))
                .andExpect(jsonPath("$.loadedLegId").doesNotExist());

        // 旧队列版本再出队 -> 409
        dequeue("LEG1", "ops-2", 2, 1).andExpect(status().isConflict());

        // 撤 C：序号 3 保留为 WITHDRAWN，队列版本 3 -> 4
        withdraw("LEG1", "ops-3", "C").andExpect(status().isOk())
                .andExpect(jsonPath("$.queueVersion").value(4))
                .andExpect(jsonPath("$.withdrawn.seq").value(3))
                .andExpect(jsonPath("$.withdrawn.bagTag").value("C"))
                .andExpect(jsonPath("$.withdrawn.status").value("WITHDRAWN"));

        // 无有效成员后可封舱，清单为 A、B
        seal("LEG1", 2).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SEALED"))
                .andExpect(jsonPath("$.manifest", contains("A", "B")));

        // 重放首次出队：返回首次完整结果，不再次移动行李
        postJson("/api/legs/LEG1/queue/dequeue", dequeueBody).andExpect(status().isOk())
                .andExpect(jsonPath("$.queueVersion").value(3))
                .andExpect(jsonPath("$.dequeued[*].bagTag", contains("A", "B")))
                .andExpect(jsonPath("$.load.version").value(2));
        Integer loadRecords = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM load_record WHERE leg_id = 'LEG1'", Integer.class);
        assertThat(loadRecords).isEqualTo(2);
        mockMvc.perform(get("/api/bags/C/trace"))
                .andExpect(jsonPath("$.loadedLegId").doesNotExist());
        Integer legVersion = jdbcTemplate.queryForObject(
                "SELECT version FROM leg WHERE leg_id = 'LEG1'", Integer.class);
        assertThat(legVersion).isEqualTo(3);

        // 队列视图保留全部历史，撤队不改已出队序号
        mockMvc.perform(get("/api/legs/LEG1/queue"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.queueVersion").value(4))
                .andExpect(jsonPath("$.entries[*].seq", contains(1, 2, 3)))
                .andExpect(jsonPath("$.entries[*].bagTag", contains("A", "B", "C")))
                .andExpect(jsonPath("$.entries[*].status",
                        contains("DEQUEUED", "DEQUEUED", "WITHDRAWN")));
    }

    @Test
    void enqueue_validatesBatchAtomically() throws Exception {
        registerLeg("LEG1", "PEK", "SHA").andExpect(status().isCreated());
        registerLeg("LEG2", "SHA", "CAN").andExpect(status().isCreated());
        registerBag("A", List.of("LEG1")).andExpect(status().isCreated());
        registerBag("B", List.of("LEG2")).andExpect(status().isCreated());

        // B 的待乘航段是 LEG2，整批 422 且 A 也不入队
        enqueue("LEG1", "ops", List.of("A", "B")).andExpect(status().isUnprocessableEntity());
        mockMvc.perform(get("/api/legs/LEG1/queue"))
                .andExpect(jsonPath("$.queueVersion").value(1))
                .andExpect(jsonPath("$.entries", hasSize(0)));

        // 请求内重复 -> 422；数量越界 -> 400；缺少操作者 -> 400；航段不存在 -> 404
        enqueue("LEG1", "ops", List.of("A", "A")).andExpect(status().isUnprocessableEntity());
        enqueue("LEG1", "ops", List.of()).andExpect(status().isBadRequest());
        enqueue("LEG1", "ops", List.of("A", "A", "A", "A", "A", "A"))
                .andExpect(status().isBadRequest());
        postJson("/api/legs/LEG1/queue/enqueue", Map.of(
                "requestId", UUID.randomUUID().toString(), "bagTags", List.of("A")))
                .andExpect(status().isBadRequest());
        enqueue("LEG_MISSING", "ops", List.of("A")).andExpect(status().isNotFound());

        // 合法入队后同件行李不得再占有效位置
        enqueue("LEG1", "ops", List.of("A")).andExpect(status().isOk());
        enqueue("LEG1", "ops", List.of("A")).andExpect(status().isUnprocessableEntity());
        // 行李不存在 -> 422
        enqueue("LEG1", "ops", List.of("BAG_MISSING")).andExpect(status().isUnprocessableEntity());
    }

    @Test
    void enqueue_rejectsBeyondTwentyWaiting() throws Exception {
        registerLeg("LEG1", "PEK", "SHA").andExpect(status().isCreated());
        for (int i = 1; i <= 21; i++) {
            registerBag(String.format("BAG%02d", i), List.of("LEG1")).andExpect(status().isCreated());
        }
        // 分四批入队 20 件，队列达到上限
        for (int batch = 0; batch < 4; batch++) {
            List<String> tags = new ArrayList<>();
            for (int i = 1; i <= 5; i++) {
                tags.add(String.format("BAG%02d", batch * 5 + i));
            }
            enqueue("LEG1", "ops", tags).andExpect(status().isOk());
        }
        mockMvc.perform(get("/api/legs/LEG1/queue"))
                .andExpect(jsonPath("$.queueVersion").value(5))
                .andExpect(jsonPath("$.entries", hasSize(20)));
        // 第 21 件超出容量 -> 422，队列不变
        enqueue("LEG1", "ops", List.of("BAG21")).andExpect(status().isUnprocessableEntity());
        mockMvc.perform(get("/api/legs/LEG1/queue"))
                .andExpect(jsonPath("$.queueVersion").value(5))
                .andExpect(jsonPath("$.entries", hasSize(20)));
    }

    @Test
    void directLoad_rejectedOnceQueueEnabled() throws Exception {
        registerLeg("LEG1", "PEK", "SHA").andExpect(status().isCreated());
        registerBag("A", List.of("LEG1")).andExpect(status().isCreated());
        // 未启用队列时直接装载正常
        load("LEG1", 1, List.of("A")).andExpect(status().isOk());

        registerLeg("LEG2", "PEK", "SHA").andExpect(status().isCreated());
        registerBag("B", List.of("LEG2")).andExpect(status().isCreated());
        enqueue("LEG2", "ops", List.of("B")).andExpect(status().isOk());
        // 启用队列后普通直接装载 409，禁止绕过顺序
        load("LEG2", 1, List.of("B")).andExpect(status().isConflict());
        // 出队清空队列后仍禁止直接装载
        dequeue("LEG2", "ops", 2, 1).andExpect(status().isOk());
        registerBag("C", List.of("LEG2")).andExpect(status().isCreated());
        load("LEG2", 2, List.of("C")).andExpect(status().isConflict());
    }

    @Test
    void seal_rejectedWhileWaitingMembers() throws Exception {
        registerLeg("LEG1", "PEK", "SHA").andExpect(status().isCreated());
        registerBag("A", List.of("LEG1")).andExpect(status().isCreated());
        registerBag("B", List.of("LEG1")).andExpect(status().isCreated());
        enqueue("LEG1", "ops", List.of("A", "B")).andExpect(status().isOk());

        // 存在有效未出队成员 -> 封舱 422
        seal("LEG1", 1).andExpect(status().isUnprocessableEntity());
        // 全部出队后可封舱
        dequeue("LEG1", "ops", 2, 2).andExpect(status().isOk());
        seal("LEG1", 2).andExpect(status().isOk())
                .andExpect(jsonPath("$.manifest", contains("A", "B")));
    }

    @Test
    void withdraw_rulesAndSeqHistoryPreserved() throws Exception {
        registerLeg("LEG1", "PEK", "SHA").andExpect(status().isCreated());
        registerBag("A", List.of("LEG1")).andExpect(status().isCreated());
        registerBag("B", List.of("LEG1")).andExpect(status().isCreated());
        registerBag("C", List.of("LEG1")).andExpect(status().isCreated());

        // 未入队 -> 422；行李不存在 -> 404
        withdraw("LEG1", "ops", "A").andExpect(status().isUnprocessableEntity());
        withdraw("LEG1", "ops", "BAG_MISSING").andExpect(status().isNotFound());

        enqueue("LEG1", "ops", List.of("A", "B")).andExpect(status().isOk());
        dequeue("LEG1", "ops", 2, 1).andExpect(status().isOk());
        // 已出队不可撤 -> 409
        withdraw("LEG1", "ops", "A").andExpect(status().isConflict());
        // 撤 B 成功，序号 2 保留历史
        withdraw("LEG1", "ops", "B").andExpect(status().isOk())
                .andExpect(jsonPath("$.withdrawn.seq").value(2));
        // 撤队后同件可重新入队，序号继续递增不重用
        enqueue("LEG1", "ops", List.of("B", "C")).andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[*].seq", contains(3, 4)))
                .andExpect(jsonPath("$.entries[*].bagTag", contains("B", "C")));

        mockMvc.perform(get("/api/legs/LEG1/queue"))
                .andExpect(jsonPath("$.entries[*].seq", contains(1, 2, 3, 4)))
                .andExpect(jsonPath("$.entries[*].status",
                        contains("DEQUEUED", "WITHDRAWN", "WAITING", "WAITING")));
    }

    @Test
    void dequeue_validatesCountVersionAndFrontState() throws Exception {
        registerLeg("LEG1", "PEK", "SHA").andExpect(status().isCreated());
        registerBag("A", List.of("LEG1")).andExpect(status().isCreated());
        registerBag("B", List.of("LEG1")).andExpect(status().isCreated());

        // 数量必须为正整数且最多 5 -> 400
        dequeue("LEG1", "ops", 1, 0).andExpect(status().isBadRequest());
        dequeue("LEG1", "ops", 1, 6).andExpect(status().isBadRequest());
        // 航段不存在 -> 404
        dequeue("LEG_MISSING", "ops", 1, 1).andExpect(status().isNotFound());

        enqueue("LEG1", "ops", List.of("A", "B")).andExpect(status().isOk());
        // 有效成员不足 -> 422；版本不符 -> 409
        dequeue("LEG1", "ops", 2, 3).andExpect(status().isUnprocessableEntity());
        dequeue("LEG1", "ops", 99, 1).andExpect(status().isConflict());

        // 首件状态不合规（模拟短卸未补到）：整批 422，不跳过也不装后件
        jdbcTemplate.update("UPDATE bag SET status = 'SHORT_UNLOADED' WHERE bag_tag = 'A'");
        dequeue("LEG1", "ops", 2, 2).andExpect(status().isUnprocessableEntity());
        Integer loadRecords = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM load_record", Integer.class);
        assertThat(loadRecords).isZero();
        mockMvc.perform(get("/api/bags/B/trace"))
                .andExpect(jsonPath("$.loadedLegId").doesNotExist());
        mockMvc.perform(get("/api/legs/LEG1/queue"))
                .andExpect(jsonPath("$.queueVersion").value(2))
                .andExpect(jsonPath("$.entries[*].status", contains("WAITING", "WAITING")));
    }

    @Test
    void queueIdempotency_replayConflictAndFailureNotOccupyingKey() throws Exception {
        registerLeg("LEG1", "PEK", "SHA").andExpect(status().isCreated());
        registerBag("A", List.of("LEG1")).andExpect(status().isCreated());
        registerBag("B", List.of("LEG1")).andExpect(status().isCreated());

        // 入队同键同参重放：返回首次结果，不产生重复成员，版本不再推进
        String enqueueRequestId = UUID.randomUUID().toString();
        Map<String, Object> enqueueBody = Map.of(
                "requestId", enqueueRequestId, "operator", "ops", "bagTags", List.of("A", "B"));
        postJson("/api/legs/LEG1/queue/enqueue", enqueueBody).andExpect(status().isOk())
                .andExpect(jsonPath("$.queueVersion").value(2));
        postJson("/api/legs/LEG1/queue/enqueue", enqueueBody).andExpect(status().isOk())
                .andExpect(jsonPath("$.queueVersion").value(2))
                .andExpect(jsonPath("$.entries[*].seq", contains(1, 2)));
        mockMvc.perform(get("/api/legs/LEG1/queue"))
                .andExpect(jsonPath("$.queueVersion").value(2))
                .andExpect(jsonPath("$.entries", hasSize(2)));
        // 同键改参 -> 409
        postJson("/api/legs/LEG1/queue/enqueue", Map.of(
                "requestId", enqueueRequestId, "operator", "ops", "bagTags", List.of("A")))
                .andExpect(status().isConflict());

        // 出队失败不占键：先以错误版本 409，再同键正确参数成功
        String dequeueRequestId = UUID.randomUUID().toString();
        postJson("/api/legs/LEG1/queue/dequeue", Map.of(
                "requestId", dequeueRequestId, "operator", "ops",
                "expectedQueueVersion", 99, "count", 1)).andExpect(status().isConflict());
        postJson("/api/legs/LEG1/queue/dequeue", Map.of(
                "requestId", dequeueRequestId, "operator", "ops",
                "expectedQueueVersion", 2, "count", 1)).andExpect(status().isOk())
                .andExpect(jsonPath("$.dequeued[*].bagTag", contains("A")));

        // 撤队同键重放：版本只推进一次
        String withdrawRequestId = UUID.randomUUID().toString();
        Map<String, Object> withdrawBody = Map.of(
                "requestId", withdrawRequestId, "operator", "ops", "bagTag", "B");
        postJson("/api/legs/LEG1/queue/withdraw", withdrawBody).andExpect(status().isOk())
                .andExpect(jsonPath("$.queueVersion").value(4));
        postJson("/api/legs/LEG1/queue/withdraw", withdrawBody).andExpect(status().isOk())
                .andExpect(jsonPath("$.queueVersion").value(4));
        Integer queueVersion = jdbcTemplate.queryForObject(
                "SELECT queue_version FROM leg WHERE leg_id = 'LEG1'", Integer.class);
        assertThat(queueVersion).isEqualTo(4);
        Integer logs = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = ?",
                Integer.class, withdrawRequestId);
        assertThat(logs).isEqualTo(1);
    }

    @Test
    void concurrentDequeueAndWithdraw_singleWinner() throws Exception {
        baggageService.registerLeg(
                new RegisterLegRequest(UUID.randomUUID().toString(), "LEG1", "PEK", "SHA"));
        baggageService.registerBag(new RegisterBagRequest(UUID.randomUUID().toString(), "A", List.of("LEG1")));
        baggageService.registerBag(new RegisterBagRequest(UUID.randomUUID().toString(), "B", List.of("LEG1")));
        baggageService.enqueue("LEG1",
                new EnqueueRequest(UUID.randomUUID().toString(), "ops", List.of("A", "B")));

        // 出队首件与撤队首件真实并发：共享航段一致视图，版本竞争一成一拒
        List<Callable<Object>> tasks = List.of(
                () -> baggageService.dequeue("LEG1", new DequeueRequest(
                        UUID.randomUUID().toString(), "ops", 2, 1)),
                () -> baggageService.withdraw("LEG1", new WithdrawRequest(
                        UUID.randomUUID().toString(), "ops", "A")));
        List<Object> results = runConcurrently(tasks);

        long successes = results.stream()
                .filter(r -> r instanceof DequeueResponse || r instanceof WithdrawResponse)
                .count();
        long conflicts = results.stream()
                .filter(ApiException.class::isInstance)
                .map(ApiException.class::cast)
                .filter(ex -> ex.getStatus().value() == 409)
                .count();
        assertThat(successes).isEqualTo(1);
        assertThat(conflicts).isEqualTo(1);

        Integer queueVersion = jdbcTemplate.queryForObject(
                "SELECT queue_version FROM leg WHERE leg_id = 'LEG1'", Integer.class);
        assertThat(queueVersion).isEqualTo(3);
        String statusA = jdbcTemplate.queryForObject(
                "SELECT status FROM load_queue_entry WHERE leg_id = 'LEG1' AND bag_tag = 'A'",
                String.class);
        Integer loadRecords = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM load_record WHERE bag_tag = 'A'", Integer.class);
        if (results.get(0) instanceof DequeueResponse) {
            // 出队先成功：A 已出队装载，撤队 409
            assertThat(statusA).isEqualTo("DEQUEUED");
            assertThat(loadRecords).isEqualTo(1);
        } else {
            // 撤队先成功：A 已撤队，出队版本 409，A 未装载
            assertThat(statusA).isEqualTo("WITHDRAWN");
            assertThat(loadRecords).isZero();
        }
    }

    @Test
    void getQueue_unknownLegReturns404() throws Exception {
        mockMvc.perform(get("/api/legs/NOPE/queue")).andExpect(status().isNotFound());
    }

    private ResultActions registerLeg(String legId, String origin, String destination) throws Exception {
        return postJson("/api/legs", Map.of("requestId", UUID.randomUUID().toString(),
                "legId", legId, "origin", origin, "destination", destination));
    }

    private ResultActions registerBag(String bagTag, List<String> legIds) throws Exception {
        return postJson("/api/bags", Map.of("requestId", UUID.randomUUID().toString(),
                "bagTag", bagTag, "legIds", legIds));
    }

    private ResultActions enqueue(String legId, String operator, List<String> bagTags) throws Exception {
        return postJson("/api/legs/" + legId + "/queue/enqueue", Map.of(
                "requestId", UUID.randomUUID().toString(), "operator", operator, "bagTags", bagTags));
    }

    private ResultActions dequeue(String legId, String operator, int expectedQueueVersion, int count)
            throws Exception {
        return postJson("/api/legs/" + legId + "/queue/dequeue", Map.of(
                "requestId", UUID.randomUUID().toString(), "operator", operator,
                "expectedQueueVersion", expectedQueueVersion, "count", count));
    }

    private ResultActions withdraw(String legId, String operator, String bagTag) throws Exception {
        return postJson("/api/legs/" + legId + "/queue/withdraw", Map.of(
                "requestId", UUID.randomUUID().toString(), "operator", operator, "bagTag", bagTag));
    }

    private ResultActions seal(String legId, int expectedVersion) throws Exception {
        return postJson("/api/legs/" + legId + "/seal", Map.of(
                "requestId", UUID.randomUUID().toString(), "expectedVersion", expectedVersion));
    }

    private ResultActions load(String legId, int expectedVersion, List<String> bagTags) throws Exception {
        return postJson("/api/legs/" + legId + "/load", Map.of(
                "requestId", UUID.randomUUID().toString(),
                "expectedVersion", expectedVersion, "bagTags", bagTags));
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
