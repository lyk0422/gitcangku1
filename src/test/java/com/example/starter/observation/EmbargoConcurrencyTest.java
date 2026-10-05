package com.example.starter.observation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 禁运与到期发布并发边界测试：同簇并发到期发布只生成一个快照、并发设置禁运按提交顺序裁决、
 * 发布后并发更正与发布竞争的最终一致性。真实 H2 内存库 + 可控 UTC 时钟 + 真实并发线程。
 */
@SpringBootTest
class EmbargoConcurrencyTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    @Autowired
    private ObservationService observationService;

    @Autowired
    private EmbargoService embargoService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private Clock clock;

    private final AtomicReference<Instant> now = new AtomicReference<>(T0);

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM embargo_release_addendum");
        jdbcTemplate.update("DELETE FROM embargo_release");
        jdbcTemplate.update("DELETE FROM observation_embargo");
        jdbcTemplate.update("DELETE FROM observation_open_conflict");
        jdbcTemplate.update("DELETE FROM conflict_resolution");
        jdbcTemplate.update("DELETE FROM request_log");
        jdbcTemplate.update("DELETE FROM observation_version");
        jdbcTemplate.update("DELETE FROM observation_current");
        now.set(T0);
        Mockito.when(clock.instant()).thenAnswer(invocation -> now.get());
        Mockito.when(clock.getZone()).thenReturn(ZoneOffset.UTC);
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

    private void createObservation(String requestId, String observationId) {
        observationService.create(new CreateObservationRequest(
                requestId, observationId, "站点A", "1.0", "备注"));
    }

    private SetEmbargoRequest embargoRequest(String requestId, String observationId, Instant until) {
        return new SetEmbargoRequest(requestId, "operator-a",
                List.of(new SetEmbargoRequest.Item(observationId, until, List.of("PUBLIC"))));
    }

    private int countRows(String table, String observationId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE observation_id = ?", Integer.class, observationId);
        return count == null ? 0 : count;
    }

    @Test
    void concurrentReleaseCreatesSingleSnapshot() throws Exception {
        createObservation("req-x1-c", "obs-x1");
        embargoService.setEmbargoes(embargoRequest("req-x1-e", "obs-x1", T0.plusSeconds(100)));
        now.set(T0.plusSeconds(100));

        // 4 个并发公开查询同时触发到期发布：全部返回同一快照，只生成一行
        List<Callable<PublicObservationResponse>> tasks = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            tasks.add(() -> embargoService.publicGet("obs-x1"));
        }
        List<PublicObservationResponse> responses = runConcurrently(tasks);

        assertThat(responses).allMatch(response -> "RELEASED".equals(response.status()));
        assertThat(responses).allMatch(response -> "rel-obs-x1".equals(response.release().releaseId()));
        assertThat(responses).allMatch(response -> response.release().releasedVersion() == 1);
        assertThat(responses).allMatch(response -> "站点A".equals(response.release().location()));
        assertThat(countRows("embargo_release", "obs-x1")).isEqualTo(1);
    }

    @Test
    void concurrentSameRequestIdSetEmbargoReplaysSingleResult() throws Exception {
        createObservation("req-x2-c", "obs-x2");

        // 同一 requestId + 相同参数并发 3 次：全部成功且响应一致，只占一键、只设一次
        List<Callable<EmbargoService.SetEmbargoOutcome>> tasks = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            tasks.add(() -> embargoService.setEmbargoes(
                    embargoRequest("req-x2-e", "obs-x2", T0.plusSeconds(100))));
        }
        List<EmbargoService.SetEmbargoOutcome> outcomes = runConcurrently(tasks);

        assertThat(outcomes).allMatch(outcome -> outcome.status() == 201);
        assertThat(outcomes).allMatch(outcome -> outcome.body().appliedCount() == 1);
        assertThat(outcomes.get(0).body()).isEqualTo(outcomes.get(1).body());
        assertThat(outcomes.get(1).body()).isEqualTo(outcomes.get(2).body());
        assertThat(countRows("observation_embargo", "obs-x2")).isEqualTo(1);
        Integer requestRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = 'req-x2-e'", Integer.class);
        assertThat(requestRows).isEqualTo(1);
    }

    @Test
    void concurrentDifferentRequestIdsOnlyOneSetSucceeds() throws Exception {
        createObservation("req-x3-c", "obs-x3");

        // 两个不同 requestId 并发设置同簇禁运：先提交者成功，后到者 409，禁运不可变
        List<Callable<String>> tasks = List.of(
                () -> {
                    try {
                        embargoService.setEmbargoes(embargoRequest("req-x3-a", "obs-x3", T0.plusSeconds(100)));
                        return "ok";
                    } catch (ApiException e) {
                        return String.valueOf(e.status().value());
                    }
                },
                () -> {
                    try {
                        embargoService.setEmbargoes(embargoRequest("req-x3-b", "obs-x3", T0.plusSeconds(200)));
                        return "ok";
                    } catch (ApiException e) {
                        return String.valueOf(e.status().value());
                    }
                });
        List<String> results = runConcurrently(tasks);

        assertThat(results).containsExactlyInAnyOrder("ok", "409");
        assertThat(countRows("observation_embargo", "obs-x3")).isEqualTo(1);
    }

    @Test
    void concurrentCorrectionAndReleaseSettleInCommitOrder() throws Exception {
        createObservation("req-x4-c", "obs-x4");
        embargoService.setEmbargoes(embargoRequest("req-x4-e", "obs-x4", T0.plusSeconds(100)));
        now.set(T0.plusSeconds(100));

        // 到期后并发执行更正（merge → v2）与首次公开发布：按提交顺序裁决，最终状态一致
        List<Callable<String>> tasks = List.of(
                () -> {
                    try {
                        observationService.merge("obs-x4", new MergeObservationRequest(
                                "req-x4-m", 1, "站点B", "1.0", "备注"));
                        return "merged";
                    } catch (ApiException e) {
                        return "merge-" + e.status().value();
                    }
                },
                () -> {
                    try {
                        PublicObservationResponse response = embargoService.publicGet("obs-x4");
                        return "released-" + response.release().releasedVersion();
                    } catch (ApiException e) {
                        return "release-" + e.status().value();
                    }
                });
        List<String> results = runConcurrently(tasks);

        assertThat(results.get(0)).isEqualTo("merged");
        assertThat(results.get(1)).matches("released-[12]");
        assertThat(countRows("embargo_release", "obs-x4")).isEqualTo(1);
        ObservationResponse current = observationService.getCurrent("obs-x4");
        assertThat(current.version()).isEqualTo(2);

        ReleaseDetailResponse detail = embargoService.getReleaseDetail("obs-x4");
        if ("released-1".equals(results.get(1))) {
            // 发布先提交：快照为 v1，更正 v2 进入附页
            assertThat(detail.release().releasedVersion()).isEqualTo(1);
            assertThat(detail.addenda()).hasSize(1);
            assertThat(detail.addenda().get(0).version()).isEqualTo(2);
            assertThat(detail.addenda().get(0).location()).isEqualTo("站点B");
        } else {
            // 更正先提交：快照直接为 v2，无附页
            assertThat(detail.release().releasedVersion()).isEqualTo(2);
            assertThat(detail.release().location()).isEqualTo("站点B");
            assertThat(detail.addenda()).isEmpty();
        }
        // 首次快照不被后续读取改写
        PublicObservationResponse again = embargoService.publicGet("obs-x4");
        assertThat(again.release().releasedVersion()).isEqualTo(detail.release().releasedVersion());
    }
}
