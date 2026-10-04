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

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 样本链并发边界（真实多线程，统一栅栏放行，不用睡眠代替断言）：
 * 并发锁定只成功一次；更正与锁定、退组与锁定竞争只形成完整一致的配对。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SampleChainConcurrencyTest extends AbstractBlindIntegrationTest {

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

    private void prepareChain(String exp, String pid) {
        assertEquals(201, rest.exchange("/api/experiments/" + exp, HttpMethod.POST,
                new HttpEntity<>("{\"blockCount\":2}", headers("c1", "COORDINATOR", exp + "-c")),
                String.class).getStatusCode().value());
        assertEquals(201, rest.exchange(
                "/api/experiments/" + exp + "/participants/" + pid + "/allocations",
                HttpMethod.POST, new HttpEntity<>(null, headers("c1", "COORDINATOR", exp + "-a")),
                String.class).getStatusCode().value());
        registerSample(exp, pid, exp + "-pre", "SMP-PRE-1", "PRE", "d-pre", 1_000L);
        registerSample(exp, pid, exp + "-post", "SMP-POST-1", "POST", "d-post", 2_000L);
    }

    private void registerSample(String exp, String pid, String reqId, String sampleNo,
                                String phase, String digest, long collectedAt) {
        String body = "{\"sampleNo\":\"" + sampleNo + "\",\"phase\":\"" + phase
                + "\",\"digest\":\"" + digest + "\",\"collectedAt\":" + collectedAt + "}";
        assertEquals(201, rest.exchange(
                "/api/experiments/" + exp + "/participants/" + pid + "/samples",
                HttpMethod.POST, new HttpEntity<>(body, headers("c1", "COORDINATOR", reqId)),
                String.class).getStatusCode().value());
    }

    private int lock(String exp, String pid, String reqId, int expectedVersion) {
        return rest.exchange("/api/experiments/" + exp + "/participants/" + pid + "/sample-lock",
                HttpMethod.POST, new HttpEntity<>("{\"expectedVersion\":" + expectedVersion + "}",
                        headers("c1", "COORDINATOR", reqId)), String.class)
                .getStatusCode().value();
    }

    /** 两个操作同栅栏放行并发执行，返回各自状态码。 */
    private List<Integer> race(Callable<Integer> first, Callable<Integer> second)
            throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            Callable<Integer> wrapFirst = () -> {
                ready.countDown();
                go.await(10, TimeUnit.SECONDS);
                return first.call();
            };
            Callable<Integer> wrapSecond = () -> {
                ready.countDown();
                go.await(10, TimeUnit.SECONDS);
                return second.call();
            };
            Future<Integer> f1 = pool.submit(wrapFirst);
            Future<Integer> f2 = pool.submit(wrapSecond);
            assertTrue(ready.await(10, TimeUnit.SECONDS), "线程未就绪");
            go.countDown();
            return List.of(f1.get(30, TimeUnit.SECONDS), f2.get(30, TimeUnit.SECONDS));
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void concurrentLocks_sameParticipant_exactlyOneSucceeds() throws Exception {
        prepareChain("EXP-C1", "P1");
        List<Integer> statuses = race(
                () -> lock("EXP-C1", "P1", "c1-lock-1", 2),
                () -> lock("EXP-C1", "P1", "c1-lock-2", 2));
        assertEquals(1, statuses.stream().filter(s -> s == 201).count(), "并发锁定恰好一次成功");
        assertEquals(1, statuses.stream().filter(s -> s == 409).count(), "另一方 409");
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM sample_pair_lock WHERE experiment_id = 'EXP-C1'",
                Integer.class), "只形成一个配对");
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(DISTINCT pair_code) FROM sample_pair_lock", Integer.class));
    }

    @Test
    void correctionRacesLock_exactlyOneWins_finalStateConsistent() throws Exception {
        prepareChain("EXP-C2", "P1");
        Callable<Integer> correct = () -> rest.exchange(
                "/api/experiments/EXP-C2/participants/P1/samples",
                HttpMethod.POST,
                new HttpEntity<>("{\"sampleNo\":\"SMP-PRE-2\",\"phase\":\"PRE\","
                                + "\"digest\":\"d-pre-new\",\"collectedAt\":1500}",
                        headers("c1", "COORDINATOR", "c2-correct")), String.class)
                .getStatusCode().value();
        List<Integer> statuses = race(correct, () -> lock("EXP-C2", "P1", "c2-lock", 2));
        int corrections = (int) statuses.stream().filter(s -> s == 201).count();
        assertEquals(1, corrections, "更正与锁定竞争恰好一方成功");
        assertEquals(1, statuses.stream().filter(s -> s == 409).count());

        Integer locks = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sample_pair_lock WHERE experiment_id = 'EXP-C2'",
                Integer.class);
        Integer records = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sample_record WHERE experiment_id = 'EXP-C2'",
                Integer.class);
        if (locks == 1) {
            // 锁定先胜：版本与记录数停留在锁定时的 2，配对完整且时序合法
            assertEquals(2, records);
            String check = jdbc.queryForObject(
                    "SELECT CONCAT(pre_digest, '|', post_digest) FROM sample_pair_lock "
                            + "WHERE experiment_id = 'EXP-C2'", String.class);
            assertEquals("d-pre|d-post", check);
            Long ordered = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM sample_pair_lock WHERE experiment_id = 'EXP-C2' "
                            + "AND post_collected_at > pre_collected_at", Long.class);
            assertEquals(1L, ordered, "锁定时序必须合法");
        } else {
            // 更正先胜：链版本变为 3，锁定因版本不匹配 409，无半成品配对
            assertEquals(0, locks);
            assertEquals(3, records);
        }
    }

    @Test
    void withdrawRacesLock_noPartialPair() throws Exception {
        prepareChain("EXP-C3", "P1");
        Callable<Integer> withdraw = () -> rest.exchange(
                "/api/experiments/EXP-C3/participants/P1/withdrawal",
                HttpMethod.POST, new HttpEntity<>(null, headers("c1", "COORDINATOR", "c3-wd")),
                String.class).getStatusCode().value();
        List<Integer> statuses = race(withdraw, () -> lock("EXP-C3", "P1", "c3-lock", 2));
        assertTrue(statuses.stream().allMatch(s -> s == 200 || s == 201 || s == 409),
                "退组与锁定竞争只出现 200/201/409: " + statuses);

        Integer locks = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sample_pair_lock WHERE experiment_id = 'EXP-C3'",
                Integer.class);
        String allocStatus = jdbc.queryForObject(
                "SELECT status FROM allocation WHERE experiment_id = 'EXP-C3' "
                        + "AND participant_id = 'P1'", String.class);
        if (locks == 0) {
            // 退组先胜：锁定被拒，参与者已退组，无配对
            assertEquals("WITHDRAWN", allocStatus);
        } else {
            // 锁定先胜：配对完整一致，退组不影响已锁定对
            assertEquals(1, locks);
            Long complete = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM sample_pair_lock WHERE experiment_id = 'EXP-C3' "
                            + "AND pre_digest = 'd-pre' AND post_digest = 'd-post' "
                            + "AND post_collected_at > pre_collected_at", Long.class);
            assertEquals(1L, complete, "已锁定对保持完整一致");
        }
    }
}