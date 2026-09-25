package com.example.starter.race.service;

import com.example.starter.race.api.CreateRaceRequest;
import com.example.starter.race.api.RegisterRunnerRequest;
import com.example.starter.race.api.StartRunnerRequest;
import com.example.starter.race.api.SubmitInspectionRequest;
import com.example.starter.race.persistence.RaceRow;
import com.example.starter.race.support.AbstractRaceH2Test;
import com.example.starter.race.support.MutableClockTestConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 检录/起跑在 H2 上的真实并发测试：
 * 无 PASS 时并发起跑全部被门禁拦截；同器材并发 PASS 仅一个成功；
 * 同 requestId 并发重放只产生一条检录历史。
 */
@SpringBootTest
@Import(MutableClockTestConfig.class)
class InspectionConcurrencyH2Test extends AbstractRaceH2Test {

    private static final String RACE = "race-insp-concurrent";

    @Autowired
    private RaceService raceService;

    @Test
    void 无pass时并发起跑全部422且无起跑记录() throws Exception {
        raceService.createRace(new CreateRaceRequest(RACE, true, 60, "req-create"));
        raceService.registerRunner(RACE,
                new RegisterRunnerRequest("a", null, 1, "req-reg-a")); // v2

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger blocked = new AtomicInteger();
        AtomicInteger other = new AtomicInteger();
        try {
            var futures = IntStream.range(0, threads)
                    .mapToObj(i -> (java.util.concurrent.Callable<Void>) () -> {
                        start.await();
                        try {
                            raceService.startRunner(RACE, "a",
                                    new StartRunnerRequest("start-" + i, 2, "rs-" + i));
                            other.incrementAndGet();
                        } catch (UnprocessableEntityException ex) {
                            blocked.incrementAndGet();
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

        assertThat(blocked.get()).isEqualTo(threads);
        assertThat(other.get()).isZero();
        assertThat(repository.findStartForRunner(RACE, "a")).isEmpty();
        // 门禁失败不推进版本
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(2);

        // 随后（PASS 提交之后）的起跑请求可用
        raceService.submitInspection(RACE, "a",
                new SubmitInspectionRequest("insp-a", "bike-1", "PASS", 2, "req-insp-a")); // v3
        ServiceResult started = raceService.startRunner(RACE, "a",
                new StartRunnerRequest("start-ok", 3, "req-start-ok")); // v4
        assertThat(started.status()).isEqualTo(201);
        assertThat(repository.findStartForRunner(RACE, "a")).isPresent();
    }

    @Test
    void 两选手并发pass同一器材仅一个成功且绑定唯一() throws Exception {
        raceService.createRace(new CreateRaceRequest(RACE, true, 60, "req-create"));
        raceService.registerRunner(RACE,
                new RegisterRunnerRequest("a", null, 1, "req-reg-a")); // v2
        raceService.registerRunner(RACE,
                new RegisterRunnerRequest("b", null, 2, "req-reg-b")); // v3

        int threads = 2;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch barrier = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        try {
            String[] bibs = {"a", "b"};
            var futures = IntStream.range(0, threads)
                    .mapToObj(i -> (java.util.concurrent.Callable<Void>) () -> {
                        barrier.await();
                        String bib = bibs[i];
                        try {
                            raceService.submitInspection(RACE, bib, new SubmitInspectionRequest(
                                    "insp-" + bib, "bike-X", "PASS", 3, "rid-" + bib));
                            success.incrementAndGet();
                        } catch (ConflictException ex) {
                            // 版本冲突或器材已绑定，二者都应是409
                            conflict.incrementAndGet();
                        }
                        return null;
                    })
                    .map(pool::submit)
                    .toList();
            barrier.countDown();
            for (Future<Void> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(success.get()).isEqualTo(1);
        assertThat(conflict.get()).isEqualTo(1);
        assertThat(raceService.getEquipmentBindings(RACE).entries()).hasSize(1);
        RaceRow race = repository.findRace(RACE).orElseThrow();
        // 仅一次成功的检录推进版本：v3 -> v4
        assertThat(race.version()).isEqualTo(4);
    }

    @Test
    void 检录同requestId并发重放只产生一条历史() throws Exception {
        raceService.createRace(new CreateRaceRequest(RACE, true, 60, "req-create"));
        raceService.registerRunner(RACE,
                new RegisterRunnerRequest("a", null, 1, "req-reg-a")); // v2

        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch barrier = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger errors = new AtomicInteger();
        try {
            var futures = IntStream.range(0, threads)
                    .mapToObj(i -> (java.util.concurrent.Callable<ServiceResult>) () -> {
                        barrier.await();
                        try {
                            ServiceResult result = raceService.submitInspection(RACE, "a",
                                    new SubmitInspectionRequest(
                                            "insp-same", "bike-1", "PASS", 2, "rid-same"));
                            ok.incrementAndGet();
                            return result;
                        } catch (RuntimeException ex) {
                            errors.incrementAndGet();
                            throw ex;
                        }
                    })
                    .map(pool::submit)
                    .toList();
            barrier.countDown();
            ServiceResult first = futures.getFirst().get(30, TimeUnit.SECONDS);
            for (Future<ServiceResult> future : futures) {
                assertThat(future.get(30, TimeUnit.SECONDS).status()).isEqualTo(first.status());
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(errors.get()).isZero();
        assertThat(ok.get()).isEqualTo(threads);
        assertThat(repository.findInspectionsForRunner(RACE, "a")).hasSize(1);
        assertThat(repository.findBindings(RACE)).hasSize(1);
    }
}
