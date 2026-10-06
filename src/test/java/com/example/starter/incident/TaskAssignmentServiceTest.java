package com.example.starter.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import com.example.starter.incident.dto.Requests.AssignmentCancelRequest;
import com.example.starter.incident.dto.Requests.AssignmentRequest;
import com.example.starter.incident.dto.Requests.ReportRequest;
import com.example.starter.incident.dto.Requests.StatusRequest;
import com.example.starter.incident.dto.Requests.TakeoverRequest;
import com.example.starter.incident.dto.Requests.TaskActionRequest;
import com.example.starter.incident.dto.Requests.TaskCreateRequest;
import com.example.starter.incident.dto.Requests.TaskStartRequest;
import com.example.starter.incident.dto.Requests.TransferAcceptRequest;
import com.example.starter.incident.dto.Requests.TransferRequest;
import com.example.starter.incident.dto.Responses.DailyLimitView;
import com.example.starter.incident.dto.Responses.RestShortageView;
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
 * 任务执行安排服务测试：覆盖安排创建/替换/撤销、版本链、休息 30 分钟与日上限 480 分钟
 * （跨午夜拆日）、开始门禁（服务端 Clock 区间 + 阻塞解除）、完成/取消对安排的保留与释放、
 * requestId 幂等（重放/异参 409/失败不占键）与权限先于重放。
 */
@SpringBootTest
@Import(ControllableClock.Config.class)
class TaskAssignmentServiceTest {

    private static final Instant T0 = Instant.parse("2026-09-22T00:00:00Z");

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
        jdbc.update("DELETE FROM task_assignments");
        jdbc.update("DELETE FROM incident_tasks");
        jdbc.update("DELETE FROM incidents");
        ((ControllableClock) clock).setInstant(T0);
    }

    private static String key() {
        return "CMD-" + UUID.randomUUID();
    }

    private void commanding(String incidentKey, String commander) {
        service.report(new ReportRequest(incidentKey, "S2", "合成场景", "reporter-1"));
        service.takeover(incidentKey, commander, new TakeoverRequest(key()));
    }

    private TaskView createTask(String incidentKey, String actor, String taskKey) {
        return service.createTask(incidentKey, actor,
                new TaskCreateRequest(key(), taskKey, "G", "任务" + taskKey, List.of()));
    }

    private static Instant at(String iso) {
        return Instant.parse(iso);
    }

    private static AssignmentRequest assignReq(String assignee, String start, String end,
                                               long version) {
        return new AssignmentRequest(key(), version, assignee, at(start), at(end));
    }

    private static void assertApiStatus(ThrowingCallable call, HttpStatus status) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.status()).isEqualTo(status));
    }

    private static ApiException apiException(ThrowingCallable call) {
        return (ApiException) org.assertj.core.api.Assertions.catchThrowable(call);
    }

    @Test
    void scheduleStartComplete_versionChainAndAssignmentKept() {
        commanding("INC-A", "alice");
        TaskView created = createTask("INC-A", "alice", "T-1");
        assertThat(created.version()).isEqualTo(1);
        assertThat(created.assignment()).isNull();

        TaskView scheduled = service.scheduleAssignment("INC-A", "T-1", "alice",
                assignReq("worker-1", "2026-09-22T10:00:00Z", "2026-09-22T11:00:00Z", 1));
        assertThat(scheduled.version()).isEqualTo(2);
        assertThat(scheduled.assignment().assignee()).isEqualTo("worker-1");
        assertThat(scheduled.assignment().startAt()).isEqualTo(at("2026-09-22T10:00:00Z"));

        ((ControllableClock) clock).setInstant(at("2026-09-22T10:30:00Z"));
        TaskView started = service.startTask("INC-A", "T-1", "alice",
                new TaskStartRequest(key(), 2L));
        assertThat(started.status()).isEqualTo("STARTED");
        assertThat(started.version()).isEqualTo(3);
        assertThat(started.startedBy()).isEqualTo("alice");
        assertThat(started.startedAt()).isEqualTo(at("2026-09-22T10:30:00Z"));

        TaskView done = service.completeTask("INC-A", "T-1", "alice",
                new TaskActionRequest(key()));
        assertThat(done.status()).isEqualTo("DONE");
        assertThat(done.version()).isEqualTo(4);
        // 已开始任务完成后保留原安排作业量
        assertThat(done.assignment()).isNotNull();
        Integer rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM task_assignments", Integer.class);
        assertThat(rows).isEqualTo(1);
    }

    @Test
    void scheduleValidation_badRequestAndVersionConflict() {
        commanding("INC-A", "alice");
        createTask("INC-A", "alice", "T-1");

        // 非整分钟 → 400
        assertApiStatus(() -> service.scheduleAssignment("INC-A", "T-1", "alice",
                new AssignmentRequest(key(), 1L, "w", at("2026-09-22T10:00:30Z"),
                        at("2026-09-22T11:00:00Z"))), HttpStatus.BAD_REQUEST);
        // 时长 0 / 241 分钟 → 400
        assertApiStatus(() -> service.scheduleAssignment("INC-A", "T-1", "alice",
                assignReq("w", "2026-09-22T10:00:00Z", "2026-09-22T10:00:00Z", 1)),
                HttpStatus.BAD_REQUEST);
        assertApiStatus(() -> service.scheduleAssignment("INC-A", "T-1", "alice",
                assignReq("w", "2026-09-22T10:00:00Z", "2026-09-22T14:01:00Z", 1)),
                HttpStatus.BAD_REQUEST);
        // 缺少 expectedTaskVersion → 400
        assertApiStatus(() -> service.scheduleAssignment("INC-A", "T-1", "alice",
                new AssignmentRequest(key(), null, "w", at("2026-09-22T10:00:00Z"),
                        at("2026-09-22T11:00:00Z"))), HttpStatus.BAD_REQUEST);
        // 非当前指挥人 → 409
        assertApiStatus(() -> service.scheduleAssignment("INC-A", "T-1", "bob",
                assignReq("w", "2026-09-22T10:00:00Z", "2026-09-22T11:00:00Z", 1)),
                HttpStatus.CONFLICT);
        // 事件/任务不存在 → 404
        assertApiStatus(() -> service.scheduleAssignment("INC-404", "T-1", "alice",
                assignReq("w", "2026-09-22T10:00:00Z", "2026-09-22T11:00:00Z", 1)),
                HttpStatus.NOT_FOUND);
        assertApiStatus(() -> service.scheduleAssignment("INC-A", "T-9", "alice",
                assignReq("w", "2026-09-22T10:00:00Z", "2026-09-22T11:00:00Z", 1)),
                HttpStatus.NOT_FOUND);
        // 版本不匹配 → 409
        assertApiStatus(() -> service.scheduleAssignment("INC-A", "T-1", "alice",
                assignReq("w", "2026-09-22T10:00:00Z", "2026-09-22T11:00:00Z", 9)),
                HttpStatus.CONFLICT);
        // 上述失败均不产生安排
        assertThat(service.getTask("INC-A", "T-1").assignment()).isNull();
    }

    @Test
    void restGap_crossMidnightBoundary_0120Rejected0130Allowed() {
        commanding("INC-A", "alice");
        commanding("INC-B", "alice");
        createTask("INC-A", "alice", "T-1");
        createTask("INC-B", "alice", "T-2");
        // 合成跨日 23:00 至 01:00 安排
        service.scheduleAssignment("INC-A", "T-1", "alice",
                assignReq("worker-1", "2026-09-22T23:00:00Z", "2026-09-23T01:00:00Z", 1));

        // 同人 01:20 开始：距 01:00 仅 20 分钟，休息不足 → 422，差额 10 分钟
        ApiException rejected = apiException(() -> service.scheduleAssignment("INC-B", "T-2",
                "alice", assignReq("worker-1", "2026-09-23T01:20:00Z", "2026-09-23T02:20:00Z", 1)));
        assertThat(rejected.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        RestShortageView rest = (RestShortageView) rejected.details();
        assertThat(rest.requiredRestMinutes()).isEqualTo(30);
        assertThat(rest.actualGapMinutes()).isEqualTo(20);
        assertThat(rest.shortfallMinutes()).isEqualTo(10);
        assertThat(rest.date().toString()).isEqualTo("2026-09-23");

        // 01:30 开始：恰好休息 30 分钟 → 允许
        TaskView ok = service.scheduleAssignment("INC-B", "T-2", "alice",
                assignReq("worker-1", "2026-09-23T01:30:00Z", "2026-09-23T02:30:00Z", 1));
        assertThat(ok.assignment().assignee()).isEqualTo("worker-1");

        // 与既有安排重叠 → 422
        assertApiStatus(() -> service.scheduleAssignment("INC-B", "T-2", "alice",
                assignReq("worker-1", "2026-09-23T00:30:00Z", "2026-09-23T01:30:00Z", 2)),
                HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void dailyLimit_splitAcrossMidnight() {
        commanding("INC-A", "alice");
        createTask("INC-A", "alice", "T-1");
        createTask("INC-A", "alice", "T-2");
        createTask("INC-A", "alice", "T-3");
        // 当日两段合计 480 分钟：08:00-12:00、12:30-16:30
        service.scheduleAssignment("INC-A", "T-1", "alice",
                assignReq("worker-1", "2026-09-22T08:00:00Z", "2026-09-22T12:00:00Z", 1));
        service.scheduleAssignment("INC-A", "T-2", "alice",
                assignReq("worker-1", "2026-09-22T12:30:00Z", "2026-09-22T16:30:00Z", 1));
        // 跨午夜 23:00-01:00：当日再计 60 分钟 → 总量 540，超限 60
        ApiException over = apiException(() -> service.scheduleAssignment("INC-A", "T-3", "alice",
                assignReq("worker-1", "2026-09-22T23:00:00Z", "2026-09-23T01:00:00Z", 1)));
        assertThat(over.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        DailyLimitView limit = (DailyLimitView) over.details();
        assertThat(limit.date().toString()).isEqualTo("2026-09-22");
        assertThat(limit.limitMinutes()).isEqualTo(480);
        assertThat(limit.scheduledMinutes()).isEqualTo(540);
        assertThat(limit.excessMinutes()).isEqualTo(60);

        // 跨日拆分各计 60：次日只有跨午夜段的 60 分钟；次日再排两段 240 分钟，
        // 第一段合计 300 允许，第二段合计 540 → 超限 60
        commanding("INC-B", "alice");
        createTask("INC-B", "alice", "T-4");
        createTask("INC-B", "alice", "T-5");
        createTask("INC-B", "alice", "T-6");
        service.scheduleAssignment("INC-B", "T-4", "alice",
                assignReq("worker-2", "2026-09-22T23:00:00Z", "2026-09-23T01:00:00Z", 1));
        service.scheduleAssignment("INC-B", "T-5", "alice",
                assignReq("worker-2", "2026-09-23T08:00:00Z", "2026-09-23T12:00:00Z", 1));
        ApiException nextDay = apiException(() -> service.scheduleAssignment("INC-B", "T-6",
                "alice", assignReq("worker-2", "2026-09-23T12:30:00Z", "2026-09-23T16:30:00Z", 1)));
        assertThat(nextDay.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        DailyLimitView nextDayLimit = (DailyLimitView) nextDay.details();
        assertThat(nextDayLimit.date().toString()).isEqualTo("2026-09-23");
        assertThat(nextDayLimit.scheduledMinutes()).isEqualTo(540);
        assertThat(nextDayLimit.excessMinutes()).isEqualTo(60);
    }

    @Test
    void replaceAndCancelAssignment_releaseSlot() {
        commanding("INC-A", "alice");
        commanding("INC-B", "alice");
        createTask("INC-A", "alice", "T-1");
        createTask("INC-B", "alice", "T-2");
        service.scheduleAssignment("INC-A", "T-1", "alice",
                assignReq("worker-1", "2026-09-22T10:00:00Z", "2026-09-22T11:00:00Z", 1));

        // 替换到 12:00-13:00，版本 2 → 3，原 10:00 时段释放
        TaskView replaced = service.scheduleAssignment("INC-A", "T-1", "alice",
                assignReq("worker-1", "2026-09-22T12:00:00Z", "2026-09-22T13:00:00Z", 2));
        assertThat(replaced.version()).isEqualTo(3);
        assertThat(replaced.assignment().startAt()).isEqualTo(at("2026-09-22T12:00:00Z"));
        Integer rows = jdbc.queryForObject("SELECT COUNT(*) FROM task_assignments", Integer.class);
        assertThat(rows).isEqualTo(1);
        // 同人 10:00 时段已释放，可安排
        service.scheduleAssignment("INC-B", "T-2", "alice",
                assignReq("worker-1", "2026-09-22T10:00:00Z", "2026-09-22T11:00:00Z", 1));

        // 撤销 T-2 的未开始安排：版本 +1，时段释放
        TaskView cancelled = service.cancelAssignment("INC-B", "T-2", "alice",
                new AssignmentCancelRequest(key(), 2L));
        assertThat(cancelled.version()).isEqualTo(3);
        assertThat(cancelled.assignment()).isNull();
        // 撤销后同人可以立即使用相同时段
        service.scheduleAssignment("INC-B", "T-2", "alice",
                assignReq("worker-1", "2026-09-22T10:00:00Z", "2026-09-22T11:00:00Z", 3));
        // 无安排任务撤销 → 409
        createTask("INC-B", "alice", "T-3");
        assertApiStatus(() -> service.cancelAssignment("INC-B", "T-3", "alice",
                new AssignmentCancelRequest(key(), 1L)), HttpStatus.CONFLICT);
    }

    @Test
    void cancelOpenTask_releasesAssignment_cancelStartedTask_keepsIt() {
        commanding("INC-A", "alice");
        commanding("INC-B", "alice");
        createTask("INC-A", "alice", "T-1");
        createTask("INC-B", "alice", "T-2");
        service.scheduleAssignment("INC-A", "T-1", "alice",
                assignReq("worker-1", "2026-09-22T10:00:00Z", "2026-09-22T11:00:00Z", 1));

        // 取消未开始任务：安排删除、时段释放、版本 +1
        TaskView cancelled = service.cancelTask("INC-A", "T-1", "alice",
                new TaskActionRequest(key()));
        assertThat(cancelled.status()).isEqualTo("CANCELLED");
        assertThat(cancelled.version()).isEqualTo(3);
        assertThat(cancelled.assignment()).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM task_assignments", Integer.class))
                .isZero();

        // 已开始任务取消：安排保留，同人同时段仍被占用
        service.scheduleAssignment("INC-B", "T-2", "alice",
                assignReq("worker-1", "2026-09-22T10:00:00Z", "2026-09-22T11:00:00Z", 1));
        ((ControllableClock) clock).setInstant(at("2026-09-22T10:15:00Z"));
        service.startTask("INC-B", "T-2", "alice", new TaskStartRequest(key(), 2L));
        TaskView startedCancelled = service.cancelTask("INC-B", "T-2", "alice",
                new TaskActionRequest(key()));
        assertThat(startedCancelled.status()).isEqualTo("CANCELLED");
        assertThat(startedCancelled.version()).isEqualTo(4);
        assertThat(startedCancelled.assignment()).isNotNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM task_assignments", Integer.class))
                .isEqualTo(1);

        // 保留的安排仍占用人员时段：同人重叠安排 → 422
        commanding("INC-C", "alice");
        createTask("INC-C", "alice", "T-9");
        assertApiStatus(() -> service.scheduleAssignment("INC-C", "T-9", "alice",
                assignReq("worker-1", "2026-09-22T10:30:00Z", "2026-09-22T11:30:00Z", 1)),
                HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void startGate_windowAndBlockers() {
        commanding("INC-A", "alice");
        commanding("INC-B", "bob");
        createTask("INC-A", "alice", "T-1");
        // 无安排 → 422
        assertApiStatus(() -> service.startTask("INC-A", "T-1", "alice",
                new TaskStartRequest(key(), 1L)), HttpStatus.UNPROCESSABLE_ENTITY);

        // 带阻塞的任务：阻塞未解除 → 422
        service.createTask("INC-A", "alice",
                new TaskCreateRequest(key(), "T-2", "G", "t", List.of("INC-B")));
        service.scheduleAssignment("INC-A", "T-2", "alice",
                assignReq("worker-1", "2026-09-22T10:00:00Z", "2026-09-22T11:00:00Z", 1));
        ((ControllableClock) clock).setInstant(at("2026-09-22T10:30:00Z"));
        assertApiStatus(() -> service.startTask("INC-A", "T-2", "alice",
                new TaskStartRequest(key(), 2L)), HttpStatus.UNPROCESSABLE_ENTITY);
        // 阻塞解除后开始成功
        service.changeStatus("INC-B", "bob", new StatusRequest(key(), "CONTAINED"));
        TaskView started = service.startTask("INC-A", "T-2", "alice",
                new TaskStartRequest(key(), 2L));
        assertThat(started.status()).isEqualTo("STARTED");

        // 窗口外 → 422：安排 12:00-13:00，当前 10:30
        service.scheduleAssignment("INC-A", "T-1", "alice",
                assignReq("worker-1", "2026-09-22T12:00:00Z", "2026-09-22T13:00:00Z", 1));
        assertApiStatus(() -> service.startTask("INC-A", "T-1", "alice",
                new TaskStartRequest(key(), 2L)), HttpStatus.UNPROCESSABLE_ENTITY);
        // 右开边界：13:00 已不在区间内
        ((ControllableClock) clock).setInstant(at("2026-09-22T13:00:00Z"));
        assertApiStatus(() -> service.startTask("INC-A", "T-1", "alice",
                new TaskStartRequest(key(), 2L)), HttpStatus.UNPROCESSABLE_ENTITY);
        // 左闭边界：12:00 恰在区间内
        ((ControllableClock) clock).setInstant(at("2026-09-22T12:00:00Z"));
        assertThat(service.startTask("INC-A", "T-1", "alice",
                new TaskStartRequest(key(), 2L)).status()).isEqualTo("STARTED");
        // 已开始任务安排不可改时 → 422
        assertApiStatus(() -> service.scheduleAssignment("INC-A", "T-1", "alice",
                assignReq("worker-1", "2026-09-22T14:00:00Z", "2026-09-22T15:00:00Z", 3)),
                HttpStatus.UNPROCESSABLE_ENTITY);
        assertApiStatus(() -> service.cancelAssignment("INC-A", "T-1", "alice",
                new AssignmentCancelRequest(key(), 3L)), HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void completeGate_assignedTaskMustStartFirst() {
        commanding("INC-A", "alice");
        createTask("INC-A", "alice", "T-1");
        createTask("INC-A", "alice", "T-2");
        service.scheduleAssignment("INC-A", "T-1", "alice",
                assignReq("worker-1", "2026-09-22T10:00:00Z", "2026-09-22T11:00:00Z", 1));
        // 有安排但未开始 → 完成 422（结束端点不允许开始）
        assertApiStatus(() -> service.completeTask("INC-A", "T-1", "alice",
                new TaskActionRequest(key())), HttpStatus.UNPROCESSABLE_ENTITY);
        // 无安排任务维持原逻辑：阻塞解除即可直接完成
        TaskView done = service.completeTask("INC-A", "T-2", "alice",
                new TaskActionRequest(key()));
        assertThat(done.status()).isEqualTo("DONE");
        assertThat(done.version()).isEqualTo(2);
        // 开始后可完成
        ((ControllableClock) clock).setInstant(at("2026-09-22T10:05:00Z"));
        service.startTask("INC-A", "T-1", "alice", new TaskStartRequest(key(), 2L));
        assertThat(service.completeTask("INC-A", "T-1", "alice",
                new TaskActionRequest(key())).status()).isEqualTo("DONE");
    }

    @Test
    void requestIdIdempotency_replayConflictAndFailureNotOccupied() {
        commanding("INC-A", "alice");
        createTask("INC-A", "alice", "T-1");
        String requestId = key();
        AssignmentRequest req = new AssignmentRequest(requestId, 1L, "worker-1",
                at("2026-09-22T10:00:00Z"), at("2026-09-22T11:00:00Z"));
        TaskView first = service.scheduleAssignment("INC-A", "T-1", "alice", req);
        // 同键同参重放首次完整响应，版本不重复递增
        TaskView replayed = service.scheduleAssignment("INC-A", "T-1", "alice", req);
        assertThat(replayed).isEqualTo(first);
        assertThat(service.getTask("INC-A", "T-1").version()).isEqualTo(2);
        // 同键异参 → 409
        assertApiStatus(() -> service.scheduleAssignment("INC-A", "T-1", "alice",
                new AssignmentRequest(requestId, 1L, "worker-2",
                        at("2026-09-22T10:00:00Z"), at("2026-09-22T11:00:00Z"))),
                HttpStatus.CONFLICT);

        // 失败不占键：另一任务先用会违反休息的时段（与 10:00-11:00 相邻不足 30 分钟）触发 422
        createTask("INC-A", "alice", "T-2");
        String failedId = key();
        assertApiStatus(() -> service.scheduleAssignment("INC-A", "T-2", "alice",
                new AssignmentRequest(failedId, 1L, "worker-1",
                        at("2026-09-22T11:10:00Z"), at("2026-09-22T12:00:00Z"))),
                HttpStatus.UNPROCESSABLE_ENTITY);
        // 同键换合法参数可成功
        TaskView afterFail = service.scheduleAssignment("INC-A", "T-2", "alice",
                new AssignmentRequest(failedId, 1L, "worker-1",
                        at("2026-09-22T12:00:00Z"), at("2026-09-22T13:00:00Z")));
        assertThat(afterFail.version()).isEqualTo(2);
    }

    @Test
    void permissionCheckedBeforeReplay() {
        commanding("INC-A", "alice");
        createTask("INC-A", "alice", "T-1");
        String requestId = key();
        AssignmentRequest req = new AssignmentRequest(requestId, 1L, "worker-1",
                at("2026-09-22T10:00:00Z"), at("2026-09-22T11:00:00Z"));
        service.scheduleAssignment("INC-A", "T-1", "alice", req);
        // 非当前指挥人持相同 requestId 重放：权限先于重放 → 409 权限冲突而非首次响应
        ApiException denied = apiException(() -> service.scheduleAssignment("INC-A", "T-1",
                "eve", req));
        assertThat(denied.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(denied.getMessage()).contains("当前指挥人");
        // 交接后新指挥人重放：幂等身份含操作者，操作者不同属异参 → 409
        service.initiateTransfer("INC-A", "alice", new TransferRequest(key(), "bob"));
        service.acceptTransfer("INC-A", "bob", new TransferAcceptRequest(key()));
        ApiException otherOperator = apiException(() -> service.scheduleAssignment("INC-A", "T-1",
                "bob", req));
        assertThat(otherOperator.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(otherOperator.getMessage()).contains("不同参数");
        // 原指挥人已失去权限：重放同样被权限拦截
        assertApiStatus(() -> service.scheduleAssignment("INC-A", "T-1", "alice", req),
                HttpStatus.CONFLICT);
    }

    @Test
    void terminalTask_scheduleRejected() {
        commanding("INC-A", "alice");
        createTask("INC-A", "alice", "T-1");
        service.cancelTask("INC-A", "T-1", "alice", new TaskActionRequest(key()));
        assertApiStatus(() -> service.scheduleAssignment("INC-A", "T-1", "alice",
                assignReq("w", "2026-09-22T10:00:00Z", "2026-09-22T11:00:00Z", 2)),
                HttpStatus.CONFLICT);
        assertApiStatus(() -> service.startTask("INC-A", "T-1", "alice",
                new TaskStartRequest(key(), 2L)), HttpStatus.CONFLICT);
    }

    @Test
    void startedTask_countsTowardResolveGate() {
        commanding("INC-A", "alice");
        createTask("INC-A", "alice", "T-1");
        service.scheduleAssignment("INC-A", "T-1", "alice",
                assignReq("worker-1", "2026-09-22T10:00:00Z", "2026-09-22T11:00:00Z", 1));
        ((ControllableClock) clock).setInstant(at("2026-09-22T10:05:00Z"));
        service.startTask("INC-A", "T-1", "alice", new TaskStartRequest(key(), 2L));
        service.changeStatus("INC-A", "alice", new StatusRequest(key(), "CONTAINED"));
        // STARTED 任务同样阻塞 RESOLVED
        assertApiStatus(() -> service.changeStatus("INC-A", "alice",
                new StatusRequest(key(), "RESOLVED")), HttpStatus.CONFLICT);
        service.completeTask("INC-A", "T-1", "alice", new TaskActionRequest(key()));
        service.changeStatus("INC-A", "alice", new StatusRequest(key(), "RESOLVED"));
        assertThat(service.get("INC-A").status()).isEqualTo("RESOLVED");
    }

    @Test
    void assignmentAcrossIncidents_samePersonConstraintIsGlobal() {
        commanding("INC-A", "alice");
        commanding("INC-B", "bob");
        createTask("INC-A", "alice", "T-1");
        createTask("INC-B", "bob", "T-1");
        service.scheduleAssignment("INC-A", "T-1", "alice",
                assignReq("worker-1", "2026-09-22T10:00:00Z", "2026-09-22T11:00:00Z", 1));
        // 跨事件同一人员：另一事件的相邻安排同样受 30 分钟休息约束
        assertApiStatus(() -> service.scheduleAssignment("INC-B", "T-1", "bob",
                assignReq("worker-1", "2026-09-22T11:10:00Z", "2026-09-22T12:00:00Z", 1)),
                HttpStatus.UNPROCESSABLE_ENTITY);
        // 不同人员不受影响
        service.scheduleAssignment("INC-B", "T-1", "bob",
                assignReq("worker-2", "2026-09-22T10:00:00Z", "2026-09-22T11:00:00Z", 1));
    }

    @Test
    void clockControlledTime_advanceChangesStartEligibility() {
        commanding("INC-A", "alice");
        createTask("INC-A", "alice", "T-1");
        service.scheduleAssignment("INC-A", "T-1", "alice",
                assignReq("worker-1", "2026-09-22T10:00:00Z", "2026-09-22T11:00:00Z", 1));
        ((ControllableClock) clock).setInstant(at("2026-09-22T09:59:00Z"));
        assertApiStatus(() -> service.startTask("INC-A", "T-1", "alice",
                new TaskStartRequest(key(), 2L)), HttpStatus.UNPROCESSABLE_ENTITY);
        ((ControllableClock) clock).setInstant(at("2026-09-22T09:59:00Z")
                .plus(61, ChronoUnit.SECONDS));
        // 10:00:01 在区间内（整分钟校验只针对安排时段，Clock 可为任意时刻）
        assertThat(service.startTask("INC-A", "T-1", "alice",
                new TaskStartRequest(key(), 2L)).status()).isEqualTo("STARTED");
    }
}
