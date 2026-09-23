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
import com.example.starter.incident.dto.Requests.TaskDependencyReplaceRequest;
import com.example.starter.incident.dto.Responses.TaskRevisionView;
import com.example.starter.incident.dto.Responses.TaskRevisionsView;
import com.example.starter.incident.dto.Responses.TaskView;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * OPEN 任务阻塞列表整体替换的服务测试：覆盖版本递增（列表未变也加一）、
 * 不可变修订历史、空列表、400/404/409 错误语义、环检测回滚、终态禁修、
 * expectedTaskVersion 乐观并发、commandKey 幂等（重放不再次替换/失败不占键/异参 409）
 * 以及完成门禁按最新依赖判定、完成/取消同样使版本加一。
 */
@SpringBootTest
class TaskDependencyServiceTest {

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
        jdbc.update("DELETE FROM incident_task_dependency_revisions");
        jdbc.update("DELETE FROM incident_task_blockers");
        jdbc.update("DELETE FROM incident_tasks");
        jdbc.update("DELETE FROM incidents");
    }

    private static String key() {
        return "CMD-" + UUID.randomUUID();
    }

    private void commanding(String incidentKey, String commander) {
        service.report(new ReportRequest(incidentKey, "S2", "依赖修订场景", "reporter-1"));
        service.takeover(incidentKey, commander, new TakeoverRequest(key()));
    }

    private TaskView createTask(String incidentKey, String commander, String taskKey,
                                List<String> blockers) {
        return service.createTask(incidentKey, commander,
                new TaskCreateRequest(key(), taskKey, "DB", "任务" + taskKey, blockers));
    }

    private static void assertApiStatus(ThrowingCallable call, HttpStatus status) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.status()).isEqualTo(status));
    }

    @Test
    void replace_mainFlow_versionBumpsAndRevisionSaved() {
        commanding("INC-D1", "alice");
        commanding("INC-D2", "bob");
        TaskView created = createTask("INC-D1", "alice", "T-1", List.of());
        assertThat(created.version()).isEqualTo(1);

        // 空列表 → 单依赖：版本 2，修订记录前后版本与排序后依赖快照
        TaskView v2 = service.replaceTaskDependencies("INC-D1", "T-1", "alice",
                new TaskDependencyReplaceRequest(key(), 1, List.of("INC-D2")));
        assertThat(v2.version()).isEqualTo(2);
        assertThat(v2.status()).isEqualTo("OPEN");
        assertThat(v2.blockers()).extracting(b -> b.incidentKey()).containsExactly("INC-D2");

        TaskRevisionsView history = service.taskRevisions("INC-D1", "T-1");
        assertThat(history.currentVersion()).isEqualTo(2);
        assertThat(history.revisions()).hasSize(1);
        TaskRevisionView r1 = history.revisions().get(0);
        assertThat(r1.revisionNo()).isEqualTo(1);
        assertThat(r1.beforeVersion()).isEqualTo(1);
        assertThat(r1.afterVersion()).isEqualTo(2);
        assertThat(r1.dependencies()).containsExactly("INC-D2");
        assertThat(r1.actor()).isEqualTo("alice");
        assertThat(r1.occurredAt()).isNotNull();

        // 相同列表再次替换：版本仍加一，历史追加不可变记录
        TaskView v3 = service.replaceTaskDependencies("INC-D1", "T-1", "alice",
                new TaskDependencyReplaceRequest(key(), 2, List.of("INC-D2")));
        assertThat(v3.version()).isEqualTo(3);
        TaskRevisionsView history2 = service.taskRevisions("INC-D1", "T-1");
        assertThat(history2.revisions()).hasSize(2);
        assertThat(history2.revisions().get(0)).isEqualTo(r1);
        assertThat(history2.revisions().get(1).beforeVersion()).isEqualTo(2);
        assertThat(history2.revisions().get(1).afterVersion()).isEqualTo(3);
    }

    @Test
    void replace_emptyListClearsEdges() {
        commanding("INC-E1", "alice");
        commanding("INC-E2", "bob");
        createTask("INC-E1", "alice", "T-1", List.of("INC-E2"));
        TaskView cleared = service.replaceTaskDependencies("INC-E1", "T-1", "alice",
                new TaskDependencyReplaceRequest(key(), 1, List.of()));
        assertThat(cleared.version()).isEqualTo(2);
        assertThat(cleared.blockers()).isEmpty();
        Integer edgeCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM incident_task_blockers", Integer.class);
        assertThat(edgeCount).isZero();
        // null 列表等价于空列表
        TaskView again = service.replaceTaskDependencies("INC-E1", "T-1", "alice",
                new TaskDependencyReplaceRequest(key(), 2, null));
        assertThat(again.version()).isEqualTo(3);
        assertThat(again.blockers()).isEmpty();
    }

    @Test
    void replace_sortsDependenciesSnapshot() {
        commanding("INC-S1", "alice");
        commanding("INC-S2", "bob");
        commanding("INC-S3", "carol");
        createTask("INC-S1", "alice", "T-1", List.of());
        TaskView view = service.replaceTaskDependencies("INC-S1", "T-1", "alice",
                new TaskDependencyReplaceRequest(key(), 1, List.of("INC-S3", "INC-S2")));
        assertThat(view.blockers()).extracting(b -> b.incidentKey())
                .containsExactly("INC-S2", "INC-S3");
        assertThat(service.taskRevisions("INC-S1", "T-1").revisions().get(0).dependencies())
                .containsExactly("INC-S2", "INC-S3");
    }

    @Test
    void replace_validation() {
        commanding("INC-V1", "alice");
        createTask("INC-V1", "alice", "T-1", List.of());
        // expectedTaskVersion 缺失或非法 → 400
        assertApiStatus(() -> service.replaceTaskDependencies("INC-V1", "T-1", "alice",
                new TaskDependencyReplaceRequest(key(), null, List.of())), HttpStatus.BAD_REQUEST);
        assertApiStatus(() -> service.replaceTaskDependencies("INC-V1", "T-1", "alice",
                new TaskDependencyReplaceRequest(key(), 0, List.of())), HttpStatus.BAD_REQUEST);
        // commandKey 缺失 → 400
        assertApiStatus(() -> service.replaceTaskDependencies("INC-V1", "T-1", "alice",
                new TaskDependencyReplaceRequest(null, 1, List.of())), HttpStatus.BAD_REQUEST);
        // 重复目标 → 400
        assertApiStatus(() -> service.replaceTaskDependencies("INC-V1", "T-1", "alice",
                new TaskDependencyReplaceRequest(key(), 1, List.of("INC-X", "INC-X"))),
                HttpStatus.BAD_REQUEST);
        // 自身依赖 → 400
        assertApiStatus(() -> service.replaceTaskDependencies("INC-V1", "T-1", "alice",
                new TaskDependencyReplaceRequest(key(), 1, List.of("INC-V1"))),
                HttpStatus.BAD_REQUEST);
        // 超过 5 个 → 400
        assertApiStatus(() -> service.replaceTaskDependencies("INC-V1", "T-1", "alice",
                new TaskDependencyReplaceRequest(key(), 1,
                        List.of("B1", "B2", "B3", "B4", "B5", "B6"))), HttpStatus.BAD_REQUEST);
        // 目标事件不存在 → 404
        assertApiStatus(() -> service.replaceTaskDependencies("INC-V1", "T-1", "alice",
                new TaskDependencyReplaceRequest(key(), 1, List.of("INC-404"))),
                HttpStatus.NOT_FOUND);
        // 任务不存在 → 404
        assertApiStatus(() -> service.replaceTaskDependencies("INC-V1", "T-9", "alice",
                new TaskDependencyReplaceRequest(key(), 1, List.of())), HttpStatus.NOT_FOUND);
        // 事件不存在 → 404
        assertApiStatus(() -> service.replaceTaskDependencies("INC-404", "T-1", "alice",
                new TaskDependencyReplaceRequest(key(), 1, List.of())), HttpStatus.NOT_FOUND);
        // 非当前指挥人 → 409
        assertApiStatus(() -> service.replaceTaskDependencies("INC-V1", "T-1", "bob",
                new TaskDependencyReplaceRequest(key(), 1, List.of())), HttpStatus.CONFLICT);
    }

    @Test
    void replace_staleVersion_conflictAndNoChange() {
        commanding("INC-X1", "alice");
        commanding("INC-X2", "bob");
        createTask("INC-X1", "alice", "T-1", List.of());
        service.replaceTaskDependencies("INC-X1", "T-1", "alice",
                new TaskDependencyReplaceRequest(key(), 1, List.of("INC-X2")));
        // 旧版本号再次提交 → 409，版本/边/历史不变
        assertApiStatus(() -> service.replaceTaskDependencies("INC-X1", "T-1", "alice",
                new TaskDependencyReplaceRequest(key(), 1, List.of())), HttpStatus.CONFLICT);
        assertThat(service.getTask("INC-X1", "T-1").version()).isEqualTo(2);
        assertThat(service.taskRevisions("INC-X1", "T-1").revisions()).hasSize(1);
        Integer edgeCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM incident_task_blockers", Integer.class);
        assertThat(edgeCount).isEqualTo(1);
    }

    @Test
    void replace_directAndIndirectCycle_rolledBack() {
        commanding("INC-A", "alice");
        commanding("INC-B", "bob");
        commanding("INC-C", "carol");
        createTask("INC-A", "alice", "TA", List.of("INC-B"));
        createTask("INC-B", "bob", "TB", List.of("INC-C"));
        createTask("INC-C", "carol", "TC", List.of());

        // 直接环：C 已被 A、B 间接依赖（A→B→C），C→A 成环 → 409
        assertApiStatus(() -> service.replaceTaskDependencies("INC-C", "TC", "carol",
                new TaskDependencyReplaceRequest(key(), 1, List.of("INC-A"))),
                HttpStatus.CONFLICT);
        // 回滚：版本仍 1、无修订历史、边未变
        TaskView tc = service.getTask("INC-C", "TC");
        assertThat(tc.version()).isEqualTo(1);
        assertThat(tc.blockers()).isEmpty();
        assertThat(service.taskRevisions("INC-C", "TC").revisions()).isEmpty();

        // 同事件其他任务的边参与判定：A 事件内再建一个任务，
        // 其依赖 [B] 与已有 A→B 不冲突；但替换为会令 B→...→A 成环的目标仍拒绝
        assertApiStatus(() -> service.replaceTaskDependencies("INC-C", "TC", "carol",
                new TaskDependencyReplaceRequest(key(), 1, List.of("INC-B"))),
                HttpStatus.CONFLICT);
        assertThat(service.getTask("INC-C", "TC").version()).isEqualTo(1);

        // 终态任务的历史边仍参与判定：B 的任务取消后，C→A 依然成环
        service.cancelTask("INC-B", "TB", "bob", new TaskActionRequest(key()));
        assertApiStatus(() -> service.replaceTaskDependencies("INC-C", "TC", "carol",
                new TaskDependencyReplaceRequest(key(), 1, List.of("INC-A"))),
                HttpStatus.CONFLICT);
    }

    @Test
    void replace_terminalTask_forbidden() {
        commanding("INC-T1", "alice");
        createTask("INC-T1", "alice", "T-1", List.of());
        TaskView done = service.completeTask("INC-T1", "T-1", "alice",
                new TaskActionRequest(key()));
        // 既有完成首次成功使版本加一（1 → 2）
        assertThat(done.version()).isEqualTo(2);
        assertApiStatus(() -> service.replaceTaskDependencies("INC-T1", "T-1", "alice",
                new TaskDependencyReplaceRequest(key(), 2, List.of())), HttpStatus.CONFLICT);

        createTask("INC-T1", "alice", "T-2", List.of());
        TaskView cancelled = service.cancelTask("INC-T1", "T-2", "alice",
                new TaskActionRequest(key()));
        assertThat(cancelled.version()).isEqualTo(2);
        assertApiStatus(() -> service.replaceTaskDependencies("INC-T1", "T-2", "alice",
                new TaskDependencyReplaceRequest(key(), 2, List.of())), HttpStatus.CONFLICT);
        // 终态禁修不留修订
        assertThat(service.taskRevisions("INC-T1", "T-1").revisions()).isEmpty();
        assertThat(service.taskRevisions("INC-T1", "T-2").revisions()).isEmpty();
    }

    @Test
    void replace_commandKeyIdempotency() {
        commanding("INC-I1", "alice");
        commanding("INC-I2", "bob");
        createTask("INC-I1", "alice", "T-1", List.of());
        String commandKey = key();
        TaskView first = service.replaceTaskDependencies("INC-I1", "T-1", "alice",
                new TaskDependencyReplaceRequest(commandKey, 1, List.of("INC-I2")));
        // 同键同操作者同参重放：返回首次结果，不再次替换（版本停在 2、历史仍 1 条）
        TaskView replay = service.replaceTaskDependencies("INC-I1", "T-1", "alice",
                new TaskDependencyReplaceRequest(commandKey, 1, List.of("INC-I2")));
        assertThat(replay).isEqualTo(first);
        assertThat(service.getTask("INC-I1", "T-1").version()).isEqualTo(2);
        assertThat(service.taskRevisions("INC-I1", "T-1").revisions()).hasSize(1);
        // 同键异参 → 409（即使 expectedTaskVersion 已推进）
        assertApiStatus(() -> service.replaceTaskDependencies("INC-I1", "T-1", "alice",
                new TaskDependencyReplaceRequest(commandKey, 2, List.of())), HttpStatus.CONFLICT);
        // 同键跨操作 → 409
        assertApiStatus(() -> service.completeTask("INC-I1", "T-1", "alice",
                new TaskActionRequest(commandKey)), HttpStatus.CONFLICT);
    }

    @Test
    void replace_failureDoesNotConsumeCommandKey() {
        commanding("INC-F1", "alice");
        createTask("INC-F1", "alice", "T-1", List.of());
        String commandKey = key();
        // 业务失败（目标不存在）回滚不占键：换参数后同键成功
        assertApiStatus(() -> service.replaceTaskDependencies("INC-F1", "T-1", "alice",
                new TaskDependencyReplaceRequest(commandKey, 1, List.of("INC-404"))),
                HttpStatus.NOT_FOUND);
        TaskView ok = service.replaceTaskDependencies("INC-F1", "T-1", "alice",
                new TaskDependencyReplaceRequest(commandKey, 1, List.of()));
        assertThat(ok.version()).isEqualTo(2);
    }

    @Test
    void complete_usesLatestDependencies() {
        commanding("INC-U1", "alice");
        commanding("INC-U2", "bob");
        createTask("INC-U1", "alice", "T-1", List.of());
        // 修订为依赖仍在处置中的 INC-U2 后，完成被门禁拦截
        service.replaceTaskDependencies("INC-U1", "T-1", "alice",
                new TaskDependencyReplaceRequest(key(), 1, List.of("INC-U2")));
        assertThatThrownBy(() -> service.completeTask("INC-U1", "T-1", "alice",
                new TaskActionRequest(key())))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.details()).isEqualTo(List.of("INC-U2"));
                });
        // 阻塞事件遏制后按最新依赖完成成功；版本在修订（2）之上再加一
        service.changeStatus("INC-U2", "bob", new StatusRequest(key(), "CONTAINED"));
        TaskView done = service.completeTask("INC-U1", "T-1", "alice",
                new TaskActionRequest(key()));
        assertThat(done.status()).isEqualTo("DONE");
        assertThat(done.version()).isEqualTo(3);
    }

    @Test
    void revisionsQuery_notFoundAndReadOnly() {
        assertApiStatus(() -> service.taskRevisions("INC-404", "T-1"), HttpStatus.NOT_FOUND);
        commanding("INC-Q1", "alice");
        createTask("INC-Q1", "alice", "T-1", List.of());
        assertApiStatus(() -> service.taskRevisions("INC-Q1", "T-9"), HttpStatus.NOT_FOUND);
        TaskRevisionsView empty = service.taskRevisions("INC-Q1", "T-1");
        assertThat(empty.revisions()).isEmpty();
        assertThat(empty.currentVersion()).isEqualTo(1);
        // 普通查询不改变数据：再查一次一致
        assertThat(service.taskRevisions("INC-Q1", "T-1")).isEqualTo(empty);
    }
}
