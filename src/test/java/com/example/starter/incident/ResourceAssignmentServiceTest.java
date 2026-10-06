package com.example.starter.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.example.starter.incident.dto.Requests.ReportRequest;
import com.example.starter.incident.dto.Requests.ResourceAssignRequest;
import com.example.starter.incident.dto.Requests.ResourceRenewItem;
import com.example.starter.incident.dto.Requests.ResourceRenewRequest;
import com.example.starter.incident.dto.Requests.StatusRequest;
import com.example.starter.incident.dto.Requests.TakeoverRequest;
import com.example.starter.incident.dto.Requests.TaskActionRequest;
import com.example.starter.incident.dto.Requests.TaskCreateRequest;
import com.example.starter.incident.dto.Requests.TaskExecuteRequest;
import com.example.starter.incident.dto.Requests.TaskStartRequest;
import com.example.starter.incident.dto.Requests.TransferRequest;
import com.example.starter.incident.dto.Responses.IncidentView;
import com.example.starter.incident.dto.Responses.ResourceAssignmentView;
import com.example.starter.incident.dto.Responses.ResourceEventView;
import com.example.starter.incident.dto.Responses.ResourceRenewView;
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
 * 事件资源到期服务测试：覆盖分配（到期时刻/授权版本校验、占用冲突）、
 * 开始/继续执行/完成的到期拦截与证据固化、续期（闭包校验、422 全回滚）、
 * 资源回收、只读不改状态与 commandKey 幂等语义。
 * 使用可控 Clock，时间相关断言不依赖真实睡眠。
 */
@SpringBootTest
@Import(ControllableClock.Config.class)
class ResourceAssignmentServiceTest {

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
        jdbc.update("DELETE FROM resource_assignment_events");
        jdbc.update("DELETE FROM task_execution_logs");
        jdbc.update("DELETE FROM task_resource_assignments");
        jdbc.update("DELETE FROM incident_resources");
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

    private IncidentView setup(String incidentKey, String commander) {
        service.report(new ReportRequest(incidentKey, "S2", "资源场景", "reporter-1"));
        return service.takeover(incidentKey, commander, new TakeoverRequest(key()));
    }

    private TaskView createTask(String incidentKey, String actor, String taskKey,
                                List<String> blockers) {
        return service.createTask(incidentKey, actor,
                new TaskCreateRequest(key(), taskKey, "G", "任务" + taskKey, blockers));
    }

    private ResourceAssignmentView assign(String incidentKey, String taskKey, String actor,
                                          String resourceKey, int version, Instant expiresAt) {
        return service.assignResource(incidentKey, taskKey, actor,
                new ResourceAssignRequest(key(), resourceKey, version, expiresAt));
    }

    private static void assertApiStatus(ThrowingCallable call, HttpStatus status) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.status()).isEqualTo(status));
    }

    private List<ResourceEventView> events(String incidentKey, String taskKey) {
        return service.listResourceEvents(incidentKey, taskKey);
    }

    @Test
    void assign_success_viewHolderAndEvidence() {
        setup("INC-R-001", "alice");
        createTask("INC-R-001", "alice", "T-1", List.of());

        ResourceAssignmentView view = assign("INC-R-001", "T-1", "alice", "DB-CLUSTER-1", 1,
                T0.plusSeconds(3600));
        assertThat(view.resourceKey()).isEqualTo("DB-CLUSTER-1");
        assertThat(view.authorizationVersion()).isEqualTo(1);
        assertThat(view.assignedBy()).isEqualTo("alice");
        assertThat(view.assignedAt()).isEqualTo(T0);
        assertThat(view.expiresAt()).isEqualTo(T0.plusSeconds(3600));
        assertThat(view.status()).isEqualTo("ACTIVE");
        assertThat(view.expired()).isFalse();
        assertThat(view.remainingSeconds()).isEqualTo(3600);
        assertThat(view.releasedBy()).isNull();
        assertThat(view.releasedAt()).isNull();

        // 任务视图携带租约信息
        TaskView task = service.getTask("INC-R-001", "T-1");
        assertThat(task.resourceKey()).isEqualTo("DB-CLUSTER-1");
        assertThat(task.authorizationVersion()).isEqualTo(1);
        assertThat(task.authorizationExpiresAt()).isEqualTo(T0.plusSeconds(3600));

        // ASSIGN 证据：旧版本为 null，新版本为 1
        List<ResourceEventView> events = events("INC-R-001", "T-1");
        assertThat(events).hasSize(1);
        assertThat(events.get(0).eventType()).isEqualTo("ASSIGN");
        assertThat(events.get(0).oldVersion()).isNull();
        assertThat(events.get(0).newVersion()).isEqualTo(1);
        assertThat(events.get(0).actor()).isEqualTo("alice");
        assertThat(events.get(0).occurredAt()).isEqualTo(T0);

        // 注册表持有方指向该租约
        var row = jdbc.queryForMap(
                "SELECT holder_assignment_id, holder_task_id FROM incident_resources"
                        + " WHERE resource_key = 'DB-CLUSTER-1'");
        assertThat(((Number) row.get("holder_assignment_id")).longValue()).isEqualTo(view.id());
        assertThat(row.get("holder_task_id")).isNotNull();
    }

    @Test
    void assign_validationFailures() {
        setup("INC-R-002", "alice");
        createTask("INC-R-002", "alice", "T-1", List.of());

        // 到期时刻必须晚于分配时刻：400 且 details 给出实际值/要求值/差额
        assertThatThrownBy(() -> service.assignResource("INC-R-002", "T-1", "alice",
                new ResourceAssignRequest(key(), "RES-1", 1, T0)))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.details()).isNotNull();
                });
        // 授权版本必须 >= 1
        assertApiStatus(() -> service.assignResource("INC-R-002", "T-1", "alice",
                new ResourceAssignRequest(key(), "RES-1", 0, T0.plusSeconds(60))),
                HttpStatus.BAD_REQUEST);
        // expiresAt 必填
        assertApiStatus(() -> service.assignResource("INC-R-002", "T-1", "alice",
                new ResourceAssignRequest(key(), "RES-1", 1, null)), HttpStatus.BAD_REQUEST);
        // 非当前指挥人 → 409
        assertApiStatus(() -> service.assignResource("INC-R-002", "T-1", "bob",
                new ResourceAssignRequest(key(), "RES-1", 1, T0.plusSeconds(60))),
                HttpStatus.CONFLICT);
        // 任务不存在 → 404
        assertApiStatus(() -> service.assignResource("INC-R-002", "T-9", "alice",
                new ResourceAssignRequest(key(), "RES-1", 1, T0.plusSeconds(60))),
                HttpStatus.NOT_FOUND);

        // 成功分配后：禁止再次分配（已有生效租约）
        assign("INC-R-002", "T-1", "alice", "RES-1", 1, T0.plusSeconds(600));
        assertApiStatus(() -> service.assignResource("INC-R-002", "T-1", "alice",
                new ResourceAssignRequest(key(), "RES-2", 1, T0.plusSeconds(60))),
                HttpStatus.CONFLICT);

        // 资源被其他事件占用 → 409
        setup("INC-R-003", "bob");
        createTask("INC-R-003", "bob", "T-1", List.of());
        assertApiStatus(() -> service.assignResource("INC-R-003", "T-1", "bob",
                new ResourceAssignRequest(key(), "RES-1", 1, T0.plusSeconds(60))),
                HttpStatus.CONFLICT);

        // 终态任务禁止分配
        createTask("INC-R-003", "bob", "T-2", List.of());
        service.completeTask("INC-R-003", "T-2", "bob", new TaskActionRequest(key()));
        assertApiStatus(() -> service.assignResource("INC-R-003", "T-2", "bob",
                new ResourceAssignRequest(key(), "RES-9", 1, T0.plusSeconds(60))),
                HttpStatus.CONFLICT);
    }

    @Test
    void expiry_completeIntercepted_taskExpired_evidencePersisted() {
        setup("INC-R-010", "alice");
        createTask("INC-R-010", "alice", "T-1", List.of());
        assign("INC-R-010", "T-1", "alice", "RES-1", 1, T0.plusSeconds(600));

        // 到期前：开始与继续执行正常，日志落库
        service.startTask("INC-R-010", "T-1", "alice", new TaskStartRequest(key(), "开工"));
        service.logExecution("INC-R-010", "T-1", "alice",
                new TaskExecuteRequest(key(), "已完成一半"));
        assertThat(service.listExecutionLogs("INC-R-010", "T-1")).hasSize(2);

        // 时钟越过到期时刻：完成被 422 拦截，任务落库 AUTHORIZATION_EXPIRED
        ((ControllableClock) clock).setInstant(T0.plusSeconds(600));
        String deniedKey = key();
        assertApiStatus(() -> service.completeTask("INC-R-010", "T-1", "alice",
                new TaskActionRequest(deniedKey)), HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(service.getTask("INC-R-010", "T-1").status())
                .isEqualTo("AUTHORIZATION_EXPIRED");

        // 证据固化：EXPIRE + EXECUTE_DENIED，且已执行日志保留
        List<ResourceEventView> events = events("INC-R-010", "T-1");
        assertThat(events).extracting(ResourceEventView::eventType)
                .containsExactly("ASSIGN", "EXPIRE", "EXECUTE_DENIED");
        assertThat(events.get(1).oldVersion()).isEqualTo(1);
        assertThat(events.get(1).newVersion()).isNull();
        assertThat(events.get(2).detail()).contains("complete");
        assertThat(service.listExecutionLogs("INC-R-010", "T-1")).hasSize(2);

        // 失败不占键：被拒的 commandKey 未占位
        Integer keyRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM command_keys WHERE command_key = ?", Integer.class,
                deniedKey);
        assertThat(keyRows).isZero();

        // 再次完成（新键）：仍 422，EXPIRE 不重复，EXECUTE_DENIED 追加
        assertApiStatus(() -> service.completeTask("INC-R-010", "T-1", "alice",
                new TaskActionRequest(key())), HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(events("INC-R-010", "T-1")).extracting(ResourceEventView::eventType)
                .containsExactly("ASSIGN", "EXPIRE", "EXECUTE_DENIED", "EXECUTE_DENIED");

        // 到期任务禁止再次分配
        assertApiStatus(() -> service.assignResource("INC-R-010", "T-1", "alice",
                new ResourceAssignRequest(key(), "RES-2", 1, T0.plusSeconds(7200))),
                HttpStatus.CONFLICT);
    }

    @Test
    void expiry_startAndExecuteIntercepted() {
        setup("INC-R-011", "alice");
        createTask("INC-R-011", "alice", "T-1", List.of());
        assign("INC-R-011", "T-1", "alice", "RES-1", 1, T0.plusSeconds(60));

        ((ControllableClock) clock).setInstant(T0.plusSeconds(61));
        assertApiStatus(() -> service.startTask("INC-R-011", "T-1", "alice",
                new TaskStartRequest(key(), "s")), HttpStatus.UNPROCESSABLE_ENTITY);
        assertApiStatus(() -> service.logExecution("INC-R-011", "T-1", "alice",
                new TaskExecuteRequest(key(), "p")), HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(service.getTask("INC-R-011", "T-1").status())
                .isEqualTo("AUTHORIZATION_EXPIRED");
        assertThat(events("INC-R-011", "T-1")).extracting(ResourceEventView::eventType)
                .containsExactly("ASSIGN", "EXPIRE", "EXECUTE_DENIED", "EXECUTE_DENIED");
    }

    @Test
    void readOnly_doesNotChangeState() {
        setup("INC-R-012", "alice");
        createTask("INC-R-012", "alice", "T-1", List.of());
        assign("INC-R-012", "T-1", "alice", "RES-1", 1, T0.plusSeconds(60));

        ((ControllableClock) clock).setInstant(T0.plusSeconds(120));
        // 读取呈现有效状态 AUTHORIZATION_EXPIRED，但不落库、不写证据
        assertThat(service.getTask("INC-R-012", "T-1").status())
                .isEqualTo("AUTHORIZATION_EXPIRED");
        assertThat(service.listTasks("INC-R-012").tasks().get(0).status())
                .isEqualTo("AUTHORIZATION_EXPIRED");
        ResourceAssignmentView resource = service.getTaskResource("INC-R-012", "T-1");
        assertThat(resource.expired()).isTrue();
        assertThat(resource.remainingSeconds()).isEqualTo(-60);

        String stored = jdbc.queryForObject(
                "SELECT t.status FROM incident_tasks t JOIN incidents i ON i.id = t.incident_id"
                        + " WHERE i.incident_key = 'INC-R-012' AND t.task_key = 'T-1'",
                String.class);
        assertThat(stored).isEqualTo("OPEN");
        assertThat(events("INC-R-012", "T-1")).extracting(ResourceEventView::eventType)
                .containsExactly("ASSIGN");
    }

    @Test
    void renew_success_restoresTaskAndRecordsEvidence() {
        setup("INC-R-020", "alice");
        createTask("INC-R-020", "alice", "T-1", List.of());
        assign("INC-R-020", "T-1", "alice", "RES-1", 1, T0.plusSeconds(60));

        // 到期并完成拦截 → AUTHORIZATION_EXPIRED
        ((ControllableClock) clock).setInstant(T0.plusSeconds(61));
        assertApiStatus(() -> service.completeTask("INC-R-020", "T-1", "alice",
                new TaskActionRequest(key())), HttpStatus.UNPROCESSABLE_ENTITY);

        // 续期：不同授权角色、新版本、新到期时刻 → 任务恢复 OPEN
        ResourceRenewView renewed = service.renewResources("INC-R-020", "auth-officer",
                new ResourceRenewRequest(key(), List.of(
                        new ResourceRenewItem("T-1", 2, T0.plusSeconds(3600)))));
        assertThat(renewed.renewed()).hasSize(1);
        assertThat(renewed.renewed().get(0).authorizationVersion()).isEqualTo(2);
        assertThat(renewed.renewed().get(0).expiresAt()).isEqualTo(T0.plusSeconds(3600));
        assertThat(renewed.renewed().get(0).expired()).isFalse();
        assertThat(service.getTask("INC-R-020", "T-1").status()).isEqualTo("OPEN");

        // RENEW 证据固化旧/新版本、时刻与操作人
        List<ResourceEventView> events = events("INC-R-020", "T-1");
        assertThat(events).extracting(ResourceEventView::eventType)
                .containsExactly("ASSIGN", "EXPIRE", "EXECUTE_DENIED", "RENEW");
        ResourceEventView renew = events.get(3);
        assertThat(renew.oldVersion()).isEqualTo(1);
        assertThat(renew.newVersion()).isEqualTo(2);
        assertThat(renew.actor()).isEqualTo("auth-officer");
        assertThat(renew.occurredAt()).isEqualTo(T0.plusSeconds(61));

        // 续期后可完成
        TaskView done = service.completeTask("INC-R-020", "T-1", "alice",
                new TaskActionRequest(key()));
        assertThat(done.status()).isEqualTo("DONE");
    }

    @Test
    void renew_conflicts_422_andNoStateChange() {
        setup("INC-R-021", "alice");
        createTask("INC-R-021", "alice", "T-1", List.of());
        assign("INC-R-021", "T-1", "alice", "RES-1", 2, T0.plusSeconds(600));

        // 续期人与原分配人相同 → 422 NOT_ASSIGNER_ROLE
        assertRenewConflict("INC-R-021", "alice",
                new ResourceRenewItem("T-1", 3, T0.plusSeconds(3600)), "NOT_ASSIGNER_ROLE");
        // 版本未递增 → 422 VERSION_NOT_INCREASING
        assertRenewConflict("INC-R-021", "officer",
                new ResourceRenewItem("T-1", 2, T0.plusSeconds(3600)), "VERSION_NOT_INCREASING");
        // 新到期时刻不晚于当前 → 422 EXPIRES_NOT_FUTURE
        assertRenewConflict("INC-R-021", "officer",
                new ResourceRenewItem("T-1", 3, T0), "EXPIRES_NOT_FUTURE");
        // 无生效租约的任务 → 422 NO_ACTIVE_ASSIGNMENT
        createTask("INC-R-021", "alice", "T-2", List.of());
        assertRenewConflict("INC-R-021", "officer",
                new ResourceRenewItem("T-2", 1, T0.plusSeconds(3600)), "NO_ACTIVE_ASSIGNMENT");
        // 任务不存在 → 404
        assertApiStatus(() -> service.renewResources("INC-R-021", "officer",
                new ResourceRenewRequest(key(), List.of(
                        new ResourceRenewItem("T-9", 3, T0.plusSeconds(3600))))),
                HttpStatus.NOT_FOUND);

        // 全部失败均不改变任何状态：版本、到期时刻、任务状态、证据均无变化
        ResourceAssignmentView current = service.getTaskResource("INC-R-021", "T-1");
        assertThat(current.authorizationVersion()).isEqualTo(2);
        assertThat(current.expiresAt()).isEqualTo(T0.plusSeconds(600));
        assertThat(service.getTask("INC-R-021", "T-1").status()).isEqualTo("OPEN");
        assertThat(events("INC-R-021", "T-1")).extracting(ResourceEventView::eventType)
                .containsExactly("ASSIGN");
    }

    @Test
    void renew_terminalTaskAndPendingTransfer_422() {
        setup("INC-R-022", "alice");
        createTask("INC-R-022", "alice", "T-1", List.of());
        createTask("INC-R-022", "alice", "T-2", List.of());
        assign("INC-R-022", "T-1", "alice", "RES-1", 1, T0.plusSeconds(600));
        assign("INC-R-022", "T-2", "alice", "RES-2", 1, T0.plusSeconds(600));

        // 已完成任务不可续期
        service.completeTask("INC-R-022", "T-1", "alice", new TaskActionRequest(key()));
        assertRenewConflict("INC-R-022", "officer",
                new ResourceRenewItem("T-1", 2, T0.plusSeconds(3600)), "TASK_TERMINAL");

        // 正在交接出去（存在待接受交接）的任务不可续期
        service.initiateTransfer("INC-R-022", "alice", new TransferRequest(key(), "bob"));
        assertRenewConflict("INC-R-022", "officer",
                new ResourceRenewItem("T-2", 2, T0.plusSeconds(3600)), "PENDING_TRANSFER");
    }

    @Test
    void renew_multiTaskClosure_validatesAllOrNothing() {
        // INC-A 的任务被 INC-B 阻塞；闭包包含 INC-B 的租约
        setup("INC-R-030", "alice");
        setup("INC-R-031", "bob");
        createTask("INC-R-031", "bob", "T-B", List.of());
        createTask("INC-R-030", "alice", "T-A1", List.of("INC-R-031"));
        createTask("INC-R-030", "alice", "T-A2", List.of());
        assign("INC-R-031", "T-B", "bob", "RES-B", 1, T0.plusSeconds(600));
        assign("INC-R-030", "T-A1", "alice", "RES-A1", 1, T0.plusSeconds(600));
        assign("INC-R-030", "T-A2", "alice", "RES-A2", 1, T0.plusSeconds(600));

        // 一次续期多个依赖任务：闭包内 INC-B 的租约被校验但不修改
        ResourceRenewView view = service.renewResources("INC-R-030", "officer",
                new ResourceRenewRequest(key(), List.of(
                        new ResourceRenewItem("T-A1", 2, T0.plusSeconds(3600)),
                        new ResourceRenewItem("T-A2", 5, T0.plusSeconds(7200)))));
        assertThat(view.renewed()).hasSize(2);
        assertThat(view.validatedClosureAssignments()).isEqualTo(1);
        assertThat(service.getTaskResource("INC-R-031", "T-B").authorizationVersion())
                .isEqualTo(1);

        // 闭包内资源被其他事件占用（测试直接改写持有方模拟并发占用结果）→ 422 全回滚
        jdbc.update("UPDATE incident_resources SET holder_assignment_id = -1"
                + " WHERE resource_key = 'RES-B'");
        assertThatThrownBy(() -> service.renewResources("INC-R-030", "officer",
                new ResourceRenewRequest(key(), List.of(
                        new ResourceRenewItem("T-A1", 3, T0.plusSeconds(4000))))))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(e.details().toString()).contains("RESOURCE_OCCUPIED");
                });
        // 任一冲突不改变任何任务状态或租约
        assertThat(service.getTaskResource("INC-R-030", "T-A1").authorizationVersion())
                .isEqualTo(2);
        assertThat(events("INC-R-030", "T-A1")).extracting(ResourceEventView::eventType)
                .containsExactly("ASSIGN", "RENEW");
    }

    @Test
    void release_onCompleteAndCancel_freesResourceForReuse() {
        setup("INC-R-040", "alice");
        setup("INC-R-041", "bob");
        createTask("INC-R-040", "alice", "T-1", List.of());
        createTask("INC-R-040", "alice", "T-2", List.of());
        assign("INC-R-040", "T-1", "alice", "RES-1", 1, T0.plusSeconds(600));
        assign("INC-R-040", "T-2", "alice", "RES-2", 1, T0.plusSeconds(600));

        // 完成回收 RES-1，取消回收 RES-2
        service.completeTask("INC-R-040", "T-1", "alice", new TaskActionRequest(key()));
        service.cancelTask("INC-R-040", "T-2", "alice", new TaskActionRequest(key()));
        assertThat(events("INC-R-040", "T-1")).extracting(ResourceEventView::eventType)
                .containsExactly("ASSIGN", "RELEASE");
        assertThat(events("INC-R-040", "T-2")).extracting(ResourceEventView::eventType)
                .containsExactly("ASSIGN", "RELEASE");
        Integer holders = jdbc.queryForObject(
                "SELECT COUNT(*) FROM incident_resources WHERE holder_assignment_id IS NOT NULL",
                Integer.class);
        assertThat(holders).isZero();

        // 回收后资源可被其他事件重新占用
        createTask("INC-R-041", "bob", "T-1", List.of());
        ResourceAssignmentView reassigned = assign("INC-R-041", "T-1", "bob", "RES-1", 1,
                T0.plusSeconds(600));
        assertThat(reassigned.status()).isEqualTo("ACTIVE");
    }

    @Test
    void idempotency_replaySameParams_conflictOnDifferentParams() {
        setup("INC-R-050", "alice");
        createTask("INC-R-050", "alice", "T-1", List.of());

        // 分配：同键同参重放首次结果
        String assignKey = key();
        ResourceAssignRequest assignReq = new ResourceAssignRequest(assignKey, "RES-1", 1,
                T0.plusSeconds(600));
        ResourceAssignmentView first = service.assignResource("INC-R-050", "T-1", "alice",
                assignReq);
        ResourceAssignmentView replayed = service.assignResource("INC-R-050", "T-1", "alice",
                assignReq);
        assertThat(replayed).isEqualTo(first);
        assertThat(events("INC-R-050", "T-1")).hasSize(1);

        // 同键异参 → 409
        assertApiStatus(() -> service.assignResource("INC-R-050", "T-1", "alice",
                new ResourceAssignRequest(assignKey, "RES-2", 1, T0.plusSeconds(600))),
                HttpStatus.CONFLICT);

        // 续期：同键同参重放，不重复加版本
        String renewKey = key();
        ResourceRenewRequest renewReq = new ResourceRenewRequest(renewKey, List.of(
                new ResourceRenewItem("T-1", 2, T0.plusSeconds(3600))));
        ResourceRenewView renewed = service.renewResources("INC-R-050", "officer", renewReq);
        ResourceRenewView renewedReplay = service.renewResources("INC-R-050", "officer", renewReq);
        assertThat(renewedReplay).isEqualTo(renewed);
        assertThat(service.getTaskResource("INC-R-050", "T-1").authorizationVersion())
                .isEqualTo(2);
        assertThat(events("INC-R-050", "T-1")).extracting(ResourceEventView::eventType)
                .containsExactly("ASSIGN", "RENEW");
        assertApiStatus(() -> service.renewResources("INC-R-050", "officer",
                new ResourceRenewRequest(renewKey, List.of(
                        new ResourceRenewItem("T-1", 3, T0.plusSeconds(3600))))),
                HttpStatus.CONFLICT);
    }

    @Test
    void resolveGate_countsEffectivelyExpiredTasks() {
        setup("INC-R-060", "alice");
        createTask("INC-R-060", "alice", "T-1", List.of());
        assign("INC-R-060", "T-1", "alice", "RES-1", 1, T0.plusSeconds(60));
        service.changeStatus("INC-R-060", "alice", new StatusRequest(key(), "CONTAINED"));

        // 租约到期后任务视为未完成，阻塞 RESOLVED
        ((ControllableClock) clock).setInstant(T0.plusSeconds(61));
        assertApiStatus(() -> service.changeStatus("INC-R-060", "alice",
                new StatusRequest(key(), "RESOLVED")), HttpStatus.CONFLICT);

        // 取消到期任务后可解决
        service.cancelTask("INC-R-060", "T-1", "alice", new TaskActionRequest(key()));
        IncidentView resolved = service.changeStatus("INC-R-060", "alice",
                new StatusRequest(key(), "RESOLVED"));
        assertThat(resolved.status()).isEqualTo("RESOLVED");
    }

    private void assertRenewConflict(String incidentKey, String actor, ResourceRenewItem item,
                                     String reason) {
        assertThatThrownBy(() -> service.renewResources(incidentKey, actor,
                new ResourceRenewRequest(key(), List.of(item))))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(e.details().toString()).contains(reason);
                });
    }
}