package com.example.starter;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.example.starter.common.BizException;
import com.example.starter.pkg.PackageDtos.AnomalyRequest;
import com.example.starter.pkg.PackageDtos.HandoverRequest;
import com.example.starter.pkg.PackageDtos.SealRequest;
import com.example.starter.pkg.PackageService;
import com.example.starter.sample.SampleDtos.CollectRequest;
import com.example.starter.sample.SampleService;
import com.example.starter.stratum.StratumDtos.RegisterRequest;
import com.example.starter.stratum.StratumService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 并发边界：两个包争夺同一样本最多一个成功；封签异常与交接竞争按提交顺序判定且终态一致。
 * 使用真实线程协调提交，统一设置超时，最终以数据库状态核对业务结果。
 */
@SpringBootTest
class ConcurrencyTest {

    @Autowired
    private StratumService strata;

    @Autowired
    private SampleService samples;

    @Autowired
    private PackageService packages;

    @Autowired
    private JdbcClient jdbc;

    private ExecutorService pool;

    @BeforeEach
    void setUp() {
        pool = Executors.newFixedThreadPool(2);
        jdbc.sql("DELETE FROM package_item").update();
        jdbc.sql("DELETE FROM seal_event").update();
        jdbc.sql("DELETE FROM request_log").update();
        jdbc.sql("DELETE FROM sample").update();
        jdbc.sql("DELETE FROM seal_package").update();
        jdbc.sql("DELETE FROM stratum").update();
        strata.register(new RegisterRequest("SITE-C", "L", "并发测试层位"));
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        pool.shutdownNow();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "线程池应在超时内退出");
    }

    private void collect(String sampleNo) {
        samples.collect(new CollectRequest("REQ-C-" + sampleNo, sampleNo, "SITE-C", "L", "G-01"));
    }

    private SealRequest sealReq(String requestId, String packageNo, List<String> sampleNos) {
        return new SealRequest(requestId, packageNo, "SITE-C", "L", "SEAL-" + packageNo, sampleNos, "合成操作员甲");
    }

    /** 执行业务调用并取 HTTP 状态码：业务拒绝以 BizException 抛出，在此折算为对应状态。 */
    private static int statusOf(Supplier<com.example.starter.common.StoredResponse> call) {
        try {
            return call.get().status();
        } catch (BizException e) {
            return e.status().value();
        }
    }

    /** 两个线程在同一起点后并发执行，返回各自的 HTTP 状态码。 */
    private int[] race(Supplier<com.example.starter.common.StoredResponse> a,
            Supplier<com.example.starter.common.StoredResponse> b) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<Integer> fa = pool.submit(() -> {
            ready.countDown();
            assertTrue(start.await(10, TimeUnit.SECONDS));
            return statusOf(a);
        });
        Future<Integer> fb = pool.submit(() -> {
            ready.countDown();
            assertTrue(start.await(10, TimeUnit.SECONDS));
            return statusOf(b);
        });
        assertTrue(ready.await(10, TimeUnit.SECONDS), "两个线程应就绪");
        start.countDown();
        return new int[] {fa.get(20, TimeUnit.SECONDS), fb.get(20, TimeUnit.SECONDS)};
    }

    @Test
    void twoPackagesCompetingForSameSampleOnlyOneWins() throws Exception {
        collect("C-01");
        collect("C-02");
        collect("C-03");
        int[] res = race(
                () -> packages.seal(sealReq("REQ-PA", "PKG-PA", List.of("C-01", "C-02"))),
                () -> packages.seal(sealReq("REQ-PB", "PKG-PB", List.of("C-01", "C-03"))));
        int created = (res[0] == 201 ? 1 : 0) + (res[1] == 201 ? 1 : 0);
        int conflicted = (res[0] == 409 ? 1 : 0) + (res[1] == 409 ? 1 : 0);
        assertEquals(1, created, "争夺同一样本时最多一个封存成功");
        assertEquals(1, conflicted, "失败方应返回 409");
        assertEquals(1, jdbc.sql("SELECT COUNT(*) FROM seal_package").query(Long.class).single(),
                "失败事务回滚后只保留一个封存包");
        assertEquals(1, jdbc.sql("SELECT COUNT(*) FROM package_item i JOIN sample s ON s.id=i.sample_id WHERE s.sample_no='C-01'")
                .query(Long.class).single(), "样本 C-01 只能归属一个包");
        Long owner = jdbc.sql("SELECT package_id FROM sample WHERE sample_no='C-01'").query(Long.class).single();
        assertTrue(owner != null && owner > 0, "样本 C-01 必须归属成功方");
    }

    @Test
    void anomalyAndHandoverRaceSettlesInSubmissionOrder() throws Exception {
        collect("D-01");
        packages.seal(sealReq("REQ-PH", "PKG-PH", List.of("D-01")));
        int[] res = race(
                () -> packages.handover("PKG-PH", new HandoverRequest("REQ-HO-C", "合成交接员乙", "合成实验室丙")),
                () -> packages.reportAnomaly("PKG-PH", new AnomalyRequest("REQ-AN-C", "封签受压开裂", "合成巡检员丁")));
        int handover = res[0];
        int anomaly = res[1];
        assertEquals(201, anomaly, "异常记录只追加，始终可上报");
        String status = jdbc.sql("SELECT status FROM seal_package WHERE package_no='PKG-PH'")
                .query(String.class).single();
        List<String> events = jdbc.sql("SELECT e.event_type FROM seal_event e JOIN seal_package p ON p.id=e.package_id"
                + " WHERE p.package_no='PKG-PH' ORDER BY e.id").query(String.class).list();
        if (handover == 200) {
            assertEquals("HANDED_OVER", status, "交接先提交则包为已交接");
            assertTrue(events.contains("HANDED_OVER") && events.contains("SEAL_BROKEN"),
                    "交接成功后异常仍应追加记录: " + events);
        } else {
            assertEquals(422, handover, "异常先提交则交接被拒绝");
            assertEquals("SEAL_BROKEN", status);
            assertTrue(events.contains("SEAL_BROKEN") && !events.contains("HANDED_OVER"),
                    "破损后不得出现交接事件: " + events);
        }
        assertEquals(1, jdbc.sql("SELECT COUNT(*) FROM seal_package WHERE package_no='PKG-PH'")
                .query(Long.class).single());
    }
}