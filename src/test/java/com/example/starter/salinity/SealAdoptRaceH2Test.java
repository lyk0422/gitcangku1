package com.example.starter.salinity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.starter.salinity.command.AdoptSampleCommand;
import com.example.starter.salinity.command.SealVoyageCommand;
import com.example.starter.salinity.command.SubmitSampleCommand;
import com.example.starter.salinity.exception.ConflictException;
import com.example.starter.salinity.po.Voyage;
import com.example.starter.salinity.repo.JdbcSalinityRepository;
import com.example.starter.salinity.repo.SalinityRepository;
import com.example.starter.salinity.service.SalinityService;
import com.example.starter.salinity.view.FrozenProfilePointView;
import com.example.starter.salinity.view.VoyageDataView;

/**
 * 质控替换与封存竞争：在真实 H2 行锁上强制让采纳线程先排队等待航次行锁，
 * 再放行封存提交，验证封存只固化一个一致版本集合，封存后才获采纳的数据不会混入。
 */
@SpringBootTest
class SealAdoptRaceH2Test {

    static final Instant FIXED_NOW = Instant.parse("2026-09-16T10:00:00Z");

    @TestConfiguration
    static class RaceConfig {

        @Bean
        @Primary
        SalinityRepository raceRepository(JdbcTemplate jdbcTemplate) {
            return new RaceRepository(jdbcTemplate);
        }

        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
        }
    }

    /**
     * 仅做线程协调的包装器：不改变任何 SQL 与事务语义。
     * 封存走到最后一步时暂停并放行给测试；采纳线程取航次行锁前先报到。
     */
    static class RaceRepository extends JdbcSalinityRepository {

        static volatile CountDownLatch sealReachingEnd = new CountDownLatch(1);
        static volatile CountDownLatch adoptAboutToLock = new CountDownLatch(1);
        static volatile CountDownLatch releaseSeal = new CountDownLatch(1);
        static final ThreadLocal<Boolean> ADOPT_THREAD = ThreadLocal.withInitial(() -> false);

        RaceRepository(JdbcTemplate jdbcTemplate) {
            super(jdbcTemplate);
        }

        static void reset() {
            sealReachingEnd = new CountDownLatch(1);
            adoptAboutToLock = new CountDownLatch(1);
            releaseSeal = new CountDownLatch(1);
        }

        @Override
        public Optional<Voyage> lockVoyage(String voyageCode) {
            if (Boolean.TRUE.equals(ADOPT_THREAD.get())) {
                adoptAboutToLock.countDown();
            }
            return super.lockVoyage(voyageCode);
        }

        @Override
        public void markSealed(String voyageCode, Instant sealedUtc) {
            sealReachingEnd.countDown();
            try {
                if (!releaseSeal.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("协调超时：封存未被放行");
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(ex);
            }
            super.markSealed(voyageCode, sealedUtc);
        }
    }

    @Autowired
    private SalinityService service;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clean() {
        RaceRepository.reset();
        jdbcTemplate.update("DELETE FROM profile_erratum");
        jdbcTemplate.update("DELETE FROM sealed_profile_point");
        jdbcTemplate.update("DELETE FROM salinity_sample");
        jdbcTemplate.update("DELETE FROM voyage");
    }

    @Test
    void sealWinsConsistently_adoptionQueuedOnLockRejectedAfterwards() throws Exception {
        String voyage = "V-RACE";
        submit(voyage, 0, "35.100", "R-0");
        submit(voyage, 50, "34.800", "R-50");
        submit(voyage, 100, "34.000", "R-100");
        service.adopt(new AdoptSampleCommand(voyage, "R-0"));
        service.adopt(new AdoptSampleCommand(voyage, "R-50"));
        service.adopt(new AdoptSampleCommand(voyage, "R-100"));
        // 50 米重测，尚未采纳。
        submit(voyage, 50, "34.700", "R-50-NEW");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> sealFuture = pool.submit(() -> service.seal(new SealVoyageCommand(voyage)));
            // 封存已拿到全部锁、停在最后一步。
            assertThat(RaceRepository.sealReachingEnd.await(10, TimeUnit.SECONDS)).isTrue();

            Future<?> adoptFuture = pool.submit(() -> {
                RaceRepository.ADOPT_THREAD.set(true);
                try {
                    service.adopt(new AdoptSampleCommand(voyage, "R-50-NEW"));
                } finally {
                    RaceRepository.ADOPT_THREAD.remove();
                }
            });
            // 等采纳线程进入取航次行锁的代码路径，它应阻塞在封存事务持有的行锁上。
            assertThat(RaceRepository.adoptAboutToLock.await(10, TimeUnit.SECONDS)).isTrue();
            // 有界等待验证：封存提交前，采纳操作无法完成（在锁上排队），数据保持封存前集合。
            assertThatThrownBy(() -> adoptFuture.get(300, TimeUnit.MILLISECONDS))
                    .isInstanceOf(java.util.concurrent.TimeoutException.class);
            Integer adoptedNew = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM salinity_sample WHERE sample_no = 'R-50-NEW' AND adopted = TRUE",
                    Integer.class);
            assertThat(adoptedNew).isZero();

            // 放行封存提交；随后采纳才能拿到锁。
            RaceRepository.releaseSeal.countDown();
            sealFuture.get(10, TimeUnit.SECONDS);
            // 封存已提交，后拿到锁的采纳必须失败。
            assertThatThrownBy(() -> adoptFuture.get(10, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(ConflictException.class);
        } finally {
            pool.shutdownNow();
        }

        VoyageDataView view = service.query(voyage);
        assertThat(view.sealed()).isTrue();
        List<FrozenProfilePointView> profile = view.frozenProfile();
        assertThat(profile).extracting(FrozenProfilePointView::depthM).containsExactly(0, 50, 100);
        // 封存固化的是封存前的一致集合，重测未混入。
        assertThat(profile.get(1).sampleNo()).isEqualTo("R-50");
        assertThat(profile.get(1).salinityPsu()).isEqualByComparingTo(new BigDecimal("34.800"));
        assertThat(view.adopted()).extracting(a -> a.depthM() + ":" + a.sampleNo())
                .containsExactly("0:R-0", "50:R-50", "100:R-100");
        // 原始重测仍保留、未采纳。
        assertThat(view.samples()).filteredOn(s -> s.sampleNo().equals("R-50-NEW"))
                .singleElement()
                .satisfies(s -> assertThat(s.adopted()).isFalse());
    }

    private void submit(String voyage, int depth, String salinity, String sampleNo) {
        service.submit(new SubmitSampleCommand(
                voyage, "S", depth, new BigDecimal(salinity),
                Instant.parse("2026-09-16T0" + (depth / 50) + ":00:00Z"),
                sampleNo, "REQ-" + sampleNo));
    }
}
