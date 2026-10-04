package com.example.starter.observation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 禁运与披露的并发边界测试：同簇并发到期发布只生成一个快照、
 * 同键并发重放只占一键、发布后并发更正的附页序号单调不重复（真实 H2，可控时钟）。
 */
@SpringBootTest
class EmbargoConcurrencyTest {

    private static final Instant T0 = Instant.parse("2030-01-01T00:00:00Z");
    private static final Instant UNTIL = Instant.parse("2030-06-01T00:00:00Z");
    private static final Instant AFTER = Instant.parse("2030-07-01T00:00:00Z");
    private static final MutableClock CLOCK = new MutableClock(T0);

    @TestConfiguration
    static class MutableClockConfiguration {
        @Bean
        @Primary
        Clock mutableClock() {
            return CLOCK;
        }
    }

    @Autowired
    private ObservationService observationService;

    @Autowired
    private EmbargoService embargoService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        CLOCK.setInstant(T0);
        jdbcTemplate.update("DELETE FROM request_log");
        jdbcTemplate.update("DELETE FROM disclosure_appendix");
        jdbcTemplate.update("DELETE FROM disclosure_snapshot");
        jdbcTemplate.update("DELETE FROM observation_embargo");
        jdbcTemplate.update("DELETE FROM open_conflict");
        jdbcTemplate.update("DELETE FROM conflict_resolution");
        jdbcTemplate.update("DELETE FROM observation_version");
        jdbcTemplate.update("DELETE FROM observation_current");
    }

    private <T> List<T> runConcurrently(List<Callable<T>> tasks) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<T>> synchronizedTasks = tasks.stream()
                .<Callable<T>>map(task -> () -> {
                    ready.countDown();
                    start.await(10, TimeUnit.SECONDS);
                    return task.call();
                })
                .toList();
        try {
            List<Future<T>> futures = synchronizedTasks.stream().map(executor::submit).toList();
            if (!ready.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("workers not ready in time");
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }

    private void createObservation(String id, String requestId) {
        observationService.create(new CreateObservationRequest(requestId, id, "站点A", "1.0", "初始备注"));
    }

    private void setEmbargo(String id, String requestId) {
        embargoService.setEmbargoes(new SetEmbargoBatchRequest(requestId, "operator-1",
                List.of(new EmbargoItemRequest(id, UNTIL, List.of("PUBLIC")))));
    }

    private int tableCount(String table, String observationId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE observation_id = ?", Integer.class, observationId);
        return count == null ? 0 : count;
    }

    @Test
    void concurrentReleasesGenerateSingleSnapshot() throws Exception {
        createObservation("obs-r1", "req-cr0");
        setEmbargo("obs-r1", "req-er0");
        CLOCK.setInstant(AFTER);

        List<Callable<EmbargoService.ReleaseOutcome>> tasks = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            final int index = i;
            tasks.add(() -> embargoService.release("obs-r1",
                    new ReleaseDisclosureRequest("req-rel" + index, "operator-" + index)));
        }
        List<EmbargoService.ReleaseOutcome> outcomes = runConcurrently(tasks);

        // 恰好一个首次发布（201），其余读取既有快照（200）；快照内容一致
        assertThat(outcomes).filteredOn(outcome -> outcome.status() == 201).hasSize(1);
        assertThat(outcomes).filteredOn(outcome -> outcome.status() == 200).hasSize(3);
        assertThat(outcomes).allMatch(outcome -> outcome.body().version() == 1);
        assertThat(outcomes).allMatch(outcome -> outcome.body().releasedAtUtc().equals(AFTER));
        assertThat(tableCount("disclosure_snapshot", "obs-r1")).isEqualTo(1);
    }

    @Test
    void concurrentSameRequestIdEmbargoSetReplaysSingleResult() throws Exception {
        createObservation("obs-r2", "req-cs0");

        List<Callable<EmbargoService.EmbargoBatchOutcome>> tasks = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            tasks.add(() -> embargoService.setEmbargoes(new SetEmbargoBatchRequest("req-es1",
                    "operator-1", List.of(new EmbargoItemRequest("obs-r2", UNTIL, List.of("PUBLIC"))))));
        }
        List<EmbargoService.EmbargoBatchOutcome> outcomes = runConcurrently(tasks);

        assertThat(outcomes).allMatch(outcome -> outcome.status() == 201);
        assertThat(outcomes).allMatch(outcome -> outcome.body().appliedCount() == 1);
        assertThat(tableCount("observation_embargo", "obs-r2")).isEqualTo(1);
        Integer requestRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = 'req-es1'", Integer.class);
        assertThat(requestRows).isEqualTo(1);
    }

    @Test
    void concurrentCorrectionsAfterReleaseProduceUniqueAppendixSeqs() throws Exception {
        createObservation("obs-r3", "req-ca0");
        setEmbargo("obs-r3", "req-ea0");
        CLOCK.setInstant(AFTER);
        EmbargoService.ReleaseOutcome release = embargoService.release("obs-r3",
                new ReleaseDisclosureRequest("req-rela", "operator-2"));
        assertThat(release.status()).isEqualTo(201);

        // 三个离线端基于版本 1 各改一个字段：互不冲突，串行化后都生效并各产生一页附页
        List<Callable<ObservationService.WriteOutcome>> tasks = List.of(
                () -> observationService.merge("obs-r3",
                        new MergeObservationRequest("req-ma", 1, "站点B", "1.0", "初始备注")),
                () -> observationService.merge("obs-r3",
                        new MergeObservationRequest("req-mb", 1, "站点A", "2.0", "初始备注")),
                () -> observationService.merge("obs-r3",
                        new MergeObservationRequest("req-mc", 1, "站点A", "1.0", "并发备注")));
        List<ObservationService.WriteOutcome> outcomes = runConcurrently(tasks);

        assertThat(outcomes).allMatch(outcome -> outcome.status() == 200);
        ObservationResponse current = observationService.getCurrent("obs-r3");
        assertThat(current.version()).isEqualTo(4);
        List<AppendixRecord> appendices = jdbcTemplate.query(
                "SELECT seq, version FROM disclosure_appendix WHERE observation_id = 'obs-r3' ORDER BY seq",
                (rs, rowNum) -> new AppendixRecord("obs-r3", rs.getInt("seq"), rs.getInt("version"),
                        null, null, null, false, "", AFTER));
        assertThat(appendices).hasSize(3);
        assertThat(appendices).extracting(AppendixRecord::seq).containsExactly(1, 2, 3);
        assertThat(appendices).extracting(AppendixRecord::version)
                .containsExactlyInAnyOrder(2, 3, 4);
        // 首次快照不被并发更正改写
        assertThat(embargoService.getDisclosure("obs-r3").version()).isEqualTo(1);
        assertThat(embargoService.getDisclosure("obs-r3").appendixCount()).isEqualTo(3);
    }
}