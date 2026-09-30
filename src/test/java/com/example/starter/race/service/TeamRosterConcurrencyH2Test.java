package com.example.starter.race.service;

import com.example.starter.race.api.CreateRaceRequest;
import com.example.starter.race.api.LockRosterRequest;
import com.example.starter.race.api.RegisterRunnerRequest;
import com.example.starter.race.api.SealRaceRequest;
import com.example.starter.race.api.TeamRosterResponse;
import com.example.starter.race.domain.RaceStatus;
import com.example.starter.race.persistence.RaceRow;
import com.example.starter.race.support.AbstractRaceH2Test;
import com.example.starter.race.support.FixedClockTestConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 团队名单在 H2 上的真实并发测试：锁定与封榜互斥、同成员跨队并发只能归属一队、
 * 同 requestId 并发锁定只产生一个完整名单版本；核对最终业务数据而非打印。
 */
@SpringBootTest
@Import(FixedClockTestConfig.class)
class TeamRosterConcurrencyH2Test extends AbstractRaceH2Test {

    private static final String RACE = "race-team-concurrent";

    @Autowired
    private RaceService raceService;

    @Autowired
    private com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    private void seedRaceWithRunners(String... bibs) {
        raceService.createRace(new CreateRaceRequest(RACE, "req-create"));
        int version = 1;
        for (String bib : bibs) {
            raceService.registerRunner(RACE,
                    new RegisterRunnerRequest(bib, (long) version * 1000, version,
                            "req-reg-" + bib));
            version++;
        }
    }

    @Test
    void 锁定与封榜并发互斥且榜单只引用一个完整名单版本() throws Exception {
        seedRaceWithRunners("jia", "yi");
        // 当前版本3：A 锁定 [甲,乙] 与 封榜 都以 expectedVersion=3 并发提交
        int runs = 8;
        ExecutorService pool = Executors.newFixedThreadPool(runs);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger lockSuccess = new AtomicInteger();
        AtomicInteger sealSuccess = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        try {
            var futures = IntStream.range(0, runs)
                    .mapToObj(i -> (java.util.concurrent.Callable<Void>) () -> {
                        start.await();
                        try {
                            if (i % 2 == 0) {
                                raceService.lockRoster(RACE, "A", new LockRosterRequest(
                                        List.of("jia", "yi"), 3, "req-lock-" + i));
                                lockSuccess.incrementAndGet();
                            } else {
                                raceService.sealRace(RACE,
                                        new SealRaceRequest(3, "req-seal-" + i));
                                sealSuccess.incrementAndGet();
                            }
                        } catch (ConflictException ex) {
                            conflicts.incrementAndGet();
                        }
                        return null;
                    })
                    .map(pool::submit)
                    .toList();
            start.countDown();
            for (Future<Void> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        // 两类操作互斥：恰有一个成功，其余409；版本只加一
        assertThat(lockSuccess.get() + sealSuccess.get()).isEqualTo(1);
        assertThat(conflicts.get()).isEqualTo(runs - 1);
        RaceRow race = repository.findRace(RACE).orElseThrow();
        assertThat(race.version()).isEqualTo(4);
        if (lockSuccess.get() == 1) {
            // 锁定先成功：赛事仍 OPEN，无任何封榜快照
            assertThat(race.status()).isEqualTo(RaceStatus.OPEN);
            assertThat(repository.findSnapshot(RACE)).isEmpty();
            assertThat(repository.findTeamStandingSnapshot(RACE)).isEmpty();
            assertThat(raceService.getRosterVersion(RACE, "A", 1).members()).hasSize(2);
        } else {
            // 封榜先成功：赛事 SEALED，团队榜快照存在但为空（封榜时尚无名单版本可引用）
            assertThat(race.status()).isEqualTo(RaceStatus.SEALED);
            assertThat(repository.findSnapshot(RACE)).isPresent();
            assertThat(repository.findTeamStandingSnapshot(RACE)).isEmpty();
            // 封榜后锁定名单必然409，不会出现半个版本
            assertThatThrownBy(() -> raceService.lockRoster(RACE, "A", new LockRosterRequest(
                    List.of("jia", "yi"), 4, "req-lock-after-seal")))
                    .isInstanceOf(ConflictException.class);
        }
    }

    @Test
    void 同一成员跨队并发锁定只有一队成功() throws Exception {
        seedRaceWithRunners("jia", "yi", "bing");
        // 版本4：A、B 同时抢甲；B 另含丙。并发下 (race_id,bib) 唯一键兜底，甲只能归属一队
        int runs = 2;
        ExecutorService pool = Executors.newFixedThreadPool(runs);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        try {
            var futures = List.of(
                    (java.util.concurrent.Callable<Void>) () -> {
                        start.await();
                        try {
                            raceService.lockRoster(RACE, "A",
                                    new LockRosterRequest(List.of("jia", "yi"), 4, "req-lock-a"));
                            success.incrementAndGet();
                        } catch (ConflictException ex) {
                            conflicts.incrementAndGet();
                        }
                        return null;
                    },
                    (java.util.concurrent.Callable<Void>) () -> {
                        start.await();
                        try {
                            raceService.lockRoster(RACE, "B",
                                    new LockRosterRequest(List.of("jia", "bing"), 4, "req-lock-b"));
                            success.incrementAndGet();
                        } catch (ConflictException ex) {
                            conflicts.incrementAndGet();
                        }
                        return null;
                    }).stream()
                    .map(pool::submit)
                    .toList();
            start.countDown();
            for (Future<Void> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(success.get()).isEqualTo(1);
        assertThat(conflicts.get()).isEqualTo(1);
        // 甲的归属唯一，且失败方没有产生半份名单
        String owner = repository.findMembership(RACE, "jia").orElseThrow().teamId();
        assertThat(owner).isIn("A", "B");
        if (owner.equals("A")) {
            assertThat(raceService.getRosterVersions(RACE, "B")).isEmpty();
            assertThat(repository.findMembership(RACE, "bing")).isEmpty();
        } else {
            assertThat(raceService.getRosterVersions(RACE, "A")).isEmpty();
            assertThat(repository.findMembership(RACE, "yi")).isEmpty();
        }
    }

    @Test
    void 同requestId并发锁定只产生一个完整版本且结果一致() throws Exception {
        seedRaceWithRunners("jia", "yi");
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger errors = new AtomicInteger();
        try {
            var futures = IntStream.range(0, threads)
                    .mapToObj(i -> (java.util.concurrent.Callable<ServiceResult>) () -> {
                        start.await();
                        try {
                            return raceService.lockRoster(RACE, "A", new LockRosterRequest(
                                    List.of("jia", "yi"), 3, "req-lock-same"));
                        } catch (RuntimeException ex) {
                            errors.incrementAndGet();
                            throw ex;
                        }
                    })
                    .map(pool::submit)
                    .toList();
            start.countDown();
            ServiceResult first = futures.getFirst().get(30, TimeUnit.SECONDS);
            for (Future<ServiceResult> future : futures) {
                ServiceResult result = future.get(30, TimeUnit.SECONDS);
                assertThat(result.status()).isEqualTo(first.status());
                // 重放体可能是反序列化的 JsonNode，统一按规范化 JSON 比较首次成员与成绩快照
                assertThat(objectMapper.writeValueAsString(result.body()))
                        .isEqualTo(objectMapper.writeValueAsString(first.body()));
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(errors.get()).isZero();
        List<TeamRosterResponse> versions = raceService.getRosterVersions(RACE, "A");
        assertThat(versions).hasSize(1);
        assertThat(versions.getFirst().members()).hasSize(2);
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(4);
    }
}
