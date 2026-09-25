package com.example.starter.race.service;

import com.example.starter.race.api.ConfigureRelayRequest;
import com.example.starter.race.api.CreateRaceRequest;
import com.example.starter.race.api.RegisterRelayTeamRequest;
import com.example.starter.race.api.RelayMemberRequest;
import com.example.starter.race.api.SealRaceRequest;
import com.example.starter.race.api.SubmitHandoffRequest;
import com.example.starter.race.domain.RaceStatus;
import com.example.starter.race.persistence.RaceRow;
import com.example.starter.race.support.AbstractRaceH2Test;
import com.example.starter.race.support.MutableClockTestConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 接力 H2 真实并发测试：赛事 version 条件 UPDATE 串行化交接提交，
 * 交接与封榜互斥，同 requestId 并发交接只产生一次业务变更。
 */
@SpringBootTest
@Import(MutableClockTestConfig.class)
class RelayConcurrencyH2Test extends AbstractRaceH2Test {

    private static final String RACE = "relay-concurrent";
    private static final int LEGS = 2;
    private static final int LIMIT_MS = 2000;

    @Autowired
    private RaceService raceService;

    @Autowired
    private RelayService relayService;

    @Autowired
    private com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }

    @BeforeEach
    void resetClock() {
        MutableClockTestConfig.CLOCK.reset();
    }

    private void prepareRelay(int teamCount) {
        raceService.createRace(new CreateRaceRequest(RACE, "req-create"));
        relayService.configureRelay(RACE,
                new ConfigureRelayRequest(LEGS, LIMIT_MS, 1, "req-cfg"));
        int version = 2;
        for (int t = 0; t < teamCount; t++) {
            String teamKey = "T" + t;
            List<RelayMemberRequest> members = new ArrayList<>();
            for (int leg = 1; leg <= LEGS; leg++) {
                members.add(new RelayMemberRequest(leg, teamKey + "-r" + leg));
            }
            relayService.registerTeam(RACE,
                    new RegisterRelayTeamRequest(teamKey, members, version, "req-reg-" + t));
            version++;
        }
    }

    @Test
    void 并发同版本交接仅一个成功且版本只加一() throws Exception {
        int teams = 8;
        prepareRelay(teams);
        // 登记8支队伍后版本为 2 + 8 = 10；各队第2棒都以 expectedVersion=10 并发提交
        int expectedVersion = 2 + teams;

        ExecutorService pool = Executors.newFixedThreadPool(teams);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        try {
            List<Future<Void>> futures = IntStream.range(0, teams)
                    .mapToObj(i -> (java.util.concurrent.Callable<Void>) () -> {
                        start.await();
                        try {
                            // 第2棒不涉及时刻先后，固定时钟下安全
                            relayService.submitHandoff(RACE, new SubmitHandoffRequest(
                                    "T" + i, 2, 1000L + i, 1000L, expectedVersion, "req-h2-" + i));
                            success.incrementAndGet();
                        } catch (ConflictException ex) {
                            conflicts.incrementAndGet();
                        }
                        return null;
                    })
                    .map(pool::submit)
                    .toList();
            start.countDown();
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(success.get()).isEqualTo(1);
        assertThat(conflicts.get()).isEqualTo(teams - 1);
        RaceRow race = repository.findRace(RACE).orElseThrow();
        assertThat(race.version()).isEqualTo(expectedVersion + 1);
        long handoffCount = repositoryHandoffCount();
        assertThat(handoffCount).isEqualTo(1L);
    }

    @Test
    void 交接与封榜并发时互斥且最终状态自洽() throws Exception {
        prepareRelay(1);
        int expectedVersion = 3;

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger sealSuccess = new AtomicInteger();
        AtomicInteger handoffSuccess = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        try {
            List<Future<Void>> futures = List.of(
                    pool.submit((java.util.concurrent.Callable<Void>) () -> {
                        start.await();
                        try {
                            raceService.sealRace(RACE,
                                    new SealRaceRequest(expectedVersion, "req-seal"));
                            sealSuccess.incrementAndGet();
                        } catch (ConflictException ex) {
                            conflicts.incrementAndGet();
                        }
                        return null;
                    }),
                    pool.submit((java.util.concurrent.Callable<Void>) () -> {
                        start.await();
                        try {
                            relayService.submitHandoff(RACE, new SubmitHandoffRequest(
                                    "T0", 2, 1000L, 1000L, expectedVersion, "req-h2"));
                            handoffSuccess.incrementAndGet();
                        } catch (ConflictException ex) {
                            conflicts.incrementAndGet();
                        }
                        return null;
                    }));
            start.countDown();
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(sealSuccess.get() + handoffSuccess.get()).isEqualTo(1);
        assertThat(conflicts.get()).isEqualTo(1);
        RaceRow race = repository.findRace(RACE).orElseThrow();
        assertThat(race.version()).isEqualTo(expectedVersion + 1);
        if (sealSuccess.get() == 1) {
            assertThat(race.status()).isEqualTo(RaceStatus.SEALED);
            assertThat(repository.findSnapshot(RACE)).isPresent();
            assertThat(repositoryHandoffCount()).isZero();
        } else {
            assertThat(race.status()).isEqualTo(RaceStatus.OPEN);
            assertThat(repository.findSnapshot(RACE)).isEmpty();
            assertThat(repositoryHandoffCount()).isEqualTo(1L);
        }
    }

    @Test
    void 同requestId并发交接重放只产生一次交接且结果一致() throws Exception {
        prepareRelay(1);
        int expectedVersion = 3;

        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger errors = new AtomicInteger();
        try {
            List<Future<ServiceResult>> futures = IntStream.range(0, threads)
                    .mapToObj(i -> (java.util.concurrent.Callable<ServiceResult>) () -> {
                        start.await();
                        try {
                            ServiceResult result = relayService.submitHandoff(RACE,
                                    new SubmitHandoffRequest(
                                            "T0", 2, 1000L, 1000L, expectedVersion, "req-same"));
                            success.incrementAndGet();
                            return result;
                        } catch (RuntimeException ex) {
                            errors.incrementAndGet();
                            throw ex;
                        }
                    })
                    .map(pool::submit)
                    .toList();
            start.countDown();
            ServiceResult first = futures.getFirst().get(30, TimeUnit.SECONDS);
            String firstJson = json(first.body());
            for (Future<ServiceResult> future : futures) {
                ServiceResult result = future.get(30, TimeUnit.SECONDS);
                assertThat(result.status()).isEqualTo(first.status());
                assertThat(json(result.body())).isEqualTo(firstJson);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(errors.get()).isZero();
        assertThat(success.get()).isEqualTo(threads);
        assertThat(repositoryHandoffCount()).isEqualTo(1L);
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(expectedVersion + 1);
    }

    private long repositoryHandoffCount() {
        return repositoryCount("SELECT COUNT(*) FROM relay_handoff WHERE race_id = '" + RACE + "'");
    }

    private long repositoryCount(String sql) {
        java.lang.Long value = jdbcTemplate.queryForObject(sql, java.lang.Long.class);
        return value == null ? 0L : value;
    }

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;
}
