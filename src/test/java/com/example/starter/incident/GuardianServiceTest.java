package com.example.starter.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.example.starter.incident.dto.Requests.AttendanceRevokeRequest;
import com.example.starter.incident.dto.Requests.AttendanceSignRequest;
import com.example.starter.incident.dto.Requests.GuardianEnableRequest;
import com.example.starter.incident.dto.Requests.ReportRequest;
import com.example.starter.incident.dto.Requests.TakeoverRequest;
import com.example.starter.incident.dto.Requests.TaskCompleteRequest;
import com.example.starter.incident.dto.Requests.TaskCreateRequest;
import com.example.starter.incident.dto.Responses.AttendanceView;
import com.example.starter.incident.dto.Responses.GuardianView;
import com.example.starter.incident.dto.Responses.TaskView;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 双人监护与限时在场凭证主流程测试（真实 H2 事务）：
 * 合成操作者 10:00:00、10:00:10 签到，10:00:59 可完成；
 * 10:01:00 第一人到期后完成 422，重新签到须引用新代次。
 * 使用可控 Clock，不依赖真实睡眠。
 */
@SpringBootTest
@Import(ControllableClock.Config.class)
class GuardianServiceTest {

    private static final Instant T1000 = Instant.parse("2026-10-08T10:00:00Z");

    @Autowired
    private IncidentService service;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Clock clock;

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
        ((ControllableClock) clock).setInstant(T1000);
    }

    private static String key() {
        return "CMD-" + UUID.randomUUID();
    }

    private void commanding(String incidentKey, String commander) {
        service.report(new ReportRequest(incidentKey, "S2", "高危处置", "reporter-1"));
        service.takeover(incidentKey, commander, new TakeoverRequest(key()));
    }

    private void guardedTask(String incidentKey, String commander, String taskKey) {
        service.createTask(incidentKey, commander,
                new TaskCreateRequest(key(), taskKey, "OPS", "带压堵漏", List.of()));
        GuardianView cfg = service.enableGuardianship(incidentKey, taskKey, commander,
                new GuardianEnableRequest(key(), "synth-exec", "synth-watch"));
        assertThat(cfg.version()).isEqualTo(1);
        assertThat(cfg.guardianOne()).isEqualTo("synth-exec");
        assertThat(cfg.guardianTwo()).isEqualTo("synth-watch");
        assertThat(cfg.attendances()).isEmpty();
    }

    private AttendanceView sign(String incidentKey, String taskKey, String guardian) {
        return service.signAttendance(incidentKey, taskKey, guardian,
                new AttendanceSignRequest(key()));
    }

    private static void assertApiStatus(ThrowingCallable call, HttpStatus status) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.status()).isEqualTo(status));
    }

    private static TaskCompleteRequest completeReq(int execGen, int watchGen) {
        return new TaskCompleteRequest(key(), 1, List.of(
                new com.example.starter.incident.dto.Requests.AttendanceRef("synth-exec", execGen),
                new com.example.starter.incident.dto.Requests.AttendanceRef("synth-watch", watchGen)));
    }

    @Test
    void signSixtySecondsWindow_andCompleteBeforeExpiry() {
        commanding("INC-G1", "alice");
        guardedTask("INC-G1", "alice", "T-1");

        // 10:00:00 执行人签到：代次 1，[10:00:00, 10:01:00)
        AttendanceView exec = sign("INC-G1", "T-1", "synth-exec");
        assertThat(exec.generation()).isEqualTo(1);
        assertThat(exec.issuedAt()).isEqualTo(T1000);
        assertThat(exec.expiresAt()).isEqualTo(Instant.parse("2026-10-08T10:01:00Z"));
        assertThat(exec.status()).isEqualTo("ACTIVE");

        // 10:00:10 监护人签到：代次 1，[10:00:10, 10:01:10)
        ((ControllableClock) clock).setInstant(Instant.parse("2026-10-08T10:00:10Z"));
        AttendanceView watch = sign("INC-G1", "T-1", "synth-watch");
        assertThat(watch.generation()).isOne();
        assertThat(watch.issuedAt()).isEqualTo(Instant.parse("2026-10-08T10:00:10Z"));

        // 10:00:59 双方均在有效期内（左闭右开），可完成
        ((ControllableClock) clock).setInstant(Instant.parse("2026-10-08T10:00:59Z"));
        TaskView done = service.completeTask("INC-G1", "T-1", "alice",
                completeReq(1, 1));
        assertThat(done.status()).isEqualTo("DONE");
        assertThat(done.doneAt()).isEqualTo(Instant.parse("2026-10-08T10:00:59Z"));

        // 双方凭证冻结，签发/到期时刻保持不变
        GuardianView cfg = service.getGuardianship("INC-G1", "T-1");
        assertThat(cfg.attendances()).extracting(AttendanceView::guardian, AttendanceView::generation,
                AttendanceView::status, AttendanceView::issuedAt, AttendanceView::expiresAt)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("synth-exec", 1, "FROZEN",
                                T1000, Instant.parse("2026-10-08T10:01:00Z")),
                        org.assertj.core.groups.Tuple.tuple("synth-watch", 1, "FROZEN",
                                Instant.parse("2026-10-08T10:00:10Z"),
                                Instant.parse("2026-10-08T10:01:10Z")));
    }

    @Test
    void firstExpiresAt100100_thenResignRequiresNewGeneration() {
        commanding("INC-G2", "alice");
        guardedTask("INC-G2", "alice", "T-1");
        sign("INC-G2", "T-1", "synth-exec");
        ((ControllableClock) clock).setInstant(Instant.parse("2026-10-08T10:00:10Z"));
        sign("INC-G2", "T-1", "synth-watch");

        // 10:01:00 第一人凭证恰好到期（右边界不含）：完成 422，任务不变
        ((ControllableClock) clock).setInstant(Instant.parse("2026-10-08T10:01:00Z"));
        assertApiStatus(() -> service.completeTask("INC-G2", "T-1", "alice",
                completeReq(1, 1)), HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(service.getTask("INC-G2", "T-1").status()).isEqualTo("OPEN");

        // 引用旧代次仍然 422（即使重新签到后，完成必须引用新代次）
        AttendanceView exec2 = sign("INC-G2", "T-1", "synth-exec");
        assertThat(exec2.generation()).isEqualTo(2);
        assertThat(exec2.issuedAt()).isEqualTo(Instant.parse("2026-10-08T10:01:00Z"));
        assertApiStatus(() -> service.completeTask("INC-G2", "T-1", "alice",
                completeReq(1, 1)), HttpStatus.UNPROCESSABLE_ENTITY);

        // 前代立即失效（SUPERSEDED）
        GuardianView cfg = service.getGuardianship("INC-G2", "T-1");
        assertThat(cfg.attendances()).filteredOn(a -> a.guardian().equals("synth-exec"))
                .extracting(AttendanceView::generation, AttendanceView::status)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(1, "SUPERSEDED"),
                        org.assertj.core.groups.Tuple.tuple(2, "ACTIVE"));

        // 引用双方当前代次（exec=2, watch=1 仍有效至 10:01:10）完成成功
        ((ControllableClock) clock).setInstant(Instant.parse("2026-10-08T10:01:05Z"));
        TaskView done = service.completeTask("INC-G2", "T-1", "alice",
                completeReq(2, 1));
        assertThat(done.status()).isEqualTo("DONE");
    }

    @Test
    void missingRefs_versionMismatch_unknownGeneration_are422_andNothingChanges() {
        commanding("INC-G3", "alice");
        guardedTask("INC-G3", "alice", "T-1");
        sign("INC-G3", "T-1", "synth-exec");
        ((ControllableClock) clock).setInstant(Instant.parse("2026-10-08T10:00:10Z"));
        sign("INC-G3", "T-1", "synth-watch");
        ((ControllableClock) clock).setInstant(Instant.parse("2026-10-08T10:00:20Z"));

        // 缺少双方引用：原完成入口（仅 commandKey）对双人任务 422
        assertApiStatus(() -> service.completeTask("INC-G3", "T-1", "alice",
                new com.example.starter.incident.dto.Requests.TaskActionRequest(key())),
                HttpStatus.UNPROCESSABLE_ENTITY);
        // expectedVersion 缺失
        assertApiStatus(() -> service.completeTask("INC-G3", "T-1", "alice",
                new TaskCompleteRequest(key(), null,
                        List.of(new com.example.starter.incident.dto.Requests.AttendanceRef(
                                "synth-exec", 1),
                                new com.example.starter.incident.dto.Requests.AttendanceRef(
                                        "synth-watch", 1)))),
                HttpStatus.UNPROCESSABLE_ENTITY);
        // 版本不符
        assertApiStatus(() -> service.completeTask("INC-G3", "T-1", "alice",
                new TaskCompleteRequest(key(), 2, List.of(
                        new com.example.starter.incident.dto.Requests.AttendanceRef("synth-exec", 1),
                        new com.example.starter.incident.dto.Requests.AttendanceRef(
                                "synth-watch", 1)))),
                HttpStatus.UNPROCESSABLE_ENTITY);
        // 旧/不存在代次
        assertApiStatus(() -> service.completeTask("INC-G3", "T-1", "alice",
                completeReq(2, 1)), HttpStatus.UNPROCESSABLE_ENTITY);
        // 只引用一人
        assertApiStatus(() -> service.completeTask("INC-G3", "T-1", "alice",
                new TaskCompleteRequest(key(), 1, List.of(
                        new com.example.starter.incident.dto.Requests.AttendanceRef(
                                "synth-exec", 1)))),
                HttpStatus.UNPROCESSABLE_ENTITY);
        // 引用非固定监护人
        assertApiStatus(() -> service.completeTask("INC-G3", "T-1", "alice",
                new TaskCompleteRequest(key(), 1, List.of(
                        new com.example.starter.incident.dto.Requests.AttendanceRef("synth-exec", 1),
                        new com.example.starter.incident.dto.Requests.AttendanceRef("bob", 1)))),
                HttpStatus.UNPROCESSABLE_ENTITY);
        // 全部 422 均不改变任务与凭证
        assertThat(service.getTask("INC-G3", "T-1").status()).isEqualTo("OPEN");
        assertThat(service.getGuardianship("INC-G3", "T-1").attendances())
                .allSatisfy(a -> assertThat(a.status()).isEqualTo("ACTIVE"));
    }

    @Test
    void revokeInvalidates_andOnlyCommanderMayRevoke() {
        commanding("INC-G4", "alice");
        guardedTask("INC-G4", "alice", "T-1");
        sign("INC-G4", "T-1", "synth-exec");
        ((ControllableClock) clock).setInstant(Instant.parse("2026-10-08T10:00:10Z"));
        sign("INC-G4", "T-1", "synth-watch");
        ((ControllableClock) clock).setInstant(Instant.parse("2026-10-08T10:00:20Z"));

        // 非当前指挥撤销 → 403
        assertApiStatus(() -> service.revokeAttendance("INC-G4", "T-1", "bob",
                new AttendanceRevokeRequest(key(), "synth-exec")), HttpStatus.FORBIDDEN);
        // 监护人本人也不能撤销（只有指挥可撤销）→ 403
        assertApiStatus(() -> service.revokeAttendance("INC-G4", "T-1", "synth-exec",
                new AttendanceRevokeRequest(key(), "synth-watch")), HttpStatus.FORBIDDEN);

        // 指挥撤销执行人凭证
        AttendanceView revoked = service.revokeAttendance("INC-G4", "T-1", "alice",
                new AttendanceRevokeRequest(key(), "synth-exec"));
        assertThat(revoked.status()).isEqualTo("REVOKED");
        assertThat(revoked.revokedAt()).isEqualTo(Instant.parse("2026-10-08T10:00:20Z"));
        // 一方失效后完成 422
        assertApiStatus(() -> service.completeTask("INC-G4", "T-1", "alice",
                completeReq(1, 1)), HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(service.getTask("INC-G4", "T-1").status()).isEqualTo("OPEN");
        // 重复撤销无 ACTIVE 凭证 → 409
        assertApiStatus(() -> service.revokeAttendance("INC-G4", "T-1", "alice",
                new AttendanceRevokeRequest(key(), "synth-exec")), HttpStatus.CONFLICT);
    }
}
