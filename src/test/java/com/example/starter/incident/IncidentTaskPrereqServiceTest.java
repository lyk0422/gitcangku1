package com.example.starter.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import com.example.starter.incident.dto.Requests.PrereqConfigureRequest;
import com.example.starter.incident.dto.Requests.PrereqMemberRequest;
import com.example.starter.incident.dto.Requests.ReportRequest;
import com.example.starter.incident.dto.Requests.StatusRequest;
import com.example.starter.incident.dto.Requests.TakeoverRequest;
import com.example.starter.incident.dto.Requests.TaskActionRequest;
import com.example.starter.incident.dto.Requests.TaskCreateRequest;
import com.example.starter.incident.dto.Requests.TransferAcceptRequest;
import com.example.starter.incident.dto.Requests.TransferRequest;
import com.example.starter.incident.dto.Responses.TaskView;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 择一任务前置组服务测试：覆盖题干合成场景（择一组 {A,B} + 原阻塞事件 C）、
 * 配置校验、版本递增与旧版本 409、任务级环 422、CANCELLED 不满足、
 * 完成快照冻结与字典序选中、requestId 幂等及权限先于重放。
 */
@SpringBootTest
class IncidentTaskPrereqServiceTest {

    @Autowired
    private IncidentService service;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM command_keys");
        jdbc.update("DELETE FROM task_prereq_snapshot");
        jdbc.update("DELETE FROM task_prereq_members");
        jdbc.update("DELETE FROM task_prereq_groups");
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

    private void commanding(String incidentKey, String commander) {
        service.report(new ReportRequest(incidentKey, "S2", "前置组场景", "reporter-1"));
        service.takeover(incidentKey, commander, new TakeoverRequest(key()));
    }

    private TaskView createTask(String incidentKey, String actor, String taskKey) {
        return service.createTask(incidentKey, actor,
                new TaskCreateRequest(key(), taskKey, "G", "任务" + taskKey, List.of()));
    }

    private static PrereqMemberRequest member(String incidentKey, String taskKey, int version) {
        return new PrereqMemberRequest(incidentKey, taskKey, version);
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
    void syntheticScenario_alternativeGroupPlusBlocker() {
        // 合成场景：T 受阻塞事件 C 约束，另有择一组 {A,B}（可跨事件成员）
        commanding("INC-T", "alice");
        commanding("INC-C", "carol");
        commanding("INC-M", "bob");
        service.createTask("INC-T", "alice",
                new TaskCreateRequest(key(), "T", "G", "合成任务", List.of("INC-C")));
        createTask("INC-M", "bob", "A");
        createTask("INC-M", "bob", "B");

        // 配置择一组 {A,B}：任务版本 1 → 2
        TaskView configured = service.configurePrerequisites("INC-T", "T", "alice",
                new PrereqConfigureRequest(key(), 1,
                        List.of(List.of(member("INC-M", "A", 1), member("INC-M", "B", 1)))));
        assertThat(configured.version()).isEqualTo(2);
        assertThat(configured.prerequisiteGroups()).hasSize(1);
        assertThat(configured.prerequisiteGroups().get(0).members())
                .extracting(m -> m.incidentKey() + "/" + m.taskKey())
                .containsExactly("INC-M/A", "INC-M/B");

        // A 完成但 C 未遏制：完成 T 仍被原阻塞事件拒绝（409，details 为未解除事件）
        service.completeTask("INC-M", "A", "bob", new TaskActionRequest(key()));
        assertThatThrownBy(() -> service.completeTask("INC-T", "T", "alice",
                new TaskActionRequest(key())))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(details(e)).containsExactly("INC-C");
                });

        // C 遏制后可完成；B 仍 OPEN 且不自动取消
        service.changeStatus("INC-C", "carol", new StatusRequest(key(), "CONTAINED"));
        TaskView done = service.completeTask("INC-T", "T", "alice",
                new TaskActionRequest(key()));
        assertThat(done.status()).isEqualTo("DONE");
        assertThat(done.version()).isEqualTo(3);
        assertThat(done.completionSnapshot()).hasSize(1);
        assertThat(done.completionSnapshot().get(0).selectedBasis()).isEqualTo("INC-M/A");
        assertThat(done.completionSnapshot().get(0).members())
                .extracting(m -> m.incidentKey() + "/" + m.taskKey() + "=" + m.selected())
                .containsExactly("INC-M/A=true", "INC-M/B=false");
        assertThat(service.getTask("INC-M", "B").status()).isEqualTo("OPEN");

        // 后续 B 完成不改历史快照
        service.completeTask("INC-M", "B", "bob", new TaskActionRequest(key()));
        TaskView after = service.getTask("INC-T", "T");
        assertThat(after.completionSnapshot().get(0).selectedBasis()).isEqualTo("INC-M/A");
        assertThat(after.completionSnapshot().get(0).members())
                .extracting(m -> m.incidentKey() + "/" + m.taskKey() + "=" + m.selected())
                .containsExactly("INC-M/A=true", "INC-M/B=false");
    }

    @Test
    void configure_validation() {
        commanding("INC-V", "alice");
        createTask("INC-V", "alice", "T");
        createTask("INC-V", "alice", "A");
        createTask("INC-V", "alice", "B");
        // requestId / expectedTaskVersion 必填
        assertApiStatus(() -> service.configurePrerequisites("INC-V", "T", "alice",
                new PrereqConfigureRequest(null, 1, List.of(List.of(
                        member("INC-V", "A", 1), member("INC-V", "B", 1))))),
                HttpStatus.BAD_REQUEST);
        assertApiStatus(() -> service.configurePrerequisites("INC-V", "T", "alice",
                new PrereqConfigureRequest(key(), null, List.of(List.of(
                        member("INC-V", "A", 1), member("INC-V", "B", 1))))),
                HttpStatus.BAD_REQUEST);
        // 组数 1~3：0 组、4 组均 400
        assertApiStatus(() -> service.configurePrerequisites("INC-V", "T", "alice",
                new PrereqConfigureRequest(key(), 1, List.of())), HttpStatus.BAD_REQUEST);
        assertApiStatus(() -> service.configurePrerequisites("INC-V", "T", "alice",
                new PrereqConfigureRequest(key(), 1, List.of(
                        List.of(member("INC-V", "A", 1), member("INC-V", "B", 1)),
                        List.of(member("INC-V", "A", 1), member("INC-V", "B", 1)),
                        List.of(member("INC-V", "A", 1), member("INC-V", "B", 1)),
                        List.of(member("INC-V", "A", 1), member("INC-V", "B", 1))))),
                HttpStatus.BAD_REQUEST);
        // 每组 2~5：单成员 400
        assertApiStatus(() -> service.configurePrerequisites("INC-V", "T", "alice",
                new PrereqConfigureRequest(key(), 1,
                        List.of(List.of(member("INC-V", "A", 1))))),
                HttpStatus.BAD_REQUEST);
        // 组内重复成员 400
        assertApiStatus(() -> service.configurePrerequisites("INC-V", "T", "alice",
                new PrereqConfigureRequest(key(), 1, List.of(List.of(
                        member("INC-V", "A", 1), member("INC-V", "A", 1))))),
                HttpStatus.BAD_REQUEST);
        // 成员期望版本必填
        assertApiStatus(() -> service.configurePrerequisites("INC-V", "T", "alice",
                new PrereqConfigureRequest(key(), 1, List.of(List.of(
                        new PrereqMemberRequest("INC-V", "A", null),
                        member("INC-V", "B", 1))))),
                HttpStatus.BAD_REQUEST);
        // 成员事件/任务不存在 404
        assertApiStatus(() -> service.configurePrerequisites("INC-V", "T", "alice",
                new PrereqConfigureRequest(key(), 1, List.of(List.of(
                        member("INC-404", "A", 1), member("INC-V", "B", 1))))),
                HttpStatus.NOT_FOUND);
        assertApiStatus(() -> service.configurePrerequisites("INC-V", "T", "alice",
                new PrereqConfigureRequest(key(), 1, List.of(List.of(
                        member("INC-V", "Z", 1), member("INC-V", "B", 1))))),
                HttpStatus.NOT_FOUND);
    }

    @Test
    void configure_versionAndStateGuards() {
        commanding("INC-G", "alice");
        createTask("INC-G", "alice", "T");
        createTask("INC-G", "alice", "A");
        createTask("INC-G", "alice", "B");
        // 旧版本 409
        assertApiStatus(() -> service.configurePrerequisites("INC-G", "T", "alice",
                new PrereqConfigureRequest(key(), 9, List.of(List.of(
                        member("INC-G", "A", 1), member("INC-G", "B", 1))))),
                HttpStatus.CONFLICT);
        // 成员期望版本与当前版本不一致 409
        assertApiStatus(() -> service.configurePrerequisites("INC-G", "T", "alice",
                new PrereqConfigureRequest(key(), 1, List.of(List.of(
                        member("INC-G", "A", 2), member("INC-G", "B", 1))))),
                HttpStatus.CONFLICT);
        // 非当前指挥人 409
        assertApiStatus(() -> service.configurePrerequisites("INC-G", "T", "bob",
                new PrereqConfigureRequest(key(), 1, List.of(List.of(
                        member("INC-G", "A", 1), member("INC-G", "B", 1))))),
                HttpStatus.CONFLICT);
        // 取消后的任务不能配置（终态）
        service.cancelTask("INC-G", "A", "alice", new TaskActionRequest(key()));
        assertApiStatus(() -> service.configurePrerequisites("INC-G", "A", "alice",
                new PrereqConfigureRequest(key(), 2, List.of(List.of(
                        member("INC-G", "B", 1), member("INC-G", "T", 1))))),
                HttpStatus.CONFLICT);
        // 已完成任务不能修改组
        service.configurePrerequisites("INC-G", "T", "alice",
                new PrereqConfigureRequest(key(), 1, List.of(List.of(
                        member("INC-G", "B", 1), member("INC-G", "A", 2)))));
        service.completeTask("INC-G", "B", "alice", new TaskActionRequest(key()));
        service.completeTask("INC-G", "T", "alice", new TaskActionRequest(key()));
        assertApiStatus(() -> service.configurePrerequisites("INC-G", "T", "alice",
                new PrereqConfigureRequest(key(), 3, List.of(List.of(
                        member("INC-G", "B", 2), member("INC-G", "A", 2))))),
                HttpStatus.CONFLICT);
    }

    @Test
    void configure_cycleRejected422_atomically() {
        commanding("INC-1", "alice");
        commanding("INC-2", "bob");
        createTask("INC-1", "alice", "X");
        createTask("INC-2", "bob", "Y");
        createTask("INC-2", "bob", "Z");
        // X 的前置组 {Y,Z}：X → Y、X → Z
        service.configurePrerequisites("INC-1", "X", "alice",
                new PrereqConfigureRequest(key(), 1, List.of(List.of(
                        member("INC-2", "Y", 1), member("INC-2", "Z", 1)))));
        // 再给 Y 配置含 X 的组会形成环：422 且不留边、版本不变
        assertApiStatus(() -> service.configurePrerequisites("INC-2", "Y", "bob",
                new PrereqConfigureRequest(key(), 1, List.of(List.of(
                        member("INC-1", "X", 2), member("INC-2", "Z", 1))))),
                HttpStatus.UNPROCESSABLE_ENTITY);
        TaskView y = service.getTask("INC-2", "Y");
        assertThat(y.version()).isEqualTo(1);
        assertThat(y.prerequisiteGroups()).isEmpty();
        // 自身作为成员同样是环：422
        assertApiStatus(() -> service.configurePrerequisites("INC-1", "X", "alice",
                new PrereqConfigureRequest(key(), 2, List.of(List.of(
                        member("INC-1", "X", 2), member("INC-2", "Z", 1))))),
                HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void complete_groupSatisfactionAndCancelledMembers() {
        commanding("INC-S", "alice");
        createTask("INC-S", "alice", "T");
        createTask("INC-S", "alice", "A");
        createTask("INC-S", "alice", "B");
        createTask("INC-S", "alice", "C");
        createTask("INC-S", "alice", "D");
        service.configurePrerequisites("INC-S", "T", "alice",
                new PrereqConfigureRequest(key(), 1, List.of(
                        List.of(member("INC-S", "A", 1), member("INC-S", "B", 1)),
                        List.of(member("INC-S", "C", 1), member("INC-S", "D", 1)))));
        // 两组均无 DONE 成员：409，details 为未满足组序号
        assertThatThrownBy(() -> service.completeTask("INC-S", "T", "alice",
                new TaskActionRequest(key())))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(details(e)).containsExactly(1, 2);
                });
        // 第一组满足后只剩第二组未满足
        service.completeTask("INC-S", "A", "alice", new TaskActionRequest(key()));
        assertThatThrownBy(() -> service.completeTask("INC-S", "T", "alice",
                new TaskActionRequest(key())))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(details(e)).containsExactly(2);
                });
        // CANCELLED 成员不满足；全部取消仍阻塞
        service.cancelTask("INC-S", "C", "alice", new TaskActionRequest(key()));
        service.cancelTask("INC-S", "D", "alice", new TaskActionRequest(key()));
        assertThatThrownBy(() -> service.completeTask("INC-S", "T", "alice",
                new TaskActionRequest(key())))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(details(e)).containsExactly(2);
                });
        // 重新配置为已 DONE 成员组成的新组后可完成（配置替换，版本递增）
        TaskView reconfigured = service.configurePrerequisites("INC-S", "T", "alice",
                new PrereqConfigureRequest(key(), 2, List.of(
                        List.of(member("INC-S", "A", 2), member("INC-S", "B", 1)))));
        assertThat(reconfigured.version()).isEqualTo(3);
        assertThat(reconfigured.prerequisiteGroups()).hasSize(1);
        TaskView done = service.completeTask("INC-S", "T", "alice",
                new TaskActionRequest(key()));
        assertThat(done.status()).isEqualTo("DONE");
        assertThat(done.version()).isEqualTo(4);
    }

    @Test
    void complete_snapshotSelectsLexicographicallySmallest() {
        commanding("INC-T9", "alice");
        commanding("INC-B", "bob");
        commanding("INC-A", "carol");
        createTask("INC-T9", "alice", "T");
        createTask("INC-B", "bob", "A");
        createTask("INC-A", "carol", "Z");
        service.configurePrerequisites("INC-T9", "T", "alice",
                new PrereqConfigureRequest(key(), 1, List.of(List.of(
                        member("INC-B", "A", 1), member("INC-A", "Z", 1)))));
        // 两个候选均 DONE：选中完整任务标识字典序最小者 INC-A/Z
        service.completeTask("INC-B", "A", "bob", new TaskActionRequest(key()));
        service.completeTask("INC-A", "Z", "carol", new TaskActionRequest(key()));
        TaskView done = service.completeTask("INC-T9", "T", "alice",
                new TaskActionRequest(key()));
        assertThat(done.completionSnapshot().get(0).selectedBasis()).isEqualTo("INC-A/Z");
    }

    @Test
    void configure_requestIdIdempotency() {
        commanding("INC-I", "alice");
        createTask("INC-I", "alice", "T");
        createTask("INC-I", "alice", "A");
        createTask("INC-I", "alice", "B");
        String requestId = key();
        TaskView first = service.configurePrerequisites("INC-I", "T", "alice",
                new PrereqConfigureRequest(requestId, 1, List.of(List.of(
                        member("INC-I", "A", 1), member("INC-I", "B", 1)))));
        // 同键同参（含组内换序）重放首次完整响应，版本不重复递增
        TaskView replay = service.configurePrerequisites("INC-I", "T", "alice",
                new PrereqConfigureRequest(requestId, 1, List.of(List.of(
                        member("INC-I", "B", 1), member("INC-I", "A", 1)))));
        assertThat(replay).isEqualTo(first);
        assertThat(service.getTask("INC-I", "T").version()).isEqualTo(2);
        // 同键异参 409
        assertApiStatus(() -> service.configurePrerequisites("INC-I", "T", "alice",
                new PrereqConfigureRequest(requestId, 2, List.of(List.of(
                        member("INC-I", "A", 1), member("INC-I", "B", 1))))),
                HttpStatus.CONFLICT);
        // 失败不占键：版本不符 409 后同键可成功复用
        String failedKey = key();
        assertApiStatus(() -> service.configurePrerequisites("INC-I", "T", "alice",
                new PrereqConfigureRequest(failedKey, 9, List.of(List.of(
                        member("INC-I", "A", 1), member("INC-I", "B", 1))))),
                HttpStatus.CONFLICT);
        TaskView reused = service.configurePrerequisites("INC-I", "T", "alice",
                new PrereqConfigureRequest(failedKey, 2, List.of(List.of(
                        member("INC-I", "A", 1), member("INC-I", "B", 1)))));
        assertThat(reused.version()).isEqualTo(3);
    }

    @Test
    void configure_permissionCheckedBeforeReplay() {
        commanding("INC-P", "alice");
        createTask("INC-P", "alice", "T");
        createTask("INC-P", "alice", "A");
        createTask("INC-P", "alice", "B");
        String requestId = key();
        service.configurePrerequisites("INC-P", "T", "alice",
                new PrereqConfigureRequest(requestId, 1, List.of(List.of(
                        member("INC-P", "A", 1), member("INC-P", "B", 1)))));
        // 交接后旧指挥无权写入：同键同参也先被权限拒绝（权限检查先于重放）
        service.initiateTransfer("INC-P", "alice", new TransferRequest(key(), "bob"));
        service.acceptTransfer("INC-P", "bob", new TransferAcceptRequest(key()));
        assertApiStatus(() -> service.configurePrerequisites("INC-P", "T", "alice",
                new PrereqConfigureRequest(requestId, 1, List.of(List.of(
                        member("INC-P", "A", 1), member("INC-P", "B", 1))))),
                HttpStatus.CONFLICT);
        // 新指挥身份不同（身份含操作者），同键按异参 409
        assertApiStatus(() -> service.configurePrerequisites("INC-P", "T", "bob",
                new PrereqConfigureRequest(requestId, 1, List.of(List.of(
                        member("INC-P", "A", 1), member("INC-P", "B", 1))))),
                HttpStatus.CONFLICT);
    }

    @Test
    void completeAndCancel_expectedVersionChecked() {
        commanding("INC-E", "alice");
        createTask("INC-E", "alice", "T");
        createTask("INC-E", "alice", "U");
        // 完成：旧版本 409，匹配版本成功且版本 +1
        assertApiStatus(() -> service.completeTask("INC-E", "T", "alice",
                new TaskActionRequest(key(), 2)), HttpStatus.CONFLICT);
        TaskView done = service.completeTask("INC-E", "T", "alice",
                new TaskActionRequest(key(), 1));
        assertThat(done.version()).isEqualTo(2);
        // 取消：旧版本 409，匹配版本成功且版本 +1
        assertApiStatus(() -> service.cancelTask("INC-E", "U", "alice",
                new TaskActionRequest(key(), 3)), HttpStatus.CONFLICT);
        TaskView cancelled = service.cancelTask("INC-E", "U", "alice",
                new TaskActionRequest(key(), 1));
        assertThat(cancelled.version()).isEqualTo(2);
    }
}
