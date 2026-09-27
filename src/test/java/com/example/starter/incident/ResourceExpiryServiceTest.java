package com.example.starter.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.example.starter.incident.dto.Requests.RenewItem;
import com.example.starter.incident.dto.Requests.ReportRequest;
import com.example.starter.incident.dto.Requests.ResourceAssignRequest;
import com.example.starter.incident.dto.Requests.ResourceRenewRequest;
import com.example.starter.incident.dto.Requests.StatusRequest;
import com.example.starter.incident.dto.Requests.TakeoverRequest;
import com.example.starter.incident.dto.Requests.TaskActionRequest;
import com.example.starter.incident.dto.Requests.TaskCreateRequest;
import com.example.starter.incident.dto.Requests.TransferRequest;
import com.example.starter.incident.dto.Responses.IncidentView;
import com.example.starter.incident.dto.Responses.ResourceDiagnosticsView;
import com.example.starter.incident.dto.Responses.ResourceEventView;
import com.example.starter.incident.dto.Responses.ResourceLeaseView;
import com.example.starter.incident.dto.Responses.ResourceRenewalView;
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
 * 事件资源授权到期服务测试：覆盖分配校验、执行门禁（开始/继续/完成）、
 * 到期判定（左闭右开、只按命令/访问时钟判定）、续期闭包校验与原子性、
 * 回收、终态规则、commandKey 幂等与证据事件固化。
 * 使用可控 Clock，时间相关断言不依赖真实睡眠。
 */
@SpringBootTest
@Import(ControllableClock.Config.class)
class ResourceExpiryServiceTest {

    private static final Instant T0 = Instant.parse("2026-09-27T00:00:00Z");

    @Autowired
    private IncidentService incidentService;

    @Autowired
    private ResourceService service;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Clock clock;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM command_keys");
        jdbc.update("DELETE FROM incident_resource_events");
        jdbc.update("DELETE FROM incident_task_leases");
        jdbc.update("DELETE FROM resource_locks");
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
        incidentService.report(new ReportRequest(incidentKey, "S2", "核心链路故障", "reporter-1"));
        return incidentService.takeover(incidentKey, commander, new TakeoverRequest(key()));
    }

    private TaskView openTask(String incidentKey, String commander, String taskKey) {
        return incidentService.createTask(incidentKey, commander,
                new TaskCreateRequest(key(), taskKey, "DB", "扩容连接池", List.of()));
    }

    private ResourceLeaseView assign(String incidentKey, String taskKey, String actor,
                                     String resourceKey, int version, Instant expiresAt) {
        return service.assign(incidentKey, taskKey, actor,
                new ResourceAssignRequest(key(), resourceKey, version, expiresAt));
    }

    private static void assertApiStatus(ThrowingCallable call, HttpStatus status) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.status()).isEqualTo(status));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> detailMap(ApiException e) {
        return (Map<String, Object>) e.details();
    }

    @Test
    void assignAndExecute_mainFlow() {
        commanding("INC-R1", "alice");
        openTask("INC-R1", "alice", "T-1");
        ResourceLeaseView lease = assign("INC-R1", "T-1", "alice", "db-pool-1", 1,
                T0.plusSeconds(600));
        assertThat(lease.taskKey()).isEqualTo("T-1");
        assertThat(lease.resourceKey()).isEqualTo("db-pool-1");
        assertThat(lease.authVersion()).isEqualTo(1);
        assertThat(lease.assignedBy()).isEqualTo("alice");
        assertThat(lease.assignedAt()).isEqualTo(T0);
        assertThat(lease.expiresAt()).isEqualTo(T0.plusSeconds(600));
        assertThat(lease.status()).isEqualTo("ACTIVE");
        assertThat(lease.expired()).isFalse();
        assertThat(lease.releasedBy()).isNull();

        // 开始执行：OPEN → IN_PROGRESS，记录 EXECUTE_START
        TaskView started = service.startTask("INC-R1", "T-1", "alice",
                new TaskActionRequest(key()));
        assertThat(started.status()).isEqualTo("IN_PROGRESS");
        // 继续执行：记录 EXECUTE_CONTINUE
        TaskView continued = service.continueTask("INC-R1", "T-1", "alice",
                new TaskActionRequest(key()));
        assertThat(continued.status()).isEqualTo("IN_PROGRESS");
        // 完成：记录 EXECUTE_COMPLETE 并自动回收租约
        TaskView done = incidentService.completeTask("INC-R1", "T-1", "alice",
                new TaskActionRequest(key()));
        assertThat(done.status()).isEqualTo("DONE");

        ResourceLeaseView after = service.getLease("INC-R1", "T-1");
        assertThat(after.status()).isEqualTo("RELEASED");
        assertThat(after.releasedBy()).isEqualTo("alice");
        assertThat(after.releasedAt()).isEqualTo(T0);

        // 证据事件按序固化：ASSIGN / EXECUTE_START / EXECUTE_CONTINUE / EXECUTE_COMPLETE / RELEASE
        List<ResourceEventView> events = service.listEvents("INC-R1", "T-1");
        assertThat(events).extracting(ResourceEventView::eventType)
                .containsExactly("ASSIGN", "EXECUTE_START", "EXECUTE_CONTINUE",
                        "EXECUTE_COMPLETE", "RELEASE");
        assertThat(events.get(0).newVersion()).isEqualTo(1);
        assertThat(events.get(0).oldVersion()).isNull();
        assertThat(events.get(0).actor()).isEqualTo("alice");
        assertThat(events.get(0).occurredAt()).isEqualTo(T0);
        assertThat(events.get(4).detail()).isEqualTo("任务完成自动回收");
    }

    @Test
    void assign_validation() {
        commanding("INC-V1", "alice");
        openTask("INC-V1", "alice", "T-1");
        // 到期时刻必须晚于分配时刻（等于/早于均 400，报文含实际值与要求）
        assertThatThrownBy(() -> service.assign("INC-V1", "T-1", "alice",
                new ResourceAssignRequest(key(), "res-1", 1, T0)))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getMessage()).contains("expiresAt=" + T0);
                    assertThat(e.getMessage()).contains("assignedAt=" + T0);
                });
        assertApiStatus(() -> service.assign("INC-V1", "T-1", "alice",
                new ResourceAssignRequest(key(), "res-1", 1, T0.minusSeconds(1))),
                HttpStatus.BAD_REQUEST);
        // 授权版本必须 >= 1
        assertApiStatus(() -> service.assign("INC-V1", "T-1", "alice",
                new ResourceAssignRequest(key(), "res-1", 0, T0.plusSeconds(60))),
                HttpStatus.BAD_REQUEST);
        assertApiStatus(() -> service.assign("INC-V1", "T-1", "alice",
                new ResourceAssignRequest(key(), "res-1", null, T0.plusSeconds(60))),
                HttpStatus.BAD_REQUEST);
        // 非空校验
        assertApiStatus(() -> service.assign("INC-V1", "T-1", "alice",
                new ResourceAssignRequest(key(), " ", 1, T0.plusSeconds(60))),
                HttpStatus.BAD_REQUEST);
        assertApiStatus(() -> service.assign("INC-V1", "T-1", "alice",
                new ResourceAssignRequest(key(), "res-1", 1, null)),
                HttpStatus.BAD_REQUEST);
        // 仅当前指挥人
        assertApiStatus(() -> service.assign("INC-V1", "T-1", "bob",
                new ResourceAssignRequest(key(), "res-1", 1, T0.plusSeconds(60))),
                HttpStatus.CONFLICT);
        // 任务不存在
        assertApiStatus(() -> service.assign("INC-V1", "T-9", "alice",
                new ResourceAssignRequest(key(), "res-1", 1, T0.plusSeconds(60))),
                HttpStatus.NOT_FOUND);
        // 事件不存在
        assertApiStatus(() -> service.assign("INC-404", "T-1", "alice",
                new ResourceAssignRequest(key(), "res-1", 1, T0.plusSeconds(60))),
                HttpStatus.NOT_FOUND);
        // 全部失败均不改变状态
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM incident_task_leases", Integer.class)).isZero();
    }

    @Test
    void assign_conflictBranches() {
        commanding("INC-A1", "alice");
        commanding("INC-A2", "bob");
        openTask("INC-A1", "alice", "T-1");
        openTask("INC-A2", "bob", "T-1");
        assign("INC-A1", "T-1", "alice", "res-shared", 1, T0.plusSeconds(600));

        // 同一任务重复分配（仍持有 ACTIVE 租约）→ 409
        assertApiStatus(() -> assign("INC-A1", "T-1", "alice", "res-other", 1,
                T0.plusSeconds(600)), HttpStatus.CONFLICT);
        // 资源被其他事件有效租约占用 → 409，details 固化占用方与到期时刻
        assertThatThrownBy(() -> assign("INC-A2", "T-1", "bob", "res-shared", 1,
                T0.plusSeconds(600)))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.details()).isNotNull();
                });
        // 对方租约到期后（左闭右开）资源释放，可分配
        ((ControllableClock) clock).setInstant(T0.plusSeconds(600));
        ResourceLeaseView second = assign("INC-A2", "T-1", "bob", "res-shared", 1,
                T0.plusSeconds(1200));
        assertThat(second.status()).isEqualTo("ACTIVE");
    }

    @Test
    void assign_terminalTask_rejected() {
        commanding("INC-T9", "alice");
        openTask("INC-T9", "alice", "T-1");
        openTask("INC-T9", "alice", "T-2");
        incidentService.completeTask("INC-T9", "T-1", "alice", new TaskActionRequest(key()));
        incidentService.cancelTask("INC-T9", "T-2", "alice", new TaskActionRequest(key()));
        assertApiStatus(() -> assign("INC-T9", "T-1", "alice", "res-1", 1,
                T0.plusSeconds(60)), HttpStatus.CONFLICT);
        assertApiStatus(() -> assign("INC-T9", "T-2", "alice", "res-1", 1,
                T0.plusSeconds(60)), HttpStatus.CONFLICT);
    }

    @Test
    void executionGates_requireLeaseAndStatus() {
        commanding("INC-G1", "alice");
        openTask("INC-G1", "alice", "T-1");
        openTask("INC-G1", "alice", "T-2");
        // 未分配资源不能开始
        assertApiStatus(() -> service.startTask("INC-G1", "T-1", "alice",
                new TaskActionRequest(key())), HttpStatus.CONFLICT);
        // 非 IN_PROGRESS 不能继续
        assertApiStatus(() -> service.continueTask("INC-G1", "T-1", "alice",
                new TaskActionRequest(key())), HttpStatus.CONFLICT);
        // 仅当前指挥人可开始
        assign("INC-G1", "T-1", "alice", "res-1", 1, T0.plusSeconds(600));
        assertApiStatus(() -> service.startTask("INC-G1", "T-1", "bob",
                new TaskActionRequest(key())), HttpStatus.CONFLICT);
        service.startTask("INC-G1", "T-1", "alice", new TaskActionRequest(key()));
        // 非 OPEN 不能再次开始
        assertApiStatus(() -> service.startTask("INC-G1", "T-1", "alice",
                new TaskActionRequest(key())), HttpStatus.CONFLICT);
    }

    @Test
    void expiry_leftClosedRightOpen() {
        commanding("INC-E1", "alice");
        openTask("INC-E1", "alice", "T-1");
        assign("INC-E1", "T-1", "alice", "res-1", 1, T0.plusSeconds(300));
        // 到期前 1 秒：仍可开始
        ((ControllableClock) clock).setInstant(T0.plusSeconds(299));
        TaskView started = service.startTask("INC-E1", "T-1", "alice",
                new TaskActionRequest(key()));
        assertThat(started.status()).isEqualTo("IN_PROGRESS");
        // 恰好到达到期时刻（左闭右开）：继续执行被拦截，任务固化 AUTHORIZATION_EXPIRED
        ((ControllableClock) clock).setInstant(T0.plusSeconds(300));
        assertThatThrownBy(() -> service.continueTask("INC-E1", "T-1", "alice",
                new TaskActionRequest(key())))
                .isInstanceOfSatisfying(AuthorizationExpiredException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(e.code()).isEqualTo("AUTHORIZATION_EXPIRED");
                    Map<String, Object> details = detailMap(e);
                    assertThat(details.get("resourceKey")).isEqualTo("res-1");
                    assertThat(details.get("authVersion")).isEqualTo(1);
                    assertThat(details.get("expiresAt")).isEqualTo(T0.plusSeconds(300));
                    assertThat(details.get("evaluatedAt")).isEqualTo(T0.plusSeconds(300));
                    assertThat(details.get("overdueSeconds")).isEqualTo(0L);
                });
        TaskView expired = incidentService.getTask("INC-E1", "T-1");
        assertThat(expired.status()).isEqualTo("AUTHORIZATION_EXPIRED");
        // 已执行日志保留：EXECUTE_START 与 EXPIRE/EXECUTE_INTERCEPT 证据均在
        List<ResourceEventView> events = service.listEvents("INC-E1", "T-1");
        assertThat(events).extracting(ResourceEventView::eventType)
                .containsExactly("ASSIGN", "EXECUTE_START", "EXPIRE", "EXECUTE_INTERCEPT");
        // 到期任务禁止完成、禁止再次分配
        assertApiStatus(() -> incidentService.completeTask("INC-E1", "T-1", "alice",
                new TaskActionRequest(key())), HttpStatus.CONFLICT);
        assertApiStatus(() -> assign("INC-E1", "T-1", "alice", "res-2", 1,
                T0.plusSeconds(900)), HttpStatus.CONFLICT);
        // 租约已固化 EXPIRED
        assertThat(service.getLease("INC-E1", "T-1").status()).isEqualTo("EXPIRED");
    }

    @Test
    void expiry_onComplete_commitsEvidence_andDoesNotConsumeKey() {
        commanding("INC-E2", "alice");
        openTask("INC-E2", "alice", "T-1");
        assign("INC-E2", "T-1", "alice", "res-1", 1, T0.plusSeconds(60));
        ((ControllableClock) clock).setInstant(T0.plusSeconds(120));
        String commandKey = key();
        assertThatThrownBy(() -> incidentService.completeTask("INC-E2", "T-1", "alice",
                new TaskActionRequest(commandKey)))
                .isInstanceOf(AuthorizationExpiredException.class);
        // 证据已提交：任务与租约状态固化
        assertThat(incidentService.getTask("INC-E2", "T-1").status())
                .isEqualTo("AUTHORIZATION_EXPIRED");
        assertThat(service.getLease("INC-E2", "T-1").status()).isEqualTo("EXPIRED");
        // 失败不占键：commandKey 无记录，可被其他操作复用
        Integer keyCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM command_keys WHERE command_key = ?", Integer.class,
                commandKey);
        assertThat(keyCount).isZero();
        openTask("INC-E2", "alice", "T-2");
        TaskView created = incidentService.createTask("INC-E2", "alice",
                new TaskCreateRequest(commandKey, "T-3", "G", "复用失败键", List.of()));
        assertThat(created.status()).isEqualTo("OPEN");
    }

    @Test
    void checkAuthorization_lazyDetection_andIdempotentReplay() {
        commanding("INC-E3", "alice");
        openTask("INC-E3", "alice", "T-1");
        assign("INC-E3", "T-1", "alice", "res-1", 1, T0.plusSeconds(60));
        // 未到期检查：不改变状态
        ResourceLeaseView before = service.checkAuthorization("INC-E3", "T-1",
                new TaskActionRequest(key()));
        assertThat(before.status()).isEqualTo("ACTIVE");
        assertThat(before.expired()).isFalse();
        assertThat(incidentService.getTask("INC-E3", "T-1").status()).isEqualTo("OPEN");
        // 到期后检查：固化状态与证据
        ((ControllableClock) clock).setInstant(T0.plusSeconds(61));
        String checkKey = key();
        ResourceLeaseView after = service.checkAuthorization("INC-E3", "T-1",
                new TaskActionRequest(checkKey));
        assertThat(after.status()).isEqualTo("EXPIRED");
        assertThat(incidentService.getTask("INC-E3", "T-1").status())
                .isEqualTo("AUTHORIZATION_EXPIRED");
        // 同键重放首次结果；换键再评估返回当前状态且不重复产生证据
        ResourceLeaseView replay = service.checkAuthorization("INC-E3", "T-1",
                new TaskActionRequest(checkKey));
        assertThat(replay).isEqualTo(after);
        ResourceLeaseView again = service.checkAuthorization("INC-E3", "T-1",
                new TaskActionRequest(key()));
        assertThat(again.status()).isEqualTo("EXPIRED");
        assertThat(service.listEvents("INC-E3", "T-1"))
                .extracting(ResourceEventView::eventType)
                .containsExactly("ASSIGN", "EXPIRE");
        // 无租约任务检查 → 404
        openTask("INC-E3", "alice", "T-2");
        assertApiStatus(() -> service.checkAuthorization("INC-E3", "T-2",
                new TaskActionRequest(key())), HttpStatus.NOT_FOUND);
    }

    @Test
    void reads_doNotChangeState() {
        commanding("INC-E4", "alice");
        openTask("INC-E4", "alice", "T-1");
        assign("INC-E4", "T-1", "alice", "res-1", 1, T0.plusSeconds(60));
        ((ControllableClock) clock).setInstant(T0.plusSeconds(3600));
        // 只读查询按查询时刻计算派生标志，但不固化状态
        ResourceLeaseView view = service.getLease("INC-E4", "T-1");
        assertThat(view.status()).isEqualTo("ACTIVE");
        assertThat(view.expired()).isTrue();
        ResourceDiagnosticsView diagnostics = service.diagnostics("INC-E4");
        assertThat(diagnostics.leases()).hasSize(1);
        assertThat(diagnostics.leases().get(0).expired()).isTrue();
        assertThat(diagnostics.leases().get(0).taskStatus()).isEqualTo("OPEN");
        // 数据库状态未被读取改变
        assertThat(jdbc.queryForObject(
                "SELECT status FROM incident_tasks WHERE task_key = 'T-1'", String.class))
                .isEqualTo("OPEN");
        assertThat(jdbc.queryForObject(
                "SELECT status FROM incident_task_leases", String.class)).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM incident_resource_events WHERE event_type = 'EXPIRE'",
                Integer.class)).isZero();
    }

    @Test
    void renew_mainFlow_andEvidence() {
        commanding("INC-N1", "alice");
        openTask("INC-N1", "alice", "T-1");
        assign("INC-N1", "T-1", "alice", "res-1", 1, T0.plusSeconds(300));
        ((ControllableClock) clock).setInstant(T0.plusSeconds(100));
        // 续期人不同于原分配人，提交新版本与新到期时刻
        ResourceRenewalView renewed = service.renew("INC-N1", "bob",
                new ResourceRenewRequest(key(), List.of(
                        new RenewItem("T-1", 1, 2, T0.plusSeconds(900)))));
        assertThat(renewed.renewedBy()).isEqualTo("bob");
        assertThat(renewed.renewedAt()).isEqualTo(T0.plusSeconds(100));
        assertThat(renewed.items()).hasSize(1);
        assertThat(renewed.items().get(0).oldVersion()).isEqualTo(1);
        assertThat(renewed.items().get(0).newVersion()).isEqualTo(2);
        assertThat(renewed.items().get(0).oldExpiresAt()).isEqualTo(T0.plusSeconds(300));
        assertThat(renewed.items().get(0).newExpiresAt()).isEqualTo(T0.plusSeconds(900));
        assertThat(renewed.items().get(0).extensionSeconds()).isEqualTo(600L);
        // 租约已更新，证据固化旧新版本
        ResourceLeaseView lease = service.getLease("INC-N1", "T-1");
        assertThat(lease.authVersion()).isEqualTo(2);
        assertThat(lease.expiresAt()).isEqualTo(T0.plusSeconds(900));
        assertThat(lease.assignedBy()).isEqualTo("alice");
        List<ResourceEventView> events = service.listEvents("INC-N1", "T-1");
        assertThat(events).extracting(ResourceEventView::eventType)
                .containsExactly("ASSIGN", "RENEW");
        assertThat(events.get(1).oldVersion()).isEqualTo(1);
        assertThat(events.get(1).newVersion()).isEqualTo(2);
        assertThat(events.get(1).actor()).isEqualTo("bob");
        // 新授权下可继续执行
        TaskView started = service.startTask("INC-N1", "T-1", "alice",
                new TaskActionRequest(key()));
        assertThat(started.status()).isEqualTo("IN_PROGRESS");
    }

    @Test
    void renew_validationBranches() {
        commanding("INC-N2", "alice");
        openTask("INC-N2", "alice", "T-1");
        assign("INC-N2", "T-1", "alice", "res-1", 3, T0.plusSeconds(300));
        // 续期人不得与原分配人相同
        assertApiStatus(() -> service.renew("INC-N2", "alice",
                new ResourceRenewRequest(key(), List.of(
                        new RenewItem("T-1", 3, 4, T0.plusSeconds(900))))),
                HttpStatus.UNPROCESSABLE_ENTITY);
        // 期望版本与当前版本不一致：422 且返回实际值与要求值
        assertThatThrownBy(() -> service.renew("INC-N2", "bob",
                new ResourceRenewRequest(key(), List.of(
                        new RenewItem("T-1", 1, 4, T0.plusSeconds(900))))))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    Map<String, Object> details = detailMap(e);
                    assertThat(details.get("expectedVersion")).isEqualTo(1);
                    assertThat(details.get("actualVersion")).isEqualTo(3);
                });
        // 新版本必须大于当前版本
        assertThatThrownBy(() -> service.renew("INC-N2", "bob",
                new ResourceRenewRequest(key(), List.of(
                        new RenewItem("T-1", 3, 3, T0.plusSeconds(900))))))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    Map<String, Object> details = detailMap(e);
                    assertThat(details.get("currentVersion")).isEqualTo(3);
                    assertThat(details.get("requestedVersion")).isEqualTo(3);
                });
        // 新到期时刻必须晚于续期时刻
        assertApiStatus(() -> service.renew("INC-N2", "bob",
                new ResourceRenewRequest(key(), List.of(
                        new RenewItem("T-1", 3, 4, T0.minusSeconds(1))))),
                HttpStatus.UNPROCESSABLE_ENTITY);
        // 任务不存在 → 404；请求非法 → 400
        assertApiStatus(() -> service.renew("INC-N2", "bob",
                new ResourceRenewRequest(key(), List.of(
                        new RenewItem("T-9", 3, 4, T0.plusSeconds(900))))),
                HttpStatus.NOT_FOUND);
        assertApiStatus(() -> service.renew("INC-N2", "bob",
                new ResourceRenewRequest(key(), List.of())),
                HttpStatus.BAD_REQUEST);
        assertApiStatus(() -> service.renew("INC-N2", "bob",
                new ResourceRenewRequest(key(), List.of(
                        new RenewItem("T-1", 3, 4, T0.plusSeconds(900)),
                        new RenewItem("T-1", 3, 5, T0.plusSeconds(900))))),
                HttpStatus.BAD_REQUEST);
        // 全部失败均不改变租约
        ResourceLeaseView lease = service.getLease("INC-N2", "T-1");
        assertThat(lease.authVersion()).isEqualTo(3);
        assertThat(lease.expiresAt()).isEqualTo(T0.plusSeconds(300));
        assertThat(service.listEvents("INC-N2", "T-1"))
                .extracting(ResourceEventView::eventType).containsExactly("ASSIGN");
    }

    @Test
    void renew_terminalOrTransferredTask_rejected() {
        commanding("INC-N3", "alice");
        openTask("INC-N3", "alice", "T-1");
        openTask("INC-N3", "alice", "T-2");
        openTask("INC-N3", "alice", "T-3");
        assign("INC-N3", "T-1", "alice", "res-1", 1, T0.plusSeconds(300));
        assign("INC-N3", "T-2", "alice", "res-2", 1, T0.plusSeconds(300));
        assign("INC-N3", "T-3", "alice", "res-3", 1, T0.plusSeconds(300));
        // 已完成任务不可续期
        incidentService.completeTask("INC-N3", "T-1", "alice", new TaskActionRequest(key()));
        assertApiStatus(() -> service.renew("INC-N3", "bob",
                new ResourceRenewRequest(key(), List.of(
                        new RenewItem("T-1", 1, 2, T0.plusSeconds(900))))),
                HttpStatus.UNPROCESSABLE_ENTITY);
        // 已取消任务不可续期
        incidentService.cancelTask("INC-N3", "T-2", "alice", new TaskActionRequest(key()));
        assertApiStatus(() -> service.renew("INC-N3", "bob",
                new ResourceRenewRequest(key(), List.of(
                        new RenewItem("T-2", 1, 2, T0.plusSeconds(900))))),
                HttpStatus.UNPROCESSABLE_ENTITY);
        // 交接中的事件任务不可续期
        incidentService.initiateTransfer("INC-N3", "alice", new TransferRequest(key(), "carol"));
        assertApiStatus(() -> service.renew("INC-N3", "bob",
                new ResourceRenewRequest(key(), List.of(
                        new RenewItem("T-3", 1, 2, T0.plusSeconds(900))))),
                HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(service.getLease("INC-N3", "T-3").authVersion()).isEqualTo(1);
    }

    @Test
    void renew_expiredAuthorization_failsAndFreezes() {
        commanding("INC-N4", "alice");
        openTask("INC-N4", "alice", "T-1");
        assign("INC-N4", "T-1", "alice", "res-1", 1, T0.plusSeconds(60));
        ((ControllableClock) clock).setInstant(T0.plusSeconds(61));
        // 授权已到期：续期被门禁拦截，任务固化 AUTHORIZATION_EXPIRED，证据提交
        assertThatThrownBy(() -> service.renew("INC-N4", "bob",
                new ResourceRenewRequest(key(), List.of(
                        new RenewItem("T-1", 1, 2, T0.plusSeconds(900))))))
                .isInstanceOf(AuthorizationExpiredException.class);
        assertThat(incidentService.getTask("INC-N4", "T-1").status())
                .isEqualTo("AUTHORIZATION_EXPIRED");
        assertThat(service.getLease("INC-N4", "T-1").authVersion()).isEqualTo(1);
    }

    @Test
    void renew_multiTaskClosure_atomic() {
        commanding("INC-N5", "alice");
        commanding("INC-N6", "bob");
        openTask("INC-N5", "alice", "T-1");
        openTask("INC-N5", "alice", "T-2");
        openTask("INC-N6", "bob", "T-9");
        assign("INC-N5", "T-1", "alice", "res-a", 1, T0.plusSeconds(300));
        assign("INC-N5", "T-2", "alice", "res-b", 1, T0.plusSeconds(300));
        // 构造闭包资源 res-a 被其他事件占用的状态（绕过分配门禁直接落库，
        // 等价于对方在我方授权到期间隙抢注后又回拨时钟的并发残留）
        Long otherIncidentId = jdbc.queryForObject(
                "SELECT id FROM incidents WHERE incident_key = 'INC-N6'", Long.class);
        Long otherTaskId = jdbc.queryForObject(
                "SELECT id FROM incident_tasks WHERE task_key = 'T-9'", Long.class);
        jdbc.update("INSERT INTO incident_task_leases (task_id, incident_id, resource_key,"
                        + " auth_version, assigned_by, assigned_at, expires_at, status,"
                        + " created_at, updated_at) VALUES (?,?,?,?,?,?,?,'ACTIVE',?,?)",
                otherTaskId, otherIncidentId, "res-a", 7, "bob",
                java.sql.Timestamp.from(T0), java.sql.Timestamp.from(T0.plusSeconds(9000)),
                java.sql.Timestamp.from(T0), java.sql.Timestamp.from(T0));
        // 任一冲突 422：完整闭包校验失败，全部任务与租约不变
        assertThatThrownBy(() -> service.renew("INC-N5", "carol",
                new ResourceRenewRequest(key(), List.of(
                        new RenewItem("T-1", 1, 2, T0.plusSeconds(900)),
                        new RenewItem("T-2", 1, 2, T0.plusSeconds(900))))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
        assertThat(service.getLease("INC-N5", "T-1").authVersion()).isEqualTo(1);
        assertThat(service.getLease("INC-N5", "T-2").authVersion()).isEqualTo(1);
        assertThat(service.listEvents("INC-N5", "T-1"))
                .extracting(ResourceEventView::eventType).containsExactly("ASSIGN");
        assertThat(service.listEvents("INC-N5", "T-2"))
                .extracting(ResourceEventView::eventType).containsExactly("ASSIGN");
        // 占用解除后，同一请求成功且两个任务同事务续期
        jdbc.update("DELETE FROM incident_task_leases WHERE task_id = ?", otherTaskId);
        ResourceRenewalView renewed = service.renew("INC-N5", "carol",
                new ResourceRenewRequest(key(), List.of(
                        new RenewItem("T-1", 1, 2, T0.plusSeconds(900)),
                        new RenewItem("T-2", 1, 2, T0.plusSeconds(900)))));
        assertThat(renewed.items()).hasSize(2);
        assertThat(service.getLease("INC-N5", "T-1").authVersion()).isEqualTo(2);
        assertThat(service.getLease("INC-N5", "T-2").authVersion()).isEqualTo(2);
    }

    @Test
    void renew_commandKeyIdempotency() {
        commanding("INC-N7", "alice");
        openTask("INC-N7", "alice", "T-1");
        assign("INC-N7", "T-1", "alice", "res-1", 1, T0.plusSeconds(300));
        String commandKey = key();
        ResourceRenewalView first = service.renew("INC-N7", "bob",
                new ResourceRenewRequest(commandKey, List.of(
                        new RenewItem("T-1", 1, 2, T0.plusSeconds(900)))));
        // 同键同参重放首次结果，不产生重复证据
        ResourceRenewalView replay = service.renew("INC-N7", "bob",
                new ResourceRenewRequest(commandKey, List.of(
                        new RenewItem("T-1", 1, 2, T0.plusSeconds(900)))));
        assertThat(replay).isEqualTo(first);
        assertThat(service.listEvents("INC-N7", "T-1"))
                .extracting(ResourceEventView::eventType).containsExactly("ASSIGN", "RENEW");
        // 同键改参 → 409
        assertApiStatus(() -> service.renew("INC-N7", "bob",
                new ResourceRenewRequest(commandKey, List.of(
                        new RenewItem("T-1", 2, 3, T0.plusSeconds(1200))))),
                HttpStatus.CONFLICT);
    }

    @Test
    void release_mainFlow_andBranches() {
        commanding("INC-L1", "alice");
        commanding("INC-L2", "bob");
        openTask("INC-L1", "alice", "T-1");
        openTask("INC-L2", "bob", "T-1");
        assign("INC-L1", "T-1", "alice", "res-1", 1, T0.plusSeconds(300));
        // 非当前指挥人 → 409
        assertApiStatus(() -> service.release("INC-L1", "T-1", "carol",
                new TaskActionRequest(key())), HttpStatus.CONFLICT);
        ResourceLeaseView released = service.release("INC-L1", "T-1", "alice",
                new TaskActionRequest(key()));
        assertThat(released.status()).isEqualTo("RELEASED");
        assertThat(released.releasedBy()).isEqualTo("alice");
        // 重复释放 → 409
        assertApiStatus(() -> service.release("INC-L1", "T-1", "alice",
                new TaskActionRequest(key())), HttpStatus.CONFLICT);
        // 回收后资源可被其他事件分配
        ResourceLeaseView other = assign("INC-L2", "T-1", "bob", "res-1", 1,
                T0.plusSeconds(600));
        assertThat(other.status()).isEqualTo("ACTIVE");
        // 取消任务自动回收
        openTask("INC-L1", "alice", "T-2");
        assign("INC-L1", "T-2", "alice", "res-2", 1, T0.plusSeconds(300));
        incidentService.cancelTask("INC-L1", "T-2", "alice", new TaskActionRequest(key()));
        ResourceLeaseView autoReleased = service.getLease("INC-L1", "T-2");
        assertThat(autoReleased.status()).isEqualTo("RELEASED");
        assertThat(service.listEvents("INC-L1", "T-2"))
                .extracting(ResourceEventView::eventType).containsExactly("ASSIGN", "RELEASE");
    }

    @Test
    void expiredTask_blocksResolve() {
        commanding("INC-Z1", "alice");
        openTask("INC-Z1", "alice", "T-1");
        assign("INC-Z1", "T-1", "alice", "res-1", 1, T0.plusSeconds(60));
        ((ControllableClock) clock).setInstant(T0.plusSeconds(61));
        assertThatThrownBy(() -> incidentService.completeTask("INC-Z1", "T-1", "alice",
                new TaskActionRequest(key()))).isInstanceOf(AuthorizationExpiredException.class);
        incidentService.changeStatus("INC-Z1", "alice", new StatusRequest(key(), "CONTAINED"));
        // AUTHORIZATION_EXPIRED 视为未完成：解决门禁 409
        assertApiStatus(() -> incidentService.changeStatus("INC-Z1", "alice",
                new StatusRequest(key(), "RESOLVED")), HttpStatus.CONFLICT);
    }

    @Test
    void assign_commandKeyIdempotency() {
        commanding("INC-K1", "alice");
        openTask("INC-K1", "alice", "T-1");
        String commandKey = key();
        ResourceLeaseView first = service.assign("INC-K1", "T-1", "alice",
                new ResourceAssignRequest(commandKey, "res-1", 1, T0.plusSeconds(300)));
        ResourceLeaseView replay = service.assign("INC-K1", "T-1", "alice",
                new ResourceAssignRequest(commandKey, "res-1", 1, T0.plusSeconds(300)));
        assertThat(replay).isEqualTo(first);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM incident_task_leases", Integer.class)).isEqualTo(1);
        // 同键改参 → 409
        assertApiStatus(() -> service.assign("INC-K1", "T-1", "alice",
                new ResourceAssignRequest(commandKey, "res-2", 1, T0.plusSeconds(300))),
                HttpStatus.CONFLICT);
    }
}
