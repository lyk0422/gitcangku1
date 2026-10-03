package com.example.starter.coral;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 真实H2并发边界测试：容量竞争、死亡释放与登记竞争、封存与复核竞争。
 * 用启动门闩协调真实并发，全部断言最终业务结果与数据，带超时。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:coral_test_conc;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CoralConcurrencyTest {

    @Autowired
    private CoralService service;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM review");
        jdbc.update("DELETE FROM plot_seal");
        jdbc.update("DELETE FROM transplant");
        jdbc.update("DELETE FROM plot");
    }

    @AfterAll
    void shutdownDb() {
        try {
            jdbc.execute("SHUTDOWN");
        } catch (Exception ignored) {
            // 库已随上下文关闭
        }
    }

    private long insertPlot(String code) {
        jdbc.update("INSERT INTO plot (plot_code, capacity, status) VALUES (?, 12, 'ACTIVE')",
                code);
        return jdbc.queryForObject("SELECT id FROM plot WHERE plot_code = ?", Long.class, code);
    }

    private void insertTransplant(String req, String plant, long plotId, String status) {
        jdbc.update(
                "INSERT INTO transplant (request_id, plant_code, nursery, plot_id,"
                        + " transplanted_at, status) VALUES (?, ?, '合成苗圃C', ?,"
                        + " TIMESTAMP '2026-09-01 00:00:00', ?)",
                req, plant, plotId, status);
    }

    private long activeCount(long plotId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM transplant WHERE plot_id = ? AND status <> 'DEAD'",
                Long.class, plotId);
    }

    /** 并发执行两个任务，返回各自结果；异常原样上抛。 */
    private <T> List<T> runConcurrently(Callable<T> a, Callable<T> b) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch gate = new CountDownLatch(1);
            Callable<T> gatedA = () -> {
                gate.await();
                return a.call();
            };
            Callable<T> gatedB = () -> {
                gate.await();
                return b.call();
            };
            Future<T> fa = pool.submit(gatedA);
            Future<T> fb = pool.submit(gatedB);
            gate.countDown();
            return List.of(fa.get(30, TimeUnit.SECONDS), fb.get(30, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    /** 结果包装：成功值或业务异常。 */
    private record Outcome<T>(T value, BizException error) {
        static <T> Outcome<T> of(Callable<T> task) {
            try {
                return new Outcome<>(task.call(), null);
            } catch (BizException e) {
                return new Outcome<>(null, e);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        boolean succeeded() {
            return error == null;
        }
    }

    @Test
    void concurrentRegistrationsNeverExceedCapacity() throws Exception {
        long plotId = insertPlot("C-CAP");
        for (int i = 0; i < 11; i++) {
            insertTransplant("cap-seed-" + i, "C-CAP-P" + i, plotId, "ALIVE");
        }
        List<Outcome<TransplantView>> results = runConcurrently(
                () -> Outcome.of(() -> service.registerTransplant(
                        new RegisterTransplantRequest("cap-r1", "C-CAP-X", "合成苗圃C", "C-CAP"))),
                () -> Outcome.of(() -> service.registerTransplant(
                        new RegisterTransplantRequest("cap-r2", "C-CAP-Y", "合成苗圃C", "C-CAP"))));

        long successes = results.stream().filter(Outcome::succeeded).count();
        assertEquals(1, successes, "仅剩1个空位时两笔并发登记只能成功一笔");
        BizException failure = results.stream().filter(r -> !r.succeeded())
                .findFirst().orElseThrow().error();
        assertEquals(409, failure.status());
        assertEquals(12, activeCount(plotId), "活跃移植体不得超过12株");
    }

    @Test
    void deathReleaseAndNewTransplantAreSerializedByCommitOrder() throws Exception {
        long plotId = insertPlot("C-REL");
        for (int i = 0; i < 12; i++) {
            insertTransplant("rel-seed-" + i, "C-REL-P" + i, plotId, "ALIVE");
        }
        List<Outcome<Object>> results = runConcurrently(
                () -> Outcome.of(() -> service.review("C-REL-P0",
                        new ReviewRequest("rel-rev", TransplantStatus.DEAD))),
                () -> Outcome.of(() -> service.registerTransplant(
                        new RegisterTransplantRequest("rel-new", "C-REL-NEW", "合成苗圃C",
                                "C-REL"))));

        Outcome<?> reviewOutcome = results.get(0);
        Outcome<?> registerOutcome = results.get(1);
        assertTrue(reviewOutcome.succeeded(), "死亡复核不应失败");
        long active = activeCount(plotId);
        if (registerOutcome.succeeded()) {
            // 死亡先提交：释放的容量被新植株占用
            assertEquals(12, active);
        } else {
            // 登记先提交：容量仍满被拒，死亡后剩11株活跃
            assertEquals(409, registerOutcome.error().status());
            assertEquals(11, active);
        }
        assertTrue(active <= 12, "任何提交顺序下都不得超过12株");
        long deadCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM transplant WHERE plot_id = ? AND status = 'DEAD'",
                Long.class, plotId);
        assertEquals(1, deadCount);
    }

    @Test
    void sealAndReviewRaceYieldsOneConsistentSnapshot() throws Exception {
        long plotId = insertPlot("C-SEAL");
        insertTransplant("cs-0", "C-SEAL-P0", plotId, "ALIVE");
        insertTransplant("cs-1", "C-SEAL-P1", plotId, "ALIVE");
        insertTransplant("cs-2", "C-SEAL-P2", plotId, "ALIVE");

        List<Outcome<Object>> results = runConcurrently(
                () -> Outcome.of(() -> service.sealPlot("C-SEAL", new SealRequest("cs-seal"))),
                () -> Outcome.of(() -> service.review("C-SEAL-P0",
                        new ReviewRequest("cs-rev", TransplantStatus.DEAD))));

        Outcome<?> sealOutcome = results.get(0);
        Outcome<?> reviewOutcome = results.get(1);
        assertTrue(sealOutcome.succeeded(), "无待复核植株时封存应成功");

        String finalStatus = jdbc.queryForObject(
                "SELECT status FROM transplant WHERE plant_code = 'C-SEAL-P0'",
                String.class);
        Integer deadInSnapshot = jdbc.queryForObject(
                "SELECT dead_count FROM plot_seal WHERE plot_id = ?", Integer.class, plotId);
        Integer plantRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM plot_seal WHERE plot_id = ?", Integer.class, plotId);
        assertEquals(1, plantRows, "一个样方只能形成一份封存快照");

        if (reviewOutcome.succeeded()) {
            // 复核先提交：快照必须包含这株死亡记录
            assertEquals("DEAD", finalStatus);
            assertEquals(1, deadInSnapshot, "复核先提交时快照必须反映死亡");
        } else {
            // 封存先提交：复核被拒，快照与现状均为存活
            assertEquals(409, reviewOutcome.error().status());
            assertEquals("ALIVE", finalStatus);
            assertEquals(0, deadInSnapshot, "封存先提交时快照不得混入后续复核");
        }
    }

    @Test
    void concurrentSamePlantRegistrationKeepsSingleRecord() throws Exception {
        long plotId = insertPlot("C-DUP");
        List<Outcome<TransplantView>> results = runConcurrently(
                () -> Outcome.of(() -> service.registerTransplant(
                        new RegisterTransplantRequest("dup-r1", "C-DUP-P", "合成苗圃C", "C-DUP"))),
                () -> Outcome.of(() -> service.registerTransplant(
                        new RegisterTransplantRequest("dup-r2", "C-DUP-P", "合成苗圃C", "C-DUP"))));

        long successes = results.stream().filter(Outcome::succeeded).count();
        assertEquals(1, successes, "同一植株并发登记只能成功一笔");
        Long rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM transplant WHERE plant_code = 'C-DUP-P'", Long.class);
        assertEquals(1, rows);
        assertEquals(1, activeCount(plotId));
    }
}