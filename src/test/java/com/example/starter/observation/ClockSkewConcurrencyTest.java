package com.example.starter.observation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.LocalDateTime;
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
 * 时钟偏移与观测提交的并发边界测试：偏移变更与提交按事务提交顺序裁决、
 * 同键并发重放只占一键、并发登记同一生效时刻只成功一条、并列顺序稳定。
 */
@SpringBootTest
class ClockSkewConcurrencyTest {

    @Autowired
    private ClockSkewService clockSkewService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM observation_reorder");
        jdbcTemplate.update("DELETE FROM device_observation");
        jdbcTemplate.update("DELETE FROM device_clock_offset");
        jdbcTemplate.update("DELETE FROM device_registry");
        jdbcTemplate.update("DELETE FROM conflict_resolution");
        jdbcTemplate.update("DELETE FROM request_log");
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

    private DeviceSubmissionRequest submission(String requestId, String submissionId, String deviceId,
                                               String deviceLocalAt, String location) {
        return new DeviceSubmissionRequest(requestId, submissionId, deviceId,
                LocalDateTime.parse(deviceLocalAt), location, "1.0", "并发");
    }

    @Test
    void concurrentOffsetRegisterAndSubmissionConvergeToCorrectedTime() throws Exception {
        // 偏移登记与观测提交并发：无论谁先提交，最终矫正时刻都必须等于本地时刻 + 新偏移。
        // 偏移先提交则提交按新偏移换算；提交先提交（偏移为 0）则纳入重建范围被修正。
        List<Callable<String>> tasks = List.of(
                () -> {
                    ClockSkewService.OffsetOutcome outcome = clockSkewService.registerOffset("dev-c1",
                            new OffsetChangeRequest("req-ca", Instant.parse("2026-09-25T00:00:00Z"), 3600));
                    return "register-" + outcome.status();
                },
                () -> {
                    ClockSkewService.SubmitOutcome outcome = clockSkewService.submit("obs-c1",
                            submission("req-cb", "sub-c1", "dev-c1", "2026-09-25T10:00:00", "站点一"));
                    return "submit-" + outcome.status();
                });
        List<String> results = runConcurrently(tasks);

        assertThat(results).containsExactlyInAnyOrder("register-201", "submit-201");
        List<DeviceSubmission> submissions = clockSkewService.listSubmissions("obs-c1");
        assertThat(submissions).hasSize(1);
        DeviceSubmission submission = submissions.get(0);
        assertThat(submission.offsetSeconds()).isEqualTo(3600);
        assertThat(submission.correctedAtUtc()).isEqualTo(Instant.parse("2026-09-25T11:00:00Z"));
        // 原始本地时刻不被改写
        assertThat(submission.deviceLocalAt()).isEqualTo(LocalDateTime.parse("2026-09-25T10:00:00"));
        ObservationResponse current = currentOf("obs-c1");
        assertThat(current.location()).isEqualTo("站点一");
        assertThat(current.version()).isEqualTo(1);
    }

    private ObservationResponse currentOf(String observationId) {
        return jdbcTemplate.query(
                        "SELECT observation_id, version, location, reading, note, deleted "
                                + "FROM observation_current WHERE observation_id = ?",
                        (rs, rowNum) -> new ObservationResponse(rs.getString("observation_id"),
                                rs.getInt("version"), rs.getBoolean("deleted"),
                                rs.getString("location"), rs.getString("reading"), rs.getString("note")),
                        observationId)
                .stream().findFirst().orElseThrow();
    }

    @Test
    void concurrentSameRequestIdSubmitReplaysSingleResult() throws Exception {
        List<Callable<ClockSkewService.SubmitOutcome>> tasks = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            tasks.add(() -> clockSkewService.submit("obs-c2",
                    submission("req-ci", "sub-ci", "dev-c2", "2026-09-25T10:00:00", "站点重放")));
        }
        List<ClockSkewService.SubmitOutcome> outcomes = runConcurrently(tasks);

        assertThat(outcomes).allMatch(outcome -> outcome.status() == 201);
        assertThat(outcomes).allMatch(outcome -> outcome.body().version() == 1);
        Integer submissionRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM device_observation WHERE submission_id = 'sub-ci'", Integer.class);
        assertThat(submissionRows).isEqualTo(1);
        Integer requestRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = 'req-ci'", Integer.class);
        assertThat(requestRows).isEqualTo(1);
        Integer versionRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM observation_version WHERE observation_id = 'obs-c2'", Integer.class);
        assertThat(versionRows).isEqualTo(1);
    }

    @Test
    void concurrentOffsetRegistrationsAtSameInstantYieldSingleRecord() throws Exception {
        List<Callable<String>> tasks = List.of(
                () -> {
                    try {
                        clockSkewService.registerOffset("dev-c3",
                                new OffsetChangeRequest("req-cr1",
                                        Instant.parse("2026-09-25T00:00:00Z"), 100));
                        return "ok";
                    } catch (ApiException e) {
                        return String.valueOf(e.status().value());
                    }
                },
                () -> {
                    try {
                        clockSkewService.registerOffset("dev-c3",
                                new OffsetChangeRequest("req-cr2",
                                        Instant.parse("2026-09-25T00:00:00Z"), 200));
                        return "ok";
                    } catch (ApiException e) {
                        return String.valueOf(e.status().value());
                    }
                });
        List<String> results = runConcurrently(tasks);

        assertThat(results).containsExactlyInAnyOrder("ok", "409");
        List<DeviceOffsetEntry> offsets = clockSkewService.listOffsets("dev-c3");
        assertThat(offsets).hasSize(1);
    }

    @Test
    void concurrentSubmissionsWithTiedCorrectedTimeResolveDeterministically() throws Exception {
        // 两台设备同一矫正时刻并发提交同一观测：都落库，设备标识字典序大者胜出
        List<Callable<ClockSkewService.SubmitOutcome>> tasks = List.of(
                () -> clockSkewService.submit("obs-c4",
                        submission("req-ct1", "sub-ct1", "dev-a", "2026-09-25T10:00:00", "站点A")),
                () -> clockSkewService.submit("obs-c4",
                        submission("req-ct2", "sub-ct2", "dev-b", "2026-09-25T10:00:00", "站点B")));
        List<ClockSkewService.SubmitOutcome> outcomes = runConcurrently(tasks);

        assertThat(outcomes).allMatch(outcome -> outcome.status() == 201);
        List<DeviceSubmission> submissions = clockSkewService.listSubmissions("obs-c4");
        assertThat(submissions).hasSize(2);
        ObservationResponse current = currentOf("obs-c4");
        assertThat(current.location()).isEqualTo("站点B");
        Integer submissionRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM device_observation WHERE observation_id = 'obs-c4'", Integer.class);
        assertThat(submissionRows).isEqualTo(2);
    }
}
