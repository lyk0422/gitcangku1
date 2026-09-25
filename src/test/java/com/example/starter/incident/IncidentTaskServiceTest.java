package com.example.starter.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import com.example.starter.incident.dto.Requests.ReportRequest;
import com.example.starter.incident.dto.Requests.StatusRequest;
import com.example.starter.incident.dto.Requests.TakeoverRequest;
import com.example.starter.incident.dto.Requests.TaskActionRequest;
import com.example.starter.incident.dto.Requests.TaskCreateRequest;
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
 */
@SpringBootTest
class IncidentTaskServiceTest {

    @Autowired
    private IncidentService service;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM credential_risks");
        jdbc.update("DELETE FROM resource_leases");
        jdbc.update("DELETE FROM resource_credentials");
        jdbc.update("DELETE FROM command_keys");
        jdbc.update("DELETE FROM incident_status_history");
        jdbc.update("DELETE FROM incident_transfers");
        jdbc.update("DELETE FROM incident_actions");
        jdbc.update("DELETE FROM incident_task_blockers");
        jdbc.update("DELETE FROM incident_tasks");
        jdbc.update("DELETE FROM incidents");
    }

    private static String key() {
        return "CMD-" + UUID.randomUUID();
    }

    private IncidentView commanding(String incidentKey, String commander) {
        service.report(new ReportRequest(incidentKey, "S2", "核心链路故障", "reporter-1"));
        return service.takeover(incidentKey, commander, new TakeoverRequest(key()));
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
        TaskView created = service.createTask("INC-T1", "alice",
                new TaskCreateRequest(key(), "T-1", "DB", "扩容连接池", List.of("INC-T2")));
        assertThat(created.status()).isEqualTo("OPEN");
        assertThat(created.groupCode()).isEqualTo("DB");
        assertThat(created.title()).isEqualTo("扩容连接池");
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
        // 非空校验：taskKey/groupCode/title/commandKey
        assertApiStatus(() -> service.createTask("INC-V1", "alice",
                new TaskCreateRequest(key(), " ", "G", "t", List.of())), HttpStatus.BAD_REQUEST);
        assertApiStatus(() -> service.createTask("INC-V1", "alice",
                new TaskCreateRequest(key(), "T", " ", "t", List.of())), HttpStatus.BAD_REQUEST);
        assertApiStatus(() -> service.createTask("INC-V1", "alice",
                new TaskCreateRequest(key(), "T", "G", " ", List.of())), HttpStatus.BAD_REQUEST);
        assertApiStatus(() -> service.createTask("INC-V1", "alice",
                new TaskCreateRequest(null, "T", "G", "t", List.of())), HttpStatus.BAD_REQUEST);
        // 阻塞事件不能是自身
        assertApiStatus(() -> service.createTask("INC-V1", "alice",
                new TaskCreateRequest(key(), "T", "G", "t", List.of("INC-V1"))),
                HttpStatus.BAD_REQUEST);
        // 阻塞事件超过 5 个
        assertApiStatus(() -> service.createTask("INC-V1", "alice",
                new TaskCreateRequest(key(), "T", "G", "t",
                        List.of("B1", "B2", "B3", "B4", "B5", "B6"))), HttpStatus.BAD_REQUEST);
        // 阻塞事件不存在
        assertApiStatus(() -> service.createTask("INC-V1", "alice",
                new TaskCreateRequest(key(), "T", "G", "t", List.of("INC-404"))),
                HttpStatus.NOT_FOUND);
        // 事件不存在
        assertApiStatus(() -> service.createTask("INC-404", "alice",
                new TaskCreateRequest(key(), "T", "G", "t", List.of())), HttpStatus.NOT_FOUND);
        // 未接管事件无指挥人
        service.report(new ReportRequest("INC-V2", "S2", "s", "r"));
        assertApiStatus(() -> service.createTask("INC-V2", "alice",
                new TaskCreateRequest(key(), "T", "G", "t", List.of())), HttpStatus.CONFLICT);
    }

    @Test
    void createTask_failureDoesNotConsumeCommandKey() {
        commanding("INC-F1", "alice");
        String commandKey = key();
        // 业务失败（阻塞事件不存在）事务回滚，不占键
        assertApiStatus(() -> service.createTask("INC-F1", "alice",
                new TaskCreateRequest(commandKey, "T-9", "G", "t", List.of("INC-404"))),
                HttpStatus.NOT_FOUND);
        TaskView ok = service.createTask("INC-F1", "alice",
                new TaskCreateRequest(commandKey, "T-9", "G", "t", List.of()));
        assertThat(ok.status()).isEqualTo("OPEN");
    }

    @Test
    void createTask_requiresCurrentCommander() {
        commanding("INC-C1", "alice");
        assertApiStatus(() -> service.createTask("INC-C1", "bob",
                new TaskCreateRequest(key(), "T-1", "G", "t", List.of())), HttpStatus.CONFLICT);
        // 指挥权变更：交接后旧指挥人失去权限，新指挥人可创建
        service.initiateTransfer("INC-C1", "alice", new TransferRequest(key(), "bob"));
        service.acceptTransfer("INC-C1", "bob", new TransferAcceptRequest(key()));
        assertApiStatus(() -> service.createTask("INC-C1", "alice",
                new TaskCreateRequest(key(), "T-1", "G", "t", List.of())), HttpStatus.CONFLICT);
        TaskView byNew = service.createTask("INC-C1", "bob",
                new TaskCreateRequest(key(), "T-1", "G", "t", List.of()));
        assertThat(byNew.createdBy()).isEqualTo("bob");
    }

    @Test
    void createTask_max20PerIncident() {
        commanding("INC-M1", "alice");
        for (int i = 1; i <= 20; i++) {
            service.createTask("INC-M1", "alice",
                    new TaskCreateRequest(key(), "T-" + i, "G", "任务" + i, List.of()));
        }
        assertThat(service.listTasks("INC-M1").tasks()).hasSize(20);
        assertApiStatus(() -> service.createTask("INC-M1", "alice",
                new TaskCreateRequest(key(), "T-21", "G", "任务21", List.of())),
                HttpStatus.CONFLICT);
        assertThat(service.listTasks("INC-M1").tasks()).hasSize(20);
    }

    @Test
    void createTask_taskKeyIdempotency() {
        commanding("INC-K1", "alice");
        commanding("INC-K2", "bob");
        TaskView first = service.createTask("INC-K1", "alice",
                new TaskCreateRequest(key(), "T-1", "G", "标题", List.of("INC-K2")));
        // 不同 commandKey、同 taskKey 同内容（含阻塞集合）：幂等返回首次任务
        TaskView again = service.createTask("INC-K1", "alice",
                new TaskCreateRequest(key(), "T-1", "G", "标题", List.of("INC-K2")));
        assertThat(again).isEqualTo(first);
        assertThat(service.listTasks("INC-K1").tasks()).hasSize(1);
        // 同 taskKey 不同内容：409
        assertApiStatus(() -> service.createTask("INC-K1", "alice",
                new TaskCreateRequest(key(), "T-1", "G", "不同标题", List.of("INC-K2"))),
                HttpStatus.CONFLICT);
        assertApiStatus(() -> service.createTask("INC-K1", "alice",
                new TaskCreateRequest(key(), "T-1", "G2", "标题", List.of("INC-K2"))),
                HttpStatus.CONFLICT);
        assertApiStatus(() -> service.createTask("INC-K1", "alice",
                new TaskCreateRequest(key(), "T-1", "G", "标题", List.of())),
                HttpStatus.CONFLICT);
    }

    @Test
    void createTask_directCycle_rejectedAtomically() {
        commanding("INC-A", "alice");
        commanding("INC-B", "bob");
        service.createTask("INC-A", "alice",
                new TaskCreateRequest(key(), "T-1", "G", "t", List.of("INC-B")));
        // B → A 与已有 A → B 形成直接环：409
        assertApiStatus(() -> service.createTask("INC-B", "bob",
                new TaskCreateRequest(key(), "T-1", "G", "t", List.of("INC-A"))),
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
        service.createTask("INC-A", "alice",
                new TaskCreateRequest(key(), "T-1", "G", "t", List.of("INC-B")));
        service.createTask("INC-B", "bob",
                new TaskCreateRequest(key(), "T-1", "G", "t", List.of("INC-C")));
        // C → A 形成间接环 A→B→C→A：409
        assertApiStatus(() -> service.createTask("INC-C", "carol",
                new TaskCreateRequest(key(), "T-1", "G", "t", List.of("INC-A"))),
                HttpStatus.CONFLICT);
        assertThat(service.listTasks("INC-C").tasks()).isEmpty();
        // 多阻塞中任一成环即整体拒绝，不留部分边
        assertApiStatus(() -> service.createTask("INC-C", "carol",
                new TaskCreateRequest(key(), "T-2", "G", "t", List.of("INC-D", "INC-A"))),
                HttpStatus.CONFLICT);
        assertThat(service.listTasks("INC-C").tasks()).isEmpty();
        // 非环依赖仍允许：C → D
        TaskView ok = service.createTask("INC-C", "carol",
                new TaskCreateRequest(key(), "T-3", "G", "t", List.of("INC-D")));
        assertThat(ok.status()).isEqualTo("OPEN");
    }

    @Test
    void completeTask_blockerGate() {
        commanding("INC-P1", "alice");
        commanding("INC-P2", "bob");
        commanding("INC-P3", "carol");
        service.createTask("INC-P1", "alice",
                new TaskCreateRequest(key(), "T-1", "G", "t", List.of("INC-P2", "INC-P3")));
        // 两个阻塞均未解除：开始 409，details 返回全部未解除事件
        assertThatThrownBy(() -> service.startTask("INC-P1", "T-1", "alice",
                new TaskActionRequest(key())))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(details(e)).containsExactlyInAnyOrder("INC-P2", "INC-P3");
                });
        // 未开始任务不能完成：409
        assertApiStatus(() -> service.completeTask("INC-P1", "T-1", "alice",
                new TaskActionRequest(key())), HttpStatus.CONFLICT);
        // 解除一个后仍 409，只剩未解除的事件
        service.changeStatus("INC-P2", "bob", new StatusRequest(key(), "CONTAINED"));
        assertThatThrownBy(() -> service.startTask("INC-P1", "T-1", "alice",
                new TaskActionRequest(key())))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(details(e)).containsExactly("INC-P3");
                });
        // 全部解除（RESOLVED/CLOSED 同样视为解除）后开始、完成
        service.changeStatus("INC-P3", "carol", new StatusRequest(key(), "CONTAINED"));
        service.changeStatus("INC-P3", "carol", new StatusRequest(key(), "RESOLVED"));
        TaskView started = service.startTask("INC-P1", "T-1", "alice",
                new TaskActionRequest(key()));
        assertThat(started.status()).isEqualTo("IN_PROGRESS");
        assertThat(started.startedBy()).isEqualTo("alice");
        assertThat(started.startedAt()).isNotNull();
        TaskView done = service.completeTask("INC-P1", "T-1", "alice",
                new TaskActionRequest(key()));
        assertThat(done.status()).isEqualTo("DONE");
        assertThat(done.doneBy()).isEqualTo("alice");
        assertThat(done.doneAt()).isNotNull();
    }

    @Test
    void taskTerminalStates() {
        commanding("INC-S1", "alice");
        service.createTask("INC-S1", "alice",
                new TaskCreateRequest(key(), "T-1", "G", "t", List.of()));
        service.createTask("INC-S1", "alice",
                new TaskCreateRequest(key(), "T-2", "G", "t", List.of()));
        // 取消仅适用 OPEN
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
        // DONE 终态：再完成/取消均 409
        service.startTask("INC-S1", "T-1", "alice", new TaskActionRequest(key()));
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
        service.createTask("INC-W1", "alice",
                new TaskCreateRequest(key(), "T-1", "G", "t", List.of()));
        assertApiStatus(() -> service.completeTask("INC-W1", "T-1", "bob",
                new TaskActionRequest(key())), HttpStatus.CONFLICT);
        // 交接后旧指挥人失去权限，新指挥人可完成
        service.initiateTransfer("INC-W1", "alice", new TransferRequest(key(), "bob"));
        service.acceptTransfer("INC-W1", "bob", new TransferAcceptRequest(key()));
        assertApiStatus(() -> service.completeTask("INC-W1", "T-1", "alice",
                new TaskActionRequest(key())), HttpStatus.CONFLICT);
        TaskView started = service.startTask("INC-W1", "T-1", "bob",
                new TaskActionRequest(key()));
        assertThat(started.status()).isEqualTo("IN_PROGRESS");
        TaskView done = service.completeTask("INC-W1", "T-1", "bob",
                new TaskActionRequest(key()));
        assertThat(done.doneBy()).isEqualTo("bob");
    }

    @Test
    void taskCommandKey_idempotency() {
        commanding("INC-I1", "alice");
        // 创建：同键同参重放首次结果
        String createKey = key();
        TaskView first = service.createTask("INC-I1", "alice",
                new TaskCreateRequest(createKey, "T-1", "G", "t", List.of()));
        TaskView replay = service.createTask("INC-I1", "alice",
                new TaskCreateRequest(createKey, "T-1", "G", "t", List.of()));
        assertThat(replay).isEqualTo(first);
        assertThat(service.listTasks("INC-I1").tasks()).hasSize(1);
        // 同键改参 → 409；同键跨操作 → 409
        assertApiStatus(() -> service.createTask("INC-I1", "alice",
                new TaskCreateRequest(createKey, "T-2", "G", "t", List.of())),
                HttpStatus.CONFLICT);
        assertApiStatus(() -> service.completeTask("INC-I1", "T-1", "alice",
                new TaskActionRequest(createKey)), HttpStatus.CONFLICT);
        // 完成：同键重放首次结果（终态同类重复操作按幂等规则返回）
        service.startTask("INC-I1", "T-1", "alice", new TaskActionRequest(key()));
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
        service.createTask("INC-R1", "alice",
                new TaskCreateRequest(key(), "T-1", "DB", "t1", List.of()));
        service.createTask("INC-R1", "alice",
                new TaskCreateRequest(key(), "T-2", "APP", "t2", List.of()));
        service.changeStatus("INC-R1", "alice", new StatusRequest(key(), "CONTAINED"));
        // 仍有 OPEN：409 并按 groupCode、taskKey 返回未完成项
        assertThatThrownBy(() -> service.changeStatus("INC-R1", "alice",
                new StatusRequest(key(), "RESOLVED")))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(details(e))
                            .containsExactlyInAnyOrder(
                                    new UnfinishedTaskView("DB", "T-1"),
                                    new UnfinishedTaskView("APP", "T-2"));
                });
        // 完成一个后仍有未完成任务：409 且只剩未完成项
        service.startTask("INC-R1", "T-1", "alice", new TaskActionRequest(key()));
        service.completeTask("INC-R1", "T-1", "alice", new TaskActionRequest(key()));
        assertThatThrownBy(() -> service.changeStatus("INC-R1", "alice",
                new StatusRequest(key(), "RESOLVED")))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(details(e))
                            .containsExactly(new UnfinishedTaskView("APP", "T-2"));
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
        assertApiStatus(() -> service.createTask("INC-Z1", "alice",
                new TaskCreateRequest(key(), "T-1", "G", "t", List.of())),
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
