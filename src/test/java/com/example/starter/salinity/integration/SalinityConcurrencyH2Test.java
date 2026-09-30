package com.example.starter.salinity.integration;

import com.example.starter.salinity.dto.AdoptRequest;
import com.example.starter.salinity.dto.SealResult;
import com.example.starter.salinity.dto.SealedProfileView;
import com.example.starter.salinity.dto.SubmitSampleRequest;
import com.example.starter.salinity.service.SalinityService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实 H2 上的并发边界测试：质控替换与封存竞争、同位置并发采纳、同参并发提交。
 * 使用 CyclicBarrier 协调真实并发操作，全部带超时，最终以数据库业务状态判定，不依赖睡眠。
 */
@SpringBootTest
class SalinityConcurrencyH2Test {

    private static final Instant COLLECTED = Instant.parse("2026-09-30T08:00:00Z");

    @Autowired
    private SalinityService service;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final AtomicInteger seq = new AtomicInteger();

    private String uniqueVoyage(String prefix) {
        return prefix + "-" + seq.incrementAndGet() + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private SubmitSampleRequest sampleReq(String voyage, String station, int depth,
                                          String salinity, String no, String req) {
        return new SubmitSampleRequest(voyage, station, depth, new BigDecimal(salinity),
                COLLECTED, no, req);
    }

    private void prepareSealable(String voyage, String station, String tag) {
        service.submitSample(sampleReq(voyage, station, 0, "35.000", "NO-" + tag + "-0", "REQ-" + tag + "-0"));
        service.submitSample(sampleReq(voyage, station, 50, "34.500", "NO-" + tag + "-50", "REQ-" + tag + "-50"));
        service.submitSample(sampleReq(voyage, station, 100, "34.000", "NO-" + tag + "-100", "REQ-" + tag + "-100"));
        service.adopt(new AdoptRequest(voyage, "NO-" + tag + "-0", "ADOPT-" + tag + "-0"));
        service.adopt(new AdoptRequest(voyage, "NO-" + tag + "-50", "ADOPT-" + tag + "-50"));
        service.adopt(new AdoptRequest(voyage, "NO-" + tag + "-100", "ADOPT-" + tag + "-100"));
    }

    @Test
    @DisplayName("采纳与封存竞争：封存只采用一个一致版本集合，封存后获采纳的数据不会混入剖面")
    void adoptVsSealRace() throws Exception {
        int pairs = 16;
        // 每对竞争使用独立线程池与二方屏障，保证两个真实事务同时起跑且互不阻塞调度。
        for (int i = 0; i < pairs; i++) {
            String voyage = uniqueVoyage("RACE");
            prepareSealable(voyage, "S", "race" + i);
            // 0 米额外重测一版 v2：封存若先提交则固化 v1，采纳若先提交则固化 v2。
            service.submitSample(sampleReq(voyage, "S", 0, "35.900",
                    "NO-race" + i + "-0v2", "REQ-race" + i + "-0v2"));
            String v1No = "NO-race" + i + "-0";
            String v2No = "NO-race" + i + "-0v2";
            String v2AdoptReq = "ADOPT-race" + i + "-0v2";

            CyclicBarrier barrier = new CyclicBarrier(2);
            ExecutorService pairPool = Executors.newFixedThreadPool(2);
            try {
                Future<?> sealFuture = pairPool.submit(() -> {
                    awaitBarrier(barrier);
                    service.seal(voyage);
                });
                Future<?> adoptFuture = pairPool.submit(() -> {
                    awaitBarrier(barrier);
                    service.adopt(new AdoptRequest(voyage, v2No, v2AdoptReq));
                });

                boolean adoptThrew = false;
                try {
                    adoptFuture.get(30, TimeUnit.SECONDS);
                } catch (Exception ex) {
                    adoptThrew = true;
                }
                sealFuture.get(30, TimeUnit.SECONDS);

                // 重复封存为幂等读取，返回的就是竞争中固化的那一份剖面。
                SealResult finalResult = service.seal(voyage);
                SealedProfileView frozenZero = finalResult.sealedProfile().stream()
                        .filter(p -> p.station().equals("S") && p.depthM() == 0)
                        .findFirst().orElseThrow();
                Integer adoptedCount = jdbcTemplate.queryForObject("""
                                SELECT COUNT(*) FROM samples
                                WHERE voyage_code = ? AND station = 'S' AND depth_m = 0 AND adopted = TRUE
                                """,
                        Integer.class, voyage);

                if (frozenZero.sampleNo().equals(v1No)) {
                    // 封存先提交：v2 的采纳必须被拒，冻结保留 v1，剖面不混入封存后才采纳的版本。
                    assertThat(adoptThrew).as("封存先提交时替换采纳必须失败").isTrue();
                } else {
                    // 采纳先提交：封存必须固化 v2，且采纳操作成功。
                    assertThat(frozenZero.sampleNo()).isEqualTo(v2No);
                    assertThat(adoptThrew).as("采纳先提交时应成功").isFalse();
                }
                // 无论谁先提交：每个位置最终仅一条采纳版本，站位剖面恰好三条且深度齐全。
                assertThat(adoptedCount).isEqualTo(1);
                assertThat(finalResult.sealedProfile()).extracting(SealedProfileView::depthM)
                        .containsExactly(0, 50, 100);
            } finally {
                pairPool.shutdownNow();
                assertThat(pairPool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
            }
        }
    }

    @Test
    @DisplayName("同位置并发采纳不同版本：唯一采纳约束与行锁保证最终仅一条采纳版本")
    void concurrentAdoptionSingleWinner() throws Exception {
        String voyage = uniqueVoyage("CADOPT");
        prepareSealable(voyage, "S", "ca");
        service.submitSample(sampleReq(voyage, "S", 50, "34.600", "NO-ca-50v2", "REQ-ca-50v2"));
        service.submitSample(sampleReq(voyage, "S", 50, "34.700", "NO-ca-50v3", "REQ-ca-50v3"));

        CyclicBarrier barrier = new CyclicBarrier(3);
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (String no : new String[]{"NO-ca-50", "NO-ca-50v2", "NO-ca-50v3"}) {
                futures.add(pool.submit(() -> {
                    awaitBarrier(barrier);
                    service.adopt(new AdoptRequest(voyage, no, "ADOPT-" + no));
                }));
            }
            for (Future<?> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        Integer adoptedCount = jdbcTemplate.queryForObject("""
                        SELECT COUNT(*) FROM samples
                        WHERE voyage_code = ? AND station = 'S' AND depth_m = 50 AND adopted = TRUE
                        """,
                Integer.class, voyage);
        assertThat(adoptedCount).as("同一位置并发采纳后只能有一条采纳版本").isEqualTo(1);

        // 每个采纳版本都可再封存成功，证明最终采纳集合一致完整。
        assertThat(service.seal(voyage).sealedProfile()).hasSize(3);
    }

    @Test
    @DisplayName("同参并发提交：一个创建一个重放，仅落一条样本且无异常")
    void concurrentSameRequestSubmit() throws Exception {
        String voyage = uniqueVoyage("CSUB");
        SubmitSampleRequest request = sampleReq(voyage, "S", 0, "35.000", "NO-cs-0", "REQ-cs-0");

        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Boolean>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(pool.submit(() -> {
                    awaitBarrier(barrier);
                    return service.submitSample(request).replayed();
                }));
            }
            boolean first = futures.get(0).get(30, TimeUnit.SECONDS);
            boolean second = futures.get(1).get(30, TimeUnit.SECONDS);
            assertThat(first ^ second).as("恰有一个请求创建样本，另一个重放").isTrue();
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        Integer rows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM samples WHERE request_id = ?", Integer.class, "REQ-cs-0");
        assertThat(rows).isEqualTo(1);
    }

    /**
     * 在工作线程内等待起跑线，超时/中断统一转为运行时异常使对应任务失败。
     */
    private static void awaitBarrier(CyclicBarrier barrier) {
        try {
            barrier.await(30, TimeUnit.SECONDS);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }
}
