package com.example.starter.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.example.starter.incident.dto.Requests.RenewalEntry;
import com.example.starter.incident.dto.Requests.ReportRequest;
import com.example.starter.incident.dto.Requests.ResourceAssignRequest;
import com.example.starter.incident.dto.Requests.TakeoverRequest;
import com.example.starter.incident.dto.Requests.TaskActionRequest;
import com.example.starter.incident.dto.Requests.TaskCreateRequest;
import com.example.starter.incident.dto.Requests.TaskRenewalRequest;
import com.example.starter.incident.dto.Requests.TaskStartRequest;
import com.example.starter.incident.dto.Requests.TransferAcceptRequest;
import com.example.starter.incident.dto.Requests.TransferRequest;
import com.example.starter.incident.dto.Responses.IncidentView;
import com.example.starter.incident.dto.Responses.RenewalView;
import com.example.starter.incident.dto.Responses.RenewedTaskView;
import com.example.starter.incident.dto.Responses.ResourceEventView;
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
 * 批量续期服务测试：覆盖续期主流程（恢复到期任务）、角色/版本/时刻校验、
 * 终态与已交接任务禁续、依赖闭包原子性（任一冲突 422 且不改任何状态或租约）、
 * 资源被其他事件占用冲突与 commandKey 幂等语义。
 */
@SpringBootTest
@Import(ControllableClock.Config.class)
class ResourceRenewalServiceTest {

    private static final Instant T0 = Instant.parse("2026-09-22T00:00:00Z");

    @Autowired
    private IncidentService incidentService;

    @Autowired
    private ResourceAuthorizationService resourceService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Clock clock;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM command_keys");
        jdbc.update("DELETE FROM resource_authorization_events");
        jdbc.update("DELETE FROM task_execution_logs");
        jdbc.update("DELETE FROM task_resource_assignments");
        jdbc.update("DELETE FROM resource_locks");
        jdbc.update("DELETE FROM incident_escalations");
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

    private IncidentView commanding(String incidentKey, String commander) {
        incidentService.report(new ReportRequest(incidentKey, "S2", "续期场景", "reporter-1"));
        return incidentService.takeover(incidentKey, commander, new TakeoverRequest(key()));
    }

    private TaskView createTask(String incidentKey, String actor, String taskKey,
                                List<String> blockers) {
        return incidentService.createTask(incidentKey, actor,
                new TaskCreateRequest(key(), taskKey, "DB", "处置", blockers));
    }

    private void assign(String incidentKey, String taskKey, String actor,
                        String resourceKey, long version, Instant expiresAt) {
        resourceService.assign(incidentKey, taskKey, actor,
                new ResourceAssignRequest(key(), resourceKey, version, expiresAt));
    }

    private RenewalView renew(String incidentKey, String actor, RenewalEntry... entries) {
        return resourceService.renew(incidentKey, actor,
                new TaskRenewalRequest(key(), List.of(entries)));
    }

    private static void assertApiStatus(ThrowingCallable call, HttpStatus status) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.status()).isEqualTo(status));
    }

    private long assignmentVersion(String taskKey) {
        Long version = jdbc.queryForObject(
                "SELECT authorization_version FROM task_resource_assignments a"
                        + " JOIN incident_tasks t ON t.id = a.task_id WHERE t.task_key = ?",
                Long.class, taskKey);
        return version;
    }

    @Test
    void renew_mainFlow_restoresExpiredTask() {
        commanding("INC-N1", "alice");
        createTask("INC-N1", "alice", "T-1", List.of());
        assign("INC-N1", "T-1", "alice", "RES-1", 1L, T0.plusSeconds(300));
        incidentService.startTask("INC-N1", "T-1", "alice", new TaskStartRequest(key(), null));
        ((ControllableClock) clock).setInstant(T0.plusSeconds(400));
        // 触发到期迁移
        assertApiStatus(() -> incidentService.completeTask("INC-N1", "T-1", "alice",
                new TaskActionRequest(key())), HttpStatus.CONFLICT);

        RenewalView view = renew("INC-N1", "carol",
                new RenewalEntry("T-1", 2L, T0.plusSeconds(900)));
        RenewedTaskView renewed = view.renewed().get(0);
        assertThat(renewed.taskKey()).isEqualTo("T-1");
        assertThat(renewed.resourceKey()).isEqualTo("RES-1");
        assertThat(renewed.oldAuthorizationVersion()).isEqualTo(1L);
        assertThat(renewed.newAuthorizationVersion()).isEqualTo(2L);
        assertThat(renewed.newExpiresAt()).isEqualTo(T0.plusSeconds(900));
        // 恢复到期前状态（IN_PROGRESS）
        assertThat(renewed.taskStatus()).isEqualTo("IN_PROGRESS");
        TaskView task = incidentService.getTask("INC-N1", "T-1");
        assertThat(task.status()).isEqualTo("IN_PROGRESS");
        assertThat(task.authorizationVersion()).isEqualTo(2L);
        assertThat(task.authorizationExpired()).isFalse();
        // RENEW 证据固化旧/新版本与操作人
        TaskResourceHistoryView history = resourceService.taskResourceHistory("INC-N1", "T-1");
        ResourceEventView renewEvent = history.events().get(history.events().size() - 1);
        assertThat(renewEvent.eventType()).isEqualTo("RENEW");
        assertThat(renewEvent.oldAuthorizationVersion()).isEqualTo(1L);
        assertThat(renewEvent.newAuthorizationVersion()).isEqualTo(2L);
        assertThat(renewEvent.actor()).isEqualTo("carol");
        assertThat(renewEvent.occurredAt()).isEqualTo(T0.plusSeconds(400));
        // 续期后可完成
        TaskView done = incidentService.completeTask("INC-N1", "T-1", "alice",
                new TaskActionRequest(key()));
        assertThat(done.status()).isEqualTo("DONE");
    }

    @Test
    void renew_validation() {
        commanding("INC-RV", "alice");
        createTask("INC-RV", "alice", "T-1", List.of());
        createTask("INC-RV", "alice", "T-2", List.of());
        createTask("INC-RV", "alice", "T-3", List.of());
        assign("INC-RV", "T-1", "alice", "RES-1", 1L, T0.plusSeconds(300));
        assign("INC-RV", "T-2", "alice", "RES-2", 1L, T0.plusSeconds(300));
        // 续期人必须不同于原分配人
        assertApiStatus(() -> renew("INC-RV", "alice",
                new RenewalEntry("T-1", 2L, T0.plusSeconds(900))),
                HttpStatus.UNPROCESSABLE_ENTITY);
        // 新版本必须严格更大（相等或更小均 422，details 含当前值与要求值）
        assertThatThrownBy(() -> renew("INC-RV", "carol",
                new RenewalEntry("T-1", 1L, T0.plusSeconds(900))))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(e.details().toString()).contains("currentVersion")
                            .contains("requestedVersion");
                });
        assertApiStatus(() -> renew("INC-RV", "carol",
                new RenewalEntry("T-1", 0L, T0.plusSeconds(900))),
                HttpStatus.UNPROCESSABLE_ENTITY);
        // 新到期时刻必须晚于续期时刻
        assertApiStatus(() -> renew("INC-RV", "carol",
                new RenewalEntry("T-1", 2L, T0)),
                HttpStatus.UNPROCESSABLE_ENTITY);
        // 未分配资源的任务不能续期
        assertApiStatus(() -> renew("INC-RV", "carol",
                new RenewalEntry("T-3", 2L, T0.plusSeconds(900))),
                HttpStatus.UNPROCESSABLE_ENTITY);
        // 任务不存在
        assertApiStatus(() -> renew("INC-RV", "carol",
                new RenewalEntry("T-9", 2L, T0.plusSeconds(900))),
                HttpStatus.NOT_FOUND);
        // 已完成任务不可续期
        incidentService.completeTask("INC-RV", "T-1", "alice", new TaskActionRequest(key()));
        assertApiStatus(() -> renew("INC-RV", "carol",
                new RenewalEntry("T-1", 2L, T0.plusSeconds(900))),
                HttpStatus.UNPROCESSABLE_ENTITY);
        // 已取消任务不可续期
        incidentService.cancelTask("INC-RV", "T-2", "alice", new TaskActionRequest(key()));
        assertApiStatus(() -> renew("INC-RV", "carol",
                new RenewalEntry("T-2", 2L, T0.plusSeconds(900))),
                HttpStatus.UNPROCESSABLE_ENTITY);
        // entries 为空 / 重复 taskKey
        assertApiStatus(() -> resourceService.renew("INC-RV", "carol",
                new TaskRenewalRequest(key(), List.of())), HttpStatus.BAD_REQUEST);
        assertApiStatus(() -> renew("INC-RV", "carol",
                new RenewalEntry("T-1", 2L, T0.plusSeconds(900)),
                new RenewalEntry("T-1", 3L, T0.plusSeconds(900))),
                HttpStatus.BAD_REQUEST);
        // 全部失败后租约未被改变
        assertThat(assignmentVersion("T-1")).isEqualTo(1L);
        assertThat(assignmentVersion("T-2")).isEqualTo(1L);
    }

    @Test
    void renew_transferredOutTask_rejected() {
        commanding("INC-TR", "alice");
        createTask("INC-TR", "alice", "T-1", List.of());
        assign("INC-TR", "T-1", "alice", "RES-1", 1L, T0.plusSeconds(600));
        // 事件交接给 bob 后，原租约任务视为已交接出去
        incidentService.initiateTransfer("INC-TR", "alice", new TransferRequest(key(), "bob"));
        incidentService.acceptTransfer("INC-TR", "bob", new TransferAcceptRequest(key()));
        assertApiStatus(() -> renew("INC-TR", "carol",
                new RenewalEntry("T-1", 2L, T0.plusSeconds(900))),
                HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(assignmentVersion("T-1")).isEqualTo(1L);
    }

    @Test
    void renew_dependencyClosure_atomic() {
        commanding("INC-CA", "alice");
        commanding("INC-CB", "bob");
        createTask("INC-CA", "alice", "T-1", List.of("INC-CB"));
        createTask("INC-CB", "bob", "T-2", List.of());
        assign("INC-CA", "T-1", "alice", "RES-A", 1L, T0.plusSeconds(300));
        assign("INC-CB", "T-2", "bob", "RES-B", 1L, T0.plusSeconds(300));
        ((ControllableClock) clock).setInstant(T0.plusSeconds(400));
        // 闭包内 T-2 已到期但未一并续期：422，且 T-1 租约与状态不变
        assertThatThrownBy(() -> renew("INC-CA", "carol",
                new RenewalEntry("T-1", 2L, T0.plusSeconds(900))))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(e.details().toString()).contains("T-2").contains("RES-B");
                });
        assertThat(assignmentVersion("T-1")).isEqualTo(1L);
        assertThat(incidentService.getTask("INC-CA", "T-1").status())
                .isEqualTo("AUTHORIZATION_EXPIRED");
        Integer renewEvents = jdbc.queryForObject(
                "SELECT COUNT(*) FROM resource_authorization_events WHERE event_type = 'RENEW'",
                Integer.class);
        assertThat(renewEvents).isZero();
        // 依赖事件先续期后，本事件续期成功
        renew("INC-CB", "dave", new RenewalEntry("T-2", 2L, T0.plusSeconds(900)));
        RenewalView view = renew("INC-CA", "carol",
                new RenewalEntry("T-1", 2L, T0.plusSeconds(900)));
        assertThat(view.renewed().get(0).taskStatus()).isEqualTo("OPEN");
        assertThat(assignmentVersion("T-1")).isEqualTo(2L);
    }

    @Test
    void renew_multipleEntriesInOneCall() {
        commanding("INC-M1", "alice");
        createTask("INC-M1", "alice", "T-1", List.of());
        createTask("INC-M1", "alice", "T-2", List.of());
        assign("INC-M1", "T-1", "alice", "RES-1", 1L, T0.plusSeconds(300));
        assign("INC-M1", "T-2", "alice", "RES-2", 1L, T0.plusSeconds(300));
        ((ControllableClock) clock).setInstant(T0.plusSeconds(400));
        RenewalView view = renew("INC-M1", "carol",
                new RenewalEntry("T-1", 2L, T0.plusSeconds(900)),
                new RenewalEntry("T-2", 5L, T0.plusSeconds(900)));
        assertThat(view.renewed()).hasSize(2);
        assertThat(assignmentVersion("T-1")).isEqualTo(2L);
        assertThat(assignmentVersion("T-2")).isEqualTo(5L);
        Integer renewEvents = jdbc.queryForObject(
                "SELECT COUNT(*) FROM resource_authorization_events WHERE event_type = 'RENEW'",
                Integer.class);
        assertThat(renewEvents).isEqualTo(2);
    }

    @Test
    void renew_resourceOccupiedByOtherIncident_rejected() {
        commanding("INC-OA", "alice");
        commanding("INC-OB", "bob");
        createTask("INC-OA", "alice", "T-1", List.of());
        createTask("INC-OB", "bob", "T-2", List.of());
        assign("INC-OA", "T-1", "alice", "RES-1", 1L, T0.plusSeconds(300));
        // 原租约到期后资源被其他事件占用
        ((ControllableClock) clock).setInstant(T0.plusSeconds(400));
        assign("INC-OB", "T-2", "bob", "RES-1", 1L, T0.plusSeconds(800));
        // 续期须重新校验占用：冲突 422，租约不变
        assertThatThrownBy(() -> renew("INC-OA", "carol",
                new RenewalEntry("T-1", 2L, T0.plusSeconds(900))))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(e.details().toString()).contains("RES-1");
                });
        assertThat(assignmentVersion("T-1")).isEqualTo(1L);
    }

    @Test
    void renew_commandKeyIdempotency() {
        commanding("INC-I1", "alice");
        createTask("INC-I1", "alice", "T-1", List.of());
        assign("INC-I1", "T-1", "alice", "RES-1", 1L, T0.plusSeconds(300));
        ((ControllableClock) clock).setInstant(T0.plusSeconds(400));
        String commandKey = key();
        RenewalView first = resourceService.renew("INC-I1", "carol",
                new TaskRenewalRequest(commandKey,
                        List.of(new RenewalEntry("T-1", 2L, T0.plusSeconds(900)))));
        RenewalView replay = resourceService.renew("INC-I1", "carol",
                new TaskRenewalRequest(commandKey,
                        List.of(new RenewalEntry("T-1", 2L, T0.plusSeconds(900)))));
        assertThat(replay).isEqualTo(first);
        assertThat(assignmentVersion("T-1")).isEqualTo(2L);
        // 同键改参 → 409
        assertApiStatus(() -> resourceService.renew("INC-I1", "carol",
                new TaskRenewalRequest(commandKey,
                        List.of(new RenewalEntry("T-1", 3L, T0.plusSeconds(900))))),
                HttpStatus.CONFLICT);
        // 失败不占键：422 后同键可携带合法参数成功
        String failedKey = key();
        assertApiStatus(() -> resourceService.renew("INC-I1", "alice",
                new TaskRenewalRequest(failedKey,
                        List.of(new RenewalEntry("T-1", 3L, T0.plusSeconds(900))))),
                HttpStatus.UNPROCESSABLE_ENTITY);
        RenewalView ok = resourceService.renew("INC-I1", "carol",
                new TaskRenewalRequest(failedKey,
                        List.of(new RenewalEntry("T-1", 3L, T0.plusSeconds(900)))));
        assertThat(ok.renewed().get(0).newAuthorizationVersion()).isEqualTo(3L);
    }
}
