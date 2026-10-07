package com.example.starter.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
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
import com.example.starter.incident.dto.Responses.IncidentView;
import com.example.starter.incident.dto.Responses.ScheduleHistoryView;
import com.example.starter.incident.dto.Responses.ScheduleView;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 整案排程提案服务测试：覆盖主流程（含题干 A/B 各 5 分钟、10 分钟窗口示例）、
 * 整体矛盾 422 且不留方案、版本递增与替换、事件版本推进导致的 STALE。
 * 使用可控 Clock，提交时刻断言不依赖真实睡眠。
 */
@SpringBootTest
@Import(ControllableClock.Config.class)
class ScheduleServiceTest {

    private static final Instant T0 = Instant.parse("2026-10-01T00:00:00Z");
    private static final Instant BASE = Instant.parse("2026-10-01T01:00:00Z");

    @Autowired
    private IncidentService incidentService;

    @Autowired
    private ScheduleService scheduleService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Clock clock;

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
        ((ControllableClock) clock).setInstant(T0);
    }

    private static String key() {
        return "REQ-" + UUID.randomUUID();
    }

    private IncidentView commanding(String incidentKey, String commander) {
        incidentService.report(new ReportRequest(incidentKey, "S2", "核心链路故障", "reporter-1"));
        return incidentService.takeover(incidentKey, commander, new TakeoverRequest(key()));
    }

    private void openTask(String incidentKey, String actor, String taskKey) {
        incidentService.createTask(incidentKey, actor,
                new TaskCreateRequest(key(), taskKey, "G", "任务" + taskKey, List.of()));
    }

    private static ScheduleTaskRequest task(String taskKey, long duration, long earliest,
                                            long latest) {
        return new ScheduleTaskRequest(taskKey, duration, earliest, latest);
    }

    private static ScheduleConstraintRequest constraint(String from, String to, long lower,
                                                        long upper) {
        return new ScheduleConstraintRequest(from, to, lower, upper);
    }

    private static void assertApiStatus(ThrowingCallable call, HttpStatus status) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.status()).isEqualTo(status));
    }

    @Test
    void propose_mainFlow_feasibleChain() {
        commanding("INC-S1", "alice");
        openTask("INC-S1", "alice", "A");
        openTask("INC-S1", "alice", "B");
        // A、B 各 5 分钟且均要求 10 分钟内完成，A 完后 B 才开始：可行（s_A=0, s_B=5）
        ScheduleView view = scheduleService.propose("INC-S1", "alice",
                new ScheduleProposeRequest(key(), BASE, null,
                        List.of(task("A", 5, 0, 10), task("B", 5, 0, 10)),
                        List.of(constraint("A", "B", 0, 5))));

        assertThat(view.scheduleVersion()).isEqualTo(1);
        assertThat(view.status()).isEqualTo("CURRENT");
        assertThat(view.baseInstant()).isEqualTo(BASE);
        assertThat(view.stateVersion()).isEqualTo(0);
        assertThat(view.createdBy()).isEqualTo("alice");
        assertThat(view.createdAt()).isEqualTo(T0);
        assertThat(view.tasks()).hasSize(2);
        assertThat(view.tasks().get(0).taskKey()).isEqualTo("A");
        assertThat(view.tasks().get(0).taskStatus()).isEqualTo("OPEN");
        assertThat(view.tasks().get(0).durationMinutes()).isEqualTo(5);
        assertThat(view.constraints()).hasSize(1);
        assertThat(view.constraints().get(0).fromTaskKey()).isEqualTo("A");
        assertThat(view.constraints().get(0).toTaskKey()).isEqualTo("B");

        ScheduleHistoryView history = scheduleService.listSchedules("INC-S1");
        assertThat(history.current()).isEqualTo(view);
        assertThat(history.history()).containsExactly(view);
    }

    @Test
    void propose_infeasibleContradiction_422_noPartialSchedule() {
        commanding("INC-S2", "alice");
        openTask("INC-S2", "alice", "A");
        openTask("INC-S2", "alice", "B");
        String requestId = key();
        List<ScheduleTaskRequest> tasks = List.of(task("A", 5, 0, 10), task("B", 5, 0, 10));
        // A 完后 B 才开始可行；再要求 B 完后 A 才开始形成整体矛盾
        assertThatThrownBy(() -> scheduleService.propose("INC-S2", "alice",
                new ScheduleProposeRequest(requestId, BASE, null, tasks,
                        List.of(constraint("A", "B", 0, 1000), constraint("B", "A", 0, 1000)))))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(e.code()).isEqualTo("SCHEDULE_INFEASIBLE");
                });
        // 不留部分排程，失败不占键
        assertThat(scheduleService.listSchedules("INC-S2").history()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM incident_schedules", Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM command_keys WHERE command_key = ?", Integer.class,
                requestId)).isZero();
        // 同一 requestId 修正参数后可重新提交（失败不占键）
        ScheduleView fixed = scheduleService.propose("INC-S2", "alice",
                new ScheduleProposeRequest(requestId, BASE, null, tasks,
                        List.of(constraint("A", "B", 0, 1000))));
        assertThat(fixed.scheduleVersion()).isEqualTo(1);
        assertThat(fixed.status()).isEqualTo("CURRENT");
    }

    @Test
    void propose_individuallyFeasibleButJointlyInfeasible_422() {
        commanding("INC-S3", "alice");
        openTask("INC-S3", "alice", "A");
        openTask("INC-S3", "alice", "B");
        // 逐边检查均可行，但 A 完后 B、B 需 8 分钟、B 最晚 10 分钟完成 ⇒ 整体不可行
        assertApiStatus(() -> scheduleService.propose("INC-S3", "alice",
                new ScheduleProposeRequest(key(), BASE, null,
                        List.of(task("A", 5, 0, 10), task("B", 8, 0, 10)),
                        List.of(constraint("A", "B", 0, 1000)))),
                HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(scheduleService.listSchedules("INC-S3").history()).isEmpty();
    }

    @Test
    void propose_versioningAndReplacement() {
        commanding("INC-S4", "alice");
        openTask("INC-S4", "alice", "A");
        ScheduleView v1 = scheduleService.propose("INC-S4", "alice",
                new ScheduleProposeRequest(key(), BASE, null,
                        List.of(task("A", 5, 0, 10)), List.of()));
        assertThat(v1.scheduleVersion()).isEqualTo(1);

        // 替换必须携带 expectedScheduleVersion：缺失或不符均 409
        assertApiStatus(() -> scheduleService.propose("INC-S4", "alice",
                new ScheduleProposeRequest(key(), BASE, null,
                        List.of(task("A", 3, 0, 10)), List.of())), HttpStatus.CONFLICT);
        assertApiStatus(() -> scheduleService.propose("INC-S4", "alice",
                new ScheduleProposeRequest(key(), BASE, 2L,
                        List.of(task("A", 3, 0, 10)), List.of())), HttpStatus.CONFLICT);
        // 期望版本不符时不产生新版本
        assertThat(scheduleService.listSchedules("INC-S4").history()).hasSize(1);

        // 携带正确期望版本：版本递增，旧方案标为 STALE 但历史保留
        ScheduleView v2 = scheduleService.propose("INC-S4", "alice",
                new ScheduleProposeRequest(key(), BASE, 1L,
                        List.of(task("A", 3, 0, 10)), List.of()));
        assertThat(v2.scheduleVersion()).isEqualTo(2);
        assertThat(v2.status()).isEqualTo("CURRENT");
        ScheduleHistoryView history = scheduleService.listSchedules("INC-S4");
        assertThat(history.history()).hasSize(2);
        assertThat(history.history().get(0).scheduleVersion()).isEqualTo(1);
        assertThat(history.history().get(0).status()).isEqualTo("STALE");
        assertThat(history.history().get(0).tasks().get(0).durationMinutes()).isEqualTo(5);
        assertThat(history.current()).isEqualTo(v2);

        // 首版携带非零期望版本：409
        commanding("INC-S5", "bob");
        openTask("INC-S5", "bob", "A");
        assertApiStatus(() -> scheduleService.propose("INC-S5", "bob",
                new ScheduleProposeRequest(key(), BASE, 3L,
                        List.of(task("A", 5, 0, 10)), List.of())), HttpStatus.CONFLICT);
    }

    @Test
    void propose_staleOnTaskCompleteCancelAndTransfer() {
        // 任务完成推进版本
        commanding("INC-S6", "alice");
        openTask("INC-S6", "alice", "A");
        openTask("INC-S6", "alice", "B");
        scheduleService.propose("INC-S6", "alice",
                new ScheduleProposeRequest(key(), BASE, null,
                        List.of(task("A", 5, 0, 10)), List.of()));
        incidentService.completeTask("INC-S6", "A", "alice", new TaskActionRequest(key()));
        ScheduleView afterComplete = scheduleService.listSchedules("INC-S6").current();
        assertThat(afterComplete.status()).isEqualTo("STALE");
        assertThat(afterComplete.stateVersion()).isEqualTo(0);
        assertThat(jdbc.queryForObject("SELECT state_version FROM incident_schedule_state"
                + " WHERE incident_id = (SELECT id FROM incidents WHERE incident_key = 'INC-S6')",
                Long.class)).isEqualTo(1L);

        // 任务取消推进版本
        commanding("INC-S7", "alice");
        openTask("INC-S7", "alice", "A");
        scheduleService.propose("INC-S7", "alice",
                new ScheduleProposeRequest(key(), BASE, null,
                        List.of(task("A", 5, 0, 10)), List.of()));
        incidentService.cancelTask("INC-S7", "A", "alice", new TaskActionRequest(key()));
        assertThat(scheduleService.listSchedules("INC-S7").current().status()).isEqualTo("STALE");

        // 仅发起交接不推进版本；接受交接才推进
        commanding("INC-S8", "alice");
        openTask("INC-S8", "alice", "A");
        scheduleService.propose("INC-S8", "alice",
                new ScheduleProposeRequest(key(), BASE, null,
                        List.of(task("A", 5, 0, 10)), List.of()));
        incidentService.initiateTransfer("INC-S8", "alice", new TransferRequest(key(), "bob"));
        assertThat(scheduleService.listSchedules("INC-S8").current().status()).isEqualTo("CURRENT");
        incidentService.acceptTransfer("INC-S8", "bob", new TransferAcceptRequest(key()));
        assertThat(scheduleService.listSchedules("INC-S8").current().status()).isEqualTo("STALE");
    }

    @Test
    void propose_staleHistoryPreservedAndVersionContinues() {
        commanding("INC-S9", "alice");
        openTask("INC-S9", "alice", "A");
        scheduleService.propose("INC-S9", "alice",
                new ScheduleProposeRequest(key(), BASE, null,
                        List.of(task("A", 5, 0, 10)), List.of()));
        incidentService.completeTask("INC-S9", "A", "alice", new TaskActionRequest(key()));
        assertThat(scheduleService.listSchedules("INC-S9").current().status()).isEqualTo("STALE");
        // 事件状态变化不影响历史：旧方案冻结的任务状态保持 OPEN 快照
        ScheduleHistoryView history = scheduleService.listSchedules("INC-S9");
        assertThat(history.history()).hasSize(1);
        assertThat(history.history().get(0).tasks().get(0).taskStatus()).isEqualTo("OPEN");
        // 版本继续递增：新提案（不含已完成任务）版本为 2
        openTask("INC-S9", "alice", "B");
        ScheduleView v2 = scheduleService.propose("INC-S9", "alice",
                new ScheduleProposeRequest(key(), BASE, 1L,
                        List.of(task("B", 5, 0, 10)), List.of()));
        assertThat(v2.scheduleVersion()).isEqualTo(2);
        assertThat(v2.stateVersion()).isEqualTo(1);
        assertThat(scheduleService.listSchedules("INC-S9").history()).hasSize(2);
    }
}
