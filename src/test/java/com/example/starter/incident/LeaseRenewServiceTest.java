package com.example.starter.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.example.starter.incident.dto.Requests.LeaseRenewRequest;
import com.example.starter.incident.dto.Requests.ReportRequest;
import com.example.starter.incident.dto.Requests.ResourceAssignRequest;
import com.example.starter.incident.dto.Requests.TakeoverRequest;
import com.example.starter.incident.dto.Requests.TaskActionRequest;
import com.example.starter.incident.dto.Requests.TaskCreateRequest;
import com.example.starter.incident.dto.Requests.TransferAcceptRequest;
import com.example.starter.incident.dto.Requests.TransferRequest;
import com.example.starter.incident.dto.Responses.IncidentTasksView;
import com.example.starter.incident.dto.Responses.TaskResourceHistoryView;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 批量续期服务测试：覆盖依赖闭包校验（恰好覆盖）、授权角色与版本规则、
 * 到期/已交接任务不可续期、任一冲突 422 且整体不变、commandKey 幂等语义。
 * 使用可控 Clock，时间相关断言不依赖真实睡眠。
 */
@SpringBootTest
@Import(ControllableClock.Config.class)
class LeaseRenewServiceTest {

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

    /**
     * 准备两个任务并分别分配资源 RES-1/RES-2（授权版本 v1，600 秒到期）。
     */
    private void twoLeasedTasks(String incidentKey, String commander) {
        commanding(incidentKey, commander);
        service.createTask(incidentKey, commander,
                new TaskCreateRequest(key(), "T-1", "DB", "任务1", List.of()));
        service.createTask(incidentKey, commander,
                new TaskCreateRequest(key(), "T-2", "APP", "任务2", List.of()));
        service.assignResource(incidentKey, "T-1", commander,
                new ResourceAssignRequest(key(), "RES-1", "v1", T0.plusSeconds(600)));
        service.assignResource(incidentKey, "T-2", commander,
                new ResourceAssignRequest(key(), "RES-2", "v1", T0.plusSeconds(600)));
    }

    private IncidentTasksView renew(String incidentKey, String actor, List<String> taskKeys,
                                    String newVersion, Instant expiresAt) {
        return service.renewLeases(incidentKey, actor,
                new LeaseRenewRequest(key(), taskKeys, newVersion, "OPS_LEAD", expiresAt));
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
    void renew_mainFlow() {
        twoLeasedTasks("INC-N1", "alice");
        ((ControllableClock) clock).setInstant(T0.plusSeconds(100));
        IncidentTasksView renewed = renew("INC-N1", "authorizer-1", List.of("T-1", "T-2"),
                "v2", T0.plusSeconds(3600));
        assertThat(renewed.tasks()).hasSize(2);
        assertThat(renewed.tasks()).allSatisfy(t -> {
            assertThat(t.lease().leaseVersion()).isEqualTo(2);
            assertThat(t.lease().authVersion()).isEqualTo("v2");
            assertThat(t.lease().assignedBy()).isEqualTo("alice");
            assertThat(t.lease().authorizedBy()).isEqualTo("authorizer-1");
            assertThat(t.lease().authorizerRole()).isEqualTo("OPS_LEAD");
            assertThat(t.lease().assignedAt()).isEqualTo(T0.plusSeconds(100));
            assertThat(t.lease().expiresAt()).isEqualTo(T0.plusSeconds(3600));
            assertThat(t.lease().status()).isEqualTo("ACTIVE");
        });
        // 旧版本保留为 SUPERSEDED，证据固化旧新授权版本
        TaskResourceHistoryView history = service.taskResourceHistory("INC-N1", "T-1");
        assertThat(history.leases()).hasSize(2);
        assertThat(history.leases().get(0).status()).isEqualTo("SUPERSEDED");
        assertThat(history.leases().get(1).status()).isEqualTo("ACTIVE");
        assertThat(history.events()).extracting("eventType").containsExactly("ASSIGN", "RENEW");
        assertThat(history.events().get(1).oldAuthVersion()).isEqualTo("v1");
        assertThat(history.events().get(1).newAuthVersion()).isEqualTo("v2");
        assertThat(history.events().get(1).actor()).isEqualTo("authorizer-1");
        // 资源活动指针指向新版本，不发生重叠授权
        Integer activeCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM resource_leases WHERE status = 'ACTIVE'", Integer.class);
        assertThat(activeCount).isEqualTo(2);
        Integer pointerCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM leased_resources r JOIN resource_leases l"
                        + " ON l.id = r.active_lease_id AND l.status = 'ACTIVE'", Integer.class);
        assertThat(pointerCount).isEqualTo(2);
    }

    @Test
    void renew_closureMustBeExactlyCovered() {
        twoLeasedTasks("INC-N2", "alice");
        // 闭包含 2 个任务，只续 1 个：422 并返回要求/实际/缺失
        assertThatThrownBy(() -> renew("INC-N2", "authorizer-1", List.of("T-1"), "v2",
                T0.plusSeconds(3600)))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(details(e)).containsEntry("required", List.of("T-1", "T-2"))
                            .containsEntry("actual", List.of("T-1"))
                            .containsEntry("missing", List.of("T-2"));
                });
        // 包含闭包外的任务：422
        assertApiStatus(() -> renew("INC-N2", "authorizer-1", List.of("T-1", "T-2", "T-9"),
                "v2", T0.plusSeconds(3600)), HttpStatus.UNPROCESSABLE_ENTITY);
        // 整体不变：租约版本与证据均未改变
        assertThat(service.taskResourceHistory("INC-N2", "T-1").leases()).hasSize(1);
        assertThat(service.taskResourceHistory("INC-N2", "T-2").leases()).hasSize(1);
        Integer renewEvents = jdbc.queryForObject(
                "SELECT COUNT(*) FROM resource_lease_events WHERE event_type = 'RENEW'",
                Integer.class);
        assertThat(renewEvents).isZero();
    }

    @Test
    void renew_requiresDifferentAuthorizerAndNewVersion() {
        twoLeasedTasks("INC-N3", "alice");
        // 续期人不得与原分配人相同
        assertThatThrownBy(() -> renew("INC-N3", "alice", List.of("T-1", "T-2"), "v2",
                T0.plusSeconds(3600)))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(details(e)).containsEntry("assignedBy", "alice")
                            .containsEntry("actor", "alice");
                });
        // 必须提交不同于当前版本的新版本
        assertThatThrownBy(() -> renew("INC-N3", "authorizer-1", List.of("T-1", "T-2"), "v1",
                T0.plusSeconds(3600)))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(details(e)).containsEntry("currentVersion", "v1")
                            .containsEntry("requestedVersion", "v1");
                });
        // 授权角色不能为空
        assertApiStatus(() -> service.renewLeases("INC-N3", "authorizer-1",
                new LeaseRenewRequest(key(), List.of("T-1", "T-2"), "v2", " ",
                        T0.plusSeconds(3600))), HttpStatus.BAD_REQUEST);
        // 新到期时刻必须晚于续期时刻
        assertApiStatus(() -> renew("INC-N3", "authorizer-1", List.of("T-1", "T-2"), "v2", T0),
                HttpStatus.BAD_REQUEST);
        // 全部失败不改变任何租约
        assertThat(service.taskResourceHistory("INC-N3", "T-1").leases()).hasSize(1);
    }

    @Test
    void renew_expiredLease_rejectedWithoutStateChange() {
        twoLeasedTasks("INC-N4", "alice");
        // 时钟推进过到期时刻：续期 422 并返回逾期时长；不物化、不改变任务状态或租约
        ((ControllableClock) clock).setInstant(T0.plusSeconds(601));
        assertThatThrownBy(() -> renew("INC-N4", "authorizer-1", List.of("T-1", "T-2"), "v2",
                T0.plusSeconds(3600)))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(details(e)).containsEntry("overdueSeconds", 1L);
                });
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM incident_tasks WHERE status = 'OPEN'", Integer.class))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM resource_leases WHERE status = 'ACTIVE'", Integer.class))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM resource_lease_events WHERE event_type = 'EXPIRE'",
                Integer.class)).isZero();
    }

    @Test
    void renew_handedOverIncident_rejected() {
        twoLeasedTasks("INC-N5", "alice");
        // 事件交接被接受后，其任务视为已交接出去，不可续期
        service.initiateTransfer("INC-N5", "alice", new TransferRequest(key(), "bob"));
        service.acceptTransfer("INC-N5", "bob", new TransferAcceptRequest(key()));
        assertApiStatus(() -> renew("INC-N5", "authorizer-1", List.of("T-1", "T-2"), "v2",
                T0.plusSeconds(3600)), HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(service.taskResourceHistory("INC-N5", "T-1").leases()).hasSize(1);
    }

    @Test
    void renew_terminalTaskLeavesClosure() {
        twoLeasedTasks("INC-N6", "alice");
        // T-2 完成后租约回收，闭包收缩为 T-1；续期请求须匹配新闭包
        service.completeTask("INC-N6", "T-2", "alice", new TaskActionRequest(key()));
        assertApiStatus(() -> renew("INC-N6", "authorizer-1", List.of("T-1", "T-2"), "v2",
                T0.plusSeconds(3600)), HttpStatus.UNPROCESSABLE_ENTITY);
        IncidentTasksView renewed = renew("INC-N6", "authorizer-1", List.of("T-1"), "v2",
                T0.plusSeconds(3600));
        assertThat(renewed.tasks()).hasSize(1);
        assertThat(renewed.tasks().get(0).lease().leaseVersion()).isEqualTo(2);
    }

    @Test
    void renew_commandKeyIdempotency() {
        twoLeasedTasks("INC-N7", "alice");
        String commandKey = key();
        IncidentTasksView first = service.renewLeases("INC-N7", "authorizer-1",
                new LeaseRenewRequest(commandKey, List.of("T-1", "T-2"), "v2", "OPS_LEAD",
                        T0.plusSeconds(3600)));
        // 同键同参重放首次结果，不产生新版本
        IncidentTasksView replay = service.renewLeases("INC-N7", "authorizer-1",
                new LeaseRenewRequest(commandKey, List.of("T-1", "T-2"), "v2", "OPS_LEAD",
                        T0.plusSeconds(3600)));
        assertThat(replay).isEqualTo(first);
        assertThat(service.taskResourceHistory("INC-N7", "T-1").leases()).hasSize(2);
        // 同键改参 409
        assertApiStatus(() -> service.renewLeases("INC-N7", "authorizer-1",
                new LeaseRenewRequest(commandKey, List.of("T-1", "T-2"), "v3", "OPS_LEAD",
                        T0.plusSeconds(3600))), HttpStatus.CONFLICT);
        // 失败不占键：422 回滚后同键可按修正参数重新使用
        String failedKey = key();
        assertApiStatus(() -> service.renewLeases("INC-N7", "alice",
                new LeaseRenewRequest(failedKey, List.of("T-1", "T-2"), "v3", "OPS_LEAD",
                        T0.plusSeconds(7200))), HttpStatus.UNPROCESSABLE_ENTITY);
        IncidentTasksView ok = service.renewLeases("INC-N7", "authorizer-2",
                new LeaseRenewRequest(failedKey, List.of("T-1", "T-2"), "v3", "OPS_LEAD",
                        T0.plusSeconds(7200)));
        assertThat(ok.tasks()).allSatisfy(t -> assertThat(t.lease().leaseVersion()).isEqualTo(3));
    }
}
