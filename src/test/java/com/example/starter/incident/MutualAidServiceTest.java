package com.example.starter.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import com.example.starter.incident.dto.Requests.DelegateRegisterRequest;
import com.example.starter.incident.dto.Requests.HandoffCreateRequest;
import com.example.starter.incident.dto.Requests.HandoffItemRequest;
import com.example.starter.incident.dto.Requests.HandoffSettleRequest;
import com.example.starter.incident.dto.Requests.ReportRequest;
import com.example.starter.incident.dto.Requests.ResourceRegisterRequest;
import com.example.starter.incident.dto.Requests.StatusRequest;
import com.example.starter.incident.dto.Requests.TakeoverRequest;
import com.example.starter.incident.dto.Requests.TaskActionRequest;
import com.example.starter.incident.dto.Requests.TaskAssignResourceRequest;
import com.example.starter.incident.dto.Requests.TaskCreateRequest;
import com.example.starter.incident.dto.Requests.TaskStartRequest;
import com.example.starter.incident.dto.Responses.CloseBlockerView;
import com.example.starter.incident.dto.Responses.HandoffBatchView;
import com.example.starter.incident.dto.Responses.HandoffSettlementView;
import com.example.starter.incident.dto.Responses.HandoffView;
import com.example.starter.incident.dto.Responses.ResourceResponsibilityView;
import com.example.starter.incident.dto.Responses.TaskView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 跨事件互助资源交接核心服务测试（真实 H2 MySQL 兼容库）。
 * 覆盖双事件责任、指挥人/代理人接收权限、UTC 左闭右开租约、批量归属与重叠校验整批回滚、
 * handoffKey 同参重放/改参冲突/失败不占键、租约到期解绑与已开始任务保留、
 * 任务终态自动归还、目标关闭归还、来源关闭阻断与责任/结算查询。使用可控 Clock。
 */
@SpringBootTest
@Import(ControllableClock.Config.class)
class MutualAidServiceTest {

    private static final Instant T0 = Instant.parse("2026-09-26T00:00:00Z");

    @Autowired
    private IncidentService service;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Clock clock;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM command_keys");
        jdbc.update("DELETE FROM handoff_settlements");
        jdbc.update("DELETE FROM resource_handoffs");
        jdbc.update("DELETE FROM incident_receiving_delegates");
        jdbc.update("DELETE FROM incident_resources");
        jdbc.update("DELETE FROM incident_task_blockers");
        jdbc.update("DELETE FROM incident_tasks");
        jdbc.update("DELETE FROM incident_escalations");
        jdbc.update("DELETE FROM incident_status_history");
        jdbc.update("DELETE FROM incident_transfers");
        jdbc.update("DELETE FROM incident_actions");
        jdbc.update("DELETE FROM incidents");
        ((ControllableClock) clock).setInstant(T0);
    }

    private static String key() {
        return "CMD-" + UUID.randomUUID();
    }

    private void commanding(String incidentKey, String commander) {
        service.report(new ReportRequest(incidentKey, "S2", "互助场景", "reporter-1"));
        service.takeover(incidentKey, commander, new TakeoverRequest(key()));
    }

    private String registerResource(String incidentKey, String actor, String resourceKey) {
        service.registerResource(incidentKey, actor,
                new ResourceRegisterRequest(key(), resourceKey, "资源-" + resourceKey));
        return resourceKey;
    }

    private HandoffItemRequest item(String handoffKey, String resourceKey,
                                    Instant start, Instant end) {
        return new HandoffItemRequest(handoffKey, resourceKey, start, end);
    }

    private static void assertApi(ApiException e, HttpStatus status, String code) {
        assertThat(e.status()).isEqualTo(status);
        assertThat(e.code()).isEqualTo(code);
    }

    // ------------------------------------------------------------------
    // 主流程：双事件责任、版本指纹、查询
    // ------------------------------------------------------------------

    @Test
    void handoff_mainFlow_responsibilityMovesAndVersionsRecorded() {
        commanding("INC-A", "alice");
        commanding("INC-B", "bob");
        registerResource("INC-A", "alice", "RES-1");

        HandoffBatchView batch = service.createHandoffs("INC-A", "alice",
                new HandoffCreateRequest("INC-B", "bob", List.of(
                        item("HK-1", "RES-1", T0, T0.plus(1, ChronoUnit.HOURS)))));
        assertThat(batch.handoffs()).hasSize(1);
        HandoffView h = batch.handoffs().get(0);
        assertThat(h.sourceIncidentKey()).isEqualTo("INC-A");
        assertThat(h.targetIncidentKey()).isEqualTo("INC-B");
        assertThat(h.sourceVersion()).isEqualTo(1L);
        assertThat(h.targetVersion()).isEqualTo(1L);
        assertThat(h.operator()).isEqualTo("alice");
        assertThat(h.receiver()).isEqualTo("bob");
        assertThat(h.status()).isEqualTo("ACTIVE");

        ResourceResponsibilityView resp = service.getResourceResponsibility("RES-1");
        assertThat(resp.responsibleParty()).isEqualTo("TARGET");
        assertThat(resp.responsibleIncidentKey()).isEqualTo("INC-B");
        assertThat(resp.activeHandoffKey()).isEqualTo("HK-1");
        assertThat(resp.resource().status()).isEqualTo("LEASED_OUT");
    }

    @Test
    void handoff_registeredDelegateCanReceive() {
        commanding("INC-A", "alice");
        commanding("INC-B", "bob");
        registerResource("INC-A", "alice", "RES-1");
        // bob 登记 carol 为接收代理人
        service.registerDelegate("INC-B", "bob",
                new DelegateRegisterRequest(key(), "carol"));

        HandoffBatchView batch = service.createHandoffs("INC-A", "alice",
                new HandoffCreateRequest("INC-B", "carol", List.of(
                        item("HK-1", "RES-1", T0, T0.plus(1, ChronoUnit.HOURS)))));
        assertThat(batch.handoffs().get(0).receiver()).isEqualTo("carol");
    }

    // ------------------------------------------------------------------
    // 失败分支：均可区分原因，且不留半成品
    // ------------------------------------------------------------------

    @Test
    void handoff_targetEqualsSource_422() {
        commanding("INC-A", "alice");
        registerResource("INC-A", "alice", "RES-1");
        assertThatThrownBy(() -> service.createHandoffs("INC-A", "alice",
                new HandoffCreateRequest("INC-A", "alice", List.of(
                        item("HK-1", "RES-1", T0, T0.plus(1, ChronoUnit.HOURS))))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertApi(e, HttpStatus.UNPROCESSABLE_ENTITY, "HANDOFF_SAME_INCIDENT"));
    }

    @Test
    void handoff_leaseEndNotAfterStart_422() {
        commanding("INC-A", "alice");
        commanding("INC-B", "bob");
        registerResource("INC-A", "alice", "RES-1");
        assertThatThrownBy(() -> service.createHandoffs("INC-A", "alice",
                new HandoffCreateRequest("INC-B", "bob", List.of(
                        item("HK-1", "RES-1", T0, T0)))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertApi(e, HttpStatus.UNPROCESSABLE_ENTITY, "HANDOFF_INVALID_LEASE"));
    }

    @Test
    void handoff_resourceNotOwnedBySource_422_andRollsBack() {
        commanding("INC-A", "alice");
        commanding("INC-B", "bob");
        registerResource("INC-B", "bob", "RES-1");
        assertThatThrownBy(() -> service.createHandoffs("INC-A", "alice",
                new HandoffCreateRequest("INC-B", "bob", List.of(
                        item("HK-1", "RES-1", T0, T0.plus(1, ChronoUnit.HOURS))))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertApi(e, HttpStatus.UNPROCESSABLE_ENTITY,
                                "HANDOFF_RESOURCE_NOT_OWNED"));
        Integer handoffCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM resource_handoffs", Integer.class);
        assertThat(handoffCount).isZero();
    }

    @Test
    void handoff_receiverUnauthorized_422() {
        commanding("INC-A", "alice");
        commanding("INC-B", "bob");
        registerResource("INC-A", "alice", "RES-1");
        assertThatThrownBy(() -> service.createHandoffs("INC-A", "alice",
                new HandoffCreateRequest("INC-B", "carol", List.of(
                        item("HK-1", "RES-1", T0, T0.plus(1, ChronoUnit.HOURS))))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertApi(e, HttpStatus.UNPROCESSABLE_ENTITY,
                                "HANDOFF_RECEIVER_UNAUTHORIZED"));
    }

    @Test
    void handoff_targetClosed_422() {
        commanding("INC-A", "alice");
        commanding("INC-B", "bob");
        registerResource("INC-A", "alice", "RES-1");
        service.changeStatus("INC-B", "bob", new StatusRequest(key(), "CONTAINED"));
        service.changeStatus("INC-B", "bob", new StatusRequest(key(), "RESOLVED"));
        service.changeStatus("INC-B", "bob", new StatusRequest(key(), "CLOSED"));
        assertThatThrownBy(() -> service.createHandoffs("INC-A", "alice",
                new HandoffCreateRequest("INC-B", "bob", List.of(
                        item("HK-1", "RES-1", T0, T0.plus(1, ChronoUnit.HOURS))))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertApi(e, HttpStatus.UNPROCESSABLE_ENTITY, "HANDOFF_TARGET_CLOSED"));
    }

    @Test
    void handoff_overlappingLease_422() {
        commanding("INC-A", "alice");
        commanding("INC-B", "bob");
        registerResource("INC-A", "alice", "RES-1");
        service.createHandoffs("INC-A", "alice", new HandoffCreateRequest("INC-B", "bob",
                List.of(item("HK-1", "RES-1", T0, T0.plus(1, ChronoUnit.HOURS)))));
        // 与 [T0,T0+1h) 重叠（左闭右开：T0+1h 起不重叠，这里取 T0+30m 起）
        assertThatThrownBy(() -> service.createHandoffs("INC-A", "alice",
                new HandoffCreateRequest("INC-B", "bob", List.of(
                        item("HK-2", "RES-1", T0.plus(30, ChronoUnit.MINUTES),
                                T0.plus(2, ChronoUnit.HOURS))))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertApi(e, HttpStatus.UNPROCESSABLE_ENTITY, "HANDOFF_LEASE_OVERLAP"));
        // 紧邻不重叠 [T0+1h, T0+2h) 仍被拒：资源未归还前不可转借（资源 LEASED_OUT）
        assertThatThrownBy(() -> service.createHandoffs("INC-A", "alice",
                new HandoffCreateRequest("INC-B", "bob", List.of(
                        item("HK-3", "RES-1", T0.plus(1, ChronoUnit.HOURS),
                                T0.plus(2, ChronoUnit.HOURS))))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertApi(e, HttpStatus.UNPROCESSABLE_ENTITY, "HANDOFF_RESOURCE_BUSY"));
    }

    @Test
    void handoff_batchOneConflict_allRollBack() {
        commanding("INC-A", "alice");
        commanding("INC-B", "bob");
        registerResource("INC-A", "alice", "RES-1");
        registerResource("INC-A", "alice", "RES-2");
        // RES-1 已借出
        service.createHandoffs("INC-A", "alice", new HandoffCreateRequest("INC-B", "bob",
                List.of(item("HK-OLD", "RES-1", T0, T0.plus(1, ChronoUnit.HOURS)))));

        assertThatThrownBy(() -> service.createHandoffs("INC-A", "alice",
                new HandoffCreateRequest("INC-B", "bob", List.of(
                        item("HK-2", "RES-2", T0, T0.plus(1, ChronoUnit.HOURS)),
                        item("HK-1B", "RES-1", T0, T0.plus(1, ChronoUnit.HOURS))))))
                .isInstanceOf(ApiException.class);

        // 整批回滚：RES-2 仍 AVAILABLE，未产生 HK-2 交接
        assertThat(service.getResourceResponsibility("RES-2").responsibleParty())
                .isEqualTo("SOURCE");
        Integer hk2 = jdbc.queryForObject(
                "SELECT COUNT(*) FROM resource_handoffs WHERE handoff_key = 'HK-2'", Integer.class);
        assertThat(hk2).isZero();
    }

    // ------------------------------------------------------------------
    // 幂等：同键同参重放、同键改参 409、失败不占键
    // ------------------------------------------------------------------

    @Test
    void handoff_sameKeySameParams_replaysFirstResult() {
        commanding("INC-A", "alice");
        commanding("INC-B", "bob");
        registerResource("INC-A", "alice", "RES-1");
        var req = new HandoffCreateRequest("INC-B", "bob", List.of(
                item("HK-IDEM", "RES-1", T0, T0.plus(1, ChronoUnit.HOURS))));
        HandoffBatchView first = service.createHandoffs("INC-A", "alice", req);
        HandoffBatchView replay = service.createHandoffs("INC-A", "alice", req);
        assertThat(replay.handoffs()).usingRecursiveFieldByFieldElementComparator()
                .isEqualTo(first.handoffs());
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM resource_handoffs WHERE handoff_key = 'HK-IDEM'", Integer.class);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void handoff_sameKeyDifferentParams_409() {
        commanding("INC-A", "alice");
        commanding("INC-B", "bob");
        registerResource("INC-A", "alice", "RES-1");
        service.createHandoffs("INC-A", "alice", new HandoffCreateRequest("INC-B", "bob",
                List.of(item("HK-CHG", "RES-1", T0, T0.plus(1, ChronoUnit.HOURS)))));
        assertThatThrownBy(() -> service.createHandoffs("INC-A", "alice",
                new HandoffCreateRequest("INC-B", "bob", List.of(
                        item("HK-CHG", "RES-1", T0, T0.plus(2, ChronoUnit.HOURS))))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void handoff_failureDoesNotOccupyKey() {
        commanding("INC-A", "alice");
        commanding("INC-B", "bob");
        registerResource("INC-A", "alice", "RES-1");
        registerResource("INC-A", "alice", "RES-2");
        // 先用 HK-FREE 交接 RES-1 成功占用，再尝试用同键交接 RES-2 之前制造一次失败：
        service.createHandoffs("INC-A", "alice", new HandoffCreateRequest("INC-B", "bob",
                List.of(item("HK-OLD2", "RES-1", T0, T0.plus(1, ChronoUnit.HOURS)))));
        // 批量内含一个不存在资源 → 404 失败回滚，HK-FREE 不落库
        assertThatThrownBy(() -> service.createHandoffs("INC-A", "alice",
                new HandoffCreateRequest("INC-B", "bob", List.of(
                        item("HK-FREE", "RES-NOPE", T0, T0.plus(1, ChronoUnit.HOURS))))))
                .isInstanceOf(ApiException.class);
        // 失败不占键：同键可用于合法资源 RES-2 并成功
        HandoffBatchView ok = service.createHandoffs("INC-A", "alice",
                new HandoffCreateRequest("INC-B", "bob", List.of(
                        item("HK-FREE", "RES-2", T0, T0.plus(1, ChronoUnit.HOURS)))));
        assertThat(ok.handoffs().get(0).handoffKey()).isEqualTo("HK-FREE");
        assertThat(ok.handoffs().get(0).resourceKey()).isEqualTo("RES-2");
    }

    // ------------------------------------------------------------------
    // 租约：分配、到期解绑未开始任务、已开始任务保留至终态归还
    // ------------------------------------------------------------------

    @Test
    void leaseExpire_unstartedTaskDetachedAndReturned_settlementImmutable() {
        commanding("INC-A", "alice");
        commanding("INC-B", "bob");
        registerResource("INC-A", "alice", "RES-1");
        service.createHandoffs("INC-A", "alice", new HandoffCreateRequest("INC-B", "bob",
                List.of(item("HK-1", "RES-1", T0, T0.plus(1, ChronoUnit.HOURS)))));
        TaskView task = service.createTask("INC-B", "bob",
                new TaskCreateRequest(key(), "T-1", "G", "处置", List.of()));
        service.assignResourceToTask("INC-B", "T-1", "bob",
                new TaskAssignResourceRequest(key(), "HK-1"));
        assertThat(service.getTask("INC-B", "T-1").assignedResourceKey()).isEqualTo("RES-1");

        // 租约内到期前结算：无效果
        assertThat(service.settleExpiredLeases("INC-B", new HandoffSettleRequest(key()))).isEmpty();

        ((ControllableClock) clock).setInstant(T0.plus(2, ChronoUnit.HOURS));
        List<HandoffSettlementView> settled = service.settleExpiredLeases("INC-B",
                new HandoffSettleRequest(key()));
        assertThat(settled).hasSize(1);
        assertThat(settled.get(0).reason()).isEqualTo("LEASE_EXPIRED");
        assertThat(settled.get(0).returnedResourceKey()).isEqualTo("RES-1");
        assertThat(settled.get(0).detail()).contains("T-1");

        TaskView after = service.getTask("INC-B", "T-1");
        assertThat(after.assignedResourceKey()).isNull();
        assertThat(after.assignedHandoffKey()).isNull();
        assertThat(service.getResourceResponsibility("RES-1").responsibleParty())
                .isEqualTo("SOURCE");
        // 交接 SETTLED，结算只一条（不可变）
        Integer settlementCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM handoff_settlements", Integer.class);
        assertThat(settlementCount).isEqualTo(1);
    }

    @Test
    void leaseExpire_startedTaskKeepsResource_untilTaskTerminalReturn() {
        commanding("INC-A", "alice");
        commanding("INC-B", "bob");
        registerResource("INC-A", "alice", "RES-1");
        service.createHandoffs("INC-A", "alice", new HandoffCreateRequest("INC-B", "bob",
                List.of(item("HK-1", "RES-1", T0, T0.plus(1, ChronoUnit.HOURS)))));
        service.createTask("INC-B", "bob",
                new TaskCreateRequest(key(), "T-1", "G", "处置", List.of()));
        service.assignResourceToTask("INC-B", "T-1", "bob",
                new TaskAssignResourceRequest(key(), "HK-1"));
        service.startTask("INC-B", "T-1", "bob", new TaskStartRequest(key()));

        ((ControllableClock) clock).setInstant(T0.plus(2, ChronoUnit.HOURS));
        // 已开始任务：租约到期不归还，交接保持 ACTIVE，资源仍 LEASED_OUT
        assertThat(service.settleExpiredLeases("INC-B", new HandoffSettleRequest(key()))).isEmpty();
        assertThat(service.getResourceResponsibility("RES-1").responsibleParty())
                .isEqualTo("TARGET");
        assertThat(jdbc.queryForObject(
                "SELECT status FROM resource_handoffs WHERE handoff_key='HK-1'", String.class))
                .isEqualTo("ACTIVE");

        // 来源在资源未归还前不可关闭
        service.changeStatus("INC-A", "alice", new StatusRequest(key(), "CONTAINED"));
        service.changeStatus("INC-A", "alice", new StatusRequest(key(), "RESOLVED"));
        assertThatThrownBy(() -> service.changeStatus("INC-A", "alice",
                new StatusRequest(key(), "CLOSED")))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertApi(e, HttpStatus.UNPROCESSABLE_ENTITY, "SOURCE_CLOSE_BLOCKED"));

        // 任务终态后自动结算归还
        service.completeTask("INC-B", "T-1", "bob", new TaskActionRequest(key()));
        assertThat(service.getResourceResponsibility("RES-1").responsibleParty())
                .isEqualTo("SOURCE");
        List<HandoffSettlementView> settlements = service.getResourceSettlements("RES-1");
        assertThat(settlements).hasSize(1);
        assertThat(settlements.get(0).reason()).isEqualTo("TASK_DONE");
    }

    @Test
    void leaseExpire_mixedResources_unstartedSettledStartedKeepsResource() {
        commanding("INC-A", "alice");
        commanding("INC-B", "bob");
        registerResource("INC-A", "alice", "RES-1");
        registerResource("INC-A", "alice", "RES-2");
        service.createHandoffs("INC-A", "alice", new HandoffCreateRequest("INC-B", "bob", List.of(
                item("HK-1", "RES-1", T0, T0.plus(1, ChronoUnit.HOURS)),
                item("HK-2", "RES-2", T0, T0.plus(1, ChronoUnit.HOURS)))));
        // RES-1 给未开始任务，RES-2 给已开始任务
        service.createTask("INC-B", "bob",
                new TaskCreateRequest(key(), "T-OPEN", "G", "未开始", List.of()));
        service.createTask("INC-B", "bob",
                new TaskCreateRequest(key(), "T-STARTED", "G", "已开始", List.of()));
        service.assignResourceToTask("INC-B", "T-OPEN", "bob",
                new TaskAssignResourceRequest(key(), "HK-1"));
        service.assignResourceToTask("INC-B", "T-STARTED", "bob",
                new TaskAssignResourceRequest(key(), "HK-2"));
        service.startTask("INC-B", "T-STARTED", "bob", new TaskStartRequest(key()));

        ((ControllableClock) clock).setInstant(T0.plus(2, ChronoUnit.HOURS));
        // 一次结算：RES-1（未开始）解绑并 LEASE_EXPIRED 归还；RES-2（已开始）保留
        List<HandoffSettlementView> settled = service.settleExpiredLeases("INC-B",
                new HandoffSettleRequest(key()));
        assertThat(settled).hasSize(1);
        assertThat(settled.get(0).returnedResourceKey()).isEqualTo("RES-1");
        assertThat(settled.get(0).reason()).isEqualTo("LEASE_EXPIRED");
        assertThat(service.getTask("INC-B", "T-OPEN").assignedResourceKey()).isNull();
        assertThat(service.getResourceResponsibility("RES-1").responsibleParty())
                .isEqualTo("SOURCE");
        assertThat(service.getTask("INC-B", "T-STARTED").assignedResourceKey()).isEqualTo("RES-2");
        assertThat(service.getResourceResponsibility("RES-2").responsibleParty())
                .isEqualTo("TARGET");

        // 已开始任务终态后 RES-2 归还（TASK_DONE）
        service.completeTask("INC-B", "T-STARTED", "bob", new TaskActionRequest(key()));
        assertThat(service.getResourceResponsibility("RES-2").responsibleParty())
                .isEqualTo("SOURCE");
        List<HandoffSettlementView> res2 = service.getResourceSettlements("RES-2");
        assertThat(res2).hasSize(1);
        assertThat(res2.get(0).reason()).isEqualTo("TASK_DONE");
    }

    @Test
    void assignResource_outsideLease_422() {
        commanding("INC-A", "alice");
        commanding("INC-B", "bob");
        registerResource("INC-A", "alice", "RES-1");
        service.createHandoffs("INC-A", "alice", new HandoffCreateRequest("INC-B", "bob",
                List.of(item("HK-1", "RES-1", T0.plus(1, ChronoUnit.HOURS),
                        T0.plus(2, ChronoUnit.HOURS)))));
        service.createTask("INC-B", "bob",
                new TaskCreateRequest(key(), "T-1", "G", "处置", List.of()));
        // 当前 T0 不在租约 [T0+1h, T0+2h) 内
        assertThatThrownBy(() -> service.assignResourceToTask("INC-B", "T-1", "bob",
                new TaskAssignResourceRequest(key(), "HK-1")))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertApi(e, HttpStatus.UNPROCESSABLE_ENTITY, "HANDOFF_OUT_OF_LEASE"));
    }

    // ------------------------------------------------------------------
    // 目标关闭归还、关闭阻断查询
    // ------------------------------------------------------------------

    @Test
    void targetClose_returnsIdleBorrowedResource_withTargetClosedSettlement() {
        commanding("INC-A", "alice");
        commanding("INC-B", "bob");
        registerResource("INC-A", "alice", "RES-1");
        service.createHandoffs("INC-A", "alice", new HandoffCreateRequest("INC-B", "bob",
                List.of(item("HK-1", "RES-1", T0, T0.plus(10, ChronoUnit.HOURS)))));
        // 目标未把资源分配给任务，自身无未终态任务 → 可正常走到 CLOSED
        service.changeStatus("INC-B", "bob", new StatusRequest(key(), "CONTAINED"));
        service.changeStatus("INC-B", "bob", new StatusRequest(key(), "RESOLVED"));
        service.changeStatus("INC-B", "bob", new StatusRequest(key(), "CLOSED"));

        assertThat(service.getResourceResponsibility("RES-1").responsibleParty())
                .isEqualTo("SOURCE");
        List<HandoffSettlementView> settlements = service.getResourceSettlements("RES-1");
        assertThat(settlements).hasSize(1);
        assertThat(settlements.get(0).reason()).isEqualTo("TARGET_CLOSED");
    }

    @Test
    void closeBlockers_reportsDistinguishableReasonAndResource() {
        commanding("INC-A", "alice");
        commanding("INC-B", "bob");
        registerResource("INC-A", "alice", "RES-1");
        service.createHandoffs("INC-A", "alice", new HandoffCreateRequest("INC-B", "bob",
                List.of(item("HK-1", "RES-1", T0, T0.plus(10, ChronoUnit.HOURS)))));
        service.changeStatus("INC-A", "alice", new StatusRequest(key(), "CONTAINED"));
        service.changeStatus("INC-A", "alice", new StatusRequest(key(), "RESOLVED"));

        CloseBlockerView blockers = service.getCloseBlockers("INC-A");
        assertThat(blockers.blocked()).isTrue();
        assertThat(blockers.reasons()).anyMatch(r -> r.contains("借出未归还"));
        assertThat(blockers.resourceKeys()).containsExactly("RES-1");
    }

    @Test
    void targetClose_startedTaskKeepsResourceThenTerminalReturns_unstartedDetached() {
        commanding("INC-A", "alice");
        commanding("INC-B", "bob");
        registerResource("INC-A", "alice", "RES-1");
        registerResource("INC-A", "alice", "RES-2");
        service.createHandoffs("INC-A", "alice", new HandoffCreateRequest("INC-B", "bob", List.of(
                item("HK-1", "RES-1", T0, T0.plus(10, ChronoUnit.HOURS)),
                item("HK-2", "RES-2", T0, T0.plus(10, ChronoUnit.HOURS)))));
        // RES-1 给未开始任务（关闭时解绑归还），RES-2 给已开始任务（继续至终态）
        service.createTask("INC-B", "bob",
                new TaskCreateRequest(key(), "T-OPEN", "G", "未开始", List.of()));
        service.createTask("INC-B", "bob",
                new TaskCreateRequest(key(), "T-STARTED", "G", "已开始", List.of()));
        service.assignResourceToTask("INC-B", "T-OPEN", "bob",
                new TaskAssignResourceRequest(key(), "HK-1"));
        service.assignResourceToTask("INC-B", "T-STARTED", "bob",
                new TaskAssignResourceRequest(key(), "HK-2"));
        service.startTask("INC-B", "T-STARTED", "bob", new TaskStartRequest(key()));

        // 目标事件可越过解决门禁直接关闭（借用任务按互助规则处理）
        service.changeStatus("INC-B", "bob", new StatusRequest(key(), "CONTAINED"));
        service.changeStatus("INC-B", "bob", new StatusRequest(key(), "RESOLVED"));
        service.changeStatus("INC-B", "bob", new StatusRequest(key(), "CLOSED"));

        // 未开始任务解绑但任务仍 OPEN；RES-1 立即 TARGET_CLOSED 归还
        TaskView openTask = service.getTask("INC-B", "T-OPEN");
        assertThat(openTask.status()).isEqualTo("OPEN");
        assertThat(openTask.assignedResourceKey()).isNull();
        assertThat(service.getResourceResponsibility("RES-1").responsibleParty())
                .isEqualTo("SOURCE");
        assertThat(service.getResourceSettlements("RES-1").get(0).reason())
                .isEqualTo("TARGET_CLOSED");
        // 已开始任务保留 RES-2；交接仍 ACTIVE、资源仍在目标侧
        TaskView startedTask = service.getTask("INC-B", "T-STARTED");
        assertThat(startedTask.status()).isEqualTo("STARTED");
        assertThat(startedTask.assignedResourceKey()).isEqualTo("RES-2");
        assertThat(service.getResourceResponsibility("RES-2").responsibleParty())
                .isEqualTo("TARGET");

        // 已开始任务在目标关闭后到达终态 → 自动结算归还
        service.completeTask("INC-B", "T-STARTED", "bob", new TaskActionRequest(key()));
        assertThat(service.getResourceResponsibility("RES-2").responsibleParty())
                .isEqualTo("SOURCE");
        List<HandoffSettlementView> settlements = service.getResourceSettlements("RES-2");
        assertThat(settlements).hasSize(1);
        assertThat(settlements.get(0).reason()).isEqualTo("TASK_DONE");
        assertThat(service.getResourceHandoffs("RES-2").get(0).status()).isEqualTo("SETTLED");
    }

    @Test
    void taskAlreadyStarted_cannotStartAgain() {
        commanding("INC-B", "bob");
        service.createTask("INC-B", "bob",
                new TaskCreateRequest(key(), "T-1", "G", "处置", List.of()));
        service.startTask("INC-B", "T-1", "bob", new TaskStartRequest(key()));
        assertThatThrownBy(() -> service.startTask("INC-B", "T-1", "bob",
                new TaskStartRequest(key())))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT));
    }
}
