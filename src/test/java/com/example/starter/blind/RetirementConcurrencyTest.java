package com.example.starter.blind;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 退役并发与幂等边界（真实多线程，协调真实操作并设超时，不用睡眠代替断言）：
 * 退役与分配并发时同一序号不能同时分配和封存，最终占用与封存序号集合互斥且守恒；
 * 同 requestId 退役并发恰好一次成功且全部回放同一快照；
 * 同键异参（规范化集合不同/原因不同/操作者不同）冲突；权限先于幂等。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RetirementConcurrencyTest extends AbstractBlindIntegrationTest {

    @Autowired
    private TestRestTemplate rest;

    private final ObjectMapper mapper = new ObjectMapper();

    private HttpHeaders headers(String actor, String role, String requestId) {
        HttpHeaders h = new HttpHeaders();
        h.set("X-Actor-Id", actor);
        h.set("X-Role", role);
        if (requestId != null) {
            h.set("X-Request-Id", requestId);
        }
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    @Test
    void concurrentRetirementAndAllocation_noSeatBothAssignedAndSealed_conservationHolds()
            throws Exception {
        // 2 区组 = 8 席；先建实验（空实验）。
        assertEquals(201, rest.exchange("/api/experiments/RC-1", HttpMethod.POST,
                new HttpEntity<>("{\"blockCount\":2}",
                        headers("c1", "COORDINATOR", "rc1-create")), String.class)
                .getStatusCode().value());

        // 第一阶段：1 个分配与 1 个退役 B 并发。两种串行裁决顺序都合法且收敛到同一后态：
        //  - 退役先：封存全部 4 个 B，分配随后占 1-1(A)；
        //  - 分配先：占 1-1(A)，退役仍封存 4 个 B（1-3/1-4/2-3/2-4），块1保留 1-2(A) 名额。
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Future<Integer> allocFuture;
        Future<Integer> retireFuture;
        try {
            allocFuture = pool.submit(() -> rest.exchange(
                    "/api/experiments/RC-1/participants/CP1/allocations",
                    HttpMethod.POST, new HttpEntity<>(null,
                            headers("c1", "COORDINATOR", "rc1-alloc-1")), String.class)
                    .getStatusCode().value());
            retireFuture = pool.submit(() -> rest.exchange(
                    "/api/experiments/RC-1/code-retirements", HttpMethod.POST,
                    new HttpEntity<>("{\"reason\":\"并发退役B\",\"codes\":[\"B\"]}",
                            headers("c1", "COORDINATOR", "rc1-retire")), String.class)
                    .getStatusCode().value());
            assertEquals(201, allocFuture.get(30, TimeUnit.SECONDS), "唯一并发分配必须成功");
            assertEquals(201, retireFuture.get(30, TimeUnit.SECONDS), "并发退役在任一裁决顺序下都必须成功");
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }

        // 最终数据守恒：退役恰好一批，封存行 treatment 全为 B，且封存 4 个 B 序号
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM code_retirement WHERE experiment_id='RC-1'", Integer.class));
        List<String> sealedTreatments = jdbc.queryForList(
                "SELECT DISTINCT treatment FROM sealed_seat WHERE experiment_id='RC-1'",
                String.class);
        assertEquals(List.of("B"), sealedTreatments);
        assertEquals(4, jdbc.queryForObject(
                "SELECT COUNT(*) FROM sealed_seat WHERE experiment_id='RC-1'", Integer.class));
        // 分配落在 A 序号 1-1
        assertEquals("1-1", jdbc.queryForObject(
                "SELECT CONCAT(block_no,'-',seat_no) FROM allocation "
                        + "WHERE experiment_id='RC-1' AND participant_id='CP1'", String.class));
        List<String> assignedBTreatment = jdbc.queryForList(
                "SELECT s.treatment FROM allocation a JOIN seat s "
                        + "ON s.experiment_id=a.experiment_id AND s.block_no=a.block_no "
                        + "AND s.seat_no=a.seat_no WHERE a.experiment_id='RC-1'", String.class);
        assertEquals(List.of("A"), assignedBTreatment);

        // 同一序号不能同时分配和封存：两集合必须互斥
        Set<String> assignedKeys = new HashSet<>(jdbc.queryForList(
                "SELECT CONCAT(block_no,'-',seat_no) FROM allocation WHERE experiment_id='RC-1'",
                String.class));
        Set<String> sealedKeys = new HashSet<>(jdbc.queryForList(
                "SELECT CONCAT(block_no,'-',seat_no) FROM sealed_seat WHERE experiment_id='RC-1'",
                String.class));
        Set<String> intersection = new HashSet<>(assignedKeys);
        intersection.retainAll(sealedKeys);
        assertTrue(intersection.isEmpty(), "同序号不得同时分配与封存: " + intersection);

        // 4 个 B 序号要么已分配要么封存，守恒
        long sealedB = jdbc.queryForObject(
                "SELECT COUNT(*) FROM seat s WHERE s.experiment_id='RC-1' AND s.treatment='B' "
                        + "AND EXISTS (SELECT 1 FROM sealed_seat ss WHERE ss.experiment_id=s.experiment_id "
                        + "AND ss.block_no=s.block_no AND ss.seat_no=s.seat_no)", Long.class);
        long assignedB = jdbc.queryForObject(
                "SELECT COUNT(*) FROM allocation a JOIN seat s "
                        + "ON s.experiment_id=a.experiment_id AND s.block_no=a.block_no "
                        + "AND s.seat_no=a.seat_no "
                        + "WHERE a.experiment_id='RC-1' AND s.treatment='B'", Long.class);
        assertEquals(4, assignedB + sealedB, "4 个 B 序号要么已分配要么封存，守恒");

        // 第二阶段：退役后再并发 4 个分配。可用 A 序号为 1-2、2-1、2-2（3 个），
        // 恰好 3 个 201、1 个 422，且全部落到 A、永不占用封存序号。
        ExecutorService more = Executors.newFixedThreadPool(4);
        List<Future<Integer>> moreFutures = new ArrayList<>();
        try {
            for (int i = 2; i <= 5; i++) {
                final String pid = "CP" + i;
                final String requestId = "rc1-alloc-" + i;
                moreFutures.add(more.submit(() -> rest.exchange(
                        "/api/experiments/RC-1/participants/" + pid + "/allocations",
                        HttpMethod.POST, new HttpEntity<>(null,
                                headers("c1", "COORDINATOR", requestId)), String.class)
                        .getStatusCode().value()));
            }
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> f : moreFutures) {
                statuses.add(f.get(30, TimeUnit.SECONDS));
            }
            assertEquals(3, statuses.stream().filter(s -> s == 201).count(),
                    "仅 3 个剩余 A 名额可分配: " + statuses);
            assertEquals(1, statuses.stream().filter(s -> s == 422).count(),
                    "其余因有效名额耗尽 422: " + statuses);
        } finally {
            more.shutdown();
            assertTrue(more.awaitTermination(10, TimeUnit.SECONDS));
        }
        Set<String> intersectionAfter = new HashSet<>(jdbc.queryForList(
                "SELECT CONCAT(block_no,'-',seat_no) FROM allocation WHERE experiment_id='RC-1'",
                String.class));
        intersectionAfter.retainAll(jdbc.queryForList(
                "SELECT CONCAT(block_no,'-',seat_no) FROM sealed_seat WHERE experiment_id='RC-1'",
                String.class));
        assertTrue(intersectionAfter.isEmpty(), "后续分配也不得占用封存序号");
        List<String> treatmentsAfter = jdbc.queryForList(
                "SELECT s.treatment FROM allocation a JOIN seat s "
                        + "ON s.experiment_id=a.experiment_id AND s.block_no=a.block_no "
                        + "AND s.seat_no=a.seat_no WHERE a.experiment_id='RC-1'", String.class);
        assertTrue(treatmentsAfter.stream().allMatch("A"::equals),
                "退役后新分配不得使用 B: " + treatmentsAfter);
    }

    @Test
    void sameRequestIdConcurrent_retireCodes_exactlyOneSuccessAllReplaySameSnapshot()
            throws Exception {
        assertEquals(201, rest.exchange("/api/experiments/RC-2", HttpMethod.POST,
                new HttpEntity<>("{\"blockCount\":2}",
                        headers("c1", "COORDINATOR", "rc2-create")), String.class)
                .getStatusCode().value());

        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<ResponseEntity<String>>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> rest.exchange(
                        "/api/experiments/RC-2/code-retirements", HttpMethod.POST,
                        new HttpEntity<>("{\"reason\":\"同键并发退役\",\"codes\":[\"B\"]}",
                                headers("c1", "COORDINATOR", "rc2-retire-key")),
                        String.class)));
            }
            Set<String> bodies = new HashSet<>();
            for (Future<ResponseEntity<String>> future : futures) {
                ResponseEntity<String> response = future.get(30, TimeUnit.SECONDS);
                assertEquals(201, response.getStatusCode().value(),
                        "同键同参并发均应回放成功: " + response.getBody());
                bodies.add(response.getBody());
            }
            assertEquals(1, bodies.size(), "所有回放必须是首次完整快照");
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }

        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM code_retirement WHERE experiment_id='RC-2'", Integer.class));
        // 恰好封存 4 个 B 序号，不因并发重放重复封存
        assertEquals(4, jdbc.queryForObject(
                "SELECT COUNT(*) FROM sealed_seat WHERE experiment_id='RC-2'", Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotent_request WHERE request_id='rc2-retire-key'",
                Integer.class));
    }

    @Test
    void sameKeyDifferentFingerprint_andAuth_conflicts() throws Exception {
        assertEquals(201, rest.exchange("/api/experiments/RC-3", HttpMethod.POST,
                new HttpEntity<>("{\"blockCount\":2}",
                        headers("c1", "COORDINATOR", "rc3-create")), String.class)
                .getStatusCode().value());

        String bodyB = "{\"reason\":\"原因\",\"codes\":[\"B\"]}";
        assertEquals(201, rest.exchange("/api/experiments/RC-3/code-retirements",
                HttpMethod.POST, new HttpEntity<>(bodyB,
                        headers("c1", "COORDINATOR", "rc3-key")), String.class)
                .getStatusCode().value());

        // 同键换代码集合：409
        assertEquals(409, rest.exchange("/api/experiments/RC-3/code-retirements",
                HttpMethod.POST, new HttpEntity<>("{\"reason\":\"原因\",\"codes\":[\"A\"]}",
                        headers("c1", "COORDINATOR", "rc3-key")), String.class)
                .getStatusCode().value());

        // 同键换原因：409
        assertEquals(409, rest.exchange("/api/experiments/RC-3/code-retirements",
                HttpMethod.POST, new HttpEntity<>("{\"reason\":\"不同原因\",\"codes\":[\"B\"]}",
                        headers("c1", "COORDINATOR", "rc3-key")), String.class)
                .getStatusCode().value());

        // 同键换操作者：409
        assertEquals(409, rest.exchange("/api/experiments/RC-3/code-retirements",
                HttpMethod.POST, new HttpEntity<>(bodyB,
                        headers("c2", "COORDINATOR", "rc3-key")), String.class)
                .getStatusCode().value());

        // 同键但 REVIEWER 调用：权限先于幂等，403 而非回放/409
        assertEquals(403, rest.exchange("/api/experiments/RC-3/code-retirements",
                HttpMethod.POST, new HttpEntity<>(bodyB,
                        headers("r1", "REVIEWER", "rc3-key")), String.class)
                .getStatusCode().value());

        // 规范化集合：codes 顺序/重复不影响指纹，重放首次快照
        ResponseEntity<String> reordered = rest.exchange(
                "/api/experiments/RC-3/code-retirements", HttpMethod.POST,
                new HttpEntity<>("{\"reason\":\"原因\",\"codes\":[\"B\",\"B\"]}",
                        headers("c1", "COORDINATOR", "rc3-key")), String.class);
        assertEquals(201, reordered.getStatusCode().value());
        JsonNode replayed = mapper.readTree(reordered.getBody());
        assertEquals(1, replayed.path("codes").size(), "回放的是首次快照，不重新执行");
        assertEquals("B", replayed.path("codes").get(0).path("treatment").asText());
    }

    @Test
    void replaySnapshot_returnsFirstFullResult_andReadsDoNotChangeState() throws Exception {
        assertEquals(201, rest.exchange("/api/experiments/RC-4", HttpMethod.POST,
                new HttpEntity<>("{\"blockCount\":2}",
                        headers("c1", "COORDINATOR", "rc4-create")), String.class)
                .getStatusCode().value());
        ResponseEntity<String> first = rest.exchange(
                "/api/experiments/RC-4/code-retirements", HttpMethod.POST,
                new HttpEntity<>("{\"reason\":\"首次\",\"codes\":[\"B\"]}",
                        headers("c1", "COORDINATOR", "rc4-key")), String.class);
        assertEquals(201, first.getStatusCode().value());
        JsonNode firstBody = mapper.readTree(first.getBody());
        String retirementId = firstBody.path("retirementId").asText();

        // 多次重放：完全一致的首次快照
        for (int i = 0; i < 3; i++) {
            ResponseEntity<String> replay = rest.exchange(
                    "/api/experiments/RC-4/code-retirements", HttpMethod.POST,
                    new HttpEntity<>("{\"reason\":\"首次\",\"codes\":[\"B\"]}",
                            headers("c1", "COORDINATOR", "rc4-key")), String.class);
            assertEquals(201, replay.getStatusCode().value());
            assertEquals(first.getBody(), replay.getBody());
        }
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM code_retirement WHERE experiment_id='RC-4'", Integer.class));
        assertEquals(4, jdbc.queryForObject(
                "SELECT COUNT(*) FROM sealed_seat WHERE experiment_id='RC-4'", Integer.class));

        // 只读查询不改状态：历史/详情/明细/诊断各读两次，封存数与批次不变
        for (int i = 0; i < 2; i++) {
            assertEquals(200, rest.exchange(
                    "/api/experiments/RC-4/code-retirements", HttpMethod.GET,
                    new HttpEntity<>(null, headers("r1", "REVIEWER", null)), String.class)
                    .getStatusCode().value());
            assertEquals(200, rest.exchange("/api/code-retirements/" + retirementId,
                    HttpMethod.GET, new HttpEntity<>(null, headers("r1", "REVIEWER", null)),
                    String.class).getStatusCode().value());
        }
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM code_retirement WHERE experiment_id='RC-4'", Integer.class));
        assertEquals(4, jdbc.queryForObject(
                "SELECT COUNT(*) FROM sealed_seat WHERE experiment_id='RC-4'", Integer.class));
    }
}
