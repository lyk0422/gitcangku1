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

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 中心启用门禁并发边界（真实多线程 + H2 行锁，协调真实操作并设超时）：
 * 启用与分配互斥：启用事务提交前排入的分配要么 409（未消耗名额），要么在提交后成功且不超冻结量；
 * 启用后并发分配不重号、不超额；同 requestId 并发确认恰好生效一次；并发申请同一中心仅一次成功。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SiteActivationConcurrencyTest extends AbstractBlindIntegrationTest {

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private DataSource dataSource;

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

    private int status(ResponseEntity<String> resp) {
        return resp.getStatusCode().value();
    }

    private void createExperiment(String expId, String reqId) {
        assertEquals(201, rest.exchange("/api/experiments/" + expId, HttpMethod.POST,
                new HttpEntity<>("{\"blockCount\":2}",
                        headers("coord-1", "COORDINATOR", reqId)), String.class)
                .getStatusCode().value());
    }

    private int submitVersionSingleBlock(String expId, String siteId, int quota, String reqId) {
        ResponseEntity<String> resp = rest.exchange(
                "/api/experiments/" + expId + "/sites/" + siteId + "/config-versions",
                HttpMethod.POST, new HttpEntity<>(
                        "{\"blocks\":[{\"blockCode\":\"A\",\"quota\":" + quota + "}]}",
                        headers("lead-1", "LEAD", reqId)), String.class);
        assertEquals(201, resp.getStatusCode().value());
        return 1;
    }

    private String applyActivation(String expId, String siteId, int version, String reqId) {
        ResponseEntity<String> resp = rest.exchange(
                "/api/experiments/" + expId + "/sites/" + siteId + "/activations",
                HttpMethod.POST, new HttpEntity<>("{\"configVersion\":" + version + "}",
                        headers("lead-1", "LEAD", reqId)), String.class);
        assertEquals(201, resp.getStatusCode().value());
        // activationId 以 SA- 开头，从响应体取出避免依赖 UUID
        String body = resp.getBody();
        int start = body.indexOf("\"activationId\":\"") + "\"activationId\":\"".length();
        int end = body.indexOf('"', start);
        return body.substring(start, end);
    }

    private Future<Integer> submitAllocate(ExecutorService pool, String expId, String siteId,
                                           String participant, String reqId) {
        return pool.submit(() -> status(rest.exchange(
                "/api/experiments/" + expId + "/sites/" + siteId
                        + "/blocks/A/participants/" + participant + "/allocations",
                HttpMethod.POST, new HttpEntity<>(null,
                        headers("coord-1", "COORDINATOR", reqId)), String.class)));
    }

    @Test
    void concurrentAllocationAfterActivation_exactlyFrozenQuotaSucceed_serialsAreUnique()
            throws Exception {
        String expId = "SITE-CONC-1";
        createExperiment(expId, "sc1-exp");
        submitVersionSingleBlock(expId, "C", 10, "sc1-cfg");
        String activationId = applyActivation(expId, "C", 1, "sc1-apply");
        assertEquals(200, rest.exchange(
                "/api/activations/" + activationId + "/confirmation", HttpMethod.POST,
                new HttpEntity<>("{\"configVersion\":1}",
                        headers("confirmer-2", "CONFIRMER", "sc1-confirm")), String.class)
                .getStatusCode().value());

        int threads = 20;
        ExecutorService pool = Executors.newFixedThreadPool(10);
        List<Future<Integer>> futures = new ArrayList<>();
        try {
            for (int i = 1; i <= threads; i++) {
                futures.add(submitAllocate(pool, expId, "C", "CP" + i, "sc1-alloc-" + i));
            }
            int success = 0;
            int rejected = 0;
            for (Future<Integer> future : futures) {
                int code = future.get(30, TimeUnit.SECONDS);
                assertTrue(code == 201 || code == 422, "只允许成功或额度不足: " + code);
                if (code == 201) {
                    success++;
                } else {
                    rejected++;
                }
            }
            assertEquals(10, success, "冻结 10 个名额，恰好 10 次成功");
            assertEquals(10, rejected, "其余 10 次额度不足");
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }

        // 最终数据：恰好 10 条，顺序号 1..10 不重号，参与者互不相同
        assertEquals(10, jdbc.queryForObject(
                "SELECT COUNT(*) FROM site_allocation WHERE experiment_id = ? AND site_id = 'C'",
                Integer.class, expId));
        assertEquals(10, jdbc.queryForObject(
                "SELECT COUNT(DISTINCT serial_no) FROM site_allocation "
                        + "WHERE experiment_id = ? AND site_id = 'C' AND block_code = 'A'",
                Integer.class, expId));
        List<Integer> serials = jdbc.queryForList(
                "SELECT serial_no FROM site_allocation WHERE experiment_id = ? "
                        + "AND site_id = 'C' ORDER BY serial_no", Integer.class, expId);
        for (int i = 1; i <= 10; i++) {
            assertEquals(i, serials.get(i - 1), "顺序号必须连续不重号");
        }
        // 第 11 个名额始终分不出来
        assertEquals(422, status(rest.exchange(
                "/api/experiments/" + expId + "/sites/C/blocks/A/participants/CPX/allocations",
                HttpMethod.POST, new HttpEntity<>(null,
                        headers("coord-1", "COORDINATOR", "sc1-alloc-x")), String.class)));
    }

    @Test
    void allocationsQueuedBeforeActivationCommit_neverConsumeQuotaBeforeCommit_andNeverExceedFrozen()
            throws Exception {
        String expId = "SITE-CONC-2";
        createExperiment(expId, "sc2-exp");
        submitVersionSingleBlock(expId, "C", 10, "sc2-cfg");
        String activationId = applyActivation(expId, "C", 1, "sc2-apply");

        // 用一个独立连接长期持有中心行锁，使确认事务（需锁中心）与分配事务都在锁上排队。
        try (Connection lockConn = dataSource.getConnection()) {
            lockConn.setAutoCommit(false);
            try (PreparedStatement ps = lockConn.prepareStatement(
                    "SELECT status FROM site WHERE experiment_id = ? AND site_id = 'C' FOR UPDATE")) {
                ps.setString(1, expId);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals("INACTIVE", rs.getString(1));
                }
            }

            ExecutorService pool = Executors.newFixedThreadPool(12);
            try {
                // 确认请求先发出：必然阻塞在中心行锁上，事务尚未提交
                Future<Integer> confirmFuture = pool.submit(() -> status(rest.exchange(
                        "/api/activations/" + activationId + "/confirmation", HttpMethod.POST,
                        new HttpEntity<>("{\"configVersion\":1}",
                                headers("confirmer-2", "CONFIRMER", "sc2-confirm")),
                        String.class)));
                // 以同步工具确认确认事务确实未完成，而非用睡眠当判据
                assertFutureNotDone(confirmFuture);

                // 启用事务提交前把 10 个分配请求也放出，全部在锁队列中等待
                List<Future<Integer>> queued = new ArrayList<>();
                for (int i = 1; i <= 10; i++) {
                    queued.add(submitAllocate(pool, expId, "C", "QP" + i, "sc2-alloc-" + i));
                }
                assertFutureNotDone(confirmFuture);
                for (Future<Integer> f : queued) {
                    assertFutureNotDone(f);
                }

                // 此刻启用尚未提交：库中不得有任何中心分配
                assertEquals(0, jdbc.queryForObject(
                        "SELECT COUNT(*) FROM site_allocation WHERE experiment_id = ? "
                                + "AND site_id = 'C'", Integer.class, expId));

                // 放行：确认与排队分配按 H2 锁调度继续；先拿到锁的分配看到 INACTIVE 必须 409，
                // 确认提交后拿到锁的分配才允许 201。
                lockConn.commit();

                assertEquals(200, confirmFuture.get(30, TimeUnit.SECONDS));
                int success = 0;
                int rejectedBefore = 0;
                for (int i = 0; i < queued.size(); i++) {
                    int code = queued.get(i).get(30, TimeUnit.SECONDS);
                    assertTrue(code == 201 || code == 409,
                            "排队分配只可能成功或因未启用被拒: " + code);
                    if (code == 201) {
                        success++;
                    } else {
                        rejectedBefore++;
                    }
                }

                // 不变量核对：被拒参与者一条记录都没有；成功数 == 实际记录数且不超过冻结量
                int rows = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM site_allocation WHERE experiment_id = ? "
                                + "AND site_id = 'C'", Integer.class, expId);
                assertEquals(success, rows, "成功次数必须与落库记录一致");
                assertTrue(rows <= 10, "不得超出冻结量 10");
                for (int i = 1; i <= 10; i++) {
                    int ownRows = jdbc.queryForObject(
                            "SELECT COUNT(*) FROM site_allocation WHERE experiment_id = ? "
                                    + "AND site_id = 'C' AND participant_id = ?",
                            Integer.class, expId, "QP" + i);
                    // 每个参与者要么恰好一条（成功），要么零条（启用提交前被拒）
                    assertTrue(ownRows == 0 || ownRows == 1);
                }

                // 补齐到冻结量：剩余 10-success 个新参与者必须全部成功，再下一个 422
                int fill = 10 - success;
                for (int i = 1; i <= fill; i++) {
                    Future<Integer> f = submitAllocate(pool, expId, "C", "QF" + i,
                            "sc2-fill-" + i);
                    assertEquals(201, f.get(30, TimeUnit.SECONDS), "剩余名额应可继续分配");
                }
                Future<Integer> overflow = submitAllocate(pool, expId, "C", "QOVER", "sc2-over");
                assertEquals(422, overflow.get(30, TimeUnit.SECONDS), "启用冻结量不可突破");

                assertEquals(10, jdbc.queryForObject(
                        "SELECT COUNT(*) FROM site_allocation WHERE experiment_id = ? "
                                + "AND site_id = 'C'", Integer.class, expId));
                assertEquals(10, jdbc.queryForObject(
                        "SELECT COUNT(DISTINCT serial_no) FROM site_allocation "
                                + "WHERE experiment_id = ? AND site_id = 'C'",
                        Integer.class, expId));
            } finally {
                pool.shutdown();
                assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void sameRequestIdConcurrentConfirm_exactlyOneActivation_allReplaySameResult()
            throws Exception {
        String expId = "SITE-CONC-3";
        createExperiment(expId, "sc3-exp");
        submitVersionSingleBlock(expId, "C", 4, "sc3-cfg");
        String activationId = applyActivation(expId, "C", 1, "sc3-apply");

        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<ResponseEntity<String>>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> rest.exchange(
                        "/api/activations/" + activationId + "/confirmation", HttpMethod.POST,
                        new HttpEntity<>("{\"configVersion\":1}",
                                headers("confirmer-2", "CONFIRMER", "sc3-confirm-key")),
                        String.class)));
            }
            java.util.Set<String> bodies = new java.util.HashSet<>();
            for (Future<ResponseEntity<String>> future : futures) {
                ResponseEntity<String> resp = future.get(30, TimeUnit.SECONDS);
                assertEquals(200, resp.getStatusCode().value(), "同键同参并发确认均回放成功");
                bodies.add(resp.getBody());
            }
            assertEquals(1, bodies.size(), "所有回放响应体必须一致");
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }

        assertEquals("ACTIVE", jdbc.queryForObject(
                "SELECT status FROM site WHERE experiment_id = ? AND site_id = 'C'",
                String.class, expId));
        // REVIEWER 确认历史恰好一条，双人确认合计两条
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM site_activation_confirmation WHERE activation_id = ? "
                        + "AND confirm_role = 'REVIEWER'", Integer.class, activationId));
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM site_activation_confirmation WHERE activation_id = ?",
                Integer.class, activationId));
        // 启用快照只写一次
        assertEquals(4, jdbc.queryForObject(
                "SELECT quota FROM site_block WHERE experiment_id = ? AND site_id = 'C' "
                        + "AND block_code = 'A'", Integer.class, expId));
    }

    @Test
    void concurrentActivationApply_sameSite_onlyOneSucceeds_restConflict_noStateChangeOnFailure()
            throws Exception {
        String expId = "SITE-CONC-4";
        createExperiment(expId, "sc4-exp");
        submitVersionSingleBlock(expId, "C", 6, "sc4-cfg");

        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<Integer>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                // 同中心并发申请：不同 requestId，中心唯一约束 + 行锁保证仅一条
                final String reqId = "sc4-apply-" + i;
                futures.add(pool.submit(() -> status(rest.exchange(
                        "/api/experiments/" + expId + "/sites/C/activations", HttpMethod.POST,
                        new HttpEntity<>("{\"configVersion\":1}",
                                headers("lead-1", "LEAD", reqId)), String.class))));
            }
            int success = 0;
            int conflict = 0;
            for (Future<Integer> future : futures) {
                int code = future.get(30, TimeUnit.SECONDS);
                assertTrue(code == 201 || code == 409, "只允许成功或冲突: " + code);
                if (code == 201) {
                    success++;
                } else {
                    conflict++;
                }
            }
            assertEquals(1, success, "同一中心仅一条启用申请成功");
            assertEquals(threads - 1, conflict, "其余并发申请全部 409");
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }

        // 中心仍 INACTIVE，仅一条待复核申请与一份快照
        assertEquals("INACTIVE", jdbc.queryForObject(
                "SELECT status FROM site WHERE experiment_id = ? AND site_id = 'C'",
                String.class, expId));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM site_activation WHERE experiment_id = ? AND site_id = 'C'",
                Integer.class, expId));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM site_activation_block b JOIN site_activation a "
                        + "ON b.activation_id = a.activation_id "
                        + "WHERE a.experiment_id = ? AND a.site_id = 'C'", Integer.class, expId));
        // 失败事务不占幂等键
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotent_request WHERE operation = 'site.activation.apply' "
                        + "AND response_status = 201", Integer.class));
    }

    private static void assertFutureNotDone(Future<?> future) throws Exception {
        try {
            future.get(300, TimeUnit.MILLISECONDS);
            throw new AssertionError("持有中心行锁期间该操作不应完成");
        } catch (TimeoutException expected) {
            // 期望：操作仍在锁队列中等待
        }
    }
}
