package com.example.starter.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.Clock;
import java.util.List;
import java.util.UUID;

import com.example.starter.incident.dto.Requests.DelegateRegisterRequest;
import com.example.starter.incident.dto.Requests.HandoffCreateRequest;
import com.example.starter.incident.dto.Requests.HandoffItemRequest;
import com.example.starter.incident.dto.Requests.HandoffSettleRequest;
import com.example.starter.incident.dto.Requests.ReportRequest;
import com.example.starter.incident.dto.Requests.ResourceAcquireRequest;
import com.example.starter.incident.dto.Requests.StatusRequest;
import com.example.starter.incident.dto.Requests.TakeoverRequest;
import com.example.starter.incident.dto.Requests.TaskActionRequest;
import com.example.starter.incident.dto.Requests.TaskCreateRequest;
import com.example.starter.incident.dto.Responses.CloseBlockersView;
import com.example.starter.incident.dto.Responses.HandoffSettleView;
import com.example.starter.incident.dto.Responses.HandoffView;
import com.example.starter.incident.dto.Responses.IncidentHandoffsView;
import com.example.starter.incident.dto.Responses.IncidentSettlementsView;
import com.example.starter.incident.dto.Responses.IncidentView;
import com.example.starter.incident.dto.Responses.ResourceResponsibilityView;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 互助资源交接服务测试：覆盖双事件责任、接收权限、租约校验与到期结算、
 * 目标关闭结算、已开始任务终态归还、来源关闭阻断、批量回滚与 handoffKey 幂等。
 */
@SpringBootTest
@Import(ControllableClock.Config.class)
class ResourceHandoffServiceTest {

    private static final Instant T0 = Instant.parse("2026-09-22T00:00:00Z");

    @Autowired
    private IncidentService incidentService;

    @Autowired
    private ResourceHandoffService handoffService;

    @Autowired
    private Clock clock;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM command_keys");
        jdbc.update("DELETE FROM handoff_task_refs");
        jdbc.update("DELETE FROM handoff_settlements");
        jdbc.update("DELETE FROM resource_handoff_items");
        jdbc.update("DELETE FROM resource_handoffs");
        jdbc.update("DELETE FROM incident_delegates");
        jdbc.update("DELETE FROM incident_resources");
        jdbc.update("DELETE FROM incident_status_history");
        jdbc.update("DELETE FROM incident_transfers");
        jdbc.update("DELETE FROM incident_actions");
        jdbc.update("DELETE FROM incident_task_blockers");
        jdbc.update("DELETE FROM incident_tasks");
        jdbc.update("DELETE FROM incidents");
        ((ControllableClock) clock).setInstant(T0);
    }

    private static String key() {
        return "CMD-" + UUID.randomUUID();
    }

    private IncidentView commanding(String incidentKey, String commander) {
        incidentService.report(new ReportRequest(incidentKey, "S2", "互助场景", "reporter-1"));
        return incidentService.takeover(incidentKey, commander, new TakeoverRequest(key()));
    }

    private void acquire(String incidentKey, String actor, String resourceKey) {
        handoffService.acquireResource(incidentKey, actor,
                new ResourceAcquireRequest(key(), resourceKey));
    }

    private void delegate(String incidentKey, String actor, String delegate) {
        handoffService.registerDelegate(incidentKey, actor,
                new DelegateRegisterRequest(key(), delegate));
    }

    private HandoffView handoff(String source, String actor, String target, String receiver,
                                List<HandoffItemRequest> items) {
        IncidentView s = incidentService.get(source);
        IncidentView t = incidentService.get(target);
        return handoffService.createHandoff(source, actor, new HandoffCreateRequest(
                "HO-" + UUID.randomUUID(), target, receiver, s.version(), t.version(),
                T0, T0.plusSeconds(3600), items));
    }

    private HandoffView handoff(String source, String actor, String target, String receiver,
                                String resourceKey) {
        return handoff(source, actor, target, receiver,
                List.of(new HandoffItemRequest(resourceKey, List.of())));
    }

    private static void assertApiStatus(ThrowingCallable call, HttpStatus status) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.status()).isEqualTo(status));
    }

    private static void assertApiCode(ThrowingCallable call, HttpStatus status, String code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.status()).isEqualTo(status);
            assertThat(e.code()).isEqualTo(code);
        });
    }

    private void closeIncident(String incidentKey, String actor) {
        incidentService.changeStatus(incidentKey, actor, new StatusRequest(key(), "CONTAINED"));
        incidentService.changeStatus(incidentKey, actor, new StatusRequest(key(), "RESOLVED"));
        incidentService.changeStatus(incidentKey, actor, new StatusRequest(key(), "CLOSED"));
    }

    // ---------- 主流程 ----------

    @Test
    void handoffMainFlow_responsibilityAndLeaseExpirySettlement() {
        commanding("INC-S", "alice");
        commanding("INC-T", "bob");
        acquire("INC-S", "alice", "RES-1");
        incidentService.createTask("INC-T", "bob",
                new TaskCreateRequest(key(), "T-1", "G", "未开始任务", List.of()));

        HandoffView created = handoff("INC-S", "alice", "INC-T", "bob",
                List.of(new HandoffItemRequest("RES-1", List.of("T-1"))));
        assertThat(created.status()).isEqualTo("ACTIVE");
        assertThat(created.sourceIncidentKey()).isEqualTo("INC-S");
        assertThat(created.targetIncidentKey()).isEqualTo("INC-T");
        assertThat(created.receiver()).isEqualTo("bob");
        assertThat(created.operator()).isEqualTo("alice");
        assertThat(created.leaseStart()).isEqualTo(T0);
        assertThat(created.leaseEnd()).isEqualTo(T0.plusSeconds(3600));
        assertThat(created.items()).hasSize(1);
        assertThat(created.items().get(0).resourceKey()).isEqualTo("RES-1");
        assertThat(created.items().get(0).taskKeys()).containsExactly("T-1");
        assertThat(created.items().get(0).settled()).isFalse();

        // 借出期间：资源责任方为目标事件
        ResourceResponsibilityView during = handoffService.resourceResponsibility("RES-1");
        assertThat(during.holderIncidentKey()).isEqualTo("INC-S");
        assertThat(during.responsibleIncidentKey()).isEqualTo("INC-T");
        assertThat(during.lentOut()).isTrue();
        assertThat(during.handoffKey()).isEqualTo(created.handoffKey());

        // 借出期间来源不可关闭（先走到 RESOLVED，再关闭被阻断）
        incidentService.changeStatus("INC-S", "alice", new StatusRequest(key(), "CONTAINED"));
        incidentService.changeStatus("INC-S", "alice", new StatusRequest(key(), "RESOLVED"));
        assertApiStatus(() -> incidentService.changeStatus("INC-S", "alice",
                new StatusRequest(key(), "CLOSED")), HttpStatus.CONFLICT);
        CloseBlockersView blockers = handoffService.closeBlockers("INC-S");
        assertThat(blockers.closeable()).isFalse();
        assertThat(blockers.blockers()).anySatisfy(b -> {
            assertThat(b.type()).isEqualTo("LENT_RESOURCE");
            assertThat(b.resourceKey()).isEqualTo("RES-1");
            assertThat(b.handoffKey()).isEqualTo(created.handoffKey());
        });

        // 租约到期结算：未开始任务解除资源引用，归还来源并写入不可变结算
        ((ControllableClock) clock).setInstant(T0.plusSeconds(3600));
        HandoffSettleView settled = handoffService.settleExpired("INC-T",
                new HandoffSettleRequest(key()));
        assertThat(settled.settlements()).hasSize(1);
        assertThat(settled.settlements().get(0).reason()).isEqualTo("LEASE_EXPIRED");
        assertThat(settled.settlements().get(0).resourceKey()).isEqualTo("RES-1");
        assertThat(settled.settlements().get(0).returnedToIncidentKey()).isEqualTo("INC-S");

        HandoffView after = handoffService.listHandoffs("INC-S").handoffs().get(0);
        assertThat(after.status()).isEqualTo("SETTLED");
        assertThat(after.endReason()).isEqualTo("LEASE_EXPIRED");
        assertThat(after.items().get(0).settled()).isTrue();
        assertThat(after.items().get(0).taskKeys()).isEmpty();
        assertThat(after.settlements()).hasSize(1);
        // 任务本身保留（仅解除资源引用）
        assertThat(incidentService.getTask("INC-T", "T-1").status()).isEqualTo("OPEN");

        // 归还后：责任方回到来源，来源可关闭
        ResourceResponsibilityView returned = handoffService.resourceResponsibility("RES-1");
        assertThat(returned.lentOut()).isFalse();
        assertThat(returned.responsibleIncidentKey()).isEqualTo("INC-S");
        assertThat(handoffService.closeBlockers("INC-S").closeable()).isTrue();
        incidentService.changeStatus("INC-S", "alice", new StatusRequest(key(), "CLOSED"));
        assertThat(incidentService.get("INC-S").status()).isEqualTo("CLOSED");

        // 结算查询（目标侧同样可见）
        IncidentSettlementsView settlements = handoffService.listSettlements("INC-T");
        assertThat(settlements.settlements()).hasSize(1);
        assertThat(settlements.settlements().get(0).handoffKey()).isEqualTo(created.handoffKey());
    }

    @Test
    void targetClose_triggersSettlementInSameTransaction() {
        commanding("INC-S", "alice");
        commanding("INC-T", "bob");
        acquire("INC-S", "alice", "RES-1");
        handoff("INC-S", "alice", "INC-T", "bob", "RES-1");

        // 目标关闭：同事务触发交接结束与结算归还
        closeIncident("INC-T", "bob");
        HandoffView after = handoffService.listHandoffs("INC-S").handoffs().get(0);
        assertThat(after.status()).isEqualTo("SETTLED");
        assertThat(after.endReason()).isEqualTo("TARGET_CLOSED");
        assertThat(after.settlements()).hasSize(1);
        assertThat(after.settlements().get(0).reason()).isEqualTo("TARGET_CLOSED");
        assertThat(after.settlements().get(0).returnedToIncidentKey()).isEqualTo("INC-S");
        assertThat(handoffService.resourceResponsibility("RES-1").responsibleIncidentKey())
                .isEqualTo("INC-S");
    }

    @Test
    void leaseExpiry_settlesAndStartedTaskKeepsResourceUntilTerminal() {
        commanding("INC-S", "alice");
        commanding("INC-T", "bob");
        acquire("INC-S", "alice", "RES-1");
        incidentService.createTask("INC-T", "bob",
                new TaskCreateRequest(key(), "T-1", "G", "会开始的任务", List.of()));
        HandoffView created = handoff("INC-S", "alice", "INC-T", "bob",
                List.of(new HandoffItemRequest("RES-1", List.of("T-1"))));

        // 任务开始后开始占用资源
        incidentService.startTask("INC-T", "T-1", "bob", new TaskActionRequest(key()));

        // 租约未到期：结算为空
        HandoffSettleView early = handoffService.settleExpired("INC-T",
                new HandoffSettleRequest(key()));
        assertThat(early.settlements()).isEmpty();

        // 租约到期：已开始任务继续持有资源，交接触发结束但未结算
        ((ControllableClock) clock).setInstant(T0.plusSeconds(3600));
        HandoffSettleView expired = handoffService.settleExpired("INC-T",
                new HandoffSettleRequest(key()));
        assertThat(expired.settlements()).isEmpty();
        HandoffView pending = handoffService.listHandoffs("INC-S").handoffs().get(0);
        assertThat(pending.status()).isEqualTo("ACTIVE");
        assertThat(pending.endReason()).isEqualTo("LEASE_EXPIRED");
        assertThat(pending.items().get(0).settled()).isFalse();
        assertThat(pending.items().get(0).taskKeys()).containsExactly("T-1");

        // 其间：来源不可关闭、资源不可转借
        incidentService.changeStatus("INC-S", "alice", new StatusRequest(key(), "CONTAINED"));
        incidentService.changeStatus("INC-S", "alice", new StatusRequest(key(), "RESOLVED"));
        assertApiStatus(() -> incidentService.changeStatus("INC-S", "alice",
                new StatusRequest(key(), "CLOSED")), HttpStatus.CONFLICT);
        commanding("INC-T2", "carol");
        assertApiCode(() -> handoff("INC-S", "alice", "INC-T2", "carol", "RES-1"),
                HttpStatus.UNPROCESSABLE_ENTITY, "LEASE_OVERLAP");

        // 任务终态后自动结算归还
        incidentService.completeTask("INC-T", "T-1", "bob", new TaskActionRequest(key()));
        HandoffView settled = handoffService.listHandoffs("INC-S").handoffs().get(0);
        assertThat(settled.status()).isEqualTo("SETTLED");
        assertThat(settled.endReason()).isEqualTo("LEASE_EXPIRED");
        assertThat(settled.settlements()).hasSize(1);
        assertThat(settled.settlements().get(0).reason()).isEqualTo("LEASE_EXPIRED");
        assertThat(handoffService.resourceResponsibility("RES-1").responsibleIncidentKey())
                .isEqualTo("INC-S");

        // 归还后来源可关闭、资源可再借出
        incidentService.changeStatus("INC-S", "alice", new StatusRequest(key(), "CLOSED"));
        assertThat(incidentService.get("INC-S").status()).isEqualTo("CLOSED");
    }

    @Test
    void taskTerminalBeforeHandoffEnd_refRemovedAndSettlesAtTargetClose() {
        commanding("INC-S", "alice");
        commanding("INC-T", "bob");
        acquire("INC-S", "alice", "RES-1");
        incidentService.createTask("INC-T", "bob",
                new TaskCreateRequest(key(), "T-1", "G", "先完成的任务", List.of()));
        handoff("INC-S", "alice", "INC-T", "bob",
                List.of(new HandoffItemRequest("RES-1", List.of("T-1"))));

        // 交接进行中任务到达终态：引用解除但交接不结算
        incidentService.completeTask("INC-T", "T-1", "bob", new TaskActionRequest(key()));
        HandoffView active = handoffService.listHandoffs("INC-S").handoffs().get(0);
        assertThat(active.status()).isEqualTo("ACTIVE");
        assertThat(active.items().get(0).taskKeys()).isEmpty();
        assertThat(active.items().get(0).settled()).isFalse();

        // 目标关闭时立即结算
        closeIncident("INC-T", "bob");
        HandoffView settled = handoffService.listHandoffs("INC-S").handoffs().get(0);
        assertThat(settled.status()).isEqualTo("SETTLED");
        assertThat(settled.settlements()).hasSize(1);
    }

    // ---------- 创建校验 ----------

    @Test
    void createHandoff_basicValidation() {
        commanding("INC-S", "alice");
        commanding("INC-T", "bob");
        acquire("INC-S", "alice", "RES-1");
        IncidentView s = incidentService.get("INC-S");
        IncidentView t = incidentService.get("INC-T");

        // 目标等于来源
        assertApiStatus(() -> handoffService.createHandoff("INC-S", "alice",
                new HandoffCreateRequest(key(), "INC-S", "bob", s.version(), t.version(),
                        T0, T0.plusSeconds(60),
                        List.of(new HandoffItemRequest("RES-1", List.of())))),
                HttpStatus.BAD_REQUEST);
        // 结束不晚于开始
        assertApiStatus(() -> handoffService.createHandoff("INC-S", "alice",
                new HandoffCreateRequest(key(), "INC-T", "bob", s.version(), t.version(),
                        T0.plusSeconds(60), T0,
                        List.of(new HandoffItemRequest("RES-1", List.of())))),
                HttpStatus.BAD_REQUEST);
        assertApiStatus(() -> handoffService.createHandoff("INC-S", "alice",
                new HandoffCreateRequest(key(), "INC-T", "bob", s.version(), t.version(),
                        T0, T0, List.of(new HandoffItemRequest("RES-1", List.of())))),
                HttpStatus.BAD_REQUEST);
        // 资源项为空
        assertApiStatus(() -> handoffService.createHandoff("INC-S", "alice",
                new HandoffCreateRequest(key(), "INC-T", "bob", s.version(), t.version(),
                        T0, T0.plusSeconds(60), List.of())),
                HttpStatus.BAD_REQUEST);
        // 必填字段
        assertApiStatus(() -> handoffService.createHandoff("INC-S", "alice",
                new HandoffCreateRequest(" ", "INC-T", "bob", s.version(), t.version(),
                        T0, T0.plusSeconds(60),
                        List.of(new HandoffItemRequest("RES-1", List.of())))),
                HttpStatus.BAD_REQUEST);
        assertApiStatus(() -> handoffService.createHandoff("INC-S", "alice",
                new HandoffCreateRequest(key(), "INC-T", "bob", null, t.version(),
                        T0, T0.plusSeconds(60),
                        List.of(new HandoffItemRequest("RES-1", List.of())))),
                HttpStatus.BAD_REQUEST);
        assertApiStatus(() -> handoffService.createHandoff("INC-S", "alice",
                new HandoffCreateRequest(key(), "INC-T", "bob", s.version(), t.version(),
                        null, T0.plusSeconds(60),
                        List.of(new HandoffItemRequest("RES-1", List.of())))),
                HttpStatus.BAD_REQUEST);
        // 目标事件不存在
        assertApiStatus(() -> handoffService.createHandoff("INC-S", "alice",
                new HandoffCreateRequest(key(), "INC-X", "bob", s.version(), t.version(),
                        T0, T0.plusSeconds(60),
                        List.of(new HandoffItemRequest("RES-1", List.of())))),
                HttpStatus.NOT_FOUND);
    }

    @Test
    void createHandoff_permissionAndReceiverChecks() {
        commanding("INC-S", "alice");
        commanding("INC-T", "bob");
        acquire("INC-S", "alice", "RES-1");

        // 非来源指挥人发起 → 409
        assertApiStatus(() -> handoff("INC-S", "mallory", "INC-T", "bob", "RES-1"),
                HttpStatus.CONFLICT);
        // 接收人既非目标指挥人也非登记代理人 → 422 RECEIVER_NOT_AUTHORIZED
        assertApiCode(() -> handoff("INC-S", "alice", "INC-T", "mallory", "RES-1"),
                HttpStatus.UNPROCESSABLE_ENTITY, "RECEIVER_NOT_AUTHORIZED");

        // 登记代理人后可作为接收人
        delegate("INC-T", "bob", "carol");
        HandoffView viaDelegate = handoff("INC-S", "alice", "INC-T", "carol", "RES-1");
        assertThat(viaDelegate.receiver()).isEqualTo("carol");
        assertThat(viaDelegate.status()).isEqualTo("ACTIVE");
    }

    @Test
    void createHandoff_versionAndOwnershipChecks() {
        commanding("INC-S", "alice");
        commanding("INC-T", "bob");
        acquire("INC-S", "alice", "RES-1");
        IncidentView s = incidentService.get("INC-S");
        IncidentView t = incidentService.get("INC-T");

        // 版本不匹配 → 409 VERSION_MISMATCH
        assertApiCode(() -> handoffService.createHandoff("INC-S", "alice",
                new HandoffCreateRequest(key(), "INC-T", "bob", s.version() + 1, t.version(),
                        T0, T0.plusSeconds(60),
                        List.of(new HandoffItemRequest("RES-1", List.of())))),
                HttpStatus.CONFLICT, "VERSION_MISMATCH");
        assertApiCode(() -> handoffService.createHandoff("INC-S", "alice",
                new HandoffCreateRequest(key(), "INC-T", "bob", s.version(), t.version() + 1,
                        T0, T0.plusSeconds(60),
                        List.of(new HandoffItemRequest("RES-1", List.of())))),
                HttpStatus.CONFLICT, "VERSION_MISMATCH");

        // 来源不持有资源 → 422 RESOURCE_NOT_HELD
        acquire("INC-T", "bob", "RES-2");
        assertApiCode(() -> handoff("INC-S", "alice", "INC-T", "bob", "RES-2"),
                HttpStatus.UNPROCESSABLE_ENTITY, "RESOURCE_NOT_HELD");
        // 资源不存在 → 404
        assertApiStatus(() -> handoff("INC-S", "alice", "INC-T", "bob", "RES-X"),
                HttpStatus.NOT_FOUND);

        // 状态变更使版本号变化后，旧版本指纹校验失败
        IncidentView before = incidentService.get("INC-S");
        incidentService.changeStatus("INC-S", "alice", new StatusRequest(key(), "CONTAINED"));
        assertApiCode(() -> handoffService.createHandoff("INC-S", "alice",
                new HandoffCreateRequest(key(), "INC-T", "bob", before.version(), t.version(),
                        T0, T0.plusSeconds(60),
                        List.of(new HandoffItemRequest("RES-1", List.of())))),
                HttpStatus.CONFLICT, "VERSION_MISMATCH");
    }

    @Test
    void createHandoff_closedIncidentsRejected() {
        commanding("INC-S", "alice");
        commanding("INC-T", "bob");
        acquire("INC-S", "alice", "RES-1");

        // 目标关闭后 → 422
        closeIncident("INC-T", "bob");
        assertApiStatus(() -> handoff("INC-S", "alice", "INC-T", "bob", "RES-1"),
                HttpStatus.UNPROCESSABLE_ENTITY);

        // 来源关闭后 → 422
        commanding("INC-T3", "carol");
        closeIncident("INC-S", "alice");
        assertApiStatus(() -> handoff("INC-S", "alice", "INC-T3", "carol", "RES-1"),
                HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void createHandoff_batchConflictRollsBackEverything() {
        commanding("INC-S", "alice");
        commanding("INC-T", "bob");
        acquire("INC-S", "alice", "RES-1");
        acquire("INC-T", "bob", "RES-2");
        incidentService.createTask("INC-T", "bob",
                new TaskCreateRequest(key(), "T-1", "G", "目标任务", List.of()));
        IncidentView s = incidentService.get("INC-S");
        IncidentView t = incidentService.get("INC-T");
        String handoffKey = "HO-BATCH-" + UUID.randomUUID();

        // 批内资源重复 → 422 HANDOFF_CONFLICT
        assertApiCode(() -> handoffService.createHandoff("INC-S", "alice",
                new HandoffCreateRequest(handoffKey, "INC-T", "bob", s.version(), t.version(),
                        T0, T0.plusSeconds(60), List.of(
                        new HandoffItemRequest("RES-1", List.of()),
                        new HandoffItemRequest("RES-1", List.of())))),
                HttpStatus.UNPROCESSABLE_ENTITY, "HANDOFF_CONFLICT");

        // 第二项来源不持有 → 422 且整体回滚：无交接、无资源项、无任务引用，handoffKey 不占键
        assertApiCode(() -> handoffService.createHandoff("INC-S", "alice",
                new HandoffCreateRequest(handoffKey, "INC-T", "bob", s.version(), t.version(),
                        T0, T0.plusSeconds(60), List.of(
                        new HandoffItemRequest("RES-1", List.of("T-1")),
                        new HandoffItemRequest("RES-2", List.of())))),
                HttpStatus.UNPROCESSABLE_ENTITY, "RESOURCE_NOT_HELD");
        Integer handoffCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM resource_handoffs", Integer.class);
        Integer itemCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM resource_handoff_items", Integer.class);
        Integer refCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM handoff_task_refs", Integer.class);
        assertThat(handoffCount).isZero();
        assertThat(itemCount).isZero();
        assertThat(refCount).isZero();

        // 失败不占键：修正参数后同键成功
        HandoffView created = handoffService.createHandoff("INC-S", "alice",
                new HandoffCreateRequest(handoffKey, "INC-T", "bob", s.version(), t.version(),
                        T0, T0.plusSeconds(60),
                        List.of(new HandoffItemRequest("RES-1", List.of("T-1")))));
        assertThat(created.handoffKey()).isEqualTo(handoffKey);
        assertThat(created.items().get(0).taskKeys()).containsExactly("T-1");

        // 重叠租约：同资源再次借出 → 422 LEASE_OVERLAP
        assertApiCode(() -> handoff("INC-S", "alice", "INC-T", "bob", "RES-1"),
                HttpStatus.UNPROCESSABLE_ENTITY, "LEASE_OVERLAP");
    }

    @Test
    void createHandoff_terminalTaskReferenceRejected() {
        commanding("INC-S", "alice");
        commanding("INC-T", "bob");
        acquire("INC-S", "alice", "RES-1");
        incidentService.createTask("INC-T", "bob",
                new TaskCreateRequest(key(), "T-1", "G", "已取消任务", List.of()));
        incidentService.cancelTask("INC-T", "T-1", "bob", new TaskActionRequest(key()));

        assertApiCode(() -> handoff("INC-S", "alice", "INC-T", "bob",
                List.of(new HandoffItemRequest("RES-1", List.of("T-1")))),
                HttpStatus.UNPROCESSABLE_ENTITY, "TASK_TERMINAL");
        // 目标任务不存在 → 404
        assertApiStatus(() -> handoff("INC-S", "alice", "INC-T", "bob",
                List.of(new HandoffItemRequest("RES-1", List.of("T-X")))),
                HttpStatus.NOT_FOUND);
    }

    // ---------- 幂等 ----------

    @Test
    void handoffKey_idempotentReplayAndConflict() {
        commanding("INC-S", "alice");
        commanding("INC-T", "bob");
        acquire("INC-S", "alice", "RES-1");
        IncidentView s = incidentService.get("INC-S");
        IncidentView t = incidentService.get("INC-T");
        String handoffKey = "HO-" + UUID.randomUUID();
        HandoffCreateRequest request = new HandoffCreateRequest(handoffKey, "INC-T", "bob",
                s.version(), t.version(), T0, T0.plusSeconds(60),
                List.of(new HandoffItemRequest("RES-1", List.of())));

        HandoffView first = handoffService.createHandoff("INC-S", "alice", request);
        HandoffView replay = handoffService.createHandoff("INC-S", "alice", request);
        assertThat(replay).isEqualTo(first);
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM resource_handoffs", Integer.class);
        assertThat(count).isEqualTo(1);

        // 同键改参 → 409
        assertApiStatus(() -> handoffService.createHandoff("INC-S", "alice",
                new HandoffCreateRequest(handoffKey, "INC-T", "bob", s.version(), t.version(),
                        T0, T0.plusSeconds(120),
                        List.of(new HandoffItemRequest("RES-1", List.of())))),
                HttpStatus.CONFLICT);
    }

    // ---------- 资源与代理人登记 ----------

    @Test
    void acquireResource_validationAndUniqueness() {
        commanding("INC-S", "alice");
        commanding("INC-T", "bob");
        // 非指挥人 → 409
        assertApiStatus(() -> handoffService.acquireResource("INC-S", "mallory",
                new ResourceAcquireRequest(key(), "RES-1")), HttpStatus.CONFLICT);
        // 空白键 → 400
        assertApiStatus(() -> handoffService.acquireResource("INC-S", "alice",
                new ResourceAcquireRequest(key(), " ")), HttpStatus.BAD_REQUEST);

        handoffService.acquireResource("INC-S", "alice", new ResourceAcquireRequest(key(), "RES-1"));
        // 全局唯一：他事件登记同键 → 409
        assertApiStatus(() -> handoffService.acquireResource("INC-T", "bob",
                new ResourceAcquireRequest(key(), "RES-1")), HttpStatus.CONFLICT);

        assertThat(handoffService.listResources("INC-S").resources())
                .extracting("resourceKey").containsExactly("RES-1");
        assertThat(handoffService.resourceResponsibility("RES-1").responsibleIncidentKey())
                .isEqualTo("INC-S");
        assertApiStatus(() -> handoffService.resourceResponsibility("RES-X"),
                HttpStatus.NOT_FOUND);

        // 事件关闭后禁止登记
        closeIncident("INC-S", "alice");
        assertApiStatus(() -> handoffService.acquireResource("INC-S", "alice",
                new ResourceAcquireRequest(key(), "RES-2")), HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void registerDelegate_validationAndIdempotence() {
        commanding("INC-T", "bob");
        // 非指挥人 → 409
        assertApiStatus(() -> handoffService.registerDelegate("INC-T", "mallory",
                new DelegateRegisterRequest(key(), "carol")), HttpStatus.CONFLICT);
        // 指挥人本人 → 400
        assertApiStatus(() -> handoffService.registerDelegate("INC-T", "bob",
                new DelegateRegisterRequest(key(), "bob")), HttpStatus.BAD_REQUEST);

        handoffService.registerDelegate("INC-T", "bob", new DelegateRegisterRequest(key(), "carol"));
        // 同人重复登记幂等返回
        handoffService.registerDelegate("INC-T", "bob", new DelegateRegisterRequest(key(), "carol"));
        assertThat(handoffService.listDelegates("INC-T").delegates())
                .extracting("delegate").containsExactly("carol");
    }

    @Test
    void listHandoffs_bothSidesVisible() {
        commanding("INC-S", "alice");
        commanding("INC-T", "bob");
        acquire("INC-S", "alice", "RES-1");
        HandoffView created = handoff("INC-S", "alice", "INC-T", "bob", "RES-1");

        IncidentHandoffsView sourceSide = handoffService.listHandoffs("INC-S");
        IncidentHandoffsView targetSide = handoffService.listHandoffs("INC-T");
        assertThat(sourceSide.handoffs()).hasSize(1);
        assertThat(targetSide.handoffs()).hasSize(1);
        assertThat(sourceSide.handoffs().get(0).handoffKey()).isEqualTo(created.handoffKey());
        assertThat(targetSide.handoffs().get(0).handoffKey()).isEqualTo(created.handoffKey());
        assertApiStatus(() -> handoffService.listHandoffs("INC-X"), HttpStatus.NOT_FOUND);
    }
}
