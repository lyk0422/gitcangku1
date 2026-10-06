package com.example.starter.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.example.starter.incident.dto.Requests.ReportRequest;
import com.example.starter.incident.dto.Requests.ResourceAssignRequest;
import com.example.starter.incident.dto.Requests.StatusRequest;
import com.example.starter.incident.dto.Requests.TakeoverRequest;
import com.example.starter.incident.dto.Requests.TaskActionRequest;
import com.example.starter.incident.dto.Requests.TaskCreateRequest;
import com.example.starter.incident.dto.Requests.TaskExecutionRequest;
import com.example.starter.incident.dto.Responses.IncidentView;
import com.example.starter.incident.dto.Responses.TaskResourceHistoryView;
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
 * 资源分配、任务执行与授权到期服务测试：覆盖分配校验、资源占用冲突、
 * 执行日志、到期拦截（左闭右开边界）、租约回收与 commandKey 幂等语义。
 * 使用可控 Clock，时间相关断言不依赖真实睡眠。
 */
@SpringBootTest
@Import(ControllableClock.Config.class)
class ResourceAssignServiceTest {

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
        jdbc.update("DELETE FROM resource_lease_events");
        jdbc.update("DELETE FROM incident_task_executions");
        jdbc.update("DELETE FROM resource_leases");
        jdbc.update("DELETE FROM leased_resources");
        jdbc.update("DELETE FROM incident_status_history");
        jdbc.update("DELETE FROM incident_transfers");
        jdbc.update("DELETE FROM incident_actions");
        jdbc.update("DELETE FROM incident_task_blockers");
        jdbc.update("DELETE FROM incident_tasks");
        jdbc.update("DELETE FROM incidents");
        ((ControllableClock) clock).setInstant(T0);
    }

    private static String key() {
        return "CMD-" + UUID.randomUUID();
    }

    private void commanding(String incidentKey, String commander) {
        service.report(new ReportRequest(incidentKey, "S2", "核心链路故障", "reporter-1"));
        service.takeover(incidentKey, commander, new TakeoverRequest(key()));
    }

    private void createTask(String incidentKey, String actor, String taskKey) {
        service.createTask(incidentKey, actor,
                new TaskCreateRequest(key(), taskKey, "DB", "扩容连接池", List.of()));
    }

    private TaskView assign(String incidentKey, String taskKey, String actor,
                            String resourceKey, String authVersion, Instant expiresAt) {
        return service.assignResource(incidentKey, taskKey, actor,
                new ResourceAssignRequest(key(), resourceKey, authVersion, expiresAt));
    }

    private static void assertApiStatus(ThrowingCallable call, HttpStatus status) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.status()).isEqualTo(status));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> details(ApiException e) {
        return (Map<String, Object>) e.details();
    }

    @Test
    void assignAndExecute_mainFlow() {
        commanding("INC-R1", "alice");
        createTask("INC-R1", "alice", "T-1");
        TaskView assigned = assign("INC-R1", "T-1", "alice", "RES-GPU-1", "v1",
                T0.plusSeconds(600));
        assertThat(assigned.status()).isEqualTo("OPEN");
        assertThat(assigned.lease()).isNotNull();
        assertThat(assigned.lease().resourceKey()).isEqualTo("RES-GPU-1");
        assertThat(assigned.lease().authVersion()).isEqualTo("v1");
        assertThat(assigned.lease().leaseVersion()).isEqualTo(1);
        assertThat(assigned.lease().assignedBy()).isEqualTo("alice");
        assertThat(assigned.lease().authorizedBy()).isEqualTo("alice");
        assertThat(assigned.lease().authorizerRole()).isNull();
        assertThat(assigned.lease().assignedAt()).isEqualTo(T0);
        assertThat(assigned.lease().expiresAt()).isEqualTo(T0.plusSeconds(600));
        assertThat(assigned.lease().status()).isEqualTo("ACTIVE");
        assertThat(assigned.lease().remainingSeconds()).isEqualTo(600L);

        // 开始/继续执行：授权未到期，日志落库
        TaskView started = service.recordExecution("INC-R1", "T-1", "alice",
                new TaskExecutionRequest(key(), "START"));
        assertThat(started.status()).isEqualTo("OPEN");
        service.recordExecution("INC-R1", "T-1", "alice", new TaskExecutionRequest(key(), "CONTINUE"));

        // 明细与历史：当前租约、版本、证据、执行日志
        TaskResourceHistoryView history = service.taskResourceHistory("INC-R1", "T-1");
        assertThat(history.currentLease().leaseVersion()).isEqualTo(1);
        assertThat(history.leases()).hasSize(1);
        assertThat(history.events()).hasSize(1);
        assertThat(history.events().get(0).eventType()).isEqualTo("ASSIGN");
        assertThat(history.events().get(0).oldAuthVersion()).isNull();
        assertThat(history.events().get(0).newAuthVersion()).isEqualTo("v1");
        assertThat(history.events().get(0).actor()).isEqualTo("alice");
        assertThat(history.events().get(0).occurredAt()).isEqualTo(T0);
        assertThat(history.executions()).hasSize(2);
        assertThat(history.executions().get(0).action()).isEqualTo("START");
        assertThat(history.executions().get(1).action()).isEqualTo("CONTINUE");

        // 只读查询不改变状态
        service.taskResourceHistory("INC-R1", "T-1");
        service.getTask("INC-R1", "T-1");
        Integer eventCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM resource_lease_events", Integer.class);
        assertThat(eventCount).isEqualTo(1);
    }

    @Test
    void assign_validation() {
        commanding("INC-V1", "alice");
        createTask("INC-V1", "alice", "T-1");
        // 非空校验
        assertApiStatus(() -> service.assignResource("INC-V1", "T-1", "alice",
                new ResourceAssignRequest(key(), " ", "v1", T0.plusSeconds(60))),
                HttpStatus.BAD_REQUEST);
        assertApiStatus(() -> service.assignResource("INC-V1", "T-1", "alice",
                new ResourceAssignRequest(key(), "RES-1", " ", T0.plusSeconds(60))),
                HttpStatus.BAD_REQUEST);
        assertApiStatus(() -> service.assignResource("INC-V1", "T-1", "alice",
                new ResourceAssignRequest(key(), "RES-1", "v1", null)),
                HttpStatus.BAD_REQUEST);
        // 到期时刻必须晚于分配时刻：等于或早于均 400，返回实际值/要求值/差额
        assertThatThrownBy(() -> service.assignResource("INC-V1", "T-1", "alice",
                new ResourceAssignRequest(key(), "RES-1", "v1", T0)))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(details(e)).containsEntry("required", "expiresAt > assignedAt")
                            .containsEntry("diffSeconds", 0L);
                });
        assertThatThrownBy(() -> service.assignResource("INC-V1", "T-1", "alice",
                new ResourceAssignRequest(key(), "RES-1", "v1", T0.minusSeconds(30))))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(details(e)).containsEntry("diffSeconds", -30L);
                });
        // 任务不存在 / 非当前指挥人
        assertApiStatus(() -> assign("INC-V1", "T-9", "alice", "RES-1", "v1",
                T0.plusSeconds(60)), HttpStatus.NOT_FOUND);
        assertApiStatus(() -> assign("INC-V1", "T-1", "bob", "RES-1", "v1",
                T0.plusSeconds(60)), HttpStatus.CONFLICT);
        // 终态任务不可分配
        createTask("INC-V1", "alice", "T-2");
        service.cancelTask("INC-V1", "T-2", "alice", new TaskActionRequest(key()));
        assertApiStatus(() -> assign("INC-V1", "T-2", "alice", "RES-1", "v1",
                T0.plusSeconds(60)), HttpStatus.CONFLICT);
        // 已有活动租约：再次分配 409，应使用续期
        assign("INC-V1", "T-1", "alice", "RES-1", "v1", T0.plusSeconds(60));
        assertApiStatus(() -> assign("INC-V1", "T-1", "alice", "RES-2", "v1",
                T0.plusSeconds(60)), HttpStatus.CONFLICT);
    }

    @Test
    void assign_resourceOccupiedByOtherIncident_thenReleasedOnComplete() {
        commanding("INC-O1", "alice");
        commanding("INC-O2", "bob");
        createTask("INC-O1", "alice", "T-1");
        createTask("INC-O2", "bob", "T-1");
        assign("INC-O1", "T-1", "alice", "RES-SHARED", "v1", T0.plusSeconds(600));
        // 同一资源被其他事件占用：409 并返回占用方明细
        assertThatThrownBy(() -> assign("INC-O2", "T-1", "bob", "RES-SHARED", "v9",
                T0.plusSeconds(600)))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(details(e)).containsEntry("resourceKey", "RES-SHARED")
                            .containsEntry("occupiedByIncidentKey", "INC-O1");
                });
        // 原任务完成后租约回收（RELEASE），资源释放，其他事件可分配
        service.completeTask("INC-O1", "T-1", "alice", new TaskActionRequest(key()));
        TaskResourceHistoryView history = service.taskResourceHistory("INC-O1", "T-1");
        assertThat(history.currentLease()).isNull();
        assertThat(history.leases().get(0).status()).isEqualTo("RELEASED");
        assertThat(history.events()).anySatisfy(ev -> {
            assertThat(ev.eventType()).isEqualTo("RELEASE");
            assertThat(ev.detail()).isEqualTo("TASK_DONE");
            assertThat(ev.oldAuthVersion()).isEqualTo("v1");
            assertThat(ev.newAuthVersion()).isNull();
        });
        TaskView reassigned = assign("INC-O2", "T-1", "bob", "RES-SHARED", "v9",
                T0.plusSeconds(600));
        assertThat(reassigned.lease().authVersion()).isEqualTo("v9");
    }

    @Test
    void expiry_interceptComplete_keepsEvidenceAndLogs() {
        commanding("INC-E1", "alice");
        createTask("INC-E1", "alice", "T-1");
        assign("INC-E1", "T-1", "alice", "RES-EXP", "v1", T0.plusSeconds(60));
        service.recordExecution("INC-E1", "T-1", "alice", new TaskExecutionRequest(key(), "START"));

        // 时钟推进过到期时刻：只读查询显示到期但不写回
        ((ControllableClock) clock).setInstant(T0.plusSeconds(61));
        TaskView viewed = service.getTask("INC-E1", "T-1");
        assertThat(viewed.status()).isEqualTo("AUTHORIZATION_EXPIRED");
        assertThat(viewed.lease().status()).isEqualTo("EXPIRED");
        assertThat(viewed.lease().remainingSeconds()).isEqualTo(0L);
        String dbStatus = jdbc.queryForObject(
                "SELECT status FROM incident_tasks WHERE task_key = 'T-1'", String.class);
        assertThat(dbStatus).isEqualTo("OPEN");

        // 完成命令被拦截：任务转为 AUTHORIZATION_EXPIRED 并落库，不置 DONE
        TaskView intercepted = service.completeTask("INC-E1", "T-1", "alice",
                new TaskActionRequest(key()));
        assertThat(intercepted.status()).isEqualTo("AUTHORIZATION_EXPIRED");
        assertThat(intercepted.doneBy()).isNull();
        assertThat(jdbc.queryForObject("SELECT status FROM incident_tasks WHERE task_key = 'T-1'",
                String.class)).isEqualTo("AUTHORIZATION_EXPIRED");

        // 证据固化：EXPIRE + EXECUTION_BLOCKED(COMPLETE)，已执行日志保留
        TaskResourceHistoryView history = service.taskResourceHistory("INC-E1", "T-1");
        assertThat(history.events()).extracting("eventType")
                .containsExactly("ASSIGN", "EXPIRE", "EXECUTION_BLOCKED");
        assertThat(history.events().get(2).detail()).isEqualTo("COMPLETE");
        assertThat(history.events().get(1).oldAuthVersion()).isEqualTo("v1");
        assertThat(history.leases().get(0).status()).isEqualTo("EXPIRED");
        assertThat(history.executions()).hasSize(1);

        // 到期后禁止完成或再次分配
        assertApiStatus(() -> service.completeTask("INC-E1", "T-1", "alice",
                new TaskActionRequest(key())), HttpStatus.CONFLICT);
        assertApiStatus(() -> assign("INC-E1", "T-1", "alice", "RES-EXP", "v2",
                T0.plusSeconds(600)), HttpStatus.CONFLICT);
    }

    @Test
    void expiry_boundary_leftClosedRightOpen() {
        commanding("INC-B1", "alice");
        createTask("INC-B1", "alice", "T-1");
        assign("INC-B1", "T-1", "alice", "RES-B", "v1", T0.plusSeconds(60));
        // 到期前 1 秒：授权仍有效，可开始执行
        ((ControllableClock) clock).setInstant(T0.plusSeconds(59));
        TaskView started = service.recordExecution("INC-B1", "T-1", "alice",
                new TaskExecutionRequest(key(), "START"));
        assertThat(started.status()).isEqualTo("OPEN");
        // 恰到期时刻：区间左闭右开，授权已到期，执行被拦截
        ((ControllableClock) clock).setInstant(T0.plusSeconds(60));
        TaskView intercepted = service.recordExecution("INC-B1", "T-1", "alice",
                new TaskExecutionRequest(key(), "CONTINUE"));
        assertThat(intercepted.status()).isEqualTo("AUTHORIZATION_EXPIRED");
        TaskResourceHistoryView history = service.taskResourceHistory("INC-B1", "T-1");
        assertThat(history.events()).extracting("eventType")
                .containsExactly("ASSIGN", "EXPIRE", "EXECUTION_BLOCKED");
        assertThat(history.events().get(2).detail()).isEqualTo("CONTINUE");
        assertThat(history.executions()).hasSize(1);
    }

    @Test
    void expiredTask_doesNotBlockResolve_andCancelReleasesLease() {
        commanding("INC-G1", "alice");
        createTask("INC-G1", "alice", "T-1");
        createTask("INC-G1", "alice", "T-2");
        assign("INC-G1", "T-1", "alice", "RES-G", "v1", T0.plusSeconds(30));
        assign("INC-G1", "T-2", "alice", "RES-G2", "v1", T0.plusSeconds(600));
        // T-1 授权到期；T-2 取消时回收租约
        ((ControllableClock) clock).setInstant(T0.plusSeconds(31));
        TaskView cancelled = service.cancelTask("INC-G1", "T-2", "alice",
                new TaskActionRequest(key()));
        assertThat(cancelled.status()).isEqualTo("CANCELLED");
        assertThat(service.taskResourceHistory("INC-G1", "T-2").leases().get(0).status())
                .isEqualTo("RELEASED");
        // 状态推进命令物化到期任务；AUTHORIZATION_EXPIRED 为终态，不阻塞解决门禁
        service.changeStatus("INC-G1", "alice", new StatusRequest(key(), "CONTAINED"));
        IncidentView resolved = service.changeStatus("INC-G1", "alice",
                new StatusRequest(key(), "RESOLVED"));
        assertThat(resolved.status()).isEqualTo("RESOLVED");
        assertThat(service.getTask("INC-G1", "T-1").status()).isEqualTo("AUTHORIZATION_EXPIRED");
    }

    @Test
    void assignCommandKey_idempotency() {
        commanding("INC-K1", "alice");
        createTask("INC-K1", "alice", "T-1");
        String commandKey = key();
        TaskView first = service.assignResource("INC-K1", "T-1", "alice",
                new ResourceAssignRequest(commandKey, "RES-K", "v1", T0.plusSeconds(60)));
        // 同键同参重放首次结果，不产生第二条租约
        TaskView replay = service.assignResource("INC-K1", "T-1", "alice",
                new ResourceAssignRequest(commandKey, "RES-K", "v1", T0.plusSeconds(60)));
        assertThat(replay).isEqualTo(first);
        assertThat(service.taskResourceHistory("INC-K1", "T-1").leases()).hasSize(1);
        // 同键改参 409
        assertApiStatus(() -> service.assignResource("INC-K1", "T-1", "alice",
                new ResourceAssignRequest(commandKey, "RES-K", "v2", T0.plusSeconds(60))),
                HttpStatus.CONFLICT);
        // 失败不占键：参数非法回滚后同键可重新使用（在另一任务上分配）
        createTask("INC-K1", "alice", "T-2");
        String failedKey = key();
        assertApiStatus(() -> service.assignResource("INC-K1", "T-2", "alice",
                new ResourceAssignRequest(failedKey, "RES-F", "v1", T0)),
                HttpStatus.BAD_REQUEST);
        TaskView ok = service.assignResource("INC-K1", "T-2", "alice",
                new ResourceAssignRequest(failedKey, "RES-F", "v1", T0.plusSeconds(60)));
        assertThat(ok.lease().resourceKey()).isEqualTo("RES-F");
    }
}
