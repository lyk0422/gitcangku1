package com.example.starter.blind;

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
 * 交叉阶段并发边界（真实多线程，不用睡眠代替断言）：
 * 阶段操作经实验行锁与退组、关闭共同串行裁决；第一阶段至多开始一次；
 * 同 requestId 并发恰好一次业务执行且全部回放同一响应。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CrossoverConcurrencyTest extends AbstractBlindIntegrationTest {

    @Autowired
    private TestRestTemplate rest;

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

    private void setup(String expId, String pid) {
        assertEquals(201, rest.exchange("/api/experiments/" + expId, HttpMethod.POST,
                new HttpEntity<>("{\"blockCount\":2,\"mode\":\"CROSSOVER\",\"washoutMinutes\":5}",
                        headers("c1", "COORDINATOR", expId + "-create")), String.class)
                .getStatusCode().value());
        assertEquals(201, rest.exchange(
                "/api/experiments/" + expId + "/participants/" + pid + "/allocations",
                HttpMethod.POST, new HttpEntity<>(null,
                        headers("c1", "COORDINATOR", expId + "-alloc")), String.class)
                .getStatusCode().value());
    }

    @Test
    void concurrentFirstStart_distinctKeys_exactlyOneSucceeds() throws Exception {
        setup("XC-1", "P1");
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<Integer>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                final String reqId = "xc1-s1-" + i;
                futures.add(pool.submit(() -> rest.exchange(
                        "/api/experiments/XC-1/participants/P1/phases/first/start",
                        HttpMethod.POST, new HttpEntity<>(null,
                                headers("c1", "COORDINATOR", reqId)), String.class)
                        .getStatusCode().value()));
            }
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> future : futures) {
                statuses.add(future.get(30, TimeUnit.SECONDS));
            }
            long ok = statuses.stream().filter(s -> s == 200).count();
            long conflict = statuses.stream().filter(s -> s == 409).count();
            assertEquals(1, ok, "第一阶段至多开始一次");
            assertEquals(threads - 1L, conflict, "其余并发开始均 409");
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }

        // 最终数据：版本恰为 1，时刻唯一，成功键仅一条幂等记录
        assertEquals(1, jdbc.queryForObject(
                "SELECT version FROM phase_state ps JOIN allocation a "
                        + "ON ps.allocation_id = a.id "
                        + "WHERE a.experiment_id = 'XC-1' AND a.participant_id = 'P1'",
                Integer.class));
        assertEquals("FIRST_RUNNING", jdbc.queryForObject(
                "SELECT ps.status FROM phase_state ps JOIN allocation a "
                        + "ON ps.allocation_id = a.id "
                        + "WHERE a.experiment_id = 'XC-1' AND a.participant_id = 'P1'",
                String.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotent_request WHERE request_id LIKE 'xc1-s1-%'",
                Integer.class));
    }

    @Test
    void concurrentFirstStart_sameKey_exactlyOneExecutionAllReplaySame() throws Exception {
        setup("XC-2", "P1");
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<ResponseEntity<String>>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> rest.exchange(
                        "/api/experiments/XC-2/participants/P1/phases/first/start",
                        HttpMethod.POST, new HttpEntity<>(null,
                                headers("c1", "COORDINATOR", "xc2-same-key")), String.class)));
            }
            Set<String> bodies = new HashSet<>();
            for (Future<ResponseEntity<String>> future : futures) {
                ResponseEntity<String> response = future.get(30, TimeUnit.SECONDS);
                assertEquals(200, response.getStatusCode().value(), "同键并发均应回放成功");
                bodies.add(response.getBody());
            }
            assertEquals(1, bodies.size(), "所有回放响应体必须一致（最初阶段与时刻）");
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }

        assertEquals(1, jdbc.queryForObject(
                "SELECT version FROM phase_state ps JOIN allocation a "
                        + "ON ps.allocation_id = a.id "
                        + "WHERE a.experiment_id = 'XC-2' AND a.participant_id = 'P1'",
                Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotent_request WHERE request_id = 'xc2-same-key'",
                Integer.class));
    }

    @Test
    void concurrentWithdrawAndSecondStart_serializedExactlyOneOutcome() throws Exception {
        setup("XC-3", "P1");
        assertEquals(200, rest.exchange(
                "/api/experiments/XC-3/participants/P1/phases/first/start",
                HttpMethod.POST, new HttpEntity<>(null,
                        headers("c1", "COORDINATOR", "xc3-s1")), String.class)
                .getStatusCode().value());
        assertEquals(200, rest.exchange(
                "/api/experiments/XC-3/participants/P1/phases/first/end",
                HttpMethod.POST, new HttpEntity<>(null,
                        headers("c1", "COORDINATOR", "xc3-e1")), String.class)
                .getStatusCode().value());
        clock.advance(5 * 60_000L);

        // 退组与第二阶段开始并发：串行裁决后恰有一个先执行，结果组合确定
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> withdraw = pool.submit(() -> rest.exchange(
                    "/api/experiments/XC-3/participants/P1/withdrawal",
                    HttpMethod.POST, new HttpEntity<>(null,
                            headers("c1", "COORDINATOR", "xc3-withdraw")), String.class)
                    .getStatusCode().value());
            Future<Integer> secondStart = pool.submit(() -> rest.exchange(
                    "/api/experiments/XC-3/participants/P1/phases/second/start",
                    HttpMethod.POST, new HttpEntity<>(null,
                            headers("c1", "COORDINATOR", "xc3-s2")), String.class)
                    .getStatusCode().value());
            int withdrawStatus = withdraw.get(30, TimeUnit.SECONDS);
            int startStatus = secondStart.get(30, TimeUnit.SECONDS);
            assertEquals(200, withdrawStatus, "退组始终允许");
            assertTrue(startStatus == 200 || startStatus == 410,
                    "第二阶段开始只能成功（先裁决）或 410（退组在先），实际: " + startStatus);
            // 最终状态一致：若开始成功则 SECOND_RUNNING，否则停留 WASHOUT 且已退组
            String phaseStatus = jdbc.queryForObject(
                    "SELECT ps.status FROM phase_state ps JOIN allocation a "
                            + "ON ps.allocation_id = a.id "
                            + "WHERE a.experiment_id = 'XC-3' AND a.participant_id = 'P1'",
                    String.class);
            assertEquals(startStatus == 200 ? "SECOND_RUNNING" : "WASHOUT", phaseStatus);
            assertEquals("WITHDRAWN", jdbc.queryForObject(
                    "SELECT status FROM allocation "
                            + "WHERE experiment_id = 'XC-3' AND participant_id = 'P1'",
                    String.class));
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }
}
