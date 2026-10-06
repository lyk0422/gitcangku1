package com.example.starter.race.service;

import com.example.starter.race.api.AdjudicateSensorRequest;
import com.example.starter.race.api.ConfigureCheckpointsRequest;
import com.example.starter.race.api.CreateRaceRequest;
import com.example.starter.race.api.RegisterRunnerRequest;
import com.example.starter.race.api.ReviseSensorSampleRequest;
import com.example.starter.race.api.SubmitSensorSampleRequest;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 三源计时共识的并发 H2 测试：采样/修订/裁定共同锁赛事版本，
 * 传感器修订号条件更新串行化，同键竞争不泄露唯一键异常。
 */
@SpringBootTest
@Import(FixedClockTestConfig.class)
class SensorQuorumConcurrencyH2Test extends AbstractRaceH2Test {

    private static final String RACE = "race-sensor-conc";
    private static final int THREADS = 8;

    @Autowired
    private RaceService raceService;

    private void setupRaceWithSensorCheckpoint() {
        raceService.createRace(new CreateRaceRequest(RACE, "req-create"));
        raceService.registerRunner(RACE,
                new RegisterRunnerRequest("a", 10_000L, 1, "req-a"));
        raceService.configureCheckpoints(RACE, new ConfigureCheckpointsRequest(List.of(
                new ConfigureCheckpointsRequest.CheckpointDefinition("cp1", 1, true)),
                2, "req-cfg"));
    }

    private int runConcurrently(java.util.function.IntConsumer task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger conflicts = new AtomicInteger();
        try {
            var futures = new java.util.ArrayList<Future<?>>();
            for (int i = 0; i < THREADS; i++) {
                final int index = i;
                futures.add(pool.submit(() -> {
                    start.await();
                    try {
                        task.accept(index);
                    } catch (ConflictException | UnprocessableEntityException ex) {
                        conflicts.incrementAndGet();
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        return conflicts.get();
    }

    @Test
    void 并发采样共同锁赛事版本且同键竞争不泄露唯一键异常() throws Exception {
        setupRaceWithSensorCheckpoint(); // 当前版本3

        // 前半线程用同一 sampleId 不同参数（异参应409），后半用不同 sampleId 抢同一版本
        AtomicInteger created = new AtomicInteger();
        int conflicts = runConcurrently(index -> {
            String sampleId = index < THREADS / 2 ? "sp-shared" : "sp-own-" + index;
            String sensorId = index < THREADS / 2 ? "sensor-" + index : "sensor-x" + index;
            ServiceResult result = raceService.submitSensorSample(RACE, "a",
                    new SubmitSensorSampleRequest(sampleId, "cp1", sensorId, 100L + index,
                            "recorder", 3, "req-conc-" + index));
            if (result.status() == 201) {
                created.incrementAndGet();
            }
        });

        // 同一赛事版本只允许一个采样成功，其余因版本冲突或异参冲突失败
        assertThat(created.get()).isEqualTo(1);
        assertThat(conflicts).isEqualTo(THREADS - 1);
        RaceRow race = repository.findRace(RACE).orElseThrow();
        assertThat(race.version()).isEqualTo(4);
        assertThat(race.status()).isEqualTo(RaceStatus.OPEN);
        assertThat(repository.findSensorSamples(RACE, "a", "cp1")).hasSize(1);
    }

    @Test
    void 并发修订同一传感器只有一个成功且修订号只推进一次() throws Exception {
        setupRaceWithSensorCheckpoint();
        raceService.submitSensorSample(RACE, "a", new SubmitSensorSampleRequest(
                "sp-1", "cp1", "sensor-1", 100L, "recorder", 3, "req-s1")); // v4

        AtomicInteger revised = new AtomicInteger();
        int conflicts = runConcurrently(index -> {
            ServiceResult result = raceService.reviseSensorSample(RACE, "a",
                    new ReviseSensorSampleRequest("cp1", "sensor-1", 200L + index,
                            "recorder", 4, 1, "req-rev-" + index));
            if (result.status() == 200) {
                revised.incrementAndGet();
            }
        });

        assertThat(revised.get()).isEqualTo(1);
        assertThat(conflicts).isEqualTo(THREADS - 1);
        assertThat(repository.findRace(RACE).orElseThrow().version()).isEqualTo(5);
        assertThat(repository.findSensorSamples(RACE, "a", "cp1"))
                .singleElement()
                .satisfies(s -> assertThat(s.sensorRevision()).isEqualTo(2));
    }

    @Test
    void 并发裁定与修订串行化后状态一致() throws Exception {
        setupRaceWithSensorCheckpoint();
        raceService.submitSensorSample(RACE, "a", new SubmitSensorSampleRequest(
                "sp-1", "cp1", "sensor-1", 100L, "recorder", 3, "req-s1")); // v4
        raceService.submitSensorSample(RACE, "a", new SubmitSensorSampleRequest(
                "sp-2", "cp1", "sensor-2", 110L, "recorder", 4, "req-s2")); // v5
        raceService.submitSensorSample(RACE, "a", new SubmitSensorSampleRequest(
                "sp-3", "cp1", "sensor-3", 120L, "recorder", 5, "req-s3")); // v6

        // 一半线程裁定（expectedVersion=6），一半线程修订（expectedRaceVersion=6）
        AtomicInteger adjudicated = new AtomicInteger();
        AtomicInteger revised = new AtomicInteger();
        runConcurrently(index -> {
            if (index % 2 == 0) {
                ServiceResult result = raceService.adjudicateSensor(RACE, "a",
                        new AdjudicateSensorRequest("adj-" + index, "cp1", "referee",
                                6, "req-adj-" + index));
                if (result.status() == 201) {
                    adjudicated.incrementAndGet();
                }
            } else {
                ServiceResult result = raceService.reviseSensorSample(RACE, "a",
                        new ReviseSensorSampleRequest("cp1", "sensor-3", 118L,
                                "recorder", 6, 1, "req-rev-" + index));
                if (result.status() == 200) {
                    revised.incrementAndGet();
                }
            }
        });

        // 同一版本只允许一个写成功
        assertThat(adjudicated.get() + revised.get()).isEqualTo(1);
        RaceRow race = repository.findRace(RACE).orElseThrow();
        assertThat(race.version()).isEqualTo(7);
        if (adjudicated.get() == 1) {
            // 裁定先成功：分段计时写入110，裁定有效
            assertThat(repository.findAllTimings(RACE)).hasSize(1);
            assertThat(repository.findValidAdjudication(RACE, "a", "cp1")).isPresent();
        } else {
            // 修订先成功：无裁定被写入，传感器修订号已推进
            assertThat(repository.findAllTimings(RACE)).isEmpty();
            assertThat(repository.findSensorSamples(RACE, "a", "cp1"))
                    .filteredOn(s -> s.sensorId().equals("sensor-3"))
                    .singleElement()
                    .satisfies(s -> assertThat(s.sensorRevision()).isEqualTo(2));
        }
    }
}
