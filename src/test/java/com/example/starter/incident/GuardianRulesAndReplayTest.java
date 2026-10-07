package com.example.starter.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

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
 * 双人监护规则、权限与幂等重放测试（真实 H2 事务）：
 * 启用校验、本人签到 403、终态禁签、撤销权限先于重放、
 * 同键重放不延长有效期/旧完成返回首次证据、失败不占键、取消与完成共享凭证边界。
 */
@SpringBootTest
@Import(ControllableClock.Config.class)
class GuardianRulesAndReplayTest {

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

    private void createTask(String incidentKey, String commander, String taskKey) {
        service.createTask(incidentKey, commander,
                new TaskCreateRequest(key(), taskKey, "OPS", "处置", List.of()));
    }

    private void enable(String incidentKey, String commander, String taskKey) {
        service.enableGuardianship(incidentKey, taskKey, commander,
                new GuardianEnableRequest(key(), "synth-exec", "synth-watch"));
    }

    private static void assertApiStatus(ThrowingCallable call, HttpStatus status) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.status()).isEqualTo(status));
    }

    @Test
    void enableRules() {
        commanding("INC-E1", "alice");
        createTask("INC-E1", "alice", "T-1");
        // 两人相同 → 400
        assertApiStatus(() -> service.enableGuardianship("INC-E1", "T-1", "alice",
                new GuardianEnableRequest(key(), "x", "x")), HttpStatus.BAD_REQUEST);
        // 非当前指挥 → 409
        assertApiStatus(() -> service.enableGuardianship("INC-E1", "T-1", "bob",
                new GuardianEnableRequest(key(), "a", "b")), HttpStatus.CONFLICT);
        // 任务不存在 → 404
        assertApiStatus(() -> service.enableGuardianship("INC-E1", "T-9", "alice",
                new GuardianEnableRequest(key(), "a", "b")), HttpStatus.NOT_FOUND);

        // 启用成功，版本从 1 开始
        GuardianView first = service.enableGuardianship("INC-E1", "T-1", "alice",
                new GuardianEnableRequest(key(), "synth-exec", "synth-watch"));
        assertThat(first.version()).isOne();
        // 同 pair 重复启用：幂等返回首次配置（不同 commandKey）
        GuardianView again = service.enableGuardianship("INC-E1", "T-1", "alice",
                new GuardianEnableRequest(key(), "synth-exec", "synth-watch"));
        assertThat(again).isEqualTo(first);
        // 更换固定 pair → 409
        assertApiStatus(() -> service.enableGuardianship("INC-E1", "T-1", "alice",
                new GuardianEnableRequest(key(), "synth-exec", "other")), HttpStatus.CONFLICT);
        assertThat(service.listTasks("INC-E1").tasks()).hasSize(1);
    }

    @Test
    void signAuthAndTerminalStates() {
        commanding("INC-E2", "alice");
        createTask("INC-E2", "alice", "T-1");
        createTask("INC-E2", "alice", "T-2");
        // 未启用监护 → 409
        assertApiStatus(() -> service.signAttendance("INC-E2", "T-2", "synth-exec",
                new AttendanceSignRequest(key())), HttpStatus.CONFLICT);
        enable("INC-E2", "alice", "T-1");
        // 非固定监护人签到 → 403
        assertApiStatus(() -> service.signAttendance("INC-E2", "T-1", "alice",
                new AttendanceSignRequest(key())), HttpStatus.FORBIDDEN);
        assertApiStatus(() -> service.signAttendance("INC-E2", "T-1", "carol",
                new AttendanceSignRequest(key())), HttpStatus.FORBIDDEN);
        // 事件/任务不存在 → 404
        assertApiStatus(() -> service.signAttendance("INC-E2", "T-9", "synth-exec",
                new AttendanceSignRequest(key())), HttpStatus.NOT_FOUND);

        // DONE 后禁止新签到
        service.signAttendance("INC-E2", "T-1", "synth-exec",
                new AttendanceSignRequest(key()));
        service.signAttendance("INC-E2", "T-1", "synth-watch",
                new AttendanceSignRequest(key()));
        service.completeTask("INC-E2", "T-1", "alice",
                new TaskCompleteRequest(key(), 1, List.of(
                        new AttendanceRef("synth-exec", 1),
                        new AttendanceRef("synth-watch", 1))));
        assertApiStatus(() -> service.signAttendance("INC-E2", "T-1", "synth-exec",
                new AttendanceSignRequest(key())), HttpStatus.UNPROCESSABLE_ENTITY);

        // CANCELLED 也禁止新签到
        enable("INC-E2", "alice", "T-2");
        service.cancelTask("INC-E2", "T-2", "alice", new TaskActionRequest(key()));
        assertApiStatus(() -> service.signAttendance("INC-E2", "T-2", "synth-exec",
                new AttendanceSignRequest(key())), HttpStatus.UNPROCESSABLE_ENTITY);
        // 终态凭证不能撤销
        assertApiStatus(() -> service.revokeAttendance("INC-E2", "T-2", "alice",
                new AttendanceRevokeRequest(key(), "synth-exec")),
                HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void signReplay_sameKeyReturnsFirst_andDoesNotExtend() {
        commanding("INC-E3", "alice");
        createTask("INC-E3", "alice", "T-1");
        enable("INC-E3", "alice", "T-1");
        String signKey = key();
        AttendanceView first = service.signAttendance("INC-E3", "T-1", "synth-exec",
                new AttendanceSignRequest(signKey));
        assertThat(first.generation()).isOne();
        // 时钟推进到 10:00:30，同键重放：不重新签发、不延长有效期、不推进代次
        ((ControllableClock) clock).setInstant(Instant.parse("2026-10-08T10:00:30Z"));
        AttendanceView replay = service.signAttendance("INC-E3", "T-1", "synth-exec",
                new AttendanceSignRequest(signKey));
        assertThat(replay).isEqualTo(first);
        assertThat(replay.issuedAt()).isEqualTo(T1000);
        assertThat(replay.expiresAt()).isEqualTo(Instant.parse("2026-10-08T10:01:00Z"));
        Integer rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM task_attendance WHERE task_id ="
                        + " (SELECT id FROM incident_tasks WHERE task_key = 'T-1')",
                Integer.class);
        assertThat(rows).isOne();
        // 同键不同操作者（改参）→ 409
        assertApiStatus(() -> service.signAttendance("INC-E3", "T-1", "synth-watch",
                new AttendanceSignRequest(signKey)), HttpStatus.CONFLICT);
    }

    @Test
    void completeReplay_oldSuccessReturnsFirstEvidence() {
        commanding("INC-E4", "alice");
        createTask("INC-E4", "alice", "T-1");
        enable("INC-E4", "alice", "T-1");
        service.signAttendance("INC-E4", "T-1", "synth-exec",
                new AttendanceSignRequest(key()));
        ((ControllableClock) clock).setInstant(Instant.parse("2026-10-08T10:00:10Z"));
        service.signAttendance("INC-E4", "T-1", "synth-watch",
                new AttendanceSignRequest(key()));
        ((ControllableClock) clock).setInstant(Instant.parse("2026-10-08T10:00:20Z"));
        String completeKey = key();
        TaskCompleteRequest req = new TaskCompleteRequest(completeKey, 1, List.of(
                new AttendanceRef("synth-exec", 1), new AttendanceRef("synth-watch", 1)));
        TaskView done = service.completeTask("INC-E4", "T-1", "alice", req);
        assertThat(done.doneAt()).isEqualTo(Instant.parse("2026-10-08T10:00:20Z"));
        // 时钟走过有效期后同键重放：返回首次证据（doneAt 不变），不报凭证失效
        ((ControllableClock) clock).setInstant(Instant.parse("2026-10-08T10:05:00Z"));
        TaskView replay = service.completeTask("INC-E4", "T-1", "alice", req);
        assertThat(replay).isEqualTo(done);
        // 同键改参（引用代次变化）→ 409
        assertApiStatus(() -> service.completeTask("INC-E4", "T-1", "alice",
                new TaskCompleteRequest(completeKey, 1, List.of(
                        new AttendanceRef("synth-exec", 2),
                        new AttendanceRef("synth-watch", 1)))), HttpStatus.CONFLICT);
    }

    @Test
    void failedSign_doesNotConsumeKey_andPermissionBeforeReplay() {
        commanding("INC-E5", "alice");
        createTask("INC-E5", "alice", "T-1");
        enable("INC-E5", "alice", "T-1");
        String signKey = key();
        // 非监护人用该键签到：403，事务回滚不占键
        assertApiStatus(() -> service.signAttendance("INC-E5", "T-1", "carol",
                new AttendanceSignRequest(signKey)), HttpStatus.FORBIDDEN);
        // 监护人本人用同一键可成功
        AttendanceView ok = service.signAttendance("INC-E5", "T-1", "synth-exec",
                new AttendanceSignRequest(signKey));
        assertThat(ok.generation()).isOne();

        // 权限先于重放：非指挥携带一个已成功撤销键重放 → 403 而非首次响应/409
        service.signAttendance("INC-E5", "T-1", "synth-watch",
                new AttendanceSignRequest(key()));
        String revokeKey = key();
        AttendanceView revoked = service.revokeAttendance("INC-E5", "T-1", "alice",
                new AttendanceRevokeRequest(revokeKey, "synth-exec"));
        assertThat(revoked.status()).isEqualTo("REVOKED");
        assertApiStatus(() -> service.revokeAttendance("INC-E5", "T-1", "bob",
                new AttendanceRevokeRequest(revokeKey, "synth-exec")), HttpStatus.FORBIDDEN);
    }

    @Test
    void cancelAndComplete_shareAttendanceBoundary() {
        commanding("INC-E6", "alice");
        createTask("INC-E6", "alice", "T-1");
        enable("INC-E6", "alice", "T-1");
        service.signAttendance("INC-E6", "T-1", "synth-exec",
                new AttendanceSignRequest(key()));
        service.signAttendance("INC-E6", "T-1", "synth-watch",
                new AttendanceSignRequest(key()));
        // 取消 OPEN 任务：终态后原引用不能再完成（409 终态冲突，走幂等新键）
        TaskView cancelled = service.cancelTask("INC-E6", "T-1", "alice",
                new TaskActionRequest(key()));
        assertThat(cancelled.status()).isEqualTo("CANCELLED");
        assertApiStatus(() -> service.completeTask("INC-E6", "T-1", "alice",
                new TaskCompleteRequest(key(), 1, List.of(
                        new AttendanceRef("synth-exec", 1),
                        new AttendanceRef("synth-watch", 1)))), HttpStatus.CONFLICT);
        // ACTIVE 凭证未被完成冻结；指挥可在另一任务语义下查证其仍为 ACTIVE（查询历史可见）
        GuardianView cfg = service.getGuardianship("INC-E6", "T-1");
        assertThat(cfg.attendances()).extracting(AttendanceView::status)
                .containsExactly("ACTIVE", "ACTIVE");
    }

    @Test
    void unguardedTask_completesWithoutAttendance_backwardCompatible() {
        commanding("INC-E7", "alice");
        createTask("INC-E7", "alice", "T-1");
        // 未启用监护：新入口不带版本/引用，或原入口仅 commandKey，均可完成
        TaskView viaNew = service.completeTask("INC-E7", "T-1", "alice",
                new TaskCompleteRequest(key(), null, null));
        assertThat(viaNew.status()).isEqualTo("DONE");
    }
}
