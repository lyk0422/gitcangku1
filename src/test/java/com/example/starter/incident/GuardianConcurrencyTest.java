package com.example.starter.incident;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.example.starter.incident.dto.Requests.AttendanceRef;
import com.example.starter.incident.dto.Requests.AttendanceRevokeRequest;
import com.example.starter.incident.dto.Requests.AttendanceSignRequest;
import com.example.starter.incident.dto.Requests.GuardianEnableRequest;
import com.example.starter.incident.dto.Requests.ReportRequest;
import com.example.starter.incident.dto.Requests.TakeoverRequest;
import com.example.starter.incident.dto.Requests.TaskActionRequest;
import com.example.starter.incident.dto.Requests.TaskCompleteRequest;
import com.example.starter.incident.dto.Requests.TaskCreateRequest;
import com.example.starter.incident.dto.Responses.AttendanceView;
import com.example.starter.incident.dto.Responses.GuardianView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 双人监护并发竞争测试（真实 H2 事务）：撤销、重新签到、取消任务与完成共享
 * 任务状态与凭证边界——事件行锁串行化后，输方必须看到赢方已提交的边界，
 * 不会在一方凭证已失效后完成；同键并发签到只生效一次。
 */
@SpringBootTest
@Import(ControllableClock.Config.class)
class GuardianConcurrencyTest {

    @Autowired
    private IncidentService service;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM command_keys");
        jdbc.update("DELETE FROM task_attendance");
        jdbc.update("DELETE FROM task_guardians");
        jdbc.update("DELETE FROM incident_escalations");
        jdbc.update("DELETE FROM incident_status_history");
        jdbc.update("DELETE FROM incident_transfers");
        jdbc.update("DELETE FROM incident_actions");
        jdbc.update("DELETE FROM incident_task_blockers");
        jdbc.update("DELETE FROM incident_tasks");
        jdbc.update("DELETE FROM incidents");
    }

    private static String key() {
        return "CMD-" + UUID.randomUUID();
    }

    private void guardedSignedTask(String incidentKey) {
        service.report(new ReportRequest(incidentKey, "S2", "高危处置", "reporter-1"));
        service.takeover(incidentKey, "alice", new TakeoverRequest(key()));
        service.createTask(incidentKey, "alice",
                new TaskCreateRequest(key(), "T-1", "OPS", "处置", List.of()));
        service.enableGuardianship(incidentKey, "T-1", "alice",
                new GuardianEnableRequest(key(), "synth-exec", "synth-watch"));
        service.signAttendance(incidentKey, "T-1", "synth-exec",
                new AttendanceSignRequest(key()));
        service.signAttendance(incidentKey, "T-1", "synth-watch",
                new AttendanceSignRequest(key()));
    }

    private TaskCompleteRequest completeV1() {
        return new TaskCompleteRequest(key(), 1, List.of(
                new AttendanceRef("synth-exec", 1),
                new AttendanceRef("synth-watch", 1)));
    }

    private static List<Object> runConcurrently(List<Callable<?>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        try {
            CountDownLatch ready = new CountDownLatch(tasks.size());
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Object>> futures = new ArrayList<>();
            for (Callable<?> task : tasks) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await(5, TimeUnit.SECONDS);
                    try {
                        return task.call();
                    } catch (Exception e) {
                        return e;
                    }
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<Object> results = new ArrayList<>();
            for (Future<Object> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private static boolean isStatus(Object result, int httpStatus) {
        return result instanceof ApiException e && e.status().value() == httpStatus;
    }

    @Test
    void revokeRacesComplete_boundaryConsistent() throws Exception {
        guardedSignedTask("INC-C1");
        List<Object> results = runConcurrently(List.of(
                () -> service.completeTask("INC-C1", "T-1", "alice", completeV1()),
                () -> service.revokeAttendance("INC-C1", "T-1", "alice",
                        new AttendanceRevokeRequest(key(), "synth-exec"))));

        GuardianView cfg = service.getGuardianship("INC-C1", "T-1");
        String taskStatus = service.getTask("INC-C1", "T-1").status();
        AttendanceView exec = cfg.attendances().stream()
                .filter(a -> a.guardian().equals("synth-exec")).findFirst().orElseThrow();
        if ("DONE".equals(taskStatus)) {
            // 完成先提交：双方凭证冻结，撤销随后被拒（任务已非 OPEN → 422）
            assertThat(results.get(0)).isNotInstanceOf(Exception.class);
            assertThat(isStatus(results.get(1), 422)).isTrue();
            assertThat(exec.status()).isEqualTo("FROZEN");
        } else {
            // 撤销先提交：执行人凭证 REVOKED，完成因一方失效 422，任务保持 OPEN
            assertThat(taskStatus).isEqualTo("OPEN");
            assertThat(isStatus(results.get(0), 422)).isTrue();
            assertThat(results.get(1)).isNotInstanceOf(Exception.class);
            assertThat(exec.status()).isEqualTo("REVOKED");
        }
    }

    @Test
    void resignRacesComplete_oldGenerationLoses() throws Exception {
        guardedSignedTask("INC-C2");
        List<Object> results = runConcurrently(List.of(
                () -> service.completeTask("INC-C2", "T-1", "alice", completeV1()),
                () -> service.signAttendance("INC-C2", "T-1", "synth-exec",
                        new AttendanceSignRequest(key()))));

        String taskStatus = service.getTask("INC-C2", "T-1").status();
        GuardianView cfg = service.getGuardianship("INC-C2", "T-1");
        List<AttendanceView> execs = cfg.attendances().stream()
                .filter(a -> a.guardian().equals("synth-exec")).toList();
        if ("DONE".equals(taskStatus)) {
            // 完成先提交：代次 1 冻结；随后 DONE 禁止新签到 → 422
            assertThat(results.get(0)).isNotInstanceOf(Exception.class);
            assertThat(isStatus(results.get(1), 422)).isTrue();
            assertThat(execs).hasSize(1);
            assertThat(execs.get(0).status()).isEqualTo("FROZEN");
        } else {
            // 重签先提交：代次 1 SUPERSEDED、代次 2 ACTIVE；旧代次完成 422，任务仍 OPEN
            assertThat(taskStatus).isEqualTo("OPEN");
            assertThat(isStatus(results.get(0), 422)).isTrue();
            assertThat(results.get(1)).isNotInstanceOf(Exception.class);
            assertThat(execs).extracting(AttendanceView::generation, AttendanceView::status)
                    .containsExactly(
                            org.assertj.core.groups.Tuple.tuple(1, "SUPERSEDED"),
                            org.assertj.core.groups.Tuple.tuple(2, "ACTIVE"));
        }
    }

    @Test
    void cancelRacesComplete_singleTerminalState() throws Exception {
        guardedSignedTask("INC-C3");
        List<Object> results = runConcurrently(List.of(
                () -> service.completeTask("INC-C3", "T-1", "alice", completeV1()),
                () -> service.cancelTask("INC-C3", "T-1", "alice",
                        new TaskActionRequest(key()))));

        String taskStatus = service.getTask("INC-C3", "T-1").status();
        // 恰好一个终态操作成功，另一个因终态冲突 409；不存在半完成状态
        long success = results.stream().filter(r -> !(r instanceof Exception)).count();
        assertThat(success).isOne();
        long conflicts = results.stream().filter(r -> isStatus(r, 409)).count();
        assertThat(conflicts).isOne();
        assertThat(taskStatus).isIn("DONE", "CANCELLED");
        GuardianView cfg = service.getGuardianship("INC-C3", "T-1");
        if ("DONE".equals(taskStatus)) {
            assertThat(cfg.attendances()).extracting(AttendanceView::status)
                    .containsExactly("FROZEN", "FROZEN");
        } else {
            assertThat(cfg.attendances()).extracting(AttendanceView::status)
                    .containsExactly("ACTIVE", "ACTIVE");
        }
    }

    @Test
    void concurrentSameKeySign_singleEffect() throws Exception {
        service.report(new ReportRequest("INC-C4", "S2", "高危处置", "reporter-1"));
        service.takeover("INC-C4", "alice", new TakeoverRequest(key()));
        service.createTask("INC-C4", "alice",
                new TaskCreateRequest(key(), "T-1", "OPS", "处置", List.of()));
        service.enableGuardianship("INC-C4", "T-1", "alice",
                new GuardianEnableRequest(key(), "synth-exec", "synth-watch"));

        String signKey = key();
        List<Callable<?>> calls = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            calls.add(() -> service.signAttendance("INC-C4", "T-1", "synth-exec",
                    new AttendanceSignRequest(signKey)));
        }
        List<Object> results = runConcurrently(calls);

        List<AttendanceView> views = results.stream()
                .filter(AttendanceView.class::isInstance).map(AttendanceView.class::cast).toList();
        assertThat(views).isNotEmpty();
        assertThat(views).allSatisfy(v -> {
            assertThat(v.generation()).isOne();
            assertThat(v).isEqualTo(views.get(0));
        });
        // 只签发一条凭证、只占一个幂等键
        Integer attendanceRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM task_attendance", Integer.class);
        assertThat(attendanceRows).isOne();
        Integer keyRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM command_keys WHERE command_key = ?", Integer.class, signKey);
        assertThat(keyRows).isOne();
    }
}
