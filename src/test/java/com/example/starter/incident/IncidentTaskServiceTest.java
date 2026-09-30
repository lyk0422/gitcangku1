package com.example.starter.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import com.example.starter.incident.dto.Requests.CredentialRegisterRequest;
import com.example.starter.incident.dto.Requests.ReportRequest;
import com.example.starter.incident.dto.Requests.StatusRequest;
import com.example.starter.incident.dto.Requests.TakeoverRequest;
import com.example.starter.incident.dto.Requests.TaskActionRequest;
import com.example.starter.incident.dto.Requests.TaskCreateRequest;
import com.example.starter.incident.dto.Requests.TaskDispatchRequest;
import com.example.starter.incident.dto.Requests.TransferAcceptRequest;
import com.example.starter.incident.dto.Requests.TransferRequest;
import com.example.starter.incident.dto.Responses.IncidentTasksView;
import com.example.starter.incident.dto.Responses.IncidentView;
import com.example.starter.incident.dto.Responses.TaskView;
import com.example.starter.incident.dto.Responses.UnfinishedTaskView;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 处置任务服务测试：覆盖创建校验、taskKey 幂等、直接与间接环检测、
 * 阻塞解除门禁、终态规则、指挥权变更、解决门禁与 commandKey 幂等语义。
 * 完成任务前必须先派发（凭据校验）并通过执行前检查进入 EXECUTING。
 */
@SpringBootTest
class IncidentTaskServiceTest {

    private static final String RESOURCE = "RES-R";
    private static final String REGION = "A";
    private static final String CRED_TYPE = "TOKEN";

    @Autowired
    private IncidentService service;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM command_keys");
        jdbc.update("DELETE FROM incident_status_history");
        jdbc.update("DELETE FROM incident_transfers");
        jdbc.update("DELETE FROM incident_actions");
        jdbc.update("DELETE FROM task_dispatches");
        jdbc.update("DELETE FROM incident_task_blockers");
        jdbc.update("DELETE FROM incident_tasks");
        jdbc.update("DELETE FROM resource_credentials");
        jdbc.update("DELETE FROM incidents");
    }

    private static String key() {
        return "CMD-" + UUID.randomUUID();
    }

    private static String reqId() {
        return "REQ-" + UUID.randomUUID();
    }

    private IncidentView commanding(String incidentKey, String commander) {
        service.report(new ReportRequest(incidentKey, "S2", "核心链路故障", "reporter-1"));
        return service.takeover(incidentKey, commander, new TakeoverRequest(key()));
    }

    /** 登记一个 1 小时后失效、覆盖区域 A 的有效凭据。 */
    private void registerValidCredential(String actor) {
        service.registerCredential(actor, new CredentialRegisterRequest(key(), RESOURCE,
                CRED_TYPE, REGION, Instant.now().plus(1, ChronoUnit.HOURS)));
    }

    private TaskView createTask(String incidentKey, String actor, String taskKey,
                                List<String> blockers) {
        return service.createTask(incidentKey, actor, new TaskCreateRequest(key(), taskKey,
                "G", "任务" + taskKey, RESOURCE, REGION, blockers));
    }

    /** 派发并执行前检查，使任务进入 EXECUTING。 */
    private TaskView dispatchAndStart(String incidentKey, String taskKey, String actor) {
        service.dispatchTask(incidentKey, taskKey, actor,
                new TaskDispatchRequest(reqId(), RESOURCE, CRED_TYPE));
        return service.startTask(incidentKey, taskKey, actor, new TaskActionRequest(key()));
    }

    private static void assertApiStatus(ThrowingCallable call, HttpStatus status) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.status()).isEqualTo(status));
    }

    @SuppressWarnings("unchecked")
    private static List<Object> details(ApiException e) {
        return (List<Object>) e.details();
    }

    @Test
    void createAndQuery_mainFlow() {
        commanding("INC-T1", "alice");
        commanding("INC-T2", "bob");
        TaskView created = createTask("INC-T1", "alice", "T-1", List.of("INC-T2"));
        assertThat(created.status()).isEqualTo("OPEN");
        assertThat(created.resourceKey()).isEqualTo(RESOURCE);
        assertThat(created.region()).isEqualTo(REGION);
        assertThat(created.credentialVersion()).isNull();
        assertThat(created.blockReason()).isNull();
        assertThat(created.dispatches()).isEmpty();
        assertThat(created.groupCode()).isEqualTo("G");
        assertThat(created.createdBy()).isEqualTo("alice");
        assertThat(created.doneBy()).isNull();
        assertThat(created.cancelledBy()).isNull();
        assertThat(created.blockers()).hasSize(1);
        assertThat(created.blockers().get(0).incidentKey()).isEqualTo("INC-T2");
        assertThat(created.blockers().get(0).incidentStatus()).isEqualTo("COMMANDING");
        assertThat(created.blockers().get(0).resolved()).isFalse();

        // 按事件分组查询与单任务明细
        IncidentTasksView list = service.listTasks("INC-T1");
        assertThat(list.incidentKey()).isEqualTo("INC-T1");
        assertThat(list.tasks()).containsExactly(created);
        assertThat(service.getTask("INC-T1", "T-1")).isEqualTo(created);

        // 阻塞解除不写回依赖任务：目标事件遏制后查询按当前状态计算
        service.changeStatus("INC-T2", "bob", new StatusRequest(key(), "CONTAINED"));
        TaskView after = service.getTask("INC-T1", "T-1");
        assertThat(after.blockers().get(0).incidentStatus()).isEqualTo("CONTAINED");
        assertThat(after.blockers().get(0).resolved()).isTrue();
    }

    @Test
    void createTask_validation() {
        commanding("INC-V1", "alice");
        // 非空校验：taskKey/groupCode/title/resourceKey/region/commandKey
        assertApiStatus(() -> service.createTask("INC-V1", "alice",
                new TaskCreateRequest(key(), " ", "G", "t", RESOURCE, REGION, List.of())),
                HttpStatus.BAD_REQUEST);
        assertApiStatus(() -> service.createTask("INC-V1", "alice",
                new TaskCreateRequest(key(), "T", " ", "t", RESOURCE, REGION, List.of())),
                HttpStatus.BAD_REQUEST);
        assertApiStatus(() -> service.createTask("INC-V1", "alice",
                new TaskCreateRequest(key(), "T", "G", " ", RESOURCE, REGION, List.of())),
                HttpStatus.BAD_REQUEST);
        assertApiStatus(() -> service.createTask("INC-V1", "alice",
                new TaskCreateRequest(key(), "T", "G", "t", " ", REGION, List.of())),
                HttpStatus.BAD_REQUEST);
        assertApiStatus(() -> service.createTask("INC-V1", "alice",
                new TaskCreateRequest(key(), "T", "G", "t", RESOURCE, " ", List.of())),
                HttpStatus.BAD_REQUEST);
        assertApiStatus(() -> service.createTask("INC-V1", "alice",
                new TaskCreateRequest(null, "T", "G", "t", RESOURCE, REGION, List.of())),
                HttpStatus.BAD_REQUEST);
        // 阻塞事件不能是自身
        assertApiStatus(() -> service.createTask("INC-V1", "alice",
                new TaskCreateRequest(key(), "T", "G", "t", RESOURCE, REGION, List.of("INC-V1"))),
                HttpStatus.BAD_REQUEST);
        // 阻塞事件超过 5 个
        assertApiStatus(() -> service.createTask("INC-V1", "alice",
                new TaskCreateRequest(key(), "T", "G", "t", RESOURCE, REGION,
                        List.of("B1", "B2", "B3", "B4", "B5", "B6"))), HttpStatus.BAD_REQUEST);
        // 阻塞事件不存在
        assertApiStatus(() -> createTask("INC-V1", "alice", "T-1", List.of("INC-404")),
                HttpStatus.NOT_FOUND);
        // 事件不存在
        assertApiStatus(() -> createTask("INC-404", "alice", "T-1", List.of()),
                HttpStatus.NOT_FOUND);
        // 未接管事件无指挥人
        service.report(new ReportRequest("INC-V2", "S2", "s", "r"));
        assertApiStatus(() -> createTask("INC-V2", "alice", "T-1", List.of()),
                HttpStatus.CONFLICT);
    }

    @Test
    void createTask_failureDoesNotConsumeCommandKey() {
        commanding("INC-F1", "alice");
        String commandKey = key();
        // 业务失败（阻塞事件不存在）事务回滚，不占键
        assertApiStatus(() -> service.createTask("INC-F1", "alice",
                new TaskCreateRequest(commandKey, "T-9", "G", "t", RESOURCE, REGION,
                        List.of("INC-404"))), HttpStatus.NOT_FOUND);
        TaskView ok = service.createTask("INC-F1", "alice",
                new TaskCreateRequest(commandKey, "T-9", "G", "t", RESOURCE, REGION, List.of()));
        assertThat(ok.status()).isEqualTo("OPEN");
    }

    @Test
    void createTask_requiresCurrentCommander() {
        commanding("INC-C1", "alice");
        assertApiStatus(() -> createTask("INC-C1", "bob", "T-1", List.of()), HttpStatus.CONFLICT);
        // 指挥权变更：交接后旧指挥人失去权限，新指挥人可创建
        service.initiateTransfer("INC-C1", "alice", new TransferRequest(key(), "bob"));
        service.acceptTransfer("INC-C1", "bob", new TransferAcceptRequest(key()));
        assertApiStatus(() -> createTask("INC-C1", "alice", "T-1", List.of()), HttpStatus.CONFLICT);
        TaskView byNew = createTask("INC-C1", "bob", "T-2", List.of());
        assertThat(byNew.createdBy()).isEqualTo("bob");
    }

    @Test
    void createTask_max20PerIncident() {
        commanding("INC-M1", "alice");
        for (int i = 1; i <= 20; i++) {
            createTask("INC-M1", "alice", "T-" + i, List.of());
        }
        assertThat(service.listTasks("INC-M1").tasks()).hasSize(20);
        assertApiStatus(() -> createTask("INC-M1", "alice", "T-21", List.of()),
                HttpStatus.CONFLICT);
        assertThat(service.listTasks("INC-M1").tasks()).hasSize(20);
    }

    @Test
    void createTask_taskKeyIdempotency() {
        commanding("INC-K1", "alice");
        commanding("INC-K2", "bob");
        TaskView first = createTask("INC-K1", "alice", "T-1", List.of("INC-K2"));
        // 不同 commandKey、同 taskKey 同内容（含资源/区域/阻塞集合）：幂等返回首次任务
        TaskView again = service.createTask("INC-K1", "alice", new TaskCreateRequest(key(), "T-1",
                "G", "任务T-1", RESOURCE, REGION, List.of("INC-K2")));
        assertThat(again).isEqualTo(first);
        assertThat(service.listTasks("INC-K1").tasks()).hasSize(1);
        // 同 taskKey 不同内容：409（标题/分组/资源/区域/阻塞集合任一不同）
        assertApiStatus(() -> service.createTask("INC-K1", "alice", new TaskCreateRequest(key(),
                "T-1", "G", "不同标题", RESOURCE, REGION, List.of("INC-K2"))),
                HttpStatus.CONFLICT);
        assertApiStatus(() -> service.createTask("INC-K1", "alice", new TaskCreateRequest(key(),
                "T-1", "G2", "任务T-1", RESOURCE, REGION, List.of("INC-K2"))),
                HttpStatus.CONFLICT);
        assertApiStatus(() -> service.createTask("INC-K1", "alice", new TaskCreateRequest(key(),
                "T-1", "G", "任务T-1", "RES-OTHER", REGION, List.of("INC-K2"))),
                HttpStatus.CONFLICT);
        assertApiStatus(() -> service.createTask("INC-K1", "alice", new TaskCreateRequest(key(),
                "T-1", "G", "任务T-1", RESOURCE, "B", List.of("INC-K2"))),
                HttpStatus.CONFLICT);
        assertApiStatus(() -> service.createTask("INC-K1", "alice", new TaskCreateRequest(key(),
                "T-1", "G", "任务T-1", RESOURCE, REGION, List.of())), HttpStatus.CONFLICT);
    }

    @Test
    void createTask_directCycle_rejectedAtomically() {
        commanding("INC-A", "alice");
        commanding("INC-B", "bob");
        createTask("INC-A", "alice", "T-1", List.of("INC-B"));
        // B → A 与已有 A → B 形成直接环：409
        assertApiStatus(() -> createTask("INC-B", "bob", "T-1", List.of("INC-A")),
                HttpStatus.CONFLICT);
        // 不留部分任务或边
        assertThat(service.listTasks("INC-B").tasks()).isEmpty();
        Integer edgeCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM incident_task_blockers", Integer.class);
        assertThat(edgeCount).isEqualTo(1);
    }

    @Test
    void createTask_indirectCycle_rejectedAtomically() {
        commanding("INC-A", "alice");
        commanding("INC-B", "bob");
        commanding("INC-C", "carol");
        commanding("INC-D", "dave");
        createTask("INC-A", "alice", "T-1", List.of("INC-B"));
        createTask("INC-B", "bob", "T-1", List.of("INC-C"));
        // C → A 形成间接环 A→B→C→A：409
        assertApiStatus(() -> createTask("INC-C", "carol", "T-1", List.of("INC-A")),
                HttpStatus.CONFLICT);
        assertThat(service.listTasks("INC-C").tasks()).isEmpty();
        // 多阻塞中任一成环即整体拒绝，不留部分边
        assertApiStatus(() -> service.createTask("INC-C", "carol",
                new TaskCreateRequest(key(), "T-2", "G", "t2", RESOURCE, REGION,
                        List.of("INC-D", "INC-A"))), HttpStatus.CONFLICT);
        assertThat(service.listTasks("INC-C").tasks()).isEmpty();
        // 非环依赖仍允许：C → D
        TaskView ok = createTask("INC-C", "carol", "T-3", List.of("INC-D"));
        assertThat(ok.status()).isEqualTo("OPEN");
    }

    @Test
    void completeTask_requiresDispatchAndExecution() {
        commanding("INC-D1", "alice");
        registerValidCredential("alice");
        createTask("INC-D1", "alice", "T-1", List.of());
        // OPEN 不能直接完成
        assertApiStatus(() -> service.completeTask("INC-D1", "T-1", "alice",
                new TaskActionRequest(key())), HttpStatus.CONFLICT);
        // 未登记凭据的资源不能派发
        commanding("INC-D2", "erin");
        service.createTask("INC-D2", "erin", new TaskCreateRequest(key(), "T-1", "G", "t2",
                "RES-NONE", REGION, List.of()));
        assertApiStatus(() -> service.dispatchTask("INC-D2", "T-1", "erin",
                new TaskDispatchRequest(reqId(), "RES-NONE", CRED_TYPE)), HttpStatus.CONFLICT);
        // DISPATCHED 未执行前检查不能完成
        service.dispatchTask("INC-D1", "T-1", "alice",
                new TaskDispatchRequest(reqId(), RESOURCE, CRED_TYPE));
        assertApiStatus(() -> service.completeTask("INC-D1", "T-1", "alice",
                new TaskActionRequest(key())), HttpStatus.CONFLICT);
        // 执行前检查通过进入 EXECUTING 后可完成
        TaskView executing = service.startTask("INC-D1", "T-1", "alice",
                new TaskActionRequest(key()));
        assertThat(executing.status()).isEqualTo("EXECUTING");
        assertThat(executing.executingBy()).isEqualTo("alice");
        assertThat(executing.executingAt()).isNotNull();
        TaskView done = service.completeTask("INC-D1", "T-1", "alice",
                new TaskActionRequest(key()));
        assertThat(done.status()).isEqualTo("DONE");
        assertThat(done.doneBy()).isEqualTo("alice");
        assertThat(done.doneAt()).isNotNull();
        assertThat(done.executingAt()).isNotNull();
    }

    @Test
    void completeTask_blockerGate() {
        commanding("INC-P1", "alice");
        commanding("INC-P2", "bob");
        commanding("INC-P3", "carol");
        registerValidCredential("alice");
        createTask("INC-P1", "alice", "T-1", List.of("INC-P2", "INC-P3"));
        dispatchAndStart("INC-P1", "T-1", "alice");
        // 两个阻塞均未解除：409，details 返回全部未解除事件
        assertThatThrownBy(() -> service.completeTask("INC-P1", "T-1", "alice",
                new TaskActionRequest(key())))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(details(e)).containsExactlyInAnyOrder("INC-P2", "INC-P3");
                });
        // 解除一个后仍 409，只剩未解除的事件
        service.changeStatus("INC-P2", "bob", new StatusRequest(key(), "CONTAINED"));
        assertThatThrownBy(() -> service.completeTask("INC-P1", "T-1", "alice",
                new TaskActionRequest(key())))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(details(e)).containsExactly("INC-P3");
                });
        // 全部解除（RESOLVED/CLOSED 同样视为解除）后完成
        service.changeStatus("INC-P3", "carol", new StatusRequest(key(), "CONTAINED"));
        service.changeStatus("INC-P3", "carol", new StatusRequest(key(), "RESOLVED"));
        TaskView done = service.completeTask("INC-P1", "T-1", "alice",
                new TaskActionRequest(key()));
        assertThat(done.status()).isEqualTo("DONE");
        assertThat(done.doneBy()).isEqualTo("alice");
        assertThat(done.doneAt()).isNotNull();
    }

    @Test
    void taskTerminalStates() {
        commanding("INC-S1", "alice");
        registerValidCredential("alice");
        createTask("INC-S1", "alice", "T-1", List.of());
        createTask("INC-S1", "alice", "T-2", List.of());
        // 取消适用任意非终态（T-2 尚 OPEN）
        TaskView cancelled = service.cancelTask("INC-S1", "T-2", "alice",
                new TaskActionRequest(key()));
        assertThat(cancelled.status()).isEqualTo("CANCELLED");
        assertThat(cancelled.cancelledBy()).isEqualTo("alice");
        assertThat(cancelled.cancelledAt()).isNotNull();
        // CANCELLED 终态：再取消/完成均 409
        assertApiStatus(() -> service.cancelTask("INC-S1", "T-2", "alice",
                new TaskActionRequest(key())), HttpStatus.CONFLICT);
        assertApiStatus(() -> service.completeTask("INC-S1", "T-2", "alice",
                new TaskActionRequest(key())), HttpStatus.CONFLICT);
        // 派发→执行→DONE 终态：再完成/取消均 409
        dispatchAndStart("INC-S1", "T-1", "alice");
        service.completeTask("INC-S1", "T-1", "alice", new TaskActionRequest(key()));
        assertApiStatus(() -> service.completeTask("INC-S1", "T-1", "alice",
                new TaskActionRequest(key())), HttpStatus.CONFLICT);
        assertApiStatus(() -> service.cancelTask("INC-S1", "T-1", "alice",
                new TaskActionRequest(key())), HttpStatus.CONFLICT);
        // 任务不存在
        assertApiStatus(() -> service.completeTask("INC-S1", "T-9", "alice",
                new TaskActionRequest(key())), HttpStatus.NOT_FOUND);
        assertApiStatus(() -> service.cancelTask("INC-S1", "T-9", "alice",
                new TaskActionRequest(key())), HttpStatus.NOT_FOUND);
    }

    @Test
    void completeTask_requiresCurrentCommander() {
        commanding("INC-W1", "alice");
        registerValidCredential("alice");
        createTask("INC-W1", "alice", "T-1", List.of());
        assertApiStatus(() -> service.completeTask("INC-W1", "T-1", "bob",
                new TaskActionRequest(key())), HttpStatus.CONFLICT);
        // 交接后旧指挥人失去权限，新指挥人可派发、启动并完成
        service.initiateTransfer("INC-W1", "alice", new TransferRequest(key(), "bob"));
        service.acceptTransfer("INC-W1", "bob", new TransferAcceptRequest(key()));
        dispatchAndStart("INC-W1", "T-1", "bob");
        TaskView done = service.completeTask("INC-W1", "T-1", "bob",
                new TaskActionRequest(key()));
        assertThat(done.doneBy()).isEqualTo("bob");
    }

    @Test
    void taskCommandKey_idempotency() {
        commanding("INC-I1", "alice");
        registerValidCredential("alice");
        // 创建：同键同参重放首次结果
        String createKey = key();
        TaskView first = service.createTask("INC-I1", "alice",
                new TaskCreateRequest(createKey, "T-1", "G", "t", RESOURCE, REGION, List.of()));
        TaskView createReplay = service.createTask("INC-I1", "alice",
                new TaskCreateRequest(createKey, "T-1", "G", "t", RESOURCE, REGION, List.of()));
        assertThat(createReplay).isEqualTo(first);
        assertThat(service.listTasks("INC-I1").tasks()).hasSize(1);
        // 同键改参 → 409；同键跨操作 → 409
        assertApiStatus(() -> service.createTask("INC-I1", "alice",
                new TaskCreateRequest(createKey, "T-2", "G", "t", RESOURCE, REGION, List.of())),
                HttpStatus.CONFLICT);
        assertApiStatus(() -> service.completeTask("INC-I1", "T-1", "alice",
                new TaskActionRequest(createKey)), HttpStatus.CONFLICT);
        // 执行启动：同键重放首次结果（EXECUTING 视图），不产生重复状态转移
        service.dispatchTask("INC-I1", "T-1", "alice",
                new TaskDispatchRequest(reqId(), RESOURCE, CRED_TYPE));
        String executeKey = key();
        TaskView executing = service.startTask("INC-I1", "T-1", "alice",
                new TaskActionRequest(executeKey));
        TaskView executeReplay = service.startTask("INC-I1", "T-1", "alice",
                new TaskActionRequest(executeKey));
        assertThat(executeReplay).isEqualTo(executing);
        // 完成：同键重放首次结果（终态同类重复操作按幂等规则返回）
        String completeKey = key();
        TaskView done = service.completeTask("INC-I1", "T-1", "alice",
                new TaskActionRequest(completeKey));
        TaskView doneReplay = service.completeTask("INC-I1", "T-1", "alice",
                new TaskActionRequest(completeKey));
        assertThat(doneReplay).isEqualTo(done);
        assertThat(service.getTask("INC-I1", "T-1").status()).isEqualTo("DONE");
    }

    @Test
    void resolveGate_openTasksBlock() {
        commanding("INC-R1", "alice");
        registerValidCredential("alice");
        createTask("INC-R1", "alice", "T-1", List.of());
        createTask("INC-R1", "alice", "T-2", List.of());
        service.changeStatus("INC-R1", "alice", new StatusRequest(key(), "CONTAINED"));
        // 仍有未终态任务（含 OPEN）：409 并按 groupCode、taskKey 返回未完成项
        assertThatThrownBy(() -> service.changeStatus("INC-R1", "alice",
                new StatusRequest(key(), "RESOLVED")))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(details(e))
                            .containsExactlyInAnyOrder(
                                    new UnfinishedTaskView("G", "T-1"),
                                    new UnfinishedTaskView("G", "T-2"));
                });
        // 完成一个（需派发并启动）后仍有 OPEN：409 且只剩未完成项
        dispatchAndStart("INC-R1", "T-1", "alice");
        service.completeTask("INC-R1", "T-1", "alice", new TaskActionRequest(key()));
        assertThatThrownBy(() -> service.changeStatus("INC-R1", "alice",
                new StatusRequest(key(), "RESOLVED")))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(details(e))
                            .containsExactly(new UnfinishedTaskView("G", "T-2"));
                });
        // 全部 DONE/CANCELLED 后可解决
        service.cancelTask("INC-R1", "T-2", "alice", new TaskActionRequest(key()));
        IncidentView resolved = service.changeStatus("INC-R1", "alice",
                new StatusRequest(key(), "RESOLVED"));
        assertThat(resolved.status()).isEqualTo("RESOLVED");
    }

    @Test
    void createTask_closedIncident_illegalTransition() {
        commanding("INC-Z1", "alice");
        service.changeStatus("INC-Z1", "alice", new StatusRequest(key(), "CONTAINED"));
        service.changeStatus("INC-Z1", "alice", new StatusRequest(key(), "RESOLVED"));
        service.changeStatus("INC-Z1", "alice", new StatusRequest(key(), "CLOSED"));
        assertApiStatus(() -> createTask("INC-Z1", "alice", "T-1", List.of()),
                HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void taskQueries_notFound() {
        assertApiStatus(() -> service.listTasks("INC-404"), HttpStatus.NOT_FOUND);
        assertApiStatus(() -> service.getTask("INC-404", "T-1"), HttpStatus.NOT_FOUND);
        commanding("INC-Q1", "alice");
        assertApiStatus(() -> service.getTask("INC-Q1", "T-9"), HttpStatus.NOT_FOUND);
        assertThat(service.listTasks("INC-Q1").tasks()).isEmpty();
    }
}
