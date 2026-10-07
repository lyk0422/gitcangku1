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

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 航段待装队列测试：合成队列 A/B/C 主流程（出队 2 只装 A、B，旧版本出队 409，
 * 撤 C 后可封舱，重放首次出队不再移动行李），入队/出队/撤队拒绝分支、
 * 序号历史保留、幂等边界与出队版本并发竞争。全部基于 H2 MySQL 兼容模式真实数据库。
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
        jdbcTemplate.update("DELETE FROM load_record");
        jdbcTemplate.update("DELETE FROM queue_member");
        jdbcTemplate.update("DELETE FROM leg_queue");
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
    void queueMainFlow_dequeueFrontMembersCancelThenSeal() throws Exception {
        registerLeg("QL1", "PEK", "SHA").andExpect(status().isCreated());
        registerBag("A", List.of("QL1")).andExpect(status().isCreated());
        registerBag("B", List.of("QL1")).andExpect(status().isCreated());
        registerBag("C", List.of("QL1")).andExpect(status().isCreated());

        // 入队 A、B、C：按请求顺序分配序号 1、2、3，队列版本 1 -> 2
        enqueue("QL1", "ops-1", List.of("A", "B", "C")).andExpect(status().isOk())
                .andExpect(jsonPath("$.queueVersion").value(2))
                .andExpect(jsonPath("$.enqueued[0].seq").value(1))
                .andExpect(jsonPath("$.enqueued[0].bagTag").value("A"))
                .andExpect(jsonPath("$.enqueued[2].seq").value(3))
                .andExpect(jsonPath("$.enqueued[2].bagTag").value("C"));

        mockMvc.perform(get("/api/legs/QL1/queue"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.queueVersion").value(2))
                .andExpect(jsonPath("$.members", hasSize(3)))
                .andExpect(jsonPath("$.members[*].status",
                        contains("WAITING", "WAITING", "WAITING")));

        // 启用队列的航段禁止普通直接装载 -> 409；存在有效未出队成员禁止封舱 -> 422
        load("QL1", 1, List.of("A")).andExpect(status().isConflict());
        seal("QL1", 1).andExpect(status().isUnprocessableEntity());

        // 出队 2：只装最前面的 A、B，冻结序号与完整装载响应
        String dequeueRequestId = UUID.randomUUID().toString();
        Map<String, Object> dequeueBody = Map.of("requestId", dequeueRequestId,
                "operator", "ops-2", "expectedQueueVersion", 2, "count", 2);
        postJson("/api/legs/QL1/queue/dequeue", dequeueBody).andExpect(status().isOk())
                .andExpect(jsonPath("$.queueVersion").value(3))
                .andExpect(jsonPath("$.dequeued", hasSize(2)))
                .andExpect(jsonPath("$.dequeued[0].seq").value(1))
                .andExpect(jsonPath("$.dequeued[0].bagTag").value("A"))
                .andExpect(jsonPath("$.dequeued[1].seq").value(2))
                .andExpect(jsonPath("$.dequeued[1].bagTag").value("B"))
                .andExpect(jsonPath("$.load.status").value("OPEN"))
                .andExpect(jsonPath("$.load.version").value(2))
                .andExpect(jsonPath("$.load.loaded", contains("A", "B")));

        mockMvc.perform(get("/api/bags/A/trace"))
                .andExpect(jsonPath("$.loadedLegId").value("QL1"));
        mockMvc.perform(get("/api/bags/C/trace"))
                .andExpect(jsonPath("$.loadedLegId").doesNotExist());

        // 重放首次出队：返回首次完整结果，不再次移动行李
        postJson("/api/legs/QL1/queue/dequeue", dequeueBody).andExpect(status().isOk())
                .andExpect(jsonPath("$.queueVersion").value(3))
                .andExpect(jsonPath("$.dequeued", hasSize(2)))
                .andExpect(jsonPath("$.load.loaded", contains("A", "B")));
        Integer loadRecords = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM load_record WHERE leg_id = 'QL1'", Integer.class);
        assertThat(loadRecords).isEqualTo(2);
        Integer loadedEvents = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bag_event WHERE event_type = 'LOADED' AND leg_id = 'QL1'",
                Integer.class);
        assertThat(loadedEvents).isEqualTo(2);

        // 旧队列版本再出队 -> 409
        dequeue("QL1", 2, 1).andExpect(status().isConflict());

        // 撤 C 后可封舱；撤队保留序号历史
        cancel("QL1", "C").andExpect(status().isOk())
                .andExpect(jsonPath("$.queueVersion").value(4))
                .andExpect(jsonPath("$.seq").value(3))
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        mockMvc.perform(get("/api/legs/QL1/queue"))
                .andExpect(jsonPath("$.queueVersion").value(4))
                .andExpect(jsonPath("$.members[*].seq", contains(1, 2, 3)))
                .andExpect(jsonPath("$.members[*].status",
                        contains("DEQUEUED", "DEQUEUED", "CANCELLED")));

        seal("QL1", 2).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SEALED"))
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.manifest", contains("A", "B")));
    }

    @Test
    void enqueue_rejectsInvalidRequests() throws Exception {
        registerLeg("QLX", "PEK", "SHA").andExpect(status().isCreated());
        registerLeg("QL3", "SHA", "CAN").andExpect(status().isCreated());
        registerBag("OK", List.of("QL3")).andExpect(status().isCreated());
        registerBag("S", List.of("QLX", "QL3")).andExpect(status().isCreated());
        registerBag("W", List.of("QLX")).andExpect(status().isCreated());

        // 操作者为空 -> 400；数量越界 -> 400
        postJson("/api/legs/QL3/queue/enqueue", Map.of(
                "requestId", UUID.randomUUID().toString(), "operator", " ",
                "bagTags", List.of("OK"))).andExpect(status().isBadRequest());
        enqueue("QL3", "ops", List.of()).andExpect(status().isBadRequest());
        enqueue("QL3", "ops", List.of("OK", "OK", "OK", "OK", "OK", "OK"))
                .andExpect(status().isBadRequest());
        // 请求内重复 -> 422；行李不存在 -> 422；待乘航段不符 -> 422
        enqueue("QL3", "ops", List.of("OK", "OK")).andExpect(status().isUnprocessableEntity());
        enqueue("QL3", "ops", List.of("MISSING")).andExpect(status().isUnprocessableEntity());
        enqueue("QL3", "ops", List.of("W")).andExpect(status().isUnprocessableEntity());
        // 航段不存在 -> 404
        enqueue("QL_MISSING", "ops", List.of("OK")).andExpect(status().isNotFound());

        // 短卸未补到的行李不可入队 -> 422；补到后可入队
        load("QLX", 1, List.of("S")).andExpect(status().isOk());
        seal("QLX", 2).andExpect(status().isOk());
        diffArrive("QLX", 3, List.of()).andExpect(status().isOk());
        enqueue("QL3", "ops", List.of("S")).andExpect(status().isUnprocessableEntity());
        recover("S", "QLX", "SHA").andExpect(status().isOk());
        enqueue("QL3", "ops", List.of("S")).andExpect(status().isOk())
                .andExpect(jsonPath("$.queueVersion").value(2));

        // 已在队列中的行李不得重复入队 -> 422
        enqueue("QL3", "ops", List.of("OK")).andExpect(status().isOk());
        enqueue("QL3", "ops", List.of("OK")).andExpect(status().isUnprocessableEntity());

        // 已封舱航段禁止入队 -> 422
        registerLeg("QLS", "PEK", "SHA").andExpect(status().isCreated());
        seal("QLS", 1).andExpect(status().isOk());
        enqueue("QLS", "ops", List.of("OK")).andExpect(status().isUnprocessableEntity());
    }

    @Test
    void enqueue_capacityLimitedTo20ActiveMembers() throws Exception {
        registerLeg("QLC", "PEK", "SHA").andExpect(status().isCreated());
        for (int i = 1; i <= 21; i++) {
            registerBag(String.format("B%02d", i), List.of("QLC")).andExpect(status().isCreated());
        }
        for (int batch = 0; batch < 4; batch++) {
            List<String> tags = new ArrayList<>();
            for (int i = 1; i <= 5; i++) {
                tags.add(String.format("B%02d", batch * 5 + i));
            }
            enqueue("QLC", "ops", tags).andExpect(status().isOk());
        }
        // 第 21 件超出有效成员上限 -> 422
        enqueue("QLC", "ops", List.of("B21")).andExpect(status().isUnprocessableEntity());
        Integer waiting = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM queue_member WHERE leg_id = 'QLC' AND status = 'WAITING'",
                Integer.class);
        assertThat(waiting).isEqualTo(20);

        // 撤销一件释放容量后可再入队，序号继续递增不重用
        cancel("QLC", "B01").andExpect(status().isOk());
        enqueue("QLC", "ops", List.of("B21")).andExpect(status().isOk())
                .andExpect(jsonPath("$.enqueued[0].seq").value(21));
    }

    @Test
    void dequeue_rejectsInvalidVersionCountAndState() throws Exception {
        registerLeg("QLD", "PEK", "SHA").andExpect(status().isCreated());
        registerBag("A", List.of("QLD")).andExpect(status().isCreated());

        // 未启用队列 -> 422；航段不存在 -> 404
        dequeue("QLD", 1, 1).andExpect(status().isUnprocessableEntity());
        dequeue("QL_MISSING", 1, 1).andExpect(status().isNotFound());

        enqueue("QLD", "ops", List.of("A")).andExpect(status().isOk());
        // 数量非正数或超过 5 -> 400；缺 count -> 400
        dequeue("QLD", 2, 0).andExpect(status().isBadRequest());
        dequeue("QLD", 2, 6).andExpect(status().isBadRequest());
        postJson("/api/legs/QLD/queue/dequeue", Map.of(
                "requestId", UUID.randomUUID().toString(), "operator", "ops",
                "expectedQueueVersion", 2)).andExpect(status().isBadRequest());
        // 队列版本冲突 -> 409
        dequeue("QLD", 99, 1).andExpect(status().isConflict());

        // 全部成员撤销后出队 -> 422（无有效待装成员）
        cancel("QLD", "A").andExpect(status().isOk());
        dequeue("QLD", 3, 1).andExpect(status().isUnprocessableEntity());

        // 封舱后的航段禁止出队 -> 422
        registerLeg("QLSE", "PEK", "SHA").andExpect(status().isCreated());
        registerBag("B", List.of("QLSE")).andExpect(status().isCreated());
        enqueue("QLSE", "ops", List.of("B")).andExpect(status().isOk());
        dequeue("QLSE", 2, 1).andExpect(status().isOk());
        seal("QLSE", 2).andExpect(status().isOk());
        dequeue("QLSE", 3, 1).andExpect(status().isUnprocessableEntity());
    }

    @Test
    void dequeue_firstMemberNotLoadableRejectsWholeBatch() throws Exception {
        registerLeg("QLF", "PEK", "SHA").andExpect(status().isCreated());
        registerBag("A", List.of("QLF")).andExpect(status().isCreated());
        registerBag("B", List.of("QLF")).andExpect(status().isCreated());
        enqueue("QLF", "ops", List.of("A", "B")).andExpect(status().isOk());

        // 模拟首件在出队前被外部登记为短卸（尚未补到）：整批 422，不跳过也不装后件
        jdbcTemplate.update("UPDATE bag SET status = 'SHORT_UNLOADED', short_leg_id = 'QLF',"
                + " short_destination = 'SHA' WHERE bag_tag = 'A'");
        dequeue("QLF", 2, 2).andExpect(status().isUnprocessableEntity());

        Integer loadRecords = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM load_record WHERE leg_id = 'QLF'", Integer.class);
        assertThat(loadRecords).isZero();
        Integer waiting = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM queue_member WHERE leg_id = 'QLF' AND status = 'WAITING'",
                Integer.class);
        assertThat(waiting).isEqualTo(2);
        Integer queueVersion = jdbcTemplate.queryForObject(
                "SELECT version FROM leg_queue WHERE leg_id = 'QLF'", Integer.class);
        assertThat(queueVersion).isEqualTo(2);
        Integer legVersion = jdbcTemplate.queryForObject(
                "SELECT version FROM leg WHERE leg_id = 'QLF'", Integer.class);
        assertThat(legVersion).isEqualTo(1);
        mockMvc.perform(get("/api/bags/B/trace"))
                .andExpect(jsonPath("$.loadedLegId").doesNotExist());
    }

    @Test
    void cancel_rulesAndSeqHistoryPreserved() throws Exception {
        registerLeg("QL2", "PEK", "SHA").andExpect(status().isCreated());
        registerBag("A", List.of("QL2")).andExpect(status().isCreated());
        registerBag("B", List.of("QL2")).andExpect(status().isCreated());
        enqueue("QL2", "ops", List.of("A", "B")).andExpect(status().isOk());

        // 成员不存在 -> 404
        cancel("QL2", "MISSING").andExpect(status().isNotFound());
        // 撤销有效成员 -> 200，序号保留
        cancel("QL2", "B").andExpect(status().isOk())
                .andExpect(jsonPath("$.queueVersion").value(3))
                .andExpect(jsonPath("$.seq").value(2))
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        // 已撤销成员再次撤队 -> 422
        cancel("QL2", "B").andExpect(status().isUnprocessableEntity());

        // 重新入队同一行李：分配新序号 3，历史序号 2 保留不重用
        enqueue("QL2", "ops", List.of("B")).andExpect(status().isOk())
                .andExpect(jsonPath("$.enqueued[0].seq").value(3));
        mockMvc.perform(get("/api/legs/QL2/queue"))
                .andExpect(jsonPath("$.members[*].seq", contains(1, 2, 3)))
                .andExpect(jsonPath("$.members[*].bagTag", contains("A", "B", "B")))
                .andExpect(jsonPath("$.members[*].status",
                        contains("WAITING", "CANCELLED", "WAITING")));

        // 出队跳过已撤成员，只取最前面有效成员（序号 1、3）
        dequeue("QL2", 4, 5).andExpect(status().isOk())
                .andExpect(jsonPath("$.dequeued[*].seq", contains(1, 3)))
                .andExpect(jsonPath("$.load.loaded", contains("A", "B")));

        // 已出队成员不可撤 -> 409
        cancel("QL2", "A").andExpect(status().isConflict());
    }

    @Test
    void queueIdempotency_replayConflictAndFailureNotOccupyingKey() throws Exception {
        registerLeg("QLI", "PEK", "SHA").andExpect(status().isCreated());
        registerBag("A", List.of("QLI")).andExpect(status().isCreated());
        registerBag("B", List.of("QLI")).andExpect(status().isCreated());

        // 入队同键同参重放：返回首次结果，成员不重复
        String enqueueKey = UUID.randomUUID().toString();
        enqueue("QLI", enqueueKey, "ops", List.of("A")).andExpect(status().isOk())
                .andExpect(jsonPath("$.queueVersion").value(2))
                .andExpect(jsonPath("$.enqueued[0].seq").value(1));
        enqueue("QLI", enqueueKey, "ops", List.of("A")).andExpect(status().isOk())
                .andExpect(jsonPath("$.queueVersion").value(2));
        Integer members = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM queue_member WHERE leg_id = 'QLI'", Integer.class);
        assertThat(members).isEqualTo(1);
        // 同键改参 -> 409
        enqueue("QLI", enqueueKey, "ops", List.of("B")).andExpect(status().isConflict());

        // 失败不占键：先入队不存在行李失败，再同键成功
        String retryKey = UUID.randomUUID().toString();
        enqueue("QLI", retryKey, "ops", List.of("MISSING"))
                .andExpect(status().isUnprocessableEntity());
        enqueue("QLI", retryKey, "ops", List.of("B")).andExpect(status().isOk())
                .andExpect(jsonPath("$.queueVersion").value(3));

        // 撤队同键重放：版本只推进一次
        String cancelKey = UUID.randomUUID().toString();
        cancel("QLI", cancelKey, "B").andExpect(status().isOk())
                .andExpect(jsonPath("$.queueVersion").value(4));
        cancel("QLI", cancelKey, "B").andExpect(status().isOk())
                .andExpect(jsonPath("$.queueVersion").value(4));
        mockMvc.perform(get("/api/legs/QLI/queue"))
                .andExpect(jsonPath("$.queueVersion").value(4));
        Integer logs = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = ?", Integer.class, cancelKey);
        assertThat(logs).isEqualTo(1);
    }

    @Test
    void concurrentDequeue_versionContentionSingleWinner() throws Exception {
        registerLeg("QLG", "PEK", "SHA").andExpect(status().isCreated());
        registerBag("A", List.of("QLG")).andExpect(status().isCreated());
        registerBag("B", List.of("QLG")).andExpect(status().isCreated());
        registerBag("C", List.of("QLG")).andExpect(status().isCreated());
        enqueue("QLG", "ops", List.of("A", "B", "C")).andExpect(status().isOk());

        // 4 个并发出队携带相同期望队列版本：一成三拒
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            tasks.add(() -> baggageService.dequeue("QLG",
                    new DequeueRequest(UUID.randomUUID().toString(), "ops", 2, 2)));
        }
        List<Object> results = runConcurrently(tasks);

        long successes = results.stream().filter(DequeueResponse.class::isInstance).count();
        long conflicts = results.stream()
                .filter(ApiException.class::isInstance)
                .map(ApiException.class::cast)
                .filter(ex -> ex.getStatus().value() == 409)
                .count();
        assertThat(successes).isEqualTo(1);
        assertThat(conflicts).isEqualTo(3);

        Integer loadRecords = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM load_record WHERE leg_id = 'QLG'", Integer.class);
        assertThat(loadRecords).isEqualTo(2);
        Integer queueVersion = jdbcTemplate.queryForObject(
                "SELECT version FROM leg_queue WHERE leg_id = 'QLG'", Integer.class);
        assertThat(queueVersion).isEqualTo(3);
        Integer dequeued = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM queue_member WHERE leg_id = 'QLG' AND status = 'DEQUEUED'",
                Integer.class);
        assertThat(dequeued).isEqualTo(2);
        Integer legVersion = jdbcTemplate.queryForObject(
                "SELECT version FROM leg WHERE leg_id = 'QLG'", Integer.class);
        assertThat(legVersion).isEqualTo(2);
    }

    private ResultActions registerLeg(String legId, String origin, String destination) throws Exception {
        return postJson("/api/legs", Map.of("requestId", UUID.randomUUID().toString(),
                "legId", legId, "origin", origin, "destination", destination));
    }

    private ResultActions registerBag(String bagTag, List<String> legIds) throws Exception {
        return postJson("/api/bags", Map.of("requestId", UUID.randomUUID().toString(),
                "bagTag", bagTag, "legIds", legIds));
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

    private ResultActions diffArrive(String legId, int expectedVersion, List<String> bagTags)
            throws Exception {
        return postJson("/api/legs/" + legId + "/arrive-difference", Map.of(
                "requestId", UUID.randomUUID().toString(),
                "expectedVersion", expectedVersion, "bagTags", bagTags));
    }

    private ResultActions recover(String bagTag, String missingLegId, String actualStation)
            throws Exception {
        return postJson("/api/bags/recover", Map.of(
                "requestId", UUID.randomUUID().toString(),
                "bagTag", bagTag, "missingLegId", missingLegId, "actualStation", actualStation));
    }

    private ResultActions enqueue(String legId, String operator, List<String> bagTags)
            throws Exception {
        return enqueue(legId, UUID.randomUUID().toString(), operator, bagTags);
    }

    private ResultActions enqueue(String legId, String requestId, String operator,
                                  List<String> bagTags) throws Exception {
        return postJson("/api/legs/" + legId + "/queue/enqueue", Map.of(
                "requestId", requestId, "operator", operator, "bagTags", bagTags));
    }

    private ResultActions dequeue(String legId, int expectedQueueVersion, int count)
            throws Exception {
        return postJson("/api/legs/" + legId + "/queue/dequeue", Map.of(
                "requestId", UUID.randomUUID().toString(), "operator", "ops",
                "expectedQueueVersion", expectedQueueVersion, "count", count));
    }

    private ResultActions cancel(String legId, String bagTag) throws Exception {
        return cancel(legId, UUID.randomUUID().toString(), bagTag);
    }

    private ResultActions cancel(String legId, String requestId, String bagTag) throws Exception {
        return postJson("/api/legs/" + legId + "/queue/cancel", Map.of(
                "requestId", requestId, "operator", "ops", "bagTag", bagTag));
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
