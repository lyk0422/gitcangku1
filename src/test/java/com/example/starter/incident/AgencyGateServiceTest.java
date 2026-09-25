package com.example.starter.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import com.example.starter.incident.dto.Requests.AgencyAckRequest;
import com.example.starter.incident.dto.Requests.AgencyConfigRequest;
import com.example.starter.incident.dto.Requests.ReportRequest;
import com.example.starter.incident.dto.Requests.TakeoverRequest;
import com.example.starter.incident.dto.Requests.TaskActionRequest;
import com.example.starter.incident.dto.Requests.TaskCreateRequest;
import com.example.starter.incident.dto.Responses.AgencyAckView;
import com.example.starter.incident.dto.Responses.AgencyGateView;
import com.example.starter.incident.dto.Responses.IncidentView;
import com.example.starter.incident.dto.Responses.TaskView;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 外部机构回执门禁服务测试：覆盖配置版本隔离与乐观并发、空/去重/排序/上限校验、
 * 回执终态与拒绝说明、HIGH 任务确认门禁与 422 明细、NORMAL 任务不受影响、
 * 拒绝触发 EXTERNAL_BLOCKED、替换配置后旧回执仅归属旧版本及阻断解除、
 * CLOSED 禁改配置与 ackKey 失败不占键。
 */
@SpringBootTest
class AgencyGateServiceTest {

    @Autowired
    private IncidentService service;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM agency_ack_keys");
        jdbc.update("DELETE FROM incident_agency_acks");
        jdbc.update("DELETE FROM incident_agency_configs");
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

    private static String ackKey() {
        return "ACK-" + UUID.randomUUID();
    }

    private IncidentView commanding(String incidentKey, String commander) {
        service.report(new ReportRequest(incidentKey, "S2", "外部协同处置", "reporter-1"));
        return service.takeover(incidentKey, commander, new TakeoverRequest(key()));
    }

    private AgencyGateView config(String incidentKey, String actor, Integer expected,
                                  List<String> codes) {
        return service.configureAgencies(incidentKey, actor,
                new AgencyConfigRequest(key(), expected, codes));
    }

    private TaskView highTask(String incidentKey, String actor, String taskKey) {
        return service.createTask(incidentKey, actor,
                new TaskCreateRequest(key(), taskKey, "NET", "高优先级处置", List.of(), "HIGH"));
    }

    private static void assertApiStatus(ThrowingCallable call, HttpStatus status) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.status()).isEqualTo(status));
    }

    @SuppressWarnings("unchecked")
    private static List<String> details(ApiException e) {
        return (List<String>) e.details();
    }

    @Test
    void configure_firstVersion_dedupSortAndEmptyAllowed() {
        commanding("INC-G1", "alice");

        // 重复代码去重、乱序排序
        AgencyGateView v1 = config("INC-G1", "alice", 0, List.of("C", "A", "A", "B"));
        assertThat(v1.currentVersion()).isEqualTo(1);
        assertThat(v1.configs()).hasSize(1);
        assertThat(v1.configs().get(0).agencyCodes()).containsExactly("A", "B", "C");
        assertThat(v1.configs().get(0).current()).isTrue();

        // 空集合合法：生成新版本
        AgencyGateView v2 = config("INC-G1", "alice", 1, List.of());
        assertThat(v2.currentVersion()).isEqualTo(2);
        assertThat(v2.configs()).hasSize(2);
        assertThat(v2.configs().get(1).agencyCodes()).isEmpty();
        assertThat(v2.configs().get(0).current()).isFalse();
        assertThat(v2.configs().get(1).current()).isTrue();
    }

    @Test
    void configure_validationErrors() {
        commanding("INC-G2", "alice");
        // 超过 5 个 → 400
        assertApiStatus(() -> config("INC-G2", "alice", 0, List.of("A", "B", "C", "D", "E", "F")),
                HttpStatus.BAD_REQUEST);
        // 空白机构代码 → 400
        assertApiStatus(() -> config("INC-G2", "alice", 0, List.of(" ")),
                HttpStatus.BAD_REQUEST);
        // expectedVersion 为负 → 400
        assertApiStatus(() -> config("INC-G2", "alice", -1, List.of("A")),
                HttpStatus.BAD_REQUEST);
        // 非当前指挥人 → 409
        assertApiStatus(() -> config("INC-G2", "bob", 0, List.of("A")),
                HttpStatus.CONFLICT);
        // 期望版本与当前不符 → 409（首次配置当前版本为 0）
        assertApiStatus(() -> config("INC-G2", "alice", 3, List.of("A")),
                HttpStatus.CONFLICT);
        // 所有失败均未产生配置版本
        assertThat(service.agencyGate("INC-G2").currentVersion()).isZero();
    }

    @Test
    void configure_closedIncident_conflict() {
        commanding("INC-G3", "alice");
        service.changeStatus("INC-G3", "alice",
                new com.example.starter.incident.dto.Requests.StatusRequest(key(), "CONTAINED"));
        service.changeStatus("INC-G3", "alice",
                new com.example.starter.incident.dto.Requests.StatusRequest(key(), "RESOLVED"));
        service.changeStatus("INC-G3", "alice",
                new com.example.starter.incident.dto.Requests.StatusRequest(key(), "CLOSED"));
        assertApiStatus(() -> config("INC-G3", "alice", 0, List.of("A")),
                HttpStatus.CONFLICT);
    }

    @Test
    void highTaskGate_unconfirmedAgencies_422WithDetails() {
        commanding("INC-G4", "alice");
        config("INC-G4", "alice", 0, List.of("A", "B"));
        highTask("INC-G4", "alice", "T-H");
        // 普通任务不受门禁：存在未确认机构仍可完成
        TaskView normal = service.createTask("INC-G4", "alice",
                new TaskCreateRequest(key(), "T-N", "OPS", "普通处置", List.of()));
        assertThat(normal.priority()).isEqualTo("NORMAL");
        TaskView normalDone = service.completeTask("INC-G4", "T-N", "alice",
                new TaskActionRequest(key()));
        assertThat(normalDone.status()).isEqualTo("DONE");

        // HIGH 任务：全部确认前完成 → 422，明细列出未确认机构（排序）
        assertThatThrownBy(() -> service.completeTask("INC-G4", "T-H", "alice",
                new TaskActionRequest(key())))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(e.code()).isEqualTo("AGENCY_GATE");
                    assertThat(details(e)).containsExactly("A", "B");
                });

        // 一个机构确认后，门禁明细只剩另一个
        service.submitAgencyAck("INC-G4", "agency-A",
                new AgencyAckRequest(ackKey(), "A", "CONFIRM", null));
        TaskView view = service.getTask("INC-G4", "T-H");
        assertThat(view.gateReason()).containsExactly("B");
        assertThatThrownBy(() -> service.completeTask("INC-G4", "T-H", "alice",
                new TaskActionRequest(key())))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(details(e)).containsExactly("B");
                });

        // 全部确认后 HIGH 可完成，gateReason 为空列表
        service.submitAgencyAck("INC-G4", "agency-B",
                new AgencyAckRequest(ackKey(), "B", "CONFIRM", null));
        assertThat(service.getTask("INC-G4", "T-H").gateReason()).isEmpty();
        TaskView done = service.completeTask("INC-G4", "T-H", "alice",
                new TaskActionRequest(key()));
        assertThat(done.status()).isEqualTo("DONE");
        assertThat(done.priority()).isEqualTo("HIGH");
    }

    @Test
    void reject_triggersExternalBlockedAndBlocksAllHighTasks() {
        commanding("INC-G5", "alice");
        config("INC-G5", "alice", 0, List.of("A", "B"));
        highTask("INC-G5", "alice", "T-H1");
        highTask("INC-G5", "alice", "T-H2");

        // B 拒绝（带说明）→ 事件进入 EXTERNAL_BLOCKED
        AgencyAckView reject = service.submitAgencyAck("INC-G5", "agency-B",
                new AgencyAckRequest(ackKey(), "B", "REJECT", "现场不具备割接条件"));
        assertThat(reject.ackType()).isEqualTo("REJECT");
        assertThat(reject.reason()).isEqualTo("现场不具备割接条件");
        assertThat(service.get("INC-G5").status()).isEqualTo("EXTERNAL_BLOCKED");

        // 所有未完成 HIGH 任务都不可完成 → 422
        for (String t : List.of("T-H1", "T-H2")) {
            assertApiStatus(() -> service.completeTask("INC-G5", t, "alice",
                    new TaskActionRequest(key())), HttpStatus.UNPROCESSABLE_ENTITY);
        }
        // 常规状态流转在阻断态不可用
        assertApiStatus(() -> service.changeStatus("INC-G5", "alice",
                new com.example.starter.incident.dto.Requests.StatusRequest(key(), "CONTAINED")),
                HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void replaceConfig_oldAcksBelongToOldVersionAndGateRecalculates() {
        commanding("INC-G6", "alice");
        config("INC-G6", "alice", 0, List.of("A", "B"));
        highTask("INC-G6", "alice", "T-H");
        // v1：A 确认、B 拒绝 → 阻断
        service.submitAgencyAck("INC-G6", "agency-A",
                new AgencyAckRequest(ackKey(), "A", "CONFIRM", null));
        service.submitAgencyAck("INC-G6", "agency-B",
                new AgencyAckRequest(ackKey(), "B", "REJECT", "设备未到位"));
        assertThat(service.get("INC-G6").status()).isEqualTo("EXTERNAL_BLOCKED");

        // 指挥员替换配置为新机构集合（v2）：阻断态允许配置，但新版本机构尚未确认，不解除阻断
        AgencyGateView v2 = config("INC-G6", "alice", 1, List.of("C", "D"));
        assertThat(v2.currentVersion()).isEqualTo(2);
        assertThat(service.get("INC-G6").status()).isEqualTo("EXTERNAL_BLOCKED");

        // 旧回执仍归属 v1 且未被改写：历史可查 v1 的 A/B 两条
        AgencyGateView gate = service.agencyGate("INC-G6");
        assertThat(gate.acks()).hasSize(2);
        assertThat(gate.acks()).extracting(AgencyAckView::configVersion)
                .containsExactly(1, 1);
        assertThat(gate.acks()).extracting(AgencyAckView::agencyCode)
                .containsExactly("A", "B");
        // 门禁按 v2 计算：C、D 未确认，HIGH 仍不可完成
        assertThat(service.getTask("INC-G6", "T-H").gateReason()).containsExactly("C", "D");
        assertApiStatus(() -> service.completeTask("INC-G6", "T-H", "alice",
                new TaskActionRequest(key())), HttpStatus.UNPROCESSABLE_ENTITY);

        // v2 机构全部确认 → 阻断解除，恢复到阻断前 COMMANDING，HIGH 可完成
        service.submitAgencyAck("INC-G6", "agency-C",
                new AgencyAckRequest(ackKey(), "C", "CONFIRM", null));
        assertThat(service.get("INC-G6").status()).isEqualTo("EXTERNAL_BLOCKED");
        service.submitAgencyAck("INC-G6", "agency-D",
                new AgencyAckRequest(ackKey(), "D", "CONFIRM", null));
        assertThat(service.get("INC-G6").status()).isEqualTo("COMMANDING");
        TaskView done = service.completeTask("INC-G6", "T-H", "alice",
                new TaskActionRequest(key()));
        assertThat(done.status()).isEqualTo("DONE");
        // 旧版本两条回执依旧完整保留
        assertThat(service.agencyGate("INC-G6").acks()).hasSize(4);
    }

    @Test
    void replaceConfig_emptyAgenciesRestoresImmediately() {
        commanding("INC-G7", "alice");
        config("INC-G7", "alice", 0, List.of("A"));
        service.submitAgencyAck("INC-G7", "agency-A",
                new AgencyAckRequest(ackKey(), "A", "REJECT", "暂不允许"));
        assertThat(service.get("INC-G7").status()).isEqualTo("EXTERNAL_BLOCKED");
        // 替换为空配置：新版本无必需机构，门禁立即满足，同事务解除阻断
        config("INC-G7", "alice", 1, List.of());
        assertThat(service.get("INC-G7").status()).isEqualTo("COMMANDING");
    }

    @Test
    void ack_terminalAndValidationAndNotInConfig() {
        commanding("INC-G8", "alice");
        config("INC-G8", "alice", 0, List.of("A"));

        // REJECT 缺少 reason → 400
        assertApiStatus(() -> service.submitAgencyAck("INC-G8", "x",
                new AgencyAckRequest(ackKey(), "A", "REJECT", "  ")), HttpStatus.BAD_REQUEST);
        // 非法类型 → 400
        assertApiStatus(() -> service.submitAgencyAck("INC-G8", "x",
                new AgencyAckRequest(ackKey(), "A", "MAYBE", null)), HttpStatus.BAD_REQUEST);
        // 机构不在配置列表 → 409
        assertApiStatus(() -> service.submitAgencyAck("INC-G8", "x",
                new AgencyAckRequest(ackKey(), "Z", "CONFIRM", null)), HttpStatus.CONFLICT);
        // 未配置过必需机构 → 409
        commanding("INC-G9", "alice");
        assertApiStatus(() -> service.submitAgencyAck("INC-G9", "x",
                new AgencyAckRequest(ackKey(), "A", "CONFIRM", null)), HttpStatus.CONFLICT);

        // 终态：确认后同版本同机构不能再提交（即使不同类型/不同 ackKey）
        service.submitAgencyAck("INC-G8", "agency-A",
                new AgencyAckRequest(ackKey(), "A", "CONFIRM", null));
        assertApiStatus(() -> service.submitAgencyAck("INC-G8", "agency-A",
                new AgencyAckRequest(ackKey(), "A", "REJECT", "改主意")), HttpStatus.CONFLICT);
        Integer ackRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM incident_agency_acks WHERE incident_id ="
                        + " (SELECT id FROM incidents WHERE incident_key='INC-G8')",
                Integer.class);
        assertThat(ackRows).isEqualTo(1);
    }

    @Test
    void ack_closedIncident_conflict() {
        commanding("INC-G10", "alice");
        config("INC-G10", "alice", 0, List.of("A"));
        service.changeStatus("INC-G10", "alice",
                new com.example.starter.incident.dto.Requests.StatusRequest(key(), "CONTAINED"));
        service.changeStatus("INC-G10", "alice",
                new com.example.starter.incident.dto.Requests.StatusRequest(key(), "RESOLVED"));
        service.changeStatus("INC-G10", "alice",
                new com.example.starter.incident.dto.Requests.StatusRequest(key(), "CLOSED"));
        assertApiStatus(() -> service.submitAgencyAck("INC-G10", "x",
                new AgencyAckRequest(ackKey(), "A", "CONFIRM", null)), HttpStatus.CONFLICT);
    }

    @Test
    void ackKey_successReplaysFirstResponse_failureDoesNotOccupyKey() {
        commanding("INC-G11", "alice");
        config("INC-G11", "alice", 0, List.of("A"));

        // 失败请求不占键：同一 ackKey 先用于非法回执（REJECT 无说明，400），后可成功使用
        String reusedKey = ackKey();
        assertApiStatus(() -> service.submitAgencyAck("INC-G11", "agency-A",
                new AgencyAckRequest(reusedKey, "A", "REJECT", null)), HttpStatus.BAD_REQUEST);
        AgencyAckView first = service.submitAgencyAck("INC-G11", "agency-A",
                new AgencyAckRequest(reusedKey, "A", "CONFIRM", null));
        assertThat(first.ackType()).isEqualTo("CONFIRM");

        // 同键同参重放首个响应（CONFIRM），即便这次请求声称 REJECT 也只重放（参数不同 → 409）
        AgencyAckView replay = service.submitAgencyAck("INC-G11", "agency-A",
                new AgencyAckRequest(reusedKey, "A", "CONFIRM", null));
        assertThat(replay).isEqualTo(first);
        assertApiStatus(() -> service.submitAgencyAck("INC-G11", "agency-A",
                new AgencyAckRequest(reusedKey, "A", "REJECT", "x")), HttpStatus.CONFLICT);
        Integer keyRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM agency_ack_keys WHERE ack_key = ?", Integer.class, reusedKey);
        assertThat(keyRows).isEqualTo(1);
    }

    @Test
    void ackKey_fingerprintIncludesVersion() {
        commanding("INC-G12", "alice");
        config("INC-G12", "alice", 0, List.of("A"));
        // v1 拒绝阻断
        service.submitAgencyAck("INC-G12", "agency-A",
                new AgencyAckRequest(ackKey(), "A", "REJECT", "v1 拒绝"));
        // 替换为仍含 A 的 v2：同机构在新版本可再次提交终态回执（版本隔离）
        config("INC-G12", "alice", 1, List.of("A"));
        AgencyAckView confirmV2 = service.submitAgencyAck("INC-G12", "agency-A",
                new AgencyAckRequest(ackKey(), "A", "CONFIRM", null));
        assertThat(confirmV2.configVersion()).isEqualTo(2);
        assertThat(service.get("INC-G12").status()).isEqualTo("COMMANDING");
        // 历史两条：v1 REJECT + v2 CONFIRM，互不改写
        assertThat(service.agencyGate("INC-G12").acks()).hasSize(2);
    }
}
