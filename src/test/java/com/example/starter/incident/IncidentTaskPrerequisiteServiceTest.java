package com.example.starter.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import com.example.starter.incident.dto.Requests.ConfigureGroupsRequest;
import com.example.starter.incident.dto.Requests.GroupSpec;
import com.example.starter.incident.dto.Requests.MemberSpec;
import com.example.starter.incident.dto.Requests.ReportRequest;
import com.example.starter.incident.dto.Requests.StatusRequest;
import com.example.starter.incident.dto.Requests.TakeoverRequest;
import com.example.starter.incident.dto.Requests.TaskActionRequest;
import com.example.starter.incident.dto.Requests.TaskCreateRequest;
import com.example.starter.incident.dto.Requests.TransferAcceptRequest;
import com.example.starter.incident.dto.Requests.TransferRequest;
import com.example.starter.incident.dto.Responses.PrerequisiteGroupView;
import com.example.starter.incident.dto.Responses.TaskView;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 择一前置组服务测试：覆盖题干合成场景（{A,B} 择一 + 原阻塞事件 C）、配置校验、
 * 版本乐观锁、requestId 幂等、任务级环检测（422）、完成门禁与冻结快照、
 * 组替换语义及权限先于重放。
 */
@SpringBootTest
class IncidentTaskPrerequisiteServiceTest {

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
        jdbc.update("DELETE FROM task_prerequisite_members");
        jdbc.update("DELETE FROM task_prerequisite_groups");
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

    private static MemberSpec member(String incidentKey, String taskKey, int version) {
        return new MemberSpec(incidentKey, taskKey, version);
    }

    private static void assertApiStatus(ThrowingCallable call, HttpStatus status) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.status()).isEqualTo(status));
    }

    @Test
    void syntheticScenario_alternativeGroupPlusBlockerIncident() {
        // 合成 T：阻塞事件 C + 择一组 {A,B}；A 完成但 C 未遏制时 T 仍不能完成
        commanding("INC-T", "alice");
        commanding("INC-C", "carol");
        commanding("INC-A", "anna");
        commanding("INC-B", "beth");
        service.createTask("INC-T", "alice",
                new TaskCreateRequest(key(), "T", "G", "合成T", List.of("INC-C")));
        createTask("INC-A", "anna", "TA");
        createTask("INC-B", "beth", "TB");

        TaskView configured = service.configureGroups("INC-T", "T", "alice",
                new ConfigureGroupsRequest(key(), 1, List.of(new GroupSpec(List.of(
                        member("INC-B", "TB", 1), member("INC-A", "TA", 1))))));
        // 配置成功版本 1→2；组内成员按事件号+任务号字典序返回
        assertThat(configured.version()).isEqualTo(2);
        assertThat(configured.prerequisiteGroups()).hasSize(1);
        PrerequisiteGroupView group = configured.prerequisiteGroups().get(0);
        assertThat(group.groupNo()).isEqualTo(1);
        assertThat(group.selectedMember()).isNull();
        assertThat(group.members()).extracting(m -> m.incidentKey() + "/" + m.taskKey())
                .containsExactly("INC-A/TA", "INC-B/TB");

        // A 完成：组已满足，但原阻塞事件 C 未遏制，T 仍 409
        service.completeTask("INC-A", "TA", "anna", new TaskActionRequest(key()));
        assertApiStatus(() -> service.completeTask("INC-T", "T", "alice",
                new TaskActionRequest(key())), HttpStatus.CONFLICT);

        // C 遏制后 T 可完成，版本 2→3；B 仍 OPEN 且不自动取消
        service.changeStatus("INC-C", "carol", new StatusRequest(key(), "CONTAINED"));
        TaskView done = service.completeTask("INC-T", "T", "alice", new TaskActionRequest(key()));
        assertThat(done.status()).isEqualTo("DONE");
        assertThat(done.version()).isEqualTo(3);
        assertThat(service.getTask("INC-B", "TB").status()).isEqualTo("OPEN");

        // 完成快照：组全集保留，选中依据冻结为 A（唯一 DONE 成员）
        PrerequisiteGroupView frozen = done.prerequisiteGroups().get(0);
        assertThat(frozen.members()).hasSize(2);
        assertThat(frozen.selectedMember()).isNotNull();
        assertThat(frozen.selectedMember().incidentKey()).isEqualTo("INC-A");
        assertThat(frozen.selectedMember().taskKey()).isEqualTo("TA");

        // 后续其他候选 B 完成不改历史快照
        service.completeTask("INC-B", "TB", "beth", new TaskActionRequest(key()));
        TaskView after = service.getTask("INC-T", "T");
        assertThat(after.prerequisiteGroups().get(0).selectedMember().taskKey()).isEqualTo("TA");
        assertThat(after.prerequisiteGroups().get(0).members())
                .extracting(m -> m.incidentKey() + "/" + m.taskKey())
                .containsExactly("INC-A/TA", "INC-B/TB");
    }

    @Test
    void configure_validation() {
        commanding("INC-V", "alice");
        createTask("INC-V", "alice", "T");
        createTask("INC-V", "alice", "M1");
        createTask("INC-V", "alice", "M2");
        // 组数：0 组与 4 组均 400
        assertApiStatus(() -> service.configureGroups("INC-V", "T", "alice",
                new ConfigureGroupsRequest(key(), 1, List.of())), HttpStatus.BAD_REQUEST);
        assertApiStatus(() -> service.configureGroups("INC-V", "T", "alice",
                new ConfigureGroupsRequest(key(), 1, List.of(
                        new GroupSpec(List.of(member("INC-V", "M1", 1), member("INC-V", "M1", 1))),
                        new GroupSpec(List.of(member("INC-V", "M1", 1), member("INC-V", "M1", 1))),
                        new GroupSpec(List.of(member("INC-V", "M1", 1), member("INC-V", "M1", 1))),
                        new GroupSpec(List.of(member("INC-V", "M1", 1), member("INC-V", "M1", 1)))))),
                HttpStatus.BAD_REQUEST);
        // 每组 2~5 个成员
        assertApiStatus(() -> service.configureGroups("INC-V", "T", "alice",
                new ConfigureGroupsRequest(key(), 1, List.of(
                        new GroupSpec(List.of(member("INC-V", "M1", 1)))))),
                HttpStatus.BAD_REQUEST);
        // 组内成员重复 400
        assertApiStatus(() -> service.configureGroups("INC-V", "T", "alice",
                new ConfigureGroupsRequest(key(), 1, List.of(new GroupSpec(List.of(
                        member("INC-V", "M1", 1), member("INC-V", "M1", 1)))))),
                HttpStatus.BAD_REQUEST);
        // requestId / expectedTaskVersion 必填
        assertApiStatus(() -> service.configureGroups("INC-V", "T", "alice",
                new ConfigureGroupsRequest(" ", 1, List.of(new GroupSpec(List.of(
                        member("INC-V", "M1", 1), member("INC-V", "M1", 1)))))),
                HttpStatus.BAD_REQUEST);
        assertApiStatus(() -> service.configureGroups("INC-V", "T", "alice",
                new ConfigureGroupsRequest(key(), null, List.of(new GroupSpec(List.of(
                        member("INC-V", "M1", 1), member("INC-V", "M1", 1)))))),
                HttpStatus.BAD_REQUEST);
        // 成员期望版本必填
        assertApiStatus(() -> service.configureGroups("INC-V", "T", "alice",
                new ConfigureGroupsRequest(key(), 1, List.of(new GroupSpec(List.of(
                        member("INC-V", "M1", 1), new MemberSpec("INC-V", "T", null)))))),
                HttpStatus.BAD_REQUEST);
        // 成员事件/任务不存在 404
        assertApiStatus(() -> service.configureGroups("INC-V", "T", "alice",
                new ConfigureGroupsRequest(key(), 1, List.of(new GroupSpec(List.of(
                        member("INC-404", "M1", 1), member("INC-V", "M1", 1)))))),
                HttpStatus.NOT_FOUND);
        assertApiStatus(() -> service.configureGroups("INC-V", "T", "alice",
                new ConfigureGroupsRequest(key(), 1, List.of(new GroupSpec(List.of(
                        member("INC-V", "M404", 1), member("INC-V", "M1", 1)))))),
                HttpStatus.NOT_FOUND);
        // 目标任务不存在 404
        assertApiStatus(() -> service.configureGroups("INC-V", "T404", "alice",
                new ConfigureGroupsRequest(key(), 1, List.of(new GroupSpec(List.of(
                        member("INC-V", "M1", 1), member("INC-V", "M2", 1)))))),
                HttpStatus.NOT_FOUND);
    }

    @Test
    void configure_versionLocking() {
        commanding("INC-L", "alice");
        createTask("INC-L", "alice", "T");
        createTask("INC-L", "alice", "M1");
        createTask("INC-L", "alice", "M2");
        // 任务期望版本不符 409
        assertApiStatus(() -> service.configureGroups("INC-L", "T", "alice",
                new ConfigureGroupsRequest(key(), 2, List.of(new GroupSpec(List.of(
                        member("INC-L", "M1", 1), member("INC-L", "M2", 1)))))),
                HttpStatus.CONFLICT);
        // 成员期望版本不符 409
        assertApiStatus(() -> service.configureGroups("INC-L", "T", "alice",
                new ConfigureGroupsRequest(key(), 1, List.of(new GroupSpec(List.of(
                        member("INC-L", "M1", 2), member("INC-L", "M2", 1)))))),
                HttpStatus.CONFLICT);
        // 配置成功：T 版本 1→2；旧版本再配置 409，新版本可替换
        service.configureGroups("INC-L", "T", "alice",
                new ConfigureGroupsRequest(key(), 1, List.of(new GroupSpec(List.of(
                        member("INC-L", "M1", 1), member("INC-L", "M2", 1))))));
        assertApiStatus(() -> service.configureGroups("INC-L", "T", "alice",
                new ConfigureGroupsRequest(key(), 1, List.of(new GroupSpec(List.of(
                        member("INC-L", "M1", 1), member("INC-L", "M2", 1)))))),
                HttpStatus.CONFLICT);
        TaskView replaced = service.configureGroups("INC-L", "T", "alice",
                new ConfigureGroupsRequest(key(), 2, List.of(new GroupSpec(List.of(
                        member("INC-L", "M1", 1), member("INC-L", "M2", 1))))));
        assertThat(replaced.version()).isEqualTo(3);
        // 成员完成推进成员版本：再用旧成员版本配置 409，用新成员版本成功
        service.completeTask("INC-L", "M1", "alice", new TaskActionRequest(key()));
        assertApiStatus(() -> service.configureGroups("INC-L", "T", "alice",
                new ConfigureGroupsRequest(key(), 3, List.of(new GroupSpec(List.of(
                        member("INC-L", "M1", 1), member("INC-L", "M2", 1)))))),
                HttpStatus.CONFLICT);
        TaskView ok = service.configureGroups("INC-L", "T", "alice",
                new ConfigureGroupsRequest(key(), 3, List.of(new GroupSpec(List.of(
                        member("INC-L", "M1", 2), member("INC-L", "M2", 1))))));
        assertThat(ok.version()).isEqualTo(4);
    }

    @Test
    void configure_idempotency() {
        commanding("INC-I", "alice");
        createTask("INC-I", "alice", "T");
        createTask("INC-I", "alice", "M1");
        createTask("INC-I", "alice", "M2");
        createTask("INC-I", "alice", "M3");
        String requestId = key();
        TaskView first = service.configureGroups("INC-I", "T", "alice",
                new ConfigureGroupsRequest(requestId, 1, List.of(new GroupSpec(List.of(
                        member("INC-I", "M1", 1), member("INC-I", "M2", 1))))));
        // 同键同参（组内换序）重放首次完整响应，版本不再推进
        TaskView replay = service.configureGroups("INC-I", "T", "alice",
                new ConfigureGroupsRequest(requestId, 1, List.of(new GroupSpec(List.of(
                        member("INC-I", "M2", 1), member("INC-I", "M1", 1))))));
        assertThat(replay).isEqualTo(first);
        assertThat(service.getTask("INC-I", "T").version()).isEqualTo(2);
        // 同键异参 409
        assertApiStatus(() -> service.configureGroups("INC-I", "T", "alice",
                new ConfigureGroupsRequest(requestId, 1, List.of(new GroupSpec(List.of(
                        member("INC-I", "M1", 1), member("INC-I", "M3", 1)))))),
                HttpStatus.CONFLICT);
        // 失败不占键：成员版本错误 409 后，同键修正参数可成功
        String failKey = key();
        assertApiStatus(() -> service.configureGroups("INC-I", "T", "alice",
                new ConfigureGroupsRequest(failKey, 2, List.of(new GroupSpec(List.of(
                        member("INC-I", "M1", 9), member("INC-I", "M2", 1)))))),
                HttpStatus.CONFLICT);
        TaskView ok = service.configureGroups("INC-I", "T", "alice",
                new ConfigureGroupsRequest(failKey, 2, List.of(new GroupSpec(List.of(
                        member("INC-I", "M1", 1), member("INC-I", "M2", 1))))));
        assertThat(ok.version()).isEqualTo(3);
    }

    @Test
    void configure_cycleRejected422_atomically() {
        commanding("INC-1", "alice");
        commanding("INC-2", "bob");
        commanding("INC-3", "carol");
        createTask("INC-1", "alice", "T1");
        createTask("INC-1", "alice", "X1");
        createTask("INC-2", "bob", "T2");
        createTask("INC-2", "bob", "X2");
        createTask("INC-3", "carol", "T3");
        // 自身作为成员：直接环 422
        assertApiStatus(() -> service.configureGroups("INC-1", "T1", "alice",
                new ConfigureGroupsRequest(key(), 1, List.of(new GroupSpec(List.of(
                        member("INC-1", "T1", 1), member("INC-1", "X1", 1)))))),
                HttpStatus.UNPROCESSABLE_ENTITY);
        // T1 前置 {T2, X1} 成功；T2 前置 {T1, X2} 成直接环 422 且不留边
        service.configureGroups("INC-1", "T1", "alice",
                new ConfigureGroupsRequest(key(), 1, List.of(new GroupSpec(List.of(
                        member("INC-2", "T2", 1), member("INC-1", "X1", 1))))));
        assertApiStatus(() -> service.configureGroups("INC-2", "T2", "bob",
                new ConfigureGroupsRequest(key(), 1, List.of(new GroupSpec(List.of(
                        member("INC-1", "T1", 2), member("INC-2", "X2", 1)))))),
                HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(service.getTask("INC-2", "T2").prerequisiteGroups()).isEmpty();
        // T2 前置 {T3, X2} 成功；T3 前置 {T1, X2 所在事件任务} 形成间接环 T1→T2→T3→T1：422
        service.configureGroups("INC-2", "T2", "bob",
                new ConfigureGroupsRequest(key(), 1, List.of(new GroupSpec(List.of(
                        member("INC-3", "T3", 1), member("INC-2", "X2", 1))))));
        assertApiStatus(() -> service.configureGroups("INC-3", "T3", "carol",
                new ConfigureGroupsRequest(key(), 1, List.of(new GroupSpec(List.of(
                        member("INC-1", "T1", 2), member("INC-2", "X2", 1)))))),
                HttpStatus.UNPROCESSABLE_ENTITY);
        // 422 替换失败整体回滚：T1、T2 原有配置保留，T3 不留边
        assertThat(service.getTask("INC-1", "T1").prerequisiteGroups()).hasSize(1);
        assertThat(service.getTask("INC-2", "T2").prerequisiteGroups()).hasSize(1);
        assertThat(service.getTask("INC-3", "T3").prerequisiteGroups()).isEmpty();
        Integer memberCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM task_prerequisite_members", Integer.class);
        assertThat(memberCount).isEqualTo(4);
    }

    @Test
    void complete_groupGateAndCancelledMembers() {
        commanding("INC-G", "alice");
        commanding("INC-MA", "anna");
        commanding("INC-MB", "beth");
        createTask("INC-G", "alice", "T");
        createTask("INC-MA", "anna", "TA");
        createTask("INC-MB", "beth", "TB");
        service.configureGroups("INC-G", "T", "alice",
                new ConfigureGroupsRequest(key(), 1, List.of(new GroupSpec(List.of(
                        member("INC-MA", "TA", 1), member("INC-MB", "TB", 1))))));
        // 组内无 DONE 成员：409，details 为未满足的组
        assertThatThrownBy(() -> service.completeTask("INC-G", "T", "alice",
                new TaskActionRequest(key())))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat((List<?>) e.details()).hasSize(1);
                });
        // 成员 CANCELLED 不满足；全部取消仍阻塞
        service.cancelTask("INC-MA", "TA", "anna", new TaskActionRequest(key()));
        service.cancelTask("INC-MB", "TB", "beth", new TaskActionRequest(key()));
        assertApiStatus(() -> service.completeTask("INC-G", "T", "alice",
                new TaskActionRequest(key())), HttpStatus.CONFLICT);
        assertThat(service.getTask("INC-G", "T").status()).isEqualTo("OPEN");
    }

    @Test
    void complete_freezesLexicographicallySmallestDoneMember() {
        commanding("INC-S", "alice");
        commanding("INC-ZB", "beth");
        commanding("INC-ZA", "anna");
        createTask("INC-S", "alice", "T");
        createTask("INC-ZB", "beth", "TB");
        createTask("INC-ZA", "anna", "TA");
        service.configureGroups("INC-S", "T", "alice",
                new ConfigureGroupsRequest(key(), 1, List.of(new GroupSpec(List.of(
                        member("INC-ZB", "TB", 1), member("INC-ZA", "TA", 1))))));
        // 两个候选均 DONE（B 先完成）：选中完整任务标识字典序最小者 INC-ZA/TA
        service.completeTask("INC-ZB", "TB", "beth", new TaskActionRequest(key()));
        service.completeTask("INC-ZA", "TA", "anna", new TaskActionRequest(key()));
        TaskView done = service.completeTask("INC-S", "T", "alice",
                new TaskActionRequest(key()));
        PrerequisiteGroupView group = done.prerequisiteGroups().get(0);
        assertThat(group.selectedMember().incidentKey()).isEqualTo("INC-ZA");
        assertThat(group.selectedMember().taskKey()).isEqualTo("TA");
        assertThat(group.members()).hasSize(2);
    }

    @Test
    void configure_replaceDiscardsOldGroups() {
        commanding("INC-R", "alice");
        createTask("INC-R", "alice", "T");
        createTask("INC-R", "alice", "A");
        createTask("INC-R", "alice", "B");
        createTask("INC-R", "alice", "C");
        createTask("INC-R", "alice", "D");
        service.configureGroups("INC-R", "T", "alice",
                new ConfigureGroupsRequest(key(), 1, List.of(new GroupSpec(List.of(
                        member("INC-R", "A", 1), member("INC-R", "B", 1))))));
        // 替换为 {C,D}：旧组不再约束
        service.configureGroups("INC-R", "T", "alice",
                new ConfigureGroupsRequest(key(), 2, List.of(new GroupSpec(List.of(
                        member("INC-R", "C", 1), member("INC-R", "D", 1))))));
        TaskView view = service.getTask("INC-R", "T");
        assertThat(view.prerequisiteGroups()).hasSize(1);
        assertThat(view.prerequisiteGroups().get(0).members())
                .extracting(m -> m.incidentKey() + "/" + m.taskKey())
                .containsExactly("INC-R/C", "INC-R/D");
        // 旧成员 A 完成不满足新组
        service.completeTask("INC-R", "A", "alice", new TaskActionRequest(key()));
        assertApiStatus(() -> service.completeTask("INC-R", "T", "alice",
                new TaskActionRequest(key())), HttpStatus.CONFLICT);
        // 新成员 C 完成后可完成
        service.completeTask("INC-R", "C", "alice", new TaskActionRequest(key()));
        assertThat(service.completeTask("INC-R", "T", "alice",
                new TaskActionRequest(key())).status()).isEqualTo("DONE");
    }

    @Test
    void configure_terminalTaskAndPermissionBeforeReplay() {
        commanding("INC-P", "alice");
        createTask("INC-P", "alice", "T");
        createTask("INC-P", "alice", "M1");
        createTask("INC-P", "alice", "M2");
        // 非当前指挥人 409
        assertApiStatus(() -> service.configureGroups("INC-P", "T", "mallory",
                new ConfigureGroupsRequest(key(), 1, List.of(new GroupSpec(List.of(
                        member("INC-P", "M1", 1), member("INC-P", "M2", 1)))))),
                HttpStatus.CONFLICT);
        String requestId = key();
        service.configureGroups("INC-P", "T", "alice",
                new ConfigureGroupsRequest(requestId, 1, List.of(new GroupSpec(List.of(
                        member("INC-P", "M1", 1), member("INC-P", "M2", 1))))));
        // 交接后旧指挥无权写入：权限检查先于重放，同 requestId 同参也得 409
        service.initiateTransfer("INC-P", "alice", new TransferRequest(key(), "bob"));
        service.acceptTransfer("INC-P", "bob", new TransferAcceptRequest(key()));
        assertApiStatus(() -> service.configureGroups("INC-P", "T", "alice",
                new ConfigureGroupsRequest(requestId, 1, List.of(new GroupSpec(List.of(
                        member("INC-P", "M1", 1), member("INC-P", "M2", 1)))))),
                HttpStatus.CONFLICT);
        // 已完成任务不能修改组
        service.completeTask("INC-P", "M1", "bob", new TaskActionRequest(key()));
        service.completeTask("INC-P", "T", "bob", new TaskActionRequest(key()));
        assertApiStatus(() -> service.configureGroups("INC-P", "T", "bob",
                new ConfigureGroupsRequest(key(), 3, List.of(new GroupSpec(List.of(
                        member("INC-P", "M1", 2), member("INC-P", "M2", 1)))))),
                HttpStatus.CONFLICT);
    }

    @Test
    void completeAndCancel_expectedTaskVersion() {
        commanding("INC-W", "alice");
        createTask("INC-W", "alice", "T1");
        createTask("INC-W", "alice", "T2");
        // 完成携带旧版本 409，携带当前版本成功且版本 +1
        assertApiStatus(() -> service.completeTask("INC-W", "T1", "alice",
                new TaskActionRequest(key(), 9)), HttpStatus.CONFLICT);
        TaskView done = service.completeTask("INC-W", "T1", "alice",
                new TaskActionRequest(key(), 1));
        assertThat(done.version()).isEqualTo(2);
        // 取消携带旧版本 409，携带当前版本成功且版本 +1
        assertApiStatus(() -> service.cancelTask("INC-W", "T2", "alice",
                new TaskActionRequest(key(), 9)), HttpStatus.CONFLICT);
        TaskView cancelled = service.cancelTask("INC-W", "T2", "alice",
                new TaskActionRequest(key(), 1));
        assertThat(cancelled.version()).isEqualTo(2);
    }
}
