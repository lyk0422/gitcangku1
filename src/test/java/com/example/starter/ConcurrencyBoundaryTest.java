package com.example.starter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.example.starter.error.BusinessException;
import com.example.starter.service.PackageService;
import com.example.starter.service.SampleService;
import com.example.starter.service.StratumService;
import com.example.starter.web.dto.Requests.AddItemRequest;
import com.example.starter.web.dto.Requests.CollectSampleRequest;
import com.example.starter.web.dto.Requests.CreatePackageRequest;
import com.example.starter.web.dto.Requests.DamageRequest;
import com.example.starter.web.dto.Requests.HandoverRequest;
import com.example.starter.web.dto.Requests.RegisterStratumRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 并发边界测试：真实 H2 库上协调两个并发事务，验证同一样本最多入一个包、
 * 封签异常与交接竞争按提交顺序判定，且失败事务不消耗样本归属。
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:test_conc;MODE=MySQL")
class ConcurrencyBoundaryTest {

    @Autowired
    private StratumService stratumService;

    @Autowired
    private SampleService sampleService;

    @Autowired
    private PackageService packageService;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM package_item");
        jdbc.update("DELETE FROM seal_event");
        jdbc.update("DELETE FROM request_log");
        jdbc.update("DELETE FROM sample");
        jdbc.update("DELETE FROM seal_package");
        jdbc.update("DELETE FROM stratum");
        stratumService.register(new RegisterStratumRequest("SITE-C", "L", "并发测试层位"));
    }

    @Test
    void twoPackagesCompeteForSameSampleOnlyOneWins() throws Exception {
        sampleService.collect(new CollectSampleRequest("req-cc-1", "SMP-C-1", "SITE-C", "L", "G-01"));
        String p1 = packageService.create(new CreatePackageRequest("SITE-C", "L")).packageNo();
        String p2 = packageService.create(new CreatePackageRequest("SITE-C", "L")).packageNo();

        List<Outcome> outcomes = runConcurrently(
                () -> addItem(p1, "SMP-C-1"), () -> addItem(p2, "SMP-C-1"));

        long successes = outcomes.stream().filter(o -> o.error() == null).count();
        assertEquals(1, successes, "两个包争夺同一样本时最多一个成功");
        Outcome loser = outcomes.stream().filter(o -> o.error() != null).findFirst().orElseThrow();
        assertEquals(HttpStatus.CONFLICT, loser.error().status());
        assertEquals("SAMPLE_ALREADY_ASSIGNED", loser.error().code());
        Integer itemRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM package_item WHERE sample_id = "
                        + "(SELECT id FROM sample WHERE sample_no = 'SMP-C-1')", Integer.class);
        assertEquals(1, itemRows, "样本只能写入一个包的清单");
        String status = jdbc.queryForObject(
                "SELECT status FROM sample WHERE sample_no = 'SMP-C-1'", String.class);
        assertEquals("IN_PACKAGE", status);
    }

    @Test
    void damageAndHandoverRaceDecidedByCommitOrder() throws Exception {
        sampleService.collect(new CollectSampleRequest("req-cc-2", "SMP-C-2", "SITE-C", "L", "G-02"));
        String packageNo = packageService.create(new CreatePackageRequest("SITE-C", "L")).packageNo();
        packageService.addItem(packageNo, new AddItemRequest("SMP-C-2"));

        List<Outcome> outcomes = runConcurrently(
                () -> handover(packageNo), () -> damage(packageNo));

        long successes = outcomes.stream().filter(o -> o.error() == null).count();
        assertEquals(1, successes, "交接与封签异常竞争只能一个先生效");
        String finalStatus = jdbc.queryForObject(
                "SELECT status FROM seal_package WHERE package_no = ?", String.class, packageNo);
        Outcome loser = outcomes.stream().filter(o -> o.error() != null).findFirst().orElseThrow();
        if ("HANDED_OVER".equals(finalStatus)) {
            assertEquals(HttpStatus.CONFLICT, loser.error().status(),
                    "交接已生效后报损必须冲突");
            assertEquals("PACKAGE_ALREADY_HANDED_OVER", loser.error().code());
        } else {
            assertEquals("ABNORMAL", finalStatus);
            assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, loser.error().status(),
                    "封签异常先生效后交接必须被拒绝");
            assertEquals("PACKAGE_NOT_HANDOVERABLE", loser.error().code());
        }
        Integer eventCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM seal_event WHERE package_id = "
                        + "(SELECT id FROM seal_package WHERE package_no = ?)", Integer.class, packageNo);
        assertEquals(1, eventCount, "只有胜出的操作能留下封签记录");
    }

    private Outcome addItem(String packageNo, String sampleNo) {
        try {
            packageService.addItem(packageNo, new AddItemRequest(sampleNo));
            return new Outcome(null);
        } catch (BusinessException e) {
            return new Outcome(e);
        }
    }

    private Outcome handover(String packageNo) {
        try {
            packageService.handover(packageNo, new HandoverRequest("req-race-h", "SEAL-RACE", null));
            return new Outcome(null);
        } catch (BusinessException e) {
            return new Outcome(e);
        }
    }

    private Outcome damage(String packageNo) {
        try {
            packageService.reportDamage(packageNo, new DamageRequest("封签破损"));
            return new Outcome(null);
        } catch (BusinessException e) {
            return new Outcome(e);
        }
    }

    private List<Outcome> runConcurrently(Callable<Outcome> first, Callable<Outcome> second)
            throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            Callable<Outcome> wrapFirst = () -> {
                ready.countDown();
                assertTrue(start.await(10, TimeUnit.SECONDS));
                return first.call();
            };
            Callable<Outcome> wrapSecond = () -> {
                ready.countDown();
                assertTrue(start.await(10, TimeUnit.SECONDS));
                return second.call();
            };
            Future<Outcome> f1 = executor.submit(wrapFirst);
            Future<Outcome> f2 = executor.submit(wrapSecond);
            assertTrue(ready.await(10, TimeUnit.SECONDS), "两个线程必须同时就绪");
            start.countDown();
            return List.of(f1.get(15, TimeUnit.SECONDS), f2.get(15, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    private record Outcome(BusinessException error) {
    }
}