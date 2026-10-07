package com.example.starter.incident;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.example.starter.incident.dto.Requests.ReportRequest;
import com.example.starter.incident.dto.Requests.ScheduleConstraintRequest;
import com.example.starter.incident.dto.Requests.ScheduleProposeRequest;
import com.example.starter.incident.dto.Requests.ScheduleTaskRequest;
import com.example.starter.incident.dto.Requests.TakeoverRequest;
import com.example.starter.incident.dto.Requests.TaskActionRequest;
import com.example.starter.incident.dto.Requests.TaskCreateRequest;
import com.example.starter.incident.dto.Requests.TransferAcceptRequest;
import com.example.starter.incident.dto.Requests.TransferRequest;
import com.example.starter.incident.dto.Responses.ScheduleView;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 整案排程提案校验与幂等测试：参数校验（400）、资源缺失（404）、
 * 引用非 OPEN 任务（409）、requestId 幂等重放/改参 409/失败不占键、
 * 当前指挥人权限先于重放。
 */
@SpringBootTest
class ScheduleValidationTest {

    private static final Instant BASE = Instant.parse("2026-10-01T01:00:00Z");

    @Autowired
    private IncidentService incidentService;

    @Autowired
    private ScheduleService scheduleService;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM command_keys");
        jdbc.update("DELETE FROM incident_schedules");
        jdbc.update("DELETE FROM incident_schedule_state");
        jdbc.update("DELETE FROM incident_status_history");
        jdbc.update("DELETE FROM incident_transfers");
        jdbc.update("DELETE FROM incident_actions");
        jdbc.update("DELETE FROM incident_task_blockers");
        jdbc.update("DELETE FROM incident_tasks");
        jdbc.update("DELETE FROM incidents");
    }

    private static String key() {
        return "REQ-" + UUID.randomUUID();
    }

    private void commanding(String incidentKey, String commander) {
        incidentService.report(new ReportRequest(incidentKey, "S2", "核心链路故障", "reporter-1"));
        incidentService.takeover(incidentKey, commander, new TakeoverRequest(key()));
    }

    private void openTask(String incidentKey, String actor, String taskKey) {
        incidentService.createTask(incidentKey, actor,
                new TaskCreateRequest(key(), taskKey, "G", "任务" + taskKey, List.of()));
    }

    private static ScheduleTaskRequest task(String taskKey, Long duration, Long earliest,
                                            Long latest) {
        return new ScheduleTaskRequest(taskKey, duration, earliest, latest);
    }

    private static ScheduleProposeRequest propose(String requestId, Long expected,
                                                  List<ScheduleTaskRequest> tasks,
                                                  List<ScheduleConstraintRequest> constraints) {
        return new ScheduleProposeRequest(requestId, BASE, expected, tasks, constraints);
    }

    private static void assertApiStatus(ThrowingCallable call, HttpStatus status) {
        org.assertj.core.api.Assertions.assertThatThrownBy(call)
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.status()).isEqualTo(status));
    }

    @Test
    void propose_fieldValidation_400() {
        commanding("INC-V1", "alice");
        openTask("INC-V1", "alice", "A");
        List<ScheduleTaskRequest> oneTask = List.of(task("A", 5L, 0L, 10L));
        // requestId / actor / baseInstant 非空
        assertApiStatus(() -> scheduleService.propose("INC-V1", "alice",
                propose(" ", null, oneTask, List.of())), HttpStatus.BAD_REQUEST);
        assertApiStatus(() -> scheduleService.propose("INC-V1", "alice",
                propose(null, null, oneTask, List.of())), HttpStatus.BAD_REQUEST);
        assertApiStatus(() -> scheduleService.propose("INC-V1", " ",
                propose(key(), null, oneTask, List.of())), HttpStatus.BAD_REQUEST);
        assertApiStatus(() -> scheduleService.propose("INC-V1", "alice",
                new ScheduleProposeRequest(key(), null, null, oneTask, List.of())),
                HttpStatus.BAD_REQUEST);
        // 任务集合：空、超 10 个、重复
        assertApiStatus(() -> scheduleService.propose("INC-V1", "alice",
                propose(key(), null, List.of(), List.of())), HttpStatus.BAD_REQUEST);
        List<ScheduleTaskRequest> eleven = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            eleven.add(task("T" + i, 5L, 0L, 10L));
        }
        assertApiStatus(() -> scheduleService.propose("INC-V1", "alice",
                propose(key(), null, eleven, List.of())), HttpStatus.BAD_REQUEST);
        assertApiStatus(() -> scheduleService.propose("INC-V1", "alice",
                propose(key(), null, List.of(task("A", 5L, 0L, 10L), task("A", 3L, 0L, 9L)),
                        List.of())), HttpStatus.BAD_REQUEST);
        // 持续分钟数为正整数，偏移非负
        assertApiStatus(() -> scheduleService.propose("INC-V1", "alice",
                propose(key(), null, List.of(task("A", 0L, 0L, 10L)), List.of())),
                HttpStatus.BAD_REQUEST);
        assertApiStatus(() -> scheduleService.propose("INC-V1", "alice",
                propose(key(), null, List.of(task("A", null, 0L, 10L)), List.of())),
                HttpStatus.BAD_REQUEST);
        assertApiStatus(() -> scheduleService.propose("INC-V1", "alice",
                propose(key(), null, List.of(task("A", 5L, -1L, 10L)), List.of())),
                HttpStatus.BAD_REQUEST);
        assertApiStatus(() -> scheduleService.propose("INC-V1", "alice",
                propose(key(), null, List.of(task("A", 5L, 0L, -1L)), List.of())),
                HttpStatus.BAD_REQUEST);
        // 约束：超 20 条、引用提案外任务、下界为负、下界大于上界
        List<ScheduleConstraintRequest> twentyOne = new ArrayList<>();
        for (int i = 0; i < 21; i++) {
            twentyOne.add(new ScheduleConstraintRequest("A", "A", 0L, 1L));
        }
        assertApiStatus(() -> scheduleService.propose("INC-V1", "alice",
                propose(key(), null, oneTask, twentyOne)), HttpStatus.BAD_REQUEST);
        assertApiStatus(() -> scheduleService.propose("INC-V1", "alice",
                propose(key(), null, oneTask,
                        List.of(new ScheduleConstraintRequest("A", "B", 0L, 1L)))),
                HttpStatus.BAD_REQUEST);
        assertApiStatus(() -> scheduleService.propose("INC-V1", "alice",
                propose(key(), null, oneTask,
                        List.of(new ScheduleConstraintRequest("A", "A", -1L, 1L)))),
                HttpStatus.BAD_REQUEST);
        assertApiStatus(() -> scheduleService.propose("INC-V1", "alice",
                propose(key(), null, oneTask,
                        List.of(new ScheduleConstraintRequest("A", "A", 5L, 1L)))),
                HttpStatus.BAD_REQUEST);
        // 校验失败不留方案、不占键
        assertThat(scheduleService.listSchedules("INC-V1").history()).isEmpty();
    }

    @Test
    void propose_notFound_404() {
        commanding("INC-V2", "alice");
        openTask("INC-V2", "alice", "A");
        // 事件不存在
        assertApiStatus(() -> scheduleService.propose("INC-404", "alice",
                propose(key(), null, List.of(task("A", 5L, 0L, 10L)), List.of())),
                HttpStatus.NOT_FOUND);
        // 引用任务不存在
        assertApiStatus(() -> scheduleService.propose("INC-V2", "alice",
                propose(key(), null, List.of(task("T-9", 5L, 0L, 10L)), List.of())),
                HttpStatus.NOT_FOUND);
        // 查询不存在事件的排程
        assertApiStatus(() -> scheduleService.listSchedules("INC-404"), HttpStatus.NOT_FOUND);
    }

    @Test
    void propose_nonOpenTask_409() {
        commanding("INC-V3", "alice");
        openTask("INC-V3", "alice", "A");
        openTask("INC-V3", "alice", "B");
        openTask("INC-V3", "alice", "C");
        incidentService.completeTask("INC-V3", "A", "alice", new TaskActionRequest(key()));
        incidentService.cancelTask("INC-V3", "B", "alice", new TaskActionRequest(key()));
        // 引用 DONE / CANCELLED 任务均 409
        assertApiStatus(() -> scheduleService.propose("INC-V3", "alice",
                propose(key(), null, List.of(task("A", 5L, 0L, 10L)), List.of())),
                HttpStatus.CONFLICT);
        assertApiStatus(() -> scheduleService.propose("INC-V3", "alice",
                propose(key(), null, List.of(task("B", 5L, 0L, 10L)), List.of())),
                HttpStatus.CONFLICT);
        // OPEN 任务仍可提交
        ScheduleView ok = scheduleService.propose("INC-V3", "alice",
                propose(key(), null, List.of(task("C", 5L, 0L, 10L)), List.of()));
        assertThat(ok.status()).isEqualTo("CURRENT");
    }

    @Test
    void propose_requestIdIdempotency() {
        commanding("INC-V4", "alice");
        openTask("INC-V4", "alice", "A");
        String requestId = key();
        ScheduleView first = scheduleService.propose("INC-V4", "alice",
                propose(requestId, null, List.of(task("A", 5L, 0L, 10L)), List.of()));
        // 同键同参重放首次完整结果
        ScheduleView replay = scheduleService.propose("INC-V4", "alice",
                propose(requestId, null, List.of(task("A", 5L, 0L, 10L)), List.of()));
        assertThat(replay).isEqualTo(first);
        assertThat(scheduleService.listSchedules("INC-V4").history()).hasSize(1);
        // 同键改参 409
        assertApiStatus(() -> scheduleService.propose("INC-V4", "alice",
                propose(requestId, null, List.of(task("A", 6L, 0L, 10L)), List.of())),
                HttpStatus.CONFLICT);
        assertApiStatus(() -> scheduleService.propose("INC-V4", "alice",
                propose(requestId, 1L, List.of(task("A", 5L, 0L, 10L)), List.of())),
                HttpStatus.CONFLICT);
        // 同键跨操作 409（与既有 commandKey 共用全局键空间）
        assertApiStatus(() -> incidentService.takeover("INC-V4", "alice",
                new TakeoverRequest(requestId)), HttpStatus.CONFLICT);
        assertThat(scheduleService.listSchedules("INC-V4").history()).hasSize(1);
    }

    @Test
    void propose_permissionCheckedBeforeReplay() {
        commanding("INC-V5", "alice");
        openTask("INC-V5", "alice", "A");
        String requestId = key();
        ScheduleProposeRequest req = propose(requestId, null,
                List.of(task("A", 5L, 0L, 10L)), List.of());
        ScheduleView first = scheduleService.propose("INC-V5", "alice", req);
        // 非当前指挥人以同键同参调用：权限先于重放，409 而非返回首次结果
        assertApiStatus(() -> scheduleService.propose("INC-V5", "bob", req),
                HttpStatus.CONFLICT);
        // 交接后 bob 成为当前指挥人：同键同参重放首次完整结果（提交人仍为 alice）
        incidentService.initiateTransfer("INC-V5", "alice", new TransferRequest(key(), "bob"));
        incidentService.acceptTransfer("INC-V5", "bob", new TransferAcceptRequest(key()));
        ScheduleView replayed = scheduleService.propose("INC-V5", "bob", req);
        assertThat(replayed).isEqualTo(first);
        assertThat(replayed.createdBy()).isEqualTo("alice");
        // 交接推进了事件版本：库存提案已 STALE，但重放返回的首次结果保持提交时内容
        assertThat(scheduleService.listSchedules("INC-V5").current().status()).isEqualTo("STALE");
        assertThat(scheduleService.listSchedules("INC-V5").history()).hasSize(1);
    }
}
