package com.example.starter.consent;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.starter.consent.dto.DelegationRequest;
import com.example.starter.consent.dto.DelegationRevokeRequest;
import com.example.starter.consent.dto.RecordWriteRequest;

/**
 * 委托并发边界测试（真实 H2 数据库）：撤销/续建/写入按提交顺序串行化，
 * 过期快照不能穿透，结果必须与最终数据一致。
 */
@SpringBootTest
@Import(TestClockConfiguration.class)
class DelegationConcurrencyTest {

    private static final Instant T0 = TestClockConfiguration.BASE_TIME;
    private static final Instant EXPIRY = T0.plusSeconds(3600);

    @Autowired
    private ConsentService consentService;

    @Autowired
    private DelegationService delegationService;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM consent_record");
        jdbc.update("DELETE FROM consent_delegation");
        jdbc.update("DELETE FROM consent_grant");
        jdbc.update("DELETE FROM idempotency_request");
    }

    private void seedChain() {
        consentService.grant(new com.example.starter.consent.dto.GrantRequest(
                "g1", "alice", Purpose.RESEARCH, EXPIRY));
        delegationService.delegate(new DelegationRequest(
                "d1", "k1", "alice", Purpose.RESEARCH, 1, "alice", "p1", T0.plusSeconds(1800)));
        delegationService.delegate(new DelegationRequest(
                "d2", "k2", "alice", Purpose.RESEARCH, 1, "p1", "p2", T0.plusSeconds(900)));
    }

    @Test
    void edgeRevokeAndWriteInterleaveByCommitOrderNeverPenetrates() throws Exception {
        // 重复多轮以覆盖两种提交顺序（撤销先提交 / 写入先提交）
        for (int round = 0; round < 6; round++) {
            cleanTables();
            seedChain();
            final int r = round;
            int threads = 2;
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch start = new CountDownLatch(1);

            Callable<String> revokeTask = () -> {
                ready.countDown();
                start.await();
                delegationService.revoke(new DelegationRevokeRequest("rd-" + r, "k1"));
                return "REVOKED";
            };
            Callable<String> writeTask = () -> {
                ready.countDown();
                start.await();
                try {
                    consentService.write(new RecordWriteRequest(
                            "w-" + r, "alice", Purpose.RESEARCH, "rec-" + r, "data",
                            List.of("p1", "p2"), List.of(1, 1)));
                    return "WRITTEN";
                } catch (ApiException e) {
                    assertThat(e.getStatus().value()).isEqualTo(403);
                    return "REJECTED";
                }
            };

            Future<String> fRevoke = pool.submit(revokeTask);
            Future<String> fWrite = pool.submit(writeTask);
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            String revokeResult = fRevoke.get(20, TimeUnit.SECONDS);
            String writeResult = fWrite.get(20, TimeUnit.SECONDS);
            pool.shutdown();

            assertThat(revokeResult).isEqualTo("REVOKED");
            int recordCount = count("SELECT COUNT(*) FROM consent_record");
            if ("REJECTED".equals(writeResult)) {
                // 撤销先提交：写入必须失败且不写数据
                assertThat(recordCount).isZero();
            } else {
                // 写入先提交：恰有一条记录，之后撤销只影响后续写入
                assertThat(writeResult).isEqualTo("WRITTEN");
                assertThat(recordCount).isEqualTo(1);
            }
            // 撤销后再写必然被拒：无过期快照穿透
            ApiException expected = catchApi(() -> consentService.write(new RecordWriteRequest(
                    "w-after-" + r, "alice", Purpose.RESEARCH, "rec-after-" + r, "data",
                    List.of("p1", "p2"), List.of(1, 1))));
            assertThat(expected).isNotNull();
            assertThat(expected.getStatus().value()).isEqualTo(403);
            assertThat(count("SELECT COUNT(*) FROM consent_record")).isEqualTo(recordCount);
        }
    }

    @Test
    void concurrentDelegationCreationForSameEdgeProducesOneActiveEdge() throws Exception {
        consentService.grant(new com.example.starter.consent.dto.GrantRequest(
                "g1", "alice", Purpose.RESEARCH, EXPIRY));
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<String>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int idx = i;
            tasks.add(() -> {
                ready.countDown();
                start.await();
                try {
                    delegationService.delegate(new DelegationRequest(
                            "dc-" + idx, "kc-" + idx, "alice", Purpose.RESEARCH, 1,
                            "alice", "p1", T0.plusSeconds(1800)));
                    return "OK";
                } catch (ApiException e) {
                    assertThat(e.getStatus().value()).isEqualTo(409);
                    return "CONFLICT";
                }
            });
        }
        List<Future<String>> futures = new ArrayList<>();
        for (Callable<String> task : tasks) {
            futures.add(pool.submit(task));
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        int ok = 0;
        int conflict = 0;
        for (Future<String> future : futures) {
            String result = future.get(20, TimeUnit.SECONDS);
            if ("OK".equals(result)) {
                ok++;
            } else {
                conflict++;
            }
        }
        pool.shutdown();
        assertThat(ok).isEqualTo(1);
        assertThat(conflict).isEqualTo(threads - 1);
        assertThat(count("SELECT COUNT(*) FROM consent_delegation WHERE status = 'ACTIVE'")).isEqualTo(1);
    }

    @Test
    void concurrentChainWritesByDifferentProcessorsAllSucceed() throws Exception {
        seedChain();
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int idx = i;
            tasks.add(() -> {
                ready.countDown();
                start.await();
                // 一半经 p1、一半经 p2 写不同记录
                List<String> path = idx % 2 == 0 ? List.of("p1") : List.of("p1", "p2");
                List<Integer> versions = idx % 2 == 0 ? List.of(1) : List.of(1, 1);
                consentService.write(new RecordWriteRequest(
                        "cw-" + idx, "alice", Purpose.RESEARCH, "rec-" + idx, "data" + idx,
                        path, versions));
                return idx;
            });
        }
        List<Future<Integer>> futures = new ArrayList<>();
        for (Callable<Integer> task : tasks) {
            futures.add(pool.submit(task));
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        for (Future<Integer> future : futures) {
            future.get(20, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertThat(count("SELECT COUNT(*) FROM consent_record")).isEqualTo(threads);
    }

    private int count(String sql) {
        Integer cnt = jdbc.queryForObject(sql, Integer.class);
        return cnt == null ? 0 : cnt;
    }

    private ApiException catchApi(RunnableThrowing runnable) {
        try {
            runnable.run();
            return null;
        } catch (ApiException e) {
            return e;
        } catch (Exception e) {
            throw new AssertionError("期望 ApiException，实际: " + e, e);
        }
    }

    @FunctionalInterface
    private interface RunnableThrowing {
        void run() throws Exception;
    }
}
