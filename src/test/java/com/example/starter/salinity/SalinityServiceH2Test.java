package com.example.starter.salinity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.starter.salinity.command.AddErratumCommand;
import com.example.starter.salinity.command.AdoptSampleCommand;
import com.example.starter.salinity.command.SealVoyageCommand;
import com.example.starter.salinity.command.SubmitSampleCommand;
import com.example.starter.salinity.exception.BusinessRuleException;
import com.example.starter.salinity.exception.ConflictException;
import com.example.starter.salinity.exception.ResourceNotFoundException;
import com.example.starter.salinity.po.SalinitySample;
import com.example.starter.salinity.repo.SalinityRepository;
import com.example.starter.salinity.service.SalinityService;
import com.example.starter.salinity.view.AdoptedSampleView;
import com.example.starter.salinity.view.FrozenProfilePointView;
import com.example.starter.salinity.view.SampleView;
import com.example.starter.salinity.view.VoyageDataView;

/**
 * 真实 H2 内存库（MODE=MySQL）上的业务集成测试：主流程、拒绝、幂等与并发边界。
 * 每例清理场景数据，固定时钟保证封存/勘误时刻可断言。
 */
@SpringBootTest
class SalinityServiceH2Test {

    static final Instant FIXED_NOW = Instant.parse("2026-09-15T08:30:00Z");

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
        }
    }

    @Autowired
    private SalinityService service;

    @Autowired
    private SalinityRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanTables() {
        jdbcTemplate.update("DELETE FROM profile_erratum");
        jdbcTemplate.update("DELETE FROM sealed_profile_point");
        jdbcTemplate.update("DELETE FROM salinity_sample");
        jdbcTemplate.update("DELETE FROM voyage");
    }

    private SubmitSampleCommand sample(String voyage, String station, int depth, String salinity,
                                       String sampleNo, String requestId) {
        return new SubmitSampleCommand(
                voyage, station, depth, new BigDecimal(salinity),
                Instant.parse("2026-09-15T0" + Math.min(depth / 50 + 1, 9) + ":00:00Z"),
                sampleNo, requestId);
    }

    private void adopt(String voyage, String sampleNo) {
        service.adopt(new AdoptSampleCommand(voyage, sampleNo));
    }

    private void submitAdoptStandard(String voyage, String station, String salt0, String salt50, String salt100,
                                     String prefix) {
        service.submit(sample(voyage, station, 0, salt0, prefix + "-0", "REQ-" + prefix + "-0"));
        service.submit(sample(voyage, station, 50, salt50, prefix + "-50", "REQ-" + prefix + "-50"));
        service.submit(sample(voyage, station, 100, salt100, prefix + "-100", "REQ-" + prefix + "-100"));
        adopt(voyage, prefix + "-0");
        adopt(voyage, prefix + "-50");
        adopt(voyage, prefix + "-100");
    }

    @Test
    void fullWorkflow_retestAdoption_sealFreeze_erratumKeepsProfile() {
        String voyage = "V-MAIN";
        submitAdoptStandard(voyage, "S", "35.100", "34.800", "34.000", "S-MAIN");

        // 同一位置重测：原始样本保留，先为未采纳。
        SampleView retest = service.submit(sample(voyage, "S", 50, "34.765", "S-MAIN-50-R", "REQ-R1"));
        assertThat(retest.adopted()).isFalse();
        assertThat(service.query(voyage).samples()).hasSize(4);

        // 质控采纳重测：该深度仍只有一个采纳版本，旧版本被登记替换。
        adopt(voyage, "S-MAIN-50-R");
        VoyageDataView beforeSeal = service.query(voyage);
        assertThat(beforeSeal.adopted()).extracting(AdoptedSampleView::sampleNo)
                .containsExactly("S-MAIN-0", "S-MAIN-50-R", "S-MAIN-100");
        SampleView old50 = beforeSeal.samples().stream()
                .filter(s -> s.sampleNo().equals("S-MAIN-50")).findFirst().orElseThrow();
        assertThat(old50.adopted()).isFalse();
        assertThat(old50.supersededBy()).isEqualTo("S-MAIN-50-R");
        assertThat(beforeSeal.frozenProfile()).isEmpty();

        // 封存：按深度升序固化 0、50、100。
        service.seal(new SealVoyageCommand(voyage));
        VoyageDataView sealed = service.query(voyage);
        assertThat(sealed.sealed()).isTrue();
        assertThat(sealed.sealedUtc()).isEqualTo(FIXED_NOW);
        assertThat(sealed.frozenProfile()).hasSize(3);
        assertThat(sealed.frozenProfile()).extracting(FrozenProfilePointView::depthM)
                .containsExactly(0, 50, 100);
        FrozenProfilePointView frozen50 = sealed.frozenProfile().get(1);
        assertThat(frozen50.sampleNo()).isEqualTo("S-MAIN-50-R");
        assertThat(frozen50.salinityPsu()).isEqualByComparingTo("34.765");

        // 封存后替换采纳版本被拒；勘误只追加，原剖面继续可查且不变。
        service.submit(sample(voyage, "S", 50, "34.700", "S-MAIN-50-R2", "REQ-R2"));
        assertThatThrownBy(() -> adopt(voyage, "S-MAIN-50-R2"))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> service.seal(new SealVoyageCommand(voyage)))
                .isInstanceOf(ConflictException.class);

        service.addErratum(new AddErratumCommand(voyage, "50 米读数存疑，剖面维持封存值"));
        VoyageDataView afterErratum = service.query(voyage);
        assertThat(afterErratum.errata()).hasSize(1);
        assertThat(afterErratum.errata().get(0).note()).isEqualTo("50 米读数存疑，剖面维持封存值");
        assertThat(afterErratum.errata().get(0).refSealedUtc()).isEqualTo(FIXED_NOW);
        assertThat(afterErratum.frozenProfile()).isEqualTo(sealed.frozenProfile());
        assertThat(afterErratum.samples()).hasSize(5);
    }

    @Test
    void outOfRangeValuesRejected_boundariesAccepted() {
        String voyage = "V-RANGE";
        assertThatThrownBy(() -> service.submit(sample(voyage, "S", 501, "35.0", "X1", null)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("深度");
        assertThatThrownBy(() -> service.submit(sample(voyage, "S", -1, "35.0", "X2", null)))
                .isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> service.submit(sample(voyage, "S", 0, "45.001", "X3", null)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("盐度");
        assertThatThrownBy(() -> service.submit(sample(voyage, "S", 0, "-0.001", "X4", null)))
                .isInstanceOf(BusinessRuleException.class);

        // 边界值合法：0 米/500 米、0 PSU/45 PSU。
        service.submit(sample(voyage, "S", 0, "0.000", "B1", null));
        service.submit(sample(voyage, "S", 500, "45.000", "B2", null));
        assertThat(repository.findSamples(voyage)).hasSize(2);
        assertThat(service.query(voyage).samples())
                .extracting(SampleView::salinityPsu)
                .usingRecursiveFieldByFieldElementComparator()
                .containsExactly(new BigDecimal("0.000"), new BigDecimal("45.000"));
    }

    @Test
    void sealMissingDepthReturns422_andFailureDoesNotLockVoyage() {
        String voyage = "V-MISS";
        service.submit(sample(voyage, "S", 0, "35.1", "M-0", null));
        service.submit(sample(voyage, "S", 100, "34.0", "M-100", null));
        adopt(voyage, "M-0");
        adopt(voyage, "M-100");

        // 题干示例：站位 S 缺 50 米 → 422。
        assertThatThrownBy(() -> service.seal(new SealVoyageCommand(voyage)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("50");

        // 失败不锁定：没有冻结点，航次仍 OPEN，可继续质控与封存。
        VoyageDataView afterFail = service.query(voyage);
        assertThat(afterFail.sealed()).isFalse();
        assertThat(afterFail.frozenProfile()).isEmpty();
        assertThat(repository.findVoyage(voyage).orElseThrow().status().name()).isEqualTo("OPEN");

        service.submit(sample(voyage, "S", 50, "34.8", "M-50", null));
        adopt(voyage, "M-50");
        service.seal(new SealVoyageCommand(voyage));
        assertThat(service.query(voyage).sealed()).isTrue();
        assertThat(service.query(voyage).frozenProfile()).extracting(FrozenProfilePointView::depthM)
                .containsExactly(0, 50, 100);
    }

    @Test
    void partialStationSealRollsBackEverything() {
        String voyage = "V-PART";
        submitAdoptStandard(voyage, "S1", "35.1", "34.8", "34.0", "P-S1");
        // S2 只具备两个标准层。
        service.submit(sample(voyage, "S2", 0, "35.2", "P-S2-0", null));
        service.submit(sample(voyage, "S2", 100, "34.1", "P-S2-100", null));
        adopt(voyage, "P-S2-0");
        adopt(voyage, "P-S2-100");

        assertThatThrownBy(() -> service.seal(new SealVoyageCommand(voyage)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("S2");

        // 全部回滚：任何站位都不允许被部分封存。
        assertThat(repository.findProfilePoints(voyage)).isEmpty();
        assertThat(repository.findVoyage(voyage).orElseThrow().status().name()).isEqualTo("OPEN");
    }

    @Test
    void idempotentReplaySameParams_sameNoDifferentContentConflict() {
        String voyage = "V-IDEM";
        SubmitSampleCommand first = sample(voyage, "S", 0, "35.100", "IDEM-1", "REQ-IDEM-1");
        SampleView v1 = service.submit(first);
        SampleView replay = service.submit(first);
        assertThat(replay).isEqualTo(v1);
        assertThat(repository.findSamples(voyage)).hasSize(1);

        // 编号相同、内容不同 → 409。
        assertThatThrownBy(() ->
                service.submit(sample(voyage, "S", 0, "35.200", "IDEM-1", "REQ-OTHER")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("采样编号");

        // requestId 相同但业务内容不同 → 409。
        assertThatThrownBy(() ->
                service.submit(sample(voyage, "S", 0, "35.100", "IDEM-2", "REQ-IDEM-1")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("requestId");

        // 不带 requestId 的同参再提（不同编号）是正常重测，保留原始。
        service.submit(sample(voyage, "S", 0, "35.100", "IDEM-3", null));
        assertThat(repository.findSamples(voyage)).hasSize(2);
    }

    @Test
    void databaseUniqueConstraintsActuallyEnforced() {
        String voyage = "V-UQ";
        repository.registerVoyageIfAbsent(voyage, FIXED_NOW);
        SalinitySample row = new SalinitySample(null, voyage, "S", 0, new BigDecimal("35.1"),
                FIXED_NOW, "UQ-1", "REQ-UQ-1", false, null, FIXED_NOW);
        repository.insertSample(row);
        assertThatThrownBy(() -> repository.insertSample(
                new SalinitySample(null, voyage, "S", 0, new BigDecimal("35.2"),
                        FIXED_NOW, "UQ-1", "REQ-UQ-2", false, null, FIXED_NOW)))
                .isInstanceOf(DuplicateKeyException.class);
        assertThatThrownBy(() -> repository.insertSample(
                new SalinitySample(null, voyage, "S", 0, new BigDecimal("35.3"),
                        FIXED_NOW, "UQ-2", "REQ-UQ-1", false, null, FIXED_NOW)))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void concurrentSubmitSameRequestIdProducesExactlyOneSample() throws Exception {
        String voyage = "V-CONC";
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> firstError = new AtomicReference<>();
        try {
            List<Future<SampleView>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < threads; i++) {
                SubmitSampleCommand cmd = sample(voyage, "S", 0, "35.100", "C-1", "REQ-C-1");
                futures.add(pool.submit(() -> {
                    start.await();
                    return service.submit(cmd);
                }));
            }
            start.countDown();
            for (Future<SampleView> future : futures) {
                assertThat(future.get(10, TimeUnit.SECONDS).sampleNo()).isEqualTo("C-1");
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(firstError.get()).isNull();
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM salinity_sample WHERE voyage_code = ? AND sample_no = 'C-1'",
                Integer.class, voyage);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void concurrentAdoptSamePositionLeavesSingleAdopted() throws Exception {
        String voyage = "V-CADOPT";
        service.submit(sample(voyage, "S", 0, "35.1", "CA-0", null));
        service.submit(sample(voyage, "S", 50, "34.8", "CA-50", null));
        service.submit(sample(voyage, "S", 100, "34.0", "CA-100", null));
        service.submit(sample(voyage, "S", 50, "34.7", "CA-50-R1", null));
        service.submit(sample(voyage, "S", 50, "34.6", "CA-50-R2", null));
        adopt(voyage, "CA-0");
        adopt(voyage, "CA-50");
        adopt(voyage, "CA-100");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<?> f1 = pool.submit(() -> {
                awaitUninterruptibly(start);
                adopt(voyage, "CA-50-R1");
            });
            Future<?> f2 = pool.submit(() -> {
                awaitUninterruptibly(start);
                adopt(voyage, "CA-50-R2");
            });
            start.countDown();
            f1.get(10, TimeUnit.SECONDS);
            f2.get(10, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        List<SalinitySample> at50 = repository.lockPosition(voyage, "S", 50);
        long adoptedCount = at50.stream().filter(SalinitySample::adopted).count();
        assertThat(adoptedCount).isEqualTo(1);
        SalinitySample winner = at50.stream().filter(SalinitySample::adopted).findFirst().orElseThrow();
        assertThat(winner.sampleNo()).isIn("CA-50-R1", "CA-50-R2");
        // 最终采纳版本之外的两条都应被替换链标记，且替换者真实存在。
        assertThat(at50.stream().filter(s -> !s.adopted()))
                .allSatisfy(s -> assertThat(s.supersededBy()).isNotNull());
    }

    @Test
    void crossVoyageAdoptionAndMissingResourcesRejected() {
        String v1 = "V-X1";
        String v2 = "V-X2";
        service.submit(sample(v1, "S", 0, "35.1", "X-1", null));
        repository.registerVoyageIfAbsent(v2, FIXED_NOW);

        // 跨航次质控 → 422。
        assertThatThrownBy(() -> adopt(v2, "X-1"))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("跨航次");
        assertThat(repository.findVoyage(v2)).isPresent();

        assertThatThrownBy(() -> adopt(v1, "NOT-EXIST"))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.query("V-NOT-EXIST"))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.seal(new SealVoyageCommand("V-NOT-EXIST")))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.addErratum(new AddErratumCommand(v1, "未封存不能勘误")))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void unopenedVoyageWithoutSamplesCannotSeal() {
        String voyage = "V-EMPTY-" + UUID.randomUUID();
        repository.registerVoyageIfAbsent(voyage, FIXED_NOW);
        assertThatThrownBy(() -> service.seal(new SealVoyageCommand(voyage)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("没有任何样本");
        assertThat(repository.findVoyage(voyage).orElseThrow().status().name()).isEqualTo("OPEN");
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    if (!latch.await(10, TimeUnit.SECONDS)) {
                        throw new AssertionError("协调超时：并发线程未按时放行");
                    }
                    return;
                } catch (InterruptedException ex) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
