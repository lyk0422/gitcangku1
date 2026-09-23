package com.example.starter.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import com.example.starter.incident.dto.Requests.ReportRequest;
import com.example.starter.incident.dto.Requests.StatusRequest;
import com.example.starter.incident.dto.Requests.TakeoverRequest;
import com.example.starter.incident.dto.Requests.TaskActionRequest;
import com.example.starter.incident.dto.Requests.TaskBlockersReplaceRequest;
import com.example.starter.incident.dto.Requests.TaskCreateRequest;
import com.example.starter.incident.dto.Requests.TransferAcceptRequest;
import com.example.starter.incident.dto.Requests.TransferRequest;
import com.example.starter.incident.dto.Responses.IncidentView;
import com.example.starter.incident.dto.Responses.TaskBlockerView;
import com.example.starter.incident.dto.Responses.TaskRevisionHistoryView;
import com.example.starter.incident.dto.Responses.TaskView;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 任务依赖修订服务测试：覆盖整体替换主流程、版本递增（含列表未变）、
 * 参数校验、版本冲突、终态禁修订、直接/间接环回滚、历史边参与判定、
 * 完成/取消版本递增、commandKey 幂等、完成读取最新依赖及修订历史查询。
 */
@SpringBootTest
class IncidentTaskRevisionServiceTest {

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
        jdbc.update("DELETE FROM incident_task_revisions");
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

    private TaskView createTask(String incidentKey, String actor, String taskKey,
                                List<String> blockers) {
        return service.createTask(incidentKey, actor,
                new TaskCreateRequest(key(), taskKey, "G", "t", blockers));
    }

    @Test
    void replaceBlockers_mainFlowAndHistory() {
        commanding("INC-A", "alice");
        commanding("INC-B", "bob");
        commanding("INC-C", "carol");
        commanding("INC-D", "dave");
        TaskView created = createTask("INC-A", "alice", "T-1", List.of("INC-B"));
        assertThat(created.version()).isEqualTo(1);

        // 整体替换：版本 1 → 2，阻塞列表更新，记录修订历史
        TaskView replaced = service.replaceTaskBlockers("INC-A", "T-1", "alice",
                new TaskBlockersReplaceRequest(key(), 1, List.of("INC-D", "INC-C")));
        assertThat(replaced.version()).isEqualTo(2);
        assertThat(replaced.blockers()).extracting(TaskBlockerView::incidentKey)
                .containsExactly("INC-C", "INC-D");

        TaskRevisionHistoryView history = service.taskRevisions("INC-A", "T-1");
        assertThat(history.incidentKey()).isEqualTo("INC-A");
        assertThat(history.taskKey()).isEqualTo("T-1");
        assertThat(history.version()).isEqualTo(2);
        assertThat(history.revisions()).hasSize(1);
        var rev = history.revisions().get(0);
        assertThat(rev.operation()).isEqualTo("REPLACE");
        assertThat(rev.actor()).isEqualTo("alice");
        assertThat(rev.fromVersion()).isEqualTo(1);
        assertThat(rev.toVersion()).isEqualTo(2);
        // 历史中的依赖列表为排序后的事件键
        assertThat(rev.blockerIncidentKeys()).containsExactly("INC-C", "INC-D");
        assertThat(rev.occurredAt()).isNotNull();

        // 列表未变也加一：版本 2 → 3
        TaskView unchanged = service.replaceTaskBlockers("INC-A", "T-1", "alice",
                new TaskBlockersReplaceRequest(key(), 2, List.of("INC-C", "INC-D")));
        assertThat(unchanged.version()).isEqualTo(3);

        // 空列表合法：版本 3 → 4，阻塞清空
        TaskView emptied = service.replaceTaskBlockers("INC-A", "T-1", "alice",
                new TaskBlockersReplaceRequest(key(), 3, List.of()));
        assertThat(emptied.version()).isEqualTo(4);
        assertThat(emptied.blockers()).isEmpty();

        TaskRevisionHistoryView after = service.taskRevisions("INC-A", "T-1");
        assertThat(after.version()).isEqualTo(4);
        assertThat(after.revisions()).hasSize(3);
        assertThat(after.revisions().get(2).blockerIncidentKeys()).isEmpty();
        // 查询不改变数据：再次查询结果一致
        assertThat(service.taskRevisions("INC-A", "T-1")).isEqualTo(after);
        assertThat(service.getTask("INC-A", "T-1").version()).isEqualTo(4);
    }

    @Test
    void replaceBlockers_validation() {
        commanding("INC-V1", "alice");
        createTask("INC-V1", "alice", "T-1", List.of());
        // expectedTaskVersion 必填
        assertApiStatus(() -> service.replaceTaskBlockers("INC-V1", "T-1", "alice",
                new TaskBlockersReplaceRequest(key(), null, List.of())), HttpStatus.BAD_REQUEST);
        // commandKey 必填
        assertApiStatus(() -> service.replaceTaskBlockers("INC-V1", "T-1", "alice",
                new TaskBlockersReplaceRequest(" ", 1, List.of())), HttpStatus.BAD_REQUEST);
        // 重复目标 400
        assertApiStatus(() -> service.replaceTaskBlockers("INC-V1", "T-1", "alice",
                new TaskBlockersReplaceRequest(key(), 1, List.of("INC-V1", "INC-V1"))),
                HttpStatus.BAD_REQUEST);
        // 依赖自身 400
        assertApiStatus(() -> service.replaceTaskBlockers("INC-V1", "T-1", "alice",
                new TaskBlockersReplaceRequest(key(), 1, List.of("INC-V1"))),
                HttpStatus.BAD_REQUEST);
        // 超过 5 个 400
        assertApiStatus(() -> service.replaceTaskBlockers("INC-V1", "T-1", "alice",
                new TaskBlockersReplaceRequest(key(), 1,
                        List.of("B1", "B2", "B3", "B4", "B5", "B6"))), HttpStatus.BAD_REQUEST);
        // 阻塞事件不存在 404
        assertApiStatus(() -> service.replaceTaskBlockers("INC-V1", "T-1", "alice",
                new TaskBlockersReplaceRequest(key(), 1, List.of("INC-404"))),
                HttpStatus.NOT_FOUND);
        // 事件不存在 404
        assertApiStatus(() -> service.replaceTaskBlockers("INC-404", "T-1", "alice",
                new TaskBlockersReplaceRequest(key(), 1, List.of())), HttpStatus.NOT_FOUND);
        // 任务不存在 404
        assertApiStatus(() -> service.replaceTaskBlockers("INC-V1", "T-9", "alice",
                new TaskBlockersReplaceRequest(key(), 1, List.of())), HttpStatus.NOT_FOUND);
        // 全部校验失败后任务状态不变
        TaskView task = service.getTask("INC-V1", "T-1");
        assertThat(task.version()).isEqualTo(1);
        assertThat(service.taskRevisions("INC-V1", "T-1").revisions()).isEmpty();
    }

    @Test
    void replaceBlockers_versionConflict() {
        commanding("INC-A", "alice");
        commanding("INC-B", "bob");
        createTask("INC-A", "alice", "T-1", List.of());
        // 版本不匹配 409，旧边、版本、历史不变
        assertApiStatus(() -> service.replaceTaskBlockers("INC-A", "T-1", "alice",
                new TaskBlockersReplaceRequest(key(), 2, List.of("INC-B"))), HttpStatus.CONFLICT);
        assertApiStatus(() -> service.replaceTaskBlockers("INC-A", "T-1", "alice",
                new TaskBlockersReplaceRequest(key(), 0, List.of("INC-B"))), HttpStatus.CONFLICT);
        TaskView task = service.getTask("INC-A", "T-1");
        assertThat(task.version()).isEqualTo(1);
        assertThat(task.blockers()).isEmpty();
        assertThat(service.taskRevisions("INC-A", "T-1").revisions()).isEmpty();
    }

    @Test
    void replaceBlockers_terminalTaskRejected() {
        commanding("INC-A", "alice");
        commanding("INC-B", "bob");
        createTask("INC-A", "alice", "T-1", List.of());
        createTask("INC-A", "alice", "T-2", List.of());
        service.completeTask("INC-A", "T-1", "alice", new TaskActionRequest(key()));
        service.cancelTask("INC-A", "T-2", "alice", new TaskActionRequest(key()));
        // 完成/取消首次成功各加一：版本 1 → 2
        assertThat(service.getTask("INC-A", "T-1").version()).isEqualTo(2);
        assertThat(service.getTask("INC-A", "T-2").version()).isEqualTo(2);
        // 终态不得修订（即使携带当前版本号也 409）
        assertApiStatus(() -> service.replaceTaskBlockers("INC-A", "T-1", "alice",
                new TaskBlockersReplaceRequest(key(), 2, List.of("INC-B"))), HttpStatus.CONFLICT);
        assertApiStatus(() -> service.replaceTaskBlockers("INC-A", "T-2", "alice",
                new TaskBlockersReplaceRequest(key(), 2, List.of())), HttpStatus.CONFLICT);
        // 完成/取消的修订历史已记录
        TaskRevisionHistoryView doneHistory = service.taskRevisions("INC-A", "T-1");
        assertThat(doneHistory.revisions()).hasSize(1);
        assertThat(doneHistory.revisions().get(0).operation()).isEqualTo("COMPLETE");
        assertThat(doneHistory.revisions().get(0).fromVersion()).isEqualTo(1);
        assertThat(doneHistory.revisions().get(0).toVersion()).isEqualTo(2);
        TaskRevisionHistoryView cancelledHistory = service.taskRevisions("INC-A", "T-2");
        assertThat(cancelledHistory.revisions()).hasSize(1);
        assertThat(cancelledHistory.revisions().get(0).operation()).isEqualTo("CANCEL");
    }

    @Test
    void replaceBlockers_requiresCurrentCommander() {
        commanding("INC-A", "alice");
        commanding("INC-B", "bob");
        createTask("INC-A", "alice", "T-1", List.of());
        assertApiStatus(() -> service.replaceTaskBlockers("INC-A", "T-1", "bob",
                new TaskBlockersReplaceRequest(key(), 1, List.of("INC-B"))), HttpStatus.CONFLICT);
        // 交接后旧指挥人失去权限，新指挥人可修订
        service.initiateTransfer("INC-A", "alice", new TransferRequest(key(), "bob"));
        service.acceptTransfer("INC-A", "bob", new TransferAcceptRequest(key()));
        assertApiStatus(() -> service.replaceTaskBlockers("INC-A", "T-1", "alice",
                new TaskBlockersReplaceRequest(key(), 1, List.of("INC-B"))), HttpStatus.CONFLICT);
        TaskView replaced = service.replaceTaskBlockers("INC-A", "T-1", "bob",
                new TaskBlockersReplaceRequest(key(), 1, List.of("INC-B")));
        assertThat(replaced.version()).isEqualTo(2);
        assertThat(service.taskRevisions("INC-A", "T-1").revisions().get(0).actor())
                .isEqualTo("bob");
    }

    @Test
    void replaceBlockers_directCycle_rollsBack() {
        commanding("INC-A", "alice");
        commanding("INC-B", "bob");
        createTask("INC-A", "alice", "T-1", List.of("INC-B"));
        createTask("INC-B", "bob", "T-1", List.of());
        // B → A 与已有 A → B 形成直接环：409
        String cycleKey = key();
        assertApiStatus(() -> service.replaceTaskBlockers("INC-B", "T-1", "bob",
                new TaskBlockersReplaceRequest(cycleKey, 1, List.of("INC-A"))),
                HttpStatus.CONFLICT);
        // 旧边、版本、历史及去重记录不变（失败不占键）
        TaskView task = service.getTask("INC-B", "T-1");
        assertThat(task.version()).isEqualTo(1);
        assertThat(task.blockers()).isEmpty();
        assertThat(service.taskRevisions("INC-B", "T-1").revisions()).isEmpty();
        Integer edgeCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM incident_task_blockers", Integer.class);
        assertThat(edgeCount).isEqualTo(1);
        Integer keyCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM command_keys WHERE command_key = ?", Integer.class,
                cycleKey);
        assertThat(keyCount).isZero();
    }

    @Test
    void replaceBlockers_indirectCycleAndHistoricalEdges() {
        commanding("INC-A", "alice");
        commanding("INC-B", "bob");
        commanding("INC-C", "carol");
        createTask("INC-A", "alice", "T-1", List.of("INC-B"));
        createTask("INC-B", "bob", "T-1", List.of("INC-C"));
        createTask("INC-C", "carol", "T-1", List.of());
        // C → A 形成间接环 A→B→C→A：409
        assertApiStatus(() -> service.replaceTaskBlockers("INC-C", "T-1", "carol",
                new TaskBlockersReplaceRequest(key(), 1, List.of("INC-A"))), HttpStatus.CONFLICT);
        assertThat(service.getTask("INC-C", "T-1").blockers()).isEmpty();

        // DONE 任务的历史边仍参与判定：A 的 T-1 完成后，B 不能再依赖 A
        service.changeStatus("INC-B", "bob", new StatusRequest(key(), "CONTAINED"));
        TaskView done = service.completeTask("INC-A", "T-1", "alice", new TaskActionRequest(key()));
        assertThat(done.status()).isEqualTo("DONE");
        assertApiStatus(() -> service.replaceTaskBlockers("INC-B", "T-1", "bob",
                new TaskBlockersReplaceRequest(key(), 1, List.of("INC-A"))), HttpStatus.CONFLICT);
    }

    @Test
    void replaceBlockers_onlyOwnEdgesRemoved() {
        commanding("INC-A", "alice");
        commanding("INC-B", "bob");
        commanding("INC-C", "carol");
        createTask("INC-A", "alice", "T-1", List.of("INC-B"));
        createTask("INC-A", "alice", "T-2", List.of("INC-C"));
        // 替换 T-1 的依赖不影响 T-2 的边
        service.replaceTaskBlockers("INC-A", "T-1", "alice",
                new TaskBlockersReplaceRequest(key(), 1, List.of()));
        assertThat(service.getTask("INC-A", "T-1").blockers()).isEmpty();
        assertThat(service.getTask("INC-A", "T-2").blockers())
                .extracting(TaskBlockerView::incidentKey).containsExactly("INC-C");
        // T-2 的边仍参与环判定：C 不能反向依赖 A
        createTask("INC-C", "carol", "T-9", List.of());
        assertApiStatus(() -> service.replaceTaskBlockers("INC-C", "T-9", "carol",
                new TaskBlockersReplaceRequest(key(), 1, List.of("INC-A"))), HttpStatus.CONFLICT);
    }

    @Test
    void replaceBlockers_idempotency() {
        commanding("INC-A", "alice");
        commanding("INC-B", "bob");
        commanding("INC-C", "carol");
        createTask("INC-A", "alice", "T-1", List.of());
        // 同键同操作者同参重放首次结果：不再次替换，版本与历史不变
        String commandKey = key();
        TaskView first = service.replaceTaskBlockers("INC-A", "T-1", "alice",
                new TaskBlockersReplaceRequest(commandKey, 1, List.of("INC-B")));
        TaskView replay = service.replaceTaskBlockers("INC-A", "T-1", "alice",
                new TaskBlockersReplaceRequest(commandKey, 1, List.of("INC-B")));
        assertThat(replay).isEqualTo(first);
        assertThat(service.getTask("INC-A", "T-1").version()).isEqualTo(2);
        assertThat(service.taskRevisions("INC-A", "T-1").revisions()).hasSize(1);
        // 同键异参 409（含不同操作者）
        assertApiStatus(() -> service.replaceTaskBlockers("INC-A", "T-1", "alice",
                new TaskBlockersReplaceRequest(commandKey, 1, List.of("INC-C"))),
                HttpStatus.CONFLICT);
        assertApiStatus(() -> service.replaceTaskBlockers("INC-A", "T-1", "bob",
                new TaskBlockersReplaceRequest(commandKey, 1, List.of("INC-B"))),
                HttpStatus.CONFLICT);
        assertThat(service.getTask("INC-A", "T-1").version()).isEqualTo(2);
        // 失败不占键：版本冲突失败后同键可成功
        String failKey = key();
        assertApiStatus(() -> service.replaceTaskBlockers("INC-A", "T-1", "alice",
                new TaskBlockersReplaceRequest(failKey, 1, List.of("INC-C"))),
                HttpStatus.CONFLICT);
        TaskView ok = service.replaceTaskBlockers("INC-A", "T-1", "alice",
                new TaskBlockersReplaceRequest(failKey, 2, List.of("INC-C")));
        assertThat(ok.version()).isEqualTo(3);
    }

    @Test
    void completeTask_usesLatestDependencies() {
        commanding("INC-A", "alice");
        commanding("INC-B", "bob");
        createTask("INC-A", "alice", "T-1", List.of("INC-B"));
        // 未解除阻塞仍 409
        assertApiStatus(() -> service.completeTask("INC-A", "T-1", "alice",
                new TaskActionRequest(key())), HttpStatus.CONFLICT);
        // 替换为空依赖后可完成（完成依据同一一致状态中的最新依赖）
        service.replaceTaskBlockers("INC-A", "T-1", "alice",
                new TaskBlockersReplaceRequest(key(), 1, List.of()));
        TaskView done = service.completeTask("INC-A", "T-1", "alice",
                new TaskActionRequest(key()));
        assertThat(done.status()).isEqualTo("DONE");

        // 反向：原无依赖，替换为未解除依赖后完成 409
        createTask("INC-A", "alice", "T-2", List.of());
        service.replaceTaskBlockers("INC-A", "T-2", "alice",
                new TaskBlockersReplaceRequest(key(), 1, List.of("INC-B")));
        assertThatThrownBy(() -> service.completeTask("INC-A", "T-2", "alice",
                new TaskActionRequest(key())))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat((List<Object>) e.details()).containsExactly("INC-B");
                });
    }

    @Test
    void replaceBlockers_doesNotBypassResolveGate() {
        commanding("INC-A", "alice");
        commanding("INC-B", "bob");
        createTask("INC-A", "alice", "T-1", List.of());
        service.changeStatus("INC-A", "alice", new StatusRequest(key(), "CONTAINED"));
        // 修订不改变任务 OPEN 状态，解决门禁仍然拦截
        service.replaceTaskBlockers("INC-A", "T-1", "alice",
                new TaskBlockersReplaceRequest(key(), 1, List.of("INC-B")));
        assertApiStatus(() -> service.changeStatus("INC-A", "alice",
                new StatusRequest(key(), "RESOLVED")), HttpStatus.CONFLICT);
        // 完成后可解决
        service.changeStatus("INC-B", "bob", new StatusRequest(key(), "CONTAINED"));
        service.completeTask("INC-A", "T-1", "alice", new TaskActionRequest(key()));
        IncidentView resolved = service.changeStatus("INC-A", "alice",
                new StatusRequest(key(), "RESOLVED"));
        assertThat(resolved.status()).isEqualTo("RESOLVED");
    }

    @Test
    void taskRevisions_notFound() {
        assertApiStatus(() -> service.taskRevisions("INC-404", "T-1"), HttpStatus.NOT_FOUND);
        commanding("INC-A", "alice");
        assertApiStatus(() -> service.taskRevisions("INC-A", "T-9"), HttpStatus.NOT_FOUND);
    }
}
