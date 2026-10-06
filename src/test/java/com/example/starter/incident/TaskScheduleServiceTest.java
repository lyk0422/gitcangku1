package com.example.starter.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.example.starter.incident.dto.Requests.ReportRequest;
import com.example.starter.incident.dto.Requests.StatusRequest;
import com.example.starter.incident.dto.Requests.TakeoverRequest;
import com.example.starter.incident.dto.Requests.TaskActionRequest;
import com.example.starter.incident.dto.Requests.TaskCreateRequest;
import com.example.starter.incident.dto.Requests.TaskScheduleRequest;
import com.example.starter.incident.dto.Requests.TaskScheduleRevokeRequest;
import com.example.starter.incident.dto.Requests.TaskStartRequest;
import com.example.starter.incident.dto.Requests.TransferAcceptRequest;
import com.example.starter.incident.dto.Requests.TransferRequest;
import com.example.starter.incident.dto.Responses.IncidentView;
import com.example.starter.incident.dto.Responses.ScheduleViolationView;
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
 * 执行时段安排服务测试：覆盖安排创建/替换与版本链、整分钟与时长校验、
 * 相邻休息 30 分钟与 UTC 日上限 480 分钟（跨午夜拆日）、开始门禁（服务端 Clock
 * 与依赖解除）、完成入口开始门禁、取消/撤销对约束的释放与保留、requestId 幂等
 * 及权限先于重放。使用可控 Clock，时间断言不依赖真实睡眠。
 */
@SpringBootTest
@Import(ControllableClock.Config.class)
class TaskScheduleServiceTest {

    private static final Instant T0 = Instant.parse("2026-10-06T00:00:00Z");

    @Autowired
    private IncidentService service;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Clock clock;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM command_keys");
        jdbc.update("DELETE FROM incident_status_history");
        jdbc.update("DELETE FROM incident_transfers");
        jdbc.update("DELETE FROM incident_actions");
        jdbc.update("DELETE FROM incident_task_blockers");
        jdbc.update("DELETE FROM incident_task_schedules");
        jdbc.update("DELETE FROM incident_tasks");
        jdbc.update("DELETE FROM incidents");
        ((ControllableClock) clock).setInstant(T0);
    }

    private static String key() {
        return "REQ-" + UUID.randomUUID();
    }

    private IncidentView commanding(String incidentKey, String commander) {
        service.report(new ReportRequest(incidentKey, "S2", "核心链路故障", "reporter-1"));
        return service.takeover(incidentKey, commander, new TakeoverRequest(key()));
    }

    private TaskView createTask(String incidentKey, String actor, String taskKey) {
        return service.createTask(incidentKey, actor,
                new TaskCreateRequest(key(), taskKey, "G", "t", List.of()));
    }

    private static TaskScheduleRequest scheduleReq(String requestId, int version, String assignee,
                                                   String start, String end) {
        return new TaskScheduleRequest(requestId, version, assignee,
                Instant.parse(start), Instant.parse(end));
    }

    private static void assertApiStatus(ThrowingCallable call, HttpStatus status) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.status()).isEqualTo(status));
    }

    @Test
    void scheduleCreate_replaceAndVersionChain() {
        commanding("INC-S1", "alice");
        TaskView task = createTask("INC-S1", "alice", "T-1");
        assertThat(task.version()).isEqualTo(1);
        assertThat(task.schedule()).isNull();
        TaskView scheduled = service.scheduleTask("INC-S1", "T-1", "alice",
                scheduleReq(key(), 1, "worker-1", "2026-10-06T23:00:00Z", "2026-10-07T01:00:00Z"));
        assertThat(scheduled.version()).isEqualTo(2);
        assertThat(scheduled.schedule().assignee()).isEqualTo("worker-1");
        assertThat(scheduled.schedule().status()).isEqualTo("SCHEDULED");
        assertThat(scheduled.schedule().startAt()).isEqualTo(Instant.parse("2026-10-06T23:00:00Z"));
        assertThat(scheduled.schedule().startedAt()).isNull();
        // 替换未开始安排：整行更新，版本再加一
        TaskView replaced = service.scheduleTask("INC-S1", "T-1", "alice",
                scheduleReq(key(), 2, "worker-2", "2026-10-06T22:00:00Z", "2026-10-06T23:00:00Z"));
        assertThat(replaced.version()).isEqualTo(3);
        assertThat(replaced.schedule().assignee()).isEqualTo("worker-2");
        assertThat(replaced.schedule().startAt()).isEqualTo(Instant.parse("2026-10-06T22:00:00Z"));
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM incident_task_schedules", Integer.class);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void scheduleCreate_validation() {
        commanding("INC-V1", "alice");
        createTask("INC-V1", "alice", "T-1");
        // 非整分钟 → 400
        assertApiStatus(() -> service.scheduleTask("INC-V1", "T-1", "alice",
                new TaskScheduleRequest(key(), 1, "w", Instant.parse("2026-10-06T10:00:30Z"),
                        Instant.parse("2026-10-06T11:00:00Z"))), HttpStatus.BAD_REQUEST);
        // 长度 0 或超过 240 分钟 → 400
        assertApiStatus(() -> service.scheduleTask("INC-V1", "T-1", "alice",
                scheduleReq(key(), 1, "w", "2026-10-06T10:00:00Z", "2026-10-06T10:00:00Z")),
                HttpStatus.BAD_REQUEST);
        assertApiStatus(() -> service.scheduleTask("INC-V1", "T-1", "alice",
                scheduleReq(key(), 1, "w", "2026-10-06T10:00:00Z", "2026-10-06T14:01:00Z")),
                HttpStatus.BAD_REQUEST);
        // assignee/requestId/expectedTaskVersion 缺失 → 400
        assertApiStatus(() -> service.scheduleTask("INC-V1", "T-1", "alice",
                scheduleReq(key(), 1, " ", "2026-10-06T10:00:00Z", "2026-10-06T11:00:00Z")),
                HttpStatus.BAD_REQUEST);
        assertApiStatus(() -> service.scheduleTask("INC-V1", "T-1", "alice",
                scheduleReq(" ", 1, "w", "2026-10-06T10:00:00Z", "2026-10-06T11:00:00Z")),
                HttpStatus.BAD_REQUEST);
        assertApiStatus(() -> service.scheduleTask("INC-V1", "T-1", "alice",
                new TaskScheduleRequest(key(), null, "w", Instant.parse("2026-10-06T10:00:00Z"),
                        Instant.parse("2026-10-06T11:00:00Z"))), HttpStatus.BAD_REQUEST);
        // 版本不匹配 → 409
        assertApiStatus(() -> service.scheduleTask("INC-V1", "T-1", "alice",
                scheduleReq(key(), 3, "w", "2026-10-06T10:00:00Z", "2026-10-06T11:00:00Z")),
                HttpStatus.CONFLICT);
        // 非当前指挥人 → 409
        assertApiStatus(() -> service.scheduleTask("INC-V1", "T-1", "bob",
                scheduleReq(key(), 1, "w", "2026-10-06T10:00:00Z", "2026-10-06T11:00:00Z")),
                HttpStatus.CONFLICT);
        // 任务不存在 → 404
        assertApiStatus(() -> service.scheduleTask("INC-V1", "T-9", "alice",
                scheduleReq(key(), 1, "w", "2026-10-06T10:00:00Z", "2026-10-06T11:00:00Z")),
                HttpStatus.NOT_FOUND);
        // 终态任务 → 409
        service.cancelTask("INC-V1", "T-1", "alice", new TaskActionRequest(key()));
        assertApiStatus(() -> service.scheduleTask("INC-V1", "T-1", "alice",
                scheduleReq(key(), 2, "w", "2026-10-06T10:00:00Z", "2026-10-06T11:00:00Z")),
                HttpStatus.CONFLICT);
    }

    @Test
    void restRule_crossMidnightSynthetic() {
        commanding("INC-R1", "alice");
        commanding("INC-R2", "alice");
        createTask("INC-R1", "alice", "T-1");
        createTask("INC-R2", "alice", "T-2");
        // 合成跨日 23:00~01:00：两日各计 60 分钟
        service.scheduleTask("INC-R1", "T-1", "alice",
                scheduleReq(key(), 1, "worker-1", "2026-10-06T23:00:00Z", "2026-10-07T01:00:00Z"));
        // 同人 01:20 开始：休息仅 20 分钟 → 422，给出日期与差额 10
        assertThatThrownBy(() -> service.scheduleTask("INC-R2", "T-2", "alice",
                scheduleReq(key(), 1, "worker-1", "2026-10-07T01:20:00Z", "2026-10-07T02:00:00Z")))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(e.code()).isEqualTo("SCHEDULE_VIOLATION");
                    assertThat(e.details()).isEqualTo(
                            new ScheduleViolationView("REST", "2026-10-07", 10L));
                });
        // 01:30 开始：恰好休息 30 分钟 → 允许
        TaskView ok = service.scheduleTask("INC-R2", "T-2", "alice",
                scheduleReq(key(), 1, "worker-1", "2026-10-07T01:30:00Z", "2026-10-07T02:00:00Z"));
        assertThat(ok.schedule().status()).isEqualTo("SCHEDULED");
        // 时段重叠同样视为休息不足 → 422
        createTask("INC-R2", "alice", "T-3");
        assertApiStatus(() -> service.scheduleTask("INC-R2", "T-3", "alice",
                scheduleReq(key(), 1, "worker-1", "2026-10-07T01:00:00Z", "2026-10-07T01:40:00Z")),
                HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void dailyLimit_perUtcDayWithSplit() {
        commanding("INC-D1", "alice");
        commanding("INC-D2", "alice");
        createTask("INC-D1", "alice", "T-1");
        createTask("INC-D1", "alice", "T-2");
        createTask("INC-D2", "alice", "T-3");
        // 单次安排最长 240 分钟：两段各 240 分钟合计达到当日上限 480
        service.scheduleTask("INC-D1", "T-1", "alice",
                scheduleReq(key(), 1, "worker-1", "2026-10-06T08:00:00Z", "2026-10-06T12:00:00Z"));
        service.scheduleTask("INC-D1", "T-2", "alice",
                scheduleReq(key(), 1, "worker-1", "2026-10-06T12:30:00Z", "2026-10-06T16:30:00Z"));
        // 17:00~17:30 休息满足但当日总量 510 → 422，差额 30
        assertThatThrownBy(() -> service.scheduleTask("INC-D2", "T-3", "alice",
                scheduleReq(key(), 1, "worker-1", "2026-10-06T17:00:00Z", "2026-10-06T17:30:00Z")))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(e.details()).isEqualTo(
                            new ScheduleViolationView("DAILY_LIMIT", "2026-10-06", 30L));
                });
        // 次日同时段不受前一日上限影响
        TaskView ok = service.scheduleTask("INC-D2", "T-3", "alice",
                scheduleReq(key(), 1, "worker-1", "2026-10-07T08:00:00Z", "2026-10-07T12:00:00Z"));
        assertThat(ok.schedule().status()).isEqualTo("SCHEDULED");
    }

    @Test
    void dailyLimit_crossMidnightSplit() {
        commanding("INC-M1", "alice");
        commanding("INC-M2", "alice");
        createTask("INC-M1", "alice", "T-1");
        createTask("INC-M2", "alice", "T-2");
        createTask("INC-M2", "alice", "T-3");
        createTask("INC-M2", "alice", "T-4");
        // 跨日 23:00~01:00：首日 60、次日 60
        service.scheduleTask("INC-M1", "T-1", "alice",
                scheduleReq(key(), 1, "worker-1", "2026-10-06T23:00:00Z", "2026-10-07T01:00:00Z"));
        // 次日再排 240 + 180：60+240+180=480 恰好达标
        service.scheduleTask("INC-M2", "T-2", "alice",
                scheduleReq(key(), 1, "worker-1", "2026-10-07T08:00:00Z", "2026-10-07T12:00:00Z"));
        service.scheduleTask("INC-M2", "T-3", "alice",
                scheduleReq(key(), 1, "worker-1", "2026-10-07T12:30:00Z", "2026-10-07T15:30:00Z"));
        // 再排 1 分钟即 481 → 422，差额 1，日期为次日（跨午夜拆分计入的 60 生效）
        assertThatThrownBy(() -> service.scheduleTask("INC-M2", "T-4", "alice",
                scheduleReq(key(), 1, "worker-1", "2026-10-07T16:00:00Z", "2026-10-07T16:01:00Z")))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(e.details()).isEqualTo(
                            new ScheduleViolationView("DAILY_LIMIT", "2026-10-07", 1L));
                });
    }

    @Test
    void startGate_timeWindowAndBlockers() {
        commanding("INC-G1", "alice");
        commanding("INC-G2", "bob");
        service.createTask("INC-G1", "alice",
                new TaskCreateRequest(key(), "T-1", "G", "t", List.of("INC-G2")));
        service.scheduleTask("INC-G1", "T-1", "alice",
                scheduleReq(key(), 1, "worker-1", "2026-10-06T10:00:00Z", "2026-10-06T11:00:00Z"));
        // 未到开始时刻 → 422
        ((ControllableClock) clock).setInstant(Instant.parse("2026-10-06T09:59:00Z"));
        assertApiStatus(() -> service.startTask("INC-G1", "T-1", "alice",
                new TaskStartRequest(key(), 2)), HttpStatus.UNPROCESSABLE_ENTITY);
        // 阻塞未解除 → 422
        ((ControllableClock) clock).setInstant(Instant.parse("2026-10-06T10:00:00Z"));
        assertApiStatus(() -> service.startTask("INC-G1", "T-1", "alice",
                new TaskStartRequest(key(), 2)), HttpStatus.UNPROCESSABLE_ENTITY);
        // 解除阻塞后开始成功：版本 2→3，安排 STARTED 并记录开始时刻
        service.changeStatus("INC-G2", "bob", new StatusRequest(key(), "CONTAINED"));
        TaskView started = service.startTask("INC-G1", "T-1", "alice",
                new TaskStartRequest(key(), 2));
        assertThat(started.version()).isEqualTo(3);
        assertThat(started.schedule().status()).isEqualTo("STARTED");
        assertThat(started.schedule().startedAt())
                .isEqualTo(Instant.parse("2026-10-06T10:00:00Z"));
        // 重复开始（新 requestId）→ 409；已开始不可改时 → 409
        assertApiStatus(() -> service.startTask("INC-G1", "T-1", "alice",
                new TaskStartRequest(key(), 3)), HttpStatus.CONFLICT);
        assertApiStatus(() -> service.scheduleTask("INC-G1", "T-1", "alice",
                scheduleReq(key(), 3, "worker-1", "2026-10-06T12:00:00Z", "2026-10-06T13:00:00Z")),
                HttpStatus.CONFLICT);
        // 时段结束后才开始 → 422
        createTask("INC-G1", "alice", "T-2");
        service.scheduleTask("INC-G1", "T-2", "alice",
                scheduleReq(key(), 1, "worker-2", "2026-10-06T10:00:00Z", "2026-10-06T11:00:00Z"));
        ((ControllableClock) clock).setInstant(Instant.parse("2026-10-06T11:00:00Z"));
        assertApiStatus(() -> service.startTask("INC-G1", "T-2", "alice",
                new TaskStartRequest(key(), 2)), HttpStatus.UNPROCESSABLE_ENTITY);
        // 未安排任务不能开始 → 422
        createTask("INC-G1", "alice", "T-3");
        assertApiStatus(() -> service.startTask("INC-G1", "T-3", "alice",
                new TaskStartRequest(key(), 1)), HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void completeRequiresStart_forScheduledTask() {
        commanding("INC-C1", "alice");
        createTask("INC-C1", "alice", "T-1");
        service.scheduleTask("INC-C1", "T-1", "alice",
                scheduleReq(key(), 1, "worker-1", "2026-10-06T10:00:00Z", "2026-10-06T11:00:00Z"));
        ((ControllableClock) clock).setInstant(Instant.parse("2026-10-06T10:30:00Z"));
        // 已安排未开始：结束端点不允许开始 → 409
        assertApiStatus(() -> service.completeTask("INC-C1", "T-1", "alice",
                new TaskActionRequest(key())), HttpStatus.CONFLICT);
        // 开始后完成成功，版本 3→4，安排保留 STARTED 作业量
        service.startTask("INC-C1", "T-1", "alice", new TaskStartRequest(key(), 2));
        TaskView done = service.completeTask("INC-C1", "T-1", "alice",
                new TaskActionRequest(key()));
        assertThat(done.status()).isEqualTo("DONE");
        assertThat(done.version()).isEqualTo(4);
        assertThat(done.schedule().status()).isEqualTo("STARTED");
        // 未安排任务维持原完成语义（仅阻塞门禁）
        createTask("INC-C1", "alice", "T-2");
        TaskView done2 = service.completeTask("INC-C1", "T-2", "alice",
                new TaskActionRequest(key()));
        assertThat(done2.status()).isEqualTo("DONE");
        assertThat(done2.version()).isEqualTo(2);
    }

    @Test
    void cancelTask_unstartedScheduleReleased() {
        commanding("INC-X1", "alice");
        commanding("INC-X2", "alice");
        createTask("INC-X1", "alice", "T-1");
        createTask("INC-X2", "alice", "T-2");
        service.scheduleTask("INC-X1", "T-1", "alice",
                scheduleReq(key(), 1, "worker-1", "2026-10-06T10:00:00Z", "2026-10-06T11:00:00Z"));
        // 取消未开始任务：安排同事务撤销并释放休息约束，版本 2→3
        TaskView cancelled = service.cancelTask("INC-X1", "T-1", "alice",
                new TaskActionRequest(key()));
        assertThat(cancelled.version()).isEqualTo(3);
        assertThat(cancelled.schedule().status()).isEqualTo("REVOKED");
        TaskView ok = service.scheduleTask("INC-X2", "T-2", "alice",
                scheduleReq(key(), 1, "worker-1", "2026-10-06T10:00:00Z", "2026-10-06T11:00:00Z"));
        assertThat(ok.schedule().status()).isEqualTo("SCHEDULED");
    }

    @Test
    void startedSchedule_retainedAfterCancel() {
        commanding("INC-Y1", "alice");
        commanding("INC-Y2", "alice");
        createTask("INC-Y1", "alice", "T-1");
        createTask("INC-Y2", "alice", "T-2");
        service.scheduleTask("INC-Y1", "T-1", "alice",
                scheduleReq(key(), 1, "worker-1", "2026-10-06T10:00:00Z", "2026-10-06T11:00:00Z"));
        ((ControllableClock) clock).setInstant(Instant.parse("2026-10-06T10:30:00Z"));
        service.startTask("INC-Y1", "T-1", "alice", new TaskStartRequest(key(), 2));
        // 取消已开始任务：安排保留 STARTED，原作业量仍计入休息约束
        TaskView cancelled = service.cancelTask("INC-Y1", "T-1", "alice",
                new TaskActionRequest(key()));
        assertThat(cancelled.schedule().status()).isEqualTo("STARTED");
        assertApiStatus(() -> service.scheduleTask("INC-Y2", "T-2", "alice",
                scheduleReq(key(), 1, "worker-1", "2026-10-06T11:00:00Z", "2026-10-06T12:00:00Z")),
                HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void revokeSchedule_flow() {
        commanding("INC-K1", "alice");
        createTask("INC-K1", "alice", "T-1");
        // 无安排 → 409
        assertApiStatus(() -> service.revokeSchedule("INC-K1", "T-1", "alice",
                new TaskScheduleRevokeRequest(key(), 1)), HttpStatus.CONFLICT);
        service.scheduleTask("INC-K1", "T-1", "alice",
                scheduleReq(key(), 1, "worker-1", "2026-10-06T10:00:00Z", "2026-10-06T11:00:00Z"));
        // 撤销成功：状态 REVOKED，版本保持 2
        TaskView revoked = service.revokeSchedule("INC-K1", "T-1", "alice",
                new TaskScheduleRevokeRequest(key(), 2));
        assertThat(revoked.version()).isEqualTo(2);
        assertThat(revoked.schedule().status()).isEqualTo("REVOKED");
        // 重复撤销 → 409；已撤销不能开始 → 422
        assertApiStatus(() -> service.revokeSchedule("INC-K1", "T-1", "alice",
                new TaskScheduleRevokeRequest(key(), 2)), HttpStatus.CONFLICT);
        ((ControllableClock) clock).setInstant(Instant.parse("2026-10-06T10:30:00Z"));
        assertApiStatus(() -> service.startTask("INC-K1", "T-1", "alice",
                new TaskStartRequest(key(), 2)), HttpStatus.UNPROCESSABLE_ENTITY);
        // 撤销后可重新安排（版本仍为 2）
        TaskView again = service.scheduleTask("INC-K1", "T-1", "alice",
                scheduleReq(key(), 2, "worker-1", "2026-10-06T12:00:00Z", "2026-10-06T13:00:00Z"));
        assertThat(again.version()).isEqualTo(3);
        // 已开始不可撤销 → 409
        ((ControllableClock) clock).setInstant(Instant.parse("2026-10-06T12:30:00Z"));
        service.startTask("INC-K1", "T-1", "alice", new TaskStartRequest(key(), 3));
        assertApiStatus(() -> service.revokeSchedule("INC-K1", "T-1", "alice",
                new TaskScheduleRevokeRequest(key(), 4)), HttpStatus.CONFLICT);
    }

    @Test
    void requestId_idempotency() {
        commanding("INC-I1", "alice");
        createTask("INC-I1", "alice", "T-1");
        String requestId = key();
        TaskScheduleRequest req = scheduleReq(requestId, 1, "worker-1",
                "2026-10-06T10:00:00Z", "2026-10-06T11:00:00Z");
        TaskView first = service.scheduleTask("INC-I1", "T-1", "alice", req);
        // 同键同参重放首次完整响应，版本不重复加
        TaskView replay = service.scheduleTask("INC-I1", "T-1", "alice", req);
        assertThat(replay).isEqualTo(first);
        assertThat(service.getTask("INC-I1", "T-1").version()).isEqualTo(2);
        // 同键异参 → 409；同键跨操作 → 409
        assertApiStatus(() -> service.scheduleTask("INC-I1", "T-1", "alice",
                scheduleReq(requestId, 1, "worker-1", "2026-10-06T12:00:00Z",
                        "2026-10-06T13:00:00Z")), HttpStatus.CONFLICT);
        assertApiStatus(() -> service.startTask("INC-I1", "T-1", "alice",
                new TaskStartRequest(requestId, 2)), HttpStatus.CONFLICT);
        // 失败不占键：休息违反 422 后同键修正参数成功
        commanding("INC-I2", "alice");
        createTask("INC-I2", "alice", "T-2");
        String failKey = key();
        assertApiStatus(() -> service.scheduleTask("INC-I2", "T-2", "alice",
                scheduleReq(failKey, 1, "worker-1", "2026-10-06T10:10:00Z",
                        "2026-10-06T11:00:00Z")), HttpStatus.UNPROCESSABLE_ENTITY);
        TaskView ok = service.scheduleTask("INC-I2", "T-2", "alice",
                scheduleReq(failKey, 1, "worker-1", "2026-10-06T12:00:00Z",
                        "2026-10-06T13:00:00Z"));
        assertThat(ok.schedule().status()).isEqualTo("SCHEDULED");
    }

    @Test
    void permissionCheckedBeforeReplay() {
        commanding("INC-P1", "alice");
        createTask("INC-P1", "alice", "T-1");
        String requestId = key();
        TaskScheduleRequest req = scheduleReq(requestId, 1, "worker-1",
                "2026-10-06T10:00:00Z", "2026-10-06T11:00:00Z");
        service.scheduleTask("INC-P1", "T-1", "alice", req);
        // 交接后旧指挥人重放同键同参：权限先于重放 → 409 权限冲突而非重放响应
        service.initiateTransfer("INC-P1", "alice", new TransferRequest(key(), "bob"));
        service.acceptTransfer("INC-P1", "bob", new TransferAcceptRequest(key()));
        assertThatThrownBy(() -> service.scheduleTask("INC-P1", "T-1", "alice", req))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getMessage()).contains("只有当前指挥人");
                });
        // 新指挥人同键异参（操作者不同）→ 409
        assertApiStatus(() -> service.scheduleTask("INC-P1", "T-1", "bob", req),
                HttpStatus.CONFLICT);
    }
}
