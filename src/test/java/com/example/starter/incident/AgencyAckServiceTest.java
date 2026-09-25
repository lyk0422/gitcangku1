package com.example.starter.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import com.example.starter.incident.dto.Requests.AgencyAckRequest;
import com.example.starter.incident.dto.Requests.AgencyConfigRequest;
import com.example.starter.incident.dto.Requests.ReportRequest;
import com.example.starter.incident.dto.Requests.StatusRequest;
import com.example.starter.incident.dto.Requests.TakeoverRequest;
import com.example.starter.incident.dto.Requests.TaskActionRequest;
import com.example.starter.incident.dto.Requests.TaskCreateRequest;
import com.example.starter.incident.dto.Responses.AgencyConfigView;
import com.example.starter.incident.dto.Responses.AgencyReceiptView;
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
 * 外部机构回执服务测试：覆盖配置版本校验与隔离、回执终态唯一、ackKey 幂等、
 * HIGH 任务门禁（422 及未确认机构明细）、拒绝阻断 EXTERNAL_BLOCKED 与
 * 替换配置恢复、门禁原因查询及历史回执不可改写。
 */
@SpringBootTest
class AgencyAckServiceTest {

    @Autowired
    private IncidentService service;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM command_keys");
        jdbc.update("DELETE FROM incident_agency_receipts");
        jdbc.update("DELETE FROM incident_agency_configs");
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

    private AgencyConfigView configure(String incidentKey, String actor, long expectedVersion,
                                       List<String> codes) {
        return service.configureAgencies(incidentKey, actor,
                new AgencyConfigRequest(expectedVersion, codes));
    }

    private AgencyReceiptView ack(String incidentKey, String ackKey, String agency,
                                  long version, String type, String reason) {
        return service.submitAgencyAck(incidentKey,
                new AgencyAckRequest(ackKey, agency, version, type, reason));
    }

    @Test
    void configure_mainFlow_dedupSortedAndVersioned() {
        commanding("INC-A1", "alice");
        // 首次配置：expectedVersion=0，代码去重排序
        AgencyConfigView v1 = configure("INC-A1", "alice", 0,
                List.of("POLICE", "FIRE", "POLICE", "MEDIC"));
        assertThat(v1.version()).isEqualTo(1);
        assertThat(v1.agencyCodes()).containsExactly("FIRE", "MEDIC", "POLICE");
        assertThat(v1.receipts()).isEmpty();
        assertThat(v1.pendingAgencies()).containsExactly("FIRE", "MEDIC", "POLICE");
        assertThat(v1.rejectedAgencies()).isEmpty();
        // 修改配置：expectedVersion=1 → 版本 2
        AgencyConfigView v2 = configure("INC-A1", "alice", 1, List.of("FIRE"));
        assertThat(v2.version()).isEqualTo(2);
        assertThat(v2.agencyCodes()).containsExactly("FIRE");
        // 旧版本已替换，仅一条 ACTIVE
        Integer activeCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM incident_agency_configs WHERE status = 'ACTIVE'",
                Integer.class);
        assertThat(activeCount).isEqualTo(1);
        // 空集合合法
        AgencyConfigView v3 = configure("INC-A1", "alice", 2, List.of());
        assertThat(v3.version()).isEqualTo(3);
        assertThat(v3.agencyCodes()).isEmpty();
        assertThat(v3.pendingAgencies()).isEmpty();
        // 查询端点与配置返回一致
        assertThat(service.agencyConfig("INC-A1")).isEqualTo(v3);
    }

    @Test
    void configure_validation() {
        commanding("INC-A2", "alice");
        // expectedVersion 缺失/负值
        assertApiStatus(() -> service.configureAgencies("INC-A2", "alice",
                new AgencyConfigRequest(null, List.of("FIRE"))), HttpStatus.BAD_REQUEST);
        assertApiStatus(() -> configure("INC-A2", "alice", -1, List.of("FIRE")),
                HttpStatus.BAD_REQUEST);
        // 超过 5 个机构
        assertApiStatus(() -> configure("INC-A2", "alice", 0,
                List.of("A1", "A2", "A3", "A4", "A5", "A6")), HttpStatus.BAD_REQUEST);
        // 空白机构代码
        assertApiStatus(() -> configure("INC-A2", "alice", 0, List.of(" ")),
                HttpStatus.BAD_REQUEST);
        // 版本不一致
        assertApiStatus(() -> configure("INC-A2", "alice", 1, List.of("FIRE")),
                HttpStatus.CONFLICT);
        // 非当前指挥人
        assertApiStatus(() -> configure("INC-A2", "bob", 0, List.of("FIRE")),
                HttpStatus.CONFLICT);
        // 事件不存在
        assertApiStatus(() -> configure("INC-404", "alice", 0, List.of("FIRE")),
                HttpStatus.NOT_FOUND);
        // 未接管事件无指挥人
        service.report(new ReportRequest("INC-A3", "S2", "s", "r"));
        assertApiStatus(() -> configure("INC-A3", "alice", 0, List.of("FIRE")),
                HttpStatus.CONFLICT);
        // 失败均不产生配置
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM incident_agency_configs", Integer.class);
        assertThat(count).isZero();
    }

    @Test
    void configure_closedIncident_conflict() {
        commanding("INC-A4", "alice");
        configure("INC-A4", "alice", 0, List.of("FIRE"));
        service.changeStatus("INC-A4", "alice", new StatusRequest(key(), "CONTAINED"));
        service.changeStatus("INC-A4", "alice", new StatusRequest(key(), "RESOLVED"));
        service.changeStatus("INC-A4", "alice", new StatusRequest(key(), "CLOSED"));
        assertApiStatus(() -> configure("INC-A4", "alice", 1, List.of("FIRE")),
                HttpStatus.CONFLICT);
    }

    @Test
    void ack_confirmFlow_andQuery() {
        commanding("INC-B1", "alice");
        configure("INC-B1", "alice", 0, List.of("FIRE", "MEDIC"));
        AgencyReceiptView receipt = ack("INC-B1", key(), "FIRE", 1, "CONFIRM", null);
        assertThat(receipt.agencyCode()).isEqualTo("FIRE");
        assertThat(receipt.configVersion()).isEqualTo(1);
        assertThat(receipt.type()).isEqualTo("CONFIRM");
        assertThat(receipt.reason()).isNull();
        assertThat(receipt.createdAt()).isNotNull();

        AgencyConfigView view = service.agencyConfig("INC-B1");
        assertThat(view.receipts()).containsExactly(receipt);
        assertThat(view.pendingAgencies()).containsExactly("MEDIC");
        assertThat(view.rejectedAgencies()).isEmpty();
    }

    @Test
    void ack_validation() {
        commanding("INC-B2", "alice");
        configure("INC-B2", "alice", 0, List.of("FIRE", "MEDIC"));
        // ackKey/agencyCode/type/configVersion 缺失
        assertApiStatus(() -> ack("INC-B2", null, "FIRE", 1, "CONFIRM", null),
                HttpStatus.BAD_REQUEST);
        assertApiStatus(() -> ack("INC-B2", key(), " ", 1, "CONFIRM", null),
                HttpStatus.BAD_REQUEST);
        assertApiStatus(() -> ack("INC-B2", key(), "FIRE", 1, " ", null),
                HttpStatus.BAD_REQUEST);
        assertApiStatus(() -> service.submitAgencyAck("INC-B2",
                new AgencyAckRequest(key(), "FIRE", null, "CONFIRM", null)),
                HttpStatus.BAD_REQUEST);
        // 非法类型
        assertApiStatus(() -> ack("INC-B2", key(), "FIRE", 1, "MAYBE", null),
                HttpStatus.BAD_REQUEST);
        // 拒绝必须携带非空说明
        assertApiStatus(() -> ack("INC-B2", key(), "FIRE", 1, "REJECT", null),
                HttpStatus.BAD_REQUEST);
        assertApiStatus(() -> ack("INC-B2", key(), "FIRE", 1, "REJECT", "  "),
                HttpStatus.BAD_REQUEST);
        // 配置版本过期
        assertApiStatus(() -> ack("INC-B2", key(), "FIRE", 9, "CONFIRM", null),
                HttpStatus.CONFLICT);
        // 机构不在必需列表
        assertApiStatus(() -> ack("INC-B2", key(), "POLICE", 1, "CONFIRM", null),
                HttpStatus.CONFLICT);
        // 事件不存在 / 未配置机构
        assertApiStatus(() -> ack("INC-404", key(), "FIRE", 1, "CONFIRM", null),
                HttpStatus.NOT_FOUND);
        commanding("INC-B3", "alice");
        assertApiStatus(() -> ack("INC-B3", key(), "FIRE", 1, "CONFIRM", null),
                HttpStatus.CONFLICT);
        // 失败不占回执
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM incident_agency_receipts", Integer.class);
        assertThat(count).isZero();
    }

    @Test
    void ack_terminalUniquePerAgencyVersion() {
        commanding("INC-B4", "alice");
        configure("INC-B4", "alice", 0, List.of("FIRE"));
        ack("INC-B4", key(), "FIRE", 1, "CONFIRM", null);
        // 同一机构同一版本第二条终态回执（不同 ackKey）→ 409
        assertApiStatus(() -> ack("INC-B4", key(), "FIRE", 1, "CONFIRM", null),
                HttpStatus.CONFLICT);
        assertApiStatus(() -> ack("INC-B4", key(), "FIRE", 1, "REJECT", "拒绝"),
                HttpStatus.CONFLICT);
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM incident_agency_receipts", Integer.class);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void ack_closedIncident_conflict() {
        commanding("INC-B5", "alice");
        configure("INC-B5", "alice", 0, List.of("FIRE"));
        service.changeStatus("INC-B5", "alice", new StatusRequest(key(), "CONTAINED"));
        service.changeStatus("INC-B5", "alice", new StatusRequest(key(), "RESOLVED"));
        service.changeStatus("INC-B5", "alice", new StatusRequest(key(), "CLOSED"));
        assertApiStatus(() -> ack("INC-B5", key(), "FIRE", 1, "CONFIRM", null),
                HttpStatus.CONFLICT);
    }

    @Test
    void ackKey_idempotency_replayAndConflict() {
        commanding("INC-B6", "alice");
        configure("INC-B6", "alice", 0, List.of("FIRE", "MEDIC"));
        String ackKey = key();
        AgencyReceiptView first = ack("INC-B6", ackKey, "FIRE", 1, "CONFIRM", null);
        // 同键同参重放首个响应，不产生新回执
        AgencyReceiptView replay = ack("INC-B6", ackKey, "FIRE", 1, "CONFIRM", null);
        assertThat(replay).isEqualTo(first);
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM incident_agency_receipts", Integer.class);
        assertThat(count).isEqualTo(1);
        // 同键改参（机构/版本/类型/说明任一不同）→ 409
        assertApiStatus(() -> ack("INC-B6", ackKey, "MEDIC", 1, "CONFIRM", null),
                HttpStatus.CONFLICT);
        assertApiStatus(() -> ack("INC-B6", ackKey, "FIRE", 2, "CONFIRM", null),
                HttpStatus.CONFLICT);
        assertApiStatus(() -> ack("INC-B6", ackKey, "FIRE", 1, "REJECT", "拒绝"),
                HttpStatus.CONFLICT);
    }

    @Test
    void ackKey_failureDoesNotConsumeKey() {
        commanding("INC-B7", "alice");
        configure("INC-B7", "alice", 0, List.of("FIRE"));
        String ackKey = key();
        // 业务失败（版本过期）事务回滚，不占键
        assertApiStatus(() -> ack("INC-B7", ackKey, "FIRE", 9, "CONFIRM", null),
                HttpStatus.CONFLICT);
        AgencyReceiptView ok = ack("INC-B7", ackKey, "FIRE", 1, "CONFIRM", null);
        assertThat(ok.type()).isEqualTo("CONFIRM");
        // 拒绝缺说明失败也不占键
        String rejectKey = key();
        assertApiStatus(() -> ack("INC-B7", rejectKey, "FIRE", 1, "REJECT", null),
                HttpStatus.BAD_REQUEST);
        configure("INC-B7", "alice", 1, List.of("FIRE"));
        AgencyReceiptView rejected = ack("INC-B7", rejectKey, "FIRE", 2, "REJECT", "超出职责");
        assertThat(rejected.type()).isEqualTo("REJECT");
    }

    @Test
    void highTaskGate_blocksUntilAllConfirmed() {
        commanding("INC-C1", "alice");
        configure("INC-C1", "alice", 0, List.of("FIRE", "MEDIC"));
        service.createTask("INC-C1", "alice",
                new TaskCreateRequest(key(), "T-H", "G", "高危处置", List.of(), "HIGH"));
        // 全部必需机构确认前：422 并列出未确认机构
        assertThatThrownBy(() -> service.completeTask("INC-C1", "T-H", "alice",
                new TaskActionRequest(key())))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(details(e)).containsExactly("FIRE", "MEDIC");
                });
        // 确认一个后仍 422，只剩未确认机构
        ack("INC-C1", key(), "FIRE", 1, "CONFIRM", null);
        assertThatThrownBy(() -> service.completeTask("INC-C1", "T-H", "alice",
                new TaskActionRequest(key())))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(details(e)).containsExactly("MEDIC");
                });
        // 全部确认后完成
        ack("INC-C1", key(), "MEDIC", 1, "CONFIRM", null);
        TaskView done = service.completeTask("INC-C1", "T-H", "alice",
                new TaskActionRequest(key()));
        assertThat(done.status()).isEqualTo("DONE");
        assertThat(done.priority()).isEqualTo("HIGH");
        assertThat(done.agencyGate().gated()).isFalse();
    }

    @Test
    void normalTask_notGated() {
        commanding("INC-C2", "alice");
        configure("INC-C2", "alice", 0, List.of("FIRE"));
        service.createTask("INC-C2", "alice",
                new TaskCreateRequest(key(), "T-N", "G", "普通处置", List.of()));
        // NORMAL 任务不受机构门禁约束
        TaskView done = service.completeTask("INC-C2", "T-N", "alice",
                new TaskActionRequest(key()));
        assertThat(done.status()).isEqualTo("DONE");
        assertThat(done.priority()).isEqualTo("NORMAL");
        assertThat(done.agencyGate().gated()).isFalse();
        assertThat(done.agencyGate().reason()).isNull();
    }

    @Test
    void taskGateReason_queryable() {
        commanding("INC-C3", "alice");
        configure("INC-C3", "alice", 0, List.of("FIRE", "MEDIC"));
        service.createTask("INC-C3", "alice",
                new TaskCreateRequest(key(), "T-H", "G", "高危处置", List.of(), "HIGH"));
        // 待确认门禁原因
        TaskView pending = service.getTask("INC-C3", "T-H");
        assertThat(pending.agencyGate().gated()).isTrue();
        assertThat(pending.agencyGate().reason()).isEqualTo("AGENCY_ACK_PENDING");
        assertThat(pending.agencyGate().pendingAgencies()).containsExactly("FIRE", "MEDIC");
        assertThat(pending.agencyGate().rejectedAgencies()).isEmpty();
        // 拒绝后门禁原因变为 EXTERNAL_BLOCKED
        ack("INC-C3", key(), "FIRE", 1, "REJECT", "无法支援");
        TaskView blocked = service.getTask("INC-C3", "T-H");
        assertThat(blocked.agencyGate().gated()).isTrue();
        assertThat(blocked.agencyGate().reason()).isEqualTo("EXTERNAL_BLOCKED");
        assertThat(blocked.agencyGate().pendingAgencies()).containsExactly("MEDIC");
        assertThat(blocked.agencyGate().rejectedAgencies()).containsExactly("FIRE");
    }

    @Test
    void reject_blocksIncidentAndHighTasks() {
        commanding("INC-D1", "alice");
        configure("INC-D1", "alice", 0, List.of("FIRE", "MEDIC"));
        service.createTask("INC-D1", "alice",
                new TaskCreateRequest(key(), "T-H", "G", "高危处置", List.of(), "HIGH"));
        AgencyReceiptView reject = ack("INC-D1", key(), "FIRE", 1, "REJECT", "超出职责范围");
        assertThat(reject.reason()).isEqualTo("超出职责范围");
        // 事件转为 EXTERNAL_BLOCKED 并记录进入前状态
        IncidentView blocked = service.get("INC-D1");
        assertThat(blocked.status()).isEqualTo("EXTERNAL_BLOCKED");
        assertThat(blocked.blockedFrom()).isEqualTo("COMMANDING");
        // 阻断态不允许常规状态变更
        assertApiStatus(() -> service.changeStatus("INC-D1", "alice",
                new StatusRequest(key(), "CONTAINED")), HttpStatus.UNPROCESSABLE_ENTITY);
        // 所有未完成 HIGH 任务不可完成（422）
        assertThatThrownBy(() -> service.completeTask("INC-D1", "T-H", "alice",
                new TaskActionRequest(key())))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(details(e)).containsExactly("FIRE", "MEDIC");
                });
        // 另一机构继续确认不改变阻断态
        ack("INC-D1", key(), "MEDIC", 1, "CONFIRM", null);
        assertThat(service.get("INC-D1").status()).isEqualTo("EXTERNAL_BLOCKED");
    }

    @Test
    void replaceConfig_restoresIncidentAndRecomputesGate() {
        commanding("INC-D2", "alice");
        configure("INC-D2", "alice", 0, List.of("FIRE"));
        service.createTask("INC-D2", "alice",
                new TaskCreateRequest(key(), "T-H", "G", "高危处置", List.of(), "HIGH"));
        ack("INC-D2", key(), "FIRE", 1, "REJECT", "无法支援");
        assertThat(service.get("INC-D2").status()).isEqualTo("EXTERNAL_BLOCKED");
        // 指挥人替换机构配置：同一事务恢复原状态，新版本门禁重新计算
        AgencyConfigView v2 = configure("INC-D2", "alice", 1, List.of("MEDIC"));
        assertThat(v2.version()).isEqualTo(2);
        assertThat(v2.incidentStatus()).isEqualTo("COMMANDING");
        assertThat(v2.pendingAgencies()).containsExactly("MEDIC");
        IncidentView restored = service.get("INC-D2");
        assertThat(restored.status()).isEqualTo("COMMANDING");
        assertThat(restored.blockedFrom()).isNull();
        // 旧回执仅归属旧版本且不可改写
        Integer oldReceipts = jdbc.queryForObject(
                "SELECT COUNT(*) FROM incident_agency_receipts"
                        + " WHERE config_version = 1 AND agency_code = 'FIRE'"
                        + " AND receipt_type = 'REJECT'", Integer.class);
        assertThat(oldReceipts).isEqualTo(1);
        assertThat(service.agencyConfig("INC-D2").receipts()).isEmpty();
        // 旧版本回执不再计入：HIGH 任务仍被新版本未确认机构门禁拦截
        assertApiStatus(() -> service.completeTask("INC-D2", "T-H", "alice",
                new TaskActionRequest(key())), HttpStatus.UNPROCESSABLE_ENTITY);
        // 旧机构向新版本提交回执 → 不在必需列表
        assertApiStatus(() -> ack("INC-D2", key(), "FIRE", 2, "CONFIRM", null),
                HttpStatus.CONFLICT);
        // 向旧版本提交回执 → 版本已过期
        assertApiStatus(() -> ack("INC-D2", key(), "FIRE", 1, "CONFIRM", null),
                HttpStatus.CONFLICT);
        // 新版本机构确认后门禁解除
        ack("INC-D2", key(), "MEDIC", 2, "CONFIRM", null);
        TaskView done = service.completeTask("INC-D2", "T-H", "alice",
                new TaskActionRequest(key()));
        assertThat(done.status()).isEqualTo("DONE");
    }

    @Test
    void replaceConfig_emptyNewConfig_unblocksImmediately() {
        commanding("INC-D3", "alice");
        configure("INC-D3", "alice", 0, List.of("FIRE"));
        service.createTask("INC-D3", "alice",
                new TaskCreateRequest(key(), "T-H", "G", "高危处置", List.of(), "HIGH"));
        ack("INC-D3", key(), "FIRE", 1, "REJECT", "拒绝");
        // 替换为空集合配置：恢复状态且门禁即刻解除
        AgencyConfigView v2 = configure("INC-D3", "alice", 1, List.of());
        assertThat(v2.agencyCodes()).isEmpty();
        assertThat(service.get("INC-D3").status()).isEqualTo("COMMANDING");
        TaskView done = service.completeTask("INC-D3", "T-H", "alice",
                new TaskActionRequest(key()));
        assertThat(done.status()).isEqualTo("DONE");
    }

    @Test
    void reject_fromContained_preservesBlockedFrom() {
        commanding("INC-D4", "alice");
        configure("INC-D4", "alice", 0, List.of("FIRE"));
        service.changeStatus("INC-D4", "alice", new StatusRequest(key(), "CONTAINED"));
        ack("INC-D4", key(), "FIRE", 1, "REJECT", "拒绝");
        IncidentView blocked = service.get("INC-D4");
        assertThat(blocked.status()).isEqualTo("EXTERNAL_BLOCKED");
        assertThat(blocked.blockedFrom()).isEqualTo("CONTAINED");
        // 替换配置后恢复到 CONTAINED，可继续推进
        configure("INC-D4", "alice", 1, List.of());
        assertThat(service.get("INC-D4").status()).isEqualTo("CONTAINED");
        IncidentView resolved = service.changeStatus("INC-D4", "alice",
                new StatusRequest(key(), "RESOLVED"));
        assertThat(resolved.status()).isEqualTo("RESOLVED");
    }

    @Test
    void secondReject_whileBlocked_keepsOriginalBlockedFrom() {
        commanding("INC-D5", "alice");
        configure("INC-D5", "alice", 0, List.of("FIRE", "MEDIC"));
        service.changeStatus("INC-D5", "alice", new StatusRequest(key(), "CONTAINED"));
        ack("INC-D5", key(), "FIRE", 1, "REJECT", "拒绝1");
        // 阻断态下另一机构拒绝：回执仍记录，blockedFrom 保持原状态
        ack("INC-D5", key(), "MEDIC", 1, "REJECT", "拒绝2");
        IncidentView blocked = service.get("INC-D5");
        assertThat(blocked.status()).isEqualTo("EXTERNAL_BLOCKED");
        assertThat(blocked.blockedFrom()).isEqualTo("CONTAINED");
        AgencyConfigView view = service.agencyConfig("INC-D5");
        assertThat(view.rejectedAgencies()).containsExactly("FIRE", "MEDIC");
        Integer receipts = jdbc.queryForObject(
                "SELECT COUNT(*) FROM incident_agency_receipts", Integer.class);
        assertThat(receipts).isEqualTo(2);
    }

    @Test
    void taskCreate_priorityIdempotencyContent() {
        commanding("INC-E1", "alice");
        service.createTask("INC-E1", "alice",
                new TaskCreateRequest(key(), "T-1", "G", "t", List.of(), "HIGH"));
        // 同 taskKey 不同优先级 → 409
        assertApiStatus(() -> service.createTask("INC-E1", "alice",
                new TaskCreateRequest(key(), "T-1", "G", "t", List.of(), "NORMAL")),
                HttpStatus.CONFLICT);
        assertApiStatus(() -> service.createTask("INC-E1", "alice",
                new TaskCreateRequest(key(), "T-1", "G", "t", List.of())),
                HttpStatus.CONFLICT);
        // 非法优先级 → 400
        assertApiStatus(() -> service.createTask("INC-E1", "alice",
                new TaskCreateRequest(key(), "T-2", "G", "t", List.of(), "URGENT")),
                HttpStatus.BAD_REQUEST);
    }
}
