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
import com.example.starter.incident.dto.Responses.BlockedIncidentView;
import com.example.starter.incident.dto.Responses.TaskGroupView;
import com.example.starter.incident.dto.Responses.TaskView;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 分组处置任务服务测试：覆盖创建/完成/取消主流程、跨事件阻塞解除、环检测、
 * 指挥权变更、解决门禁与 commandKey 幂等/失败不占键。
 */
@SpringBootTest
class TaskServiceTest {

    @Autowired
    private TaskService taskService;

    @Autowired
    private IncidentService incidentService;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM command_keys");
        jdbc.update("DELETE FROM incident_task_blocks");
        jdbc.update("DELETE FROM incident_tasks");
        jdbc.update("DELETE FROM incident_status_history");
        jdbc.update("DELETE FROM incident_transfers");
        jdbc.update("DELETE FROM incident_actions");
        jdbc.update("DELETE FROM incident_escalations");
        jdbc.update("DELETE FROM incidents");
    }

    private static String key() {
        return "CMD-" + UUID.randomUUID();
    }

    private String reportAndTakeover(String incidentKey, String commander) {
        incidentService.report(new ReportRequest(incidentKey, "S2", "事件 " + incidentKey, "reporter"));
        incidentService.takeover(incidentKey, commander, new TakeoverRequest(key()));
        return incidentKey;
    }

    private static void assertApiStatus(ThrowingCallable call, HttpStatus status) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.status()).isEqualTo(status));
    }

    @Test
    void createTask_andQuery_detailAndGroup() {
        String a = reportAndTakeover("INC-A", "alice");
        String b = reportAndTakeover("INC-B", "bob");

        TaskView created = taskService.createTask(a, "alice", new TaskCreateRequest(key(), "T1",
                "G1", "等待 B 恢复", List.of(b)));
        assertThat(created.status()).isEqualTo("OPEN");
        assertThat(created.groupCode()).isEqualTo("G1");
        assertThat(created.blockedIncidents()).hasSize(1);
        BlockedIncidentView block = created.blockedIncidents().get(0);
        assertThat(block.incidentKey()).isEqualTo(b);
        assertThat(block.status()).isEqualTo("COMMANDING");
        assertThat(block.lifted()).isFalse();
        assertThat(created.completable()).isFalse();

        TaskView detail = taskService.getTask(a, "T1");
        assertThat(detail.title()).isEqualTo("等待 B 恢复");
        assertThat(detail.blockedIncidents().get(0).lifted()).isFalse();

        List<TaskGroupView> groups = taskService.listTasks(a);
        assertThat(groups).hasSize(1);
        assertThat(groups.get(0).groupCode()).isEqualTo("G1");
        assertThat(groups.get(0).tasks()).hasSize(1);
    }

    @Test
    void createTask_withoutBlocks_completableAndCompletableDirectly() {
        String a = reportAndTakeover("INC-A", "alice");
        TaskView created = taskService.createTask(a, "alice",
                new TaskCreateRequest(key(), "T1", "G1", "无依赖任务", null));
        assertThat(created.blockedIncidents()).isEmpty();
        assertThat(created.completable()).isTrue();

        TaskView done = taskService.completeTask(a, "T1", "alice", new TaskActionRequest(key()));
        assertThat(done.status()).isEqualTo("DONE");
        assertThat(done.completedAt()).isNotNull();
    }

    @Test
    void createTask_rejectsNonCommander_missingTarget_selfBlock() {
        String a = reportAndTakeover("INC-A", "alice");
        String b = reportAndTakeover("INC-B", "bob");

        assertApiStatus(() -> taskService.createTask(a, "bob",
                new TaskCreateRequest(key(), "T1", "G1", "非指挥人", List.of())), HttpStatus.CONFLICT);

        assertApiStatus(() -> taskService.createTask(a, "alice",
                new TaskCreateRequest(key(), "T1", "G1", "目标不存在", List.of("INC-NOPE"))),
                HttpStatus.NOT_FOUND);

        assertApiStatus(() -> taskService.createTask(a, "alice",
                new TaskCreateRequest(key(), "T1", "G1", "自身依赖", List.of(a))),
                HttpStatus.BAD_REQUEST);

        assertApiStatus(() -> taskService.createTask(a, "alice",
                new TaskCreateRequest(key(), "T1", " ", "空分组", List.of())), HttpStatus.BAD_REQUEST);

        assertApiStatus(() -> taskService.createTask(a, "alice",
                new TaskCreateRequest(key(), "T1", "G1", "重复目标", List.of(b, b))),
                HttpStatus.BAD_REQUEST);

        assertApiStatus(() -> taskService.createTask(a, "alice",
                new TaskCreateRequest(key(), "T1", "G1", "六个阻塞",
                        List.of(b, "X2", "X3", "X4", "X5", "X6"))), HttpStatus.BAD_REQUEST);

        // 全部失败后不允许留下任务
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM incident_tasks", Integer.class)).isZero();
    }

    @Test
    void createTask_duplicateTaskKeyAndTwentyLimit() {
        String a = reportAndTakeover("INC-A", "alice");
        taskService.createTask(a, "alice",
                new TaskCreateRequest(key(), "DUP", "G1", "首个", List.of()));
        assertApiStatus(() -> taskService.createTask(a, "alice",
                new TaskCreateRequest(key(), "DUP", "G2", "重复键", List.of())), HttpStatus.CONFLICT);

        for (int i = 1; i <= 19; i++) {
            taskService.createTask(a, "alice",
                    new TaskCreateRequest(key(), "T" + i, "G", "任务" + i, List.of()));
        }
        // 已有 20 个（DUP + T1..T19）
        assertApiStatus(() -> taskService.createTask(a, "alice",
                new TaskCreateRequest(key(), "TOO-MANY", "G", "第 21 个", List.of())), HttpStatus.CONFLICT);
        assertThat(taskService.listTasks(a).stream().mapToInt(g -> g.tasks().size()).sum())
                .isEqualTo(20);
    }

    @Test
    void directAndIndirectCycle_rejectedWithoutPartialWrites() {
        String a = reportAndTakeover("INC-A", "alice");
        String b = reportAndTakeover("INC-B", "bob");
        String c = reportAndTakeover("INC-C", "carol");

        // A 依赖 B、C：建立 A -> B, A -> C
        taskService.createTask(a, "alice", new TaskCreateRequest(key(), "A1", "G", "A 等 B、C",
                List.of(b, c)));

        // 直接环：B -> A
        assertApiStatus(() -> taskService.createTask(b, "bob",
                new TaskCreateRequest(key(), "B1", "G", "B 等 A", List.of(a))), HttpStatus.CONFLICT);

        // 间接环：B -> C 合法（先建立），随后 C -> A 形成 A->C->...->A
        taskService.createTask(b, "bob",
                new TaskCreateRequest(key(), "B2", "G", "B 等 C", List.of(c)));
        assertApiStatus(() -> taskService.createTask(c, "carol",
                new TaskCreateRequest(key(), "C1", "G", "C 等 A", List.of(a))), HttpStatus.CONFLICT);

        // 环被拒绝后没有部分任务或边落库（B1、C1 均不存在，边仍为 3 条）
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM incident_tasks", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM incident_task_blocks", Integer.class))
                .isEqualTo(3);
        assertThat(taskService.getTask(b, "B2").blockedIncidents()).hasSize(1);
    }

    @Test
    void completeBlocked_untilAllTargetsContained_conflictListsUnlifted() {
        String a = reportAndTakeover("INC-A", "alice");
        String b = reportAndTakeover("INC-B", "bob");
        String c = reportAndTakeover("INC-C", "carol");
        taskService.createTask(a, "alice",
                new TaskCreateRequest(key(), "A1", "G", "等 B、C", List.of(b, c)));

        ApiException early = catchApi(() -> taskService.completeTask(a, "A1", "alice",
                new TaskActionRequest(key())));
        assertThat(early.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(early.details()).isEqualTo(List.of(b, c));

        incidentService.changeStatus(b, "bob", new StatusRequest(key(), "CONTAINED"));
        // 仅 B 解除：仍返回 C
        assertThat(taskService.getTask(a, "A1").completable()).isFalse();
        ApiException half = catchApi(() -> taskService.completeTask(a, "A1", "alice",
                new TaskActionRequest(key())));
        assertThat(half.details()).isEqualTo(List.of(c));

        incidentService.changeStatus(c, "carol", new StatusRequest(key(), "CONTAINED"));
        TaskView done = taskService.completeTask(a, "A1", "alice", new TaskActionRequest(key()));
        assertThat(done.status()).isEqualTo("DONE");
        // 解除不写回：边仍在，但查询实时为 lifted
        assertThat(taskService.getTask(a, "A1").blockedIncidents())
                .allSatisfy(v -> assertThat(v.lifted()).isTrue());
    }

    @Test
    void resolvedTargetAlsoLifts_andTaskQueriesDoNotWrite() {
        String a = reportAndTakeover("INC-A", "alice");
        String b = reportAndTakeover("INC-B", "bob");
        taskService.createTask(a, "alice",
                new TaskCreateRequest(key(), "A1", "G", "等 B", List.of(b)));

        Integer before = jdbc.queryForObject("SELECT COUNT(*) FROM incident_status_history",
                Integer.class);
        taskService.listTasks(a);
        taskService.getTask(a, "A1");
        Integer after = jdbc.queryForObject("SELECT COUNT(*) FROM incident_status_history",
                Integer.class);
        assertThat(after).isEqualTo(before);

        // B 无任务门禁，可直接一路 CONTAINED -> RESOLVED -> CLOSED，两种状态都算解除
        incidentService.changeStatus(b, "bob", new StatusRequest(key(), "CONTAINED"));
        incidentService.changeStatus(b, "bob", new StatusRequest(key(), "RESOLVED"));
        incidentService.changeStatus(b, "bob", new StatusRequest(key(), "CLOSED"));
        assertThat(taskService.getTask(a, "A1").blockedIncidents().get(0).status())
                .isEqualTo("CLOSED");
        assertThat(taskService.completeTask(a, "A1", "alice", new TaskActionRequest(key())).status())
                .isEqualTo("DONE");
    }

    @Test
    void commanderChange_takesAwayTaskPermissions() {
        String a = reportAndTakeover("INC-A", "alice");
        taskService.createTask(a, "alice",
                new TaskCreateRequest(key(), "A1", "G", "待办", List.of()));

        incidentService.initiateTransfer(a, "alice", new TransferRequest(key(), "bob"));
        // 待接受期间旧指挥人也不能操作（其看到的快照仍为 alice，但接受后立即失效——此处验证交接完成后）
        incidentService.acceptTransfer(a, "bob", new TransferAcceptRequest(key()));

        assertApiStatus(() -> taskService.completeTask(a, "A1", "alice",
                new TaskActionRequest(key())), HttpStatus.CONFLICT);
        assertApiStatus(() -> taskService.cancelTask(a, "A1", "alice",
                new TaskActionRequest(key())), HttpStatus.CONFLICT);
        assertApiStatus(() -> taskService.createTask(a, "alice",
                new TaskCreateRequest(key(), "A2", "G", "旧指挥人创建", List.of())),
                HttpStatus.CONFLICT);

        assertThat(taskService.completeTask(a, "A1", "bob", new TaskActionRequest(key())).status())
                .isEqualTo("DONE");
        assertThat(incidentService.get(a).commander()).isEqualTo("bob");
    }

    @Test
    void resolveGate_returnsOpenTasksByGroupAndKey() {
        String a = reportAndTakeover("INC-A", "alice");
        taskService.createTask(a, "alice",
                new TaskCreateRequest(key(), "T1", "G2", "完成项", List.of()));
        taskService.createTask(a, "alice",
                new TaskCreateRequest(key(), "T2", "G1", "取消项", List.of()));
        taskService.createTask(a, "alice",
                new TaskCreateRequest(key(), "T3", "G1", "待办项", List.of()));
        taskService.completeTask(a, "T1", "alice", new TaskActionRequest(key()));
        taskService.cancelTask(a, "T2", "alice", new TaskActionRequest(key()));

        // 先遏制；仍有 OPEN（G1/T3），解决被门禁拒绝
        incidentService.changeStatus(a, "alice", new StatusRequest(key(), "CONTAINED"));
        ApiException blocked = catchApi(() -> incidentService.changeStatus(a, "alice",
                new StatusRequest(key(), "RESOLVED")));
        assertThat(blocked.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(blocked.details()).isEqualTo(List.of(
                java.util.Map.of("groupCode", "G1", "taskKey", "T3")));

        // 取消最后一个 OPEN 后门禁放行，随后可关闭
        taskService.cancelTask(a, "T3", "alice", new TaskActionRequest(key()));
        assertThat(incidentService.changeStatus(a, "alice",
                new StatusRequest(key(), "RESOLVED")).status()).isEqualTo("RESOLVED");
        assertThat(incidentService.changeStatus(a, "alice",
                new StatusRequest(key(), "CLOSED")).status()).isEqualTo("CLOSED");

        // CLOSED 后不能再创建任务
        assertApiStatus(() -> taskService.createTask(a, "alice",
                new TaskCreateRequest(key(), "T4", "G", "关闭后", List.of())),
                HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void terminalTransitions_duplicatesAndCrossOps() {
        String a = reportAndTakeover("INC-A", "alice");
        taskService.createTask(a, "alice",
                new TaskCreateRequest(key(), "D1", "G", "将完成", List.of()));
        taskService.createTask(a, "alice",
                new TaskCreateRequest(key(), "C1", "G", "将取消", List.of()));

        String completeKey = key();
        TaskView done = taskService.completeTask(a, "D1", "alice", new TaskActionRequest(completeKey));
        // 同 commandKey 同参重放首次结果
        TaskView replayed = taskService.completeTask(a, "D1", "alice",
                new TaskActionRequest(completeKey));
        assertThat(replayed).isEqualTo(done);

        // 新键对已完成任务再次完成：终态同类操作仍返回首次结果（DONE）
        assertThat(taskService.completeTask(a, "D1", "alice", new TaskActionRequest(key())).status())
                .isEqualTo("DONE");
        // 对 DONE 取消：409
        assertApiStatus(() -> taskService.cancelTask(a, "D1", "alice",
                new TaskActionRequest(key())), HttpStatus.CONFLICT);

        String cancelKey = key();
        TaskView cancelled = taskService.cancelTask(a, "C1", "alice",
                new TaskActionRequest(cancelKey));
        assertThat(cancelled.status()).isEqualTo("CANCELLED");
        assertThat(cancelled.cancelledAt()).isNotNull();
        assertThat(taskService.cancelTask(a, "C1", "alice",
                new TaskActionRequest(cancelKey)).status()).isEqualTo("CANCELLED");
        // 对 CANCELLED 完成：409
        assertApiStatus(() -> taskService.completeTask(a, "C1", "alice",
                new TaskActionRequest(key())), HttpStatus.CONFLICT);
    }

    @Test
    void commandKey_differentParamsConflict_andFailureDoesNotOccupyKey() {
        String a = reportAndTakeover("INC-A", "alice");
        String b = reportAndTakeover("INC-B", "bob");
        String shared = key();
        taskService.createTask(a, "alice",
                new TaskCreateRequest(shared, "T1", "G", "首次", List.of()));
        // 同键改参（标题不同）：409
        assertApiStatus(() -> taskService.createTask(a, "alice",
                new TaskCreateRequest(shared, "T1", "G", "改参", List.of())), HttpStatus.CONFLICT);

        // 失败不占键：用一个新键先做必然失败的环请求，再用同一键发起合法请求应成功
        String recycled = key();
        taskService.createTask(a, "alice",
                new TaskCreateRequest(key(), "A2", "G", "A 依赖 B", List.of(b)));
        assertApiStatus(() -> taskService.createTask(b, "bob",
                new TaskCreateRequest(recycled, "B9", "G", "成环", List.of(a))), HttpStatus.CONFLICT);
        TaskView ok = taskService.createTask(b, "bob",
                new TaskCreateRequest(recycled, "B9", "G", "不再成环", List.of()));
        assertThat(ok.taskKey()).isEqualTo("B9");
        Integer keyRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM command_keys WHERE command_key = ?", Integer.class, recycled);
        assertThat(keyRows).isEqualTo(1);
    }

    @Test
    void taskAndBlock_notFoundSemantics() {
        String a = reportAndTakeover("INC-A", "alice");
        assertApiStatus(() -> taskService.listTasks("INC-MISSING"), HttpStatus.NOT_FOUND);
        assertApiStatus(() -> taskService.getTask(a, "NOPE"), HttpStatus.NOT_FOUND);
        assertApiStatus(() -> taskService.completeTask(a, "NOPE", "alice",
                new TaskActionRequest(key())), HttpStatus.NOT_FOUND);
        assertApiStatus(() -> taskService.cancelTask(a, "NOPE", "alice",
                new TaskActionRequest(key())), HttpStatus.NOT_FOUND);
    }

    private static ApiException catchApi(ThrowingCallable call) {
        try {
            call.call();
        } catch (ApiException e) {
            return e;
        } catch (Throwable t) {
            throw new AssertionError("expected ApiException but got " + t, t);
        }
        throw new AssertionError("expected ApiException but nothing was thrown");
    }
}
