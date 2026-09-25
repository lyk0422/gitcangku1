package com.example.starter.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.example.starter.incident.dto.Requests.DispatchItem;
import com.example.starter.incident.dto.Requests.DispatchRequest;
import com.example.starter.incident.dto.Requests.ExemptionGrantRequest;
import com.example.starter.incident.dto.Requests.ReportRequest;
import com.example.starter.incident.dto.Requests.StatusRequest;
import com.example.starter.incident.dto.Requests.TakeoverRequest;
import com.example.starter.incident.dto.Requests.TaskActionRequest;
import com.example.starter.incident.dto.Requests.TaskCreateRequest;
import com.example.starter.incident.dto.Requests.ZoneRegisterRequest;
import com.example.starter.incident.dto.Requests.ZoneReviseRequest;
import com.example.starter.incident.dto.Responses.DispatchView;
import com.example.starter.incident.dto.Responses.ExemptionView;
import com.example.starter.incident.dto.Responses.IncidentExemptionsView;
import com.example.starter.incident.dto.Responses.IncidentView;
import com.example.starter.incident.dto.Responses.IncidentZoneBlocksView;
import com.example.starter.incident.dto.Responses.IncidentZonesView;
import com.example.starter.incident.dto.Responses.TaskView;
import com.example.starter.incident.dto.Responses.ZoneView;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 疏散区域与高危任务门禁服务测试：覆盖区域登记/修订/重叠规则、zoneKey 指纹重放、
 * 区域生效阻断与结束恢复、撤离终态、豁免作用域、批量派工事务回滚与解决门禁。
 * 使用可控 Clock，时间相关断言不依赖真实睡眠。
 */
@SpringBootTest
@Import(ControllableClock.Config.class)
class EvacuationZoneServiceTest {

    private static final Instant T0 = Instant.parse("2026-09-22T00:00:00Z");

    @Autowired
    private IncidentService incidentService;

    @Autowired
    private EvacuationService zones;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Clock clock;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM command_keys");
        jdbc.update("DELETE FROM incident_status_history");
        jdbc.update("DELETE FROM incident_transfers");
        jdbc.update("DELETE FROM incident_actions");
        jdbc.update("DELETE FROM incident_task_blockers");
        jdbc.update("DELETE FROM incident_task_zone_blocks");
        jdbc.update("DELETE FROM zone_exemptions");
        jdbc.update("DELETE FROM resource_leases");
        jdbc.update("DELETE FROM incident_zones");
        jdbc.update("DELETE FROM incident_tasks");
        jdbc.update("DELETE FROM incidents");
        ((ControllableClock) clock).setInstant(T0);
    }

    private static String key() {
        return "CMD-" + UUID.randomUUID();
    }

    private IncidentView commanding(String incidentKey, String commander) {
        incidentService.report(new ReportRequest(incidentKey, "S2", "核心链路故障", "reporter-1"));
        return incidentService.takeover(incidentKey, commander, new TakeoverRequest(key()));
    }

    private TaskView createHighRiskTask(String incidentKey, String actor, String taskKey,
                                        List<String> workGrids, String finalPosition) {
        return incidentService.createTask(incidentKey, actor,
                new TaskCreateRequest(key(), taskKey, "G", "高危作业", List.of(),
                        true, workGrids, finalPosition));
    }

    private static void assertApiStatus(ThrowingCallable call, HttpStatus status) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.status()).isEqualTo(status));
    }

    @SuppressWarnings("unchecked")
    private static List<Object> details(ApiException e) {
        return (List<Object>) e.details();
    }

    // ---------- 区域登记 ----------

    @Test
    void registerZone_mainFlow_normalizesAndSortsGrids() {
        commanding("INC-Z1", "alice");
        ZoneView zone = zones.registerZone("INC-Z1", "alice",
                new ZoneRegisterRequest(key(), List.of(" b2 ", "A1", "a1", "C3"),
                        T0.plusSeconds(3600), T0.plusSeconds(7200), "high"));
        assertThat(zone.zoneKey()).startsWith("Z-");
        assertThat(zone.version()).isEqualTo(1);
        assertThat(zone.groupKey()).isEqualTo(zone.zoneKey());
        // 规范化：去空白、大写、去重、字典序排序
        assertThat(zone.grids()).containsExactly("A1", "B2", "C3");
        assertThat(zone.riskLevel()).isEqualTo("HIGH");
        assertThat(zone.latest()).isTrue();
        assertThat(zone.effective()).isFalse();
        assertThat(zone.operator()).isEqualTo("alice");

        // 窗口内生效标记
        ((ControllableClock) clock).setInstant(T0.plusSeconds(3600));
        IncidentZonesView view = zones.listZones("INC-Z1");
        assertThat(view.zones()).hasSize(1);
        assertThat(view.zones().get(0).effective()).isTrue();
        // 右开：终点时刻不再生效
        ((ControllableClock) clock).setInstant(T0.plusSeconds(7200));
        assertThat(zones.listZones("INC-Z1").zones().get(0).effective()).isFalse();
    }

    @Test
    void registerZone_validation() {
        commanding("INC-ZV", "alice");
        // 网格为空 / 非法网格码
        assertApiStatus(() -> zones.registerZone("INC-ZV", "alice",
                new ZoneRegisterRequest(key(), List.of(), T0, T0.plusSeconds(60), "LOW")),
                HttpStatus.BAD_REQUEST);
        assertApiStatus(() -> zones.registerZone("INC-ZV", "alice",
                new ZoneRegisterRequest(key(), List.of("!!"), T0, T0.plusSeconds(60), "LOW")),
                HttpStatus.BAD_REQUEST);
        // 窗口非法：空窗口、起点不早于终点
        assertApiStatus(() -> zones.registerZone("INC-ZV", "alice",
                new ZoneRegisterRequest(key(), List.of("A1"), null, T0, "LOW")),
                HttpStatus.BAD_REQUEST);
        assertApiStatus(() -> zones.registerZone("INC-ZV", "alice",
                new ZoneRegisterRequest(key(), List.of("A1"), T0, T0, "LOW")),
                HttpStatus.BAD_REQUEST);
        assertApiStatus(() -> zones.registerZone("INC-ZV", "alice",
                new ZoneRegisterRequest(key(), List.of("A1"), T0.plusSeconds(60), T0, "LOW")),
                HttpStatus.BAD_REQUEST);
        // 等级非法
        assertApiStatus(() -> zones.registerZone("INC-ZV", "alice",
                new ZoneRegisterRequest(key(), List.of("A1"), T0, T0.plusSeconds(60), "X")),
                HttpStatus.BAD_REQUEST);
        // 事件不存在
        assertApiStatus(() -> zones.registerZone("INC-404", "alice",
                new ZoneRegisterRequest(key(), List.of("A1"), T0, T0.plusSeconds(60), "LOW")),
                HttpStatus.NOT_FOUND);
        assertThat(zones.listZones("INC-ZV").zones()).isEmpty();
    }

    @Test
    void registerZone_resolvedOrClosed_rejected() {
        commanding("INC-ZC", "alice");
        incidentService.changeStatus("INC-ZC", "alice", new StatusRequest(key(), "CONTAINED"));
        incidentService.changeStatus("INC-ZC", "alice", new StatusRequest(key(), "RESOLVED"));
        assertApiStatus(() -> zones.registerZone("INC-ZC", "alice",
                new ZoneRegisterRequest(key(), List.of("A1"), T0, T0.plusSeconds(60), "LOW")),
                HttpStatus.UNPROCESSABLE_ENTITY);
        incidentService.changeStatus("INC-ZC", "alice", new StatusRequest(key(), "CLOSED"));
        assertApiStatus(() -> zones.registerZone("INC-ZC", "alice",
                new ZoneRegisterRequest(key(), List.of("A1"), T0, T0.plusSeconds(60), "LOW")),
                HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void registerZone_overlapRules() {
        commanding("INC-ZO", "alice");
        zones.registerZone("INC-ZO", "alice",
                new ZoneRegisterRequest(key(), List.of("A1", "A2"), T0.plusSeconds(100),
                        T0.plusSeconds(200), "HIGH"));
        // 同事件同等级、网格相交且窗口相交 → 409
        assertApiStatus(() -> zones.registerZone("INC-ZO", "alice",
                new ZoneRegisterRequest(key(), List.of("A2"), T0.plusSeconds(150),
                        T0.plusSeconds(250), "HIGH")),
                HttpStatus.CONFLICT);
        // 网格不相交 → 允许
        zones.registerZone("INC-ZO", "alice",
                new ZoneRegisterRequest(key(), List.of("B1"), T0.plusSeconds(150),
                        T0.plusSeconds(250), "HIGH"));
        // 等级不同 → 允许
        zones.registerZone("INC-ZO", "alice",
                new ZoneRegisterRequest(key(), List.of("A2"), T0.plusSeconds(150),
                        T0.plusSeconds(250), "LOW"));
        // 窗口相邻（左闭右开，终点=起点不重叠）→ 允许
        zones.registerZone("INC-ZO", "alice",
                new ZoneRegisterRequest(key(), List.of("A2"), T0.plusSeconds(200),
                        T0.plusSeconds(300), "HIGH"));
        assertThat(zones.listZones("INC-ZO").zones()).hasSize(4);
    }

    @Test
    void registerZone_sameFingerprintReplay_failureDoesNotOccupyKey() {
        commanding("INC-ZR", "alice");
        ZoneRegisterRequest req = new ZoneRegisterRequest(key(), List.of("A1"),
                T0.plusSeconds(100), T0.plusSeconds(200), "HIGH");
        ZoneView first = zones.registerZone("INC-ZR", "alice", req);
        // 不同 commandKey、同内容同操作者：zoneKey 指纹相同，重放首次结果，不产生新区域
        ZoneView replay = zones.registerZone("INC-ZR", "alice",
                new ZoneRegisterRequest(key(), req.grids(), req.effectiveFrom(),
                        req.effectiveTo(), req.riskLevel()));
        assertThat(replay.zoneKey()).isEqualTo(first.zoneKey());
        assertThat(zones.listZones("INC-ZR").zones()).hasSize(1);

        // 失败不占键：重叠冲突的 commandKey 可在修正参数后复用
        String failedKey = key();
        assertApiStatus(() -> zones.registerZone("INC-ZR", "alice",
                new ZoneRegisterRequest(failedKey, List.of("A1"), T0.plusSeconds(150),
                        T0.plusSeconds(250), "HIGH")),
                HttpStatus.CONFLICT);
        ZoneView ok = zones.registerZone("INC-ZR", "alice",
                new ZoneRegisterRequest(failedKey, List.of("C9"), T0.plusSeconds(150),
                        T0.plusSeconds(250), "HIGH"));
        assertThat(ok.grids()).containsExactly("C9");
        Integer keyCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM command_keys WHERE command_key = ?", Integer.class,
                failedKey);
        assertThat(keyCount).isEqualTo(1);
    }

    // ---------- 区域修订与豁免版本 ----------

    @Test
    void reviseZone_versionBump_invalidatesOldExemptions() {
        commanding("INC-ZS", "alice");
        ZoneView v1 = zones.registerZone("INC-ZS", "alice",
                new ZoneRegisterRequest(key(), List.of("A1"), T0.plusSeconds(100),
                        T0.plusSeconds(200), "HIGH"));
        ExemptionView exemption = zones.grantExemption("INC-ZS", "alice",
                new ExemptionGrantRequest(key(), "T-1", v1.zoneKey(), "预授权"));
        assertThat(exemption.zoneVersion()).isEqualTo(1);
        assertThat(exemption.valid()).isTrue();

        ZoneView v2 = zones.reviseZone("INC-ZS", v1.zoneKey(), "alice",
                new ZoneReviseRequest(key(), List.of("A1", "A2"), T0.plusSeconds(300),
                        T0.plusSeconds(400)));
        assertThat(v2.version()).isEqualTo(2);
        assertThat(v2.groupKey()).isEqualTo(v1.groupKey());
        assertThat(v2.zoneKey()).isNotEqualTo(v1.zoneKey());
        assertThat(v2.latest()).isTrue();

        // 旧版本被取代，旧版本豁免失效
        IncidentZonesView all = zones.listZones("INC-ZS");
        assertThat(all.zones()).hasSize(2);
        assertThat(all.zones().get(0).latest()).isFalse();
        IncidentExemptionsView exemptions = zones.listExemptions("INC-ZS");
        assertThat(exemptions.exemptions()).hasSize(1);
        assertThat(exemptions.exemptions().get(0).valid()).isFalse();

        // 已取代版本不能再次修订，也不能对其签发豁免
        assertApiStatus(() -> zones.reviseZone("INC-ZS", v1.zoneKey(), "alice",
                new ZoneReviseRequest(key(), List.of("A1"), T0, T0.plusSeconds(50))),
                HttpStatus.CONFLICT);
        assertApiStatus(() -> zones.grantExemption("INC-ZS", "alice",
                new ExemptionGrantRequest(key(), "T-2", v1.zoneKey(), "x")),
                HttpStatus.CONFLICT);
        // 区域不存在 / 不属于该事件
        assertApiStatus(() -> zones.reviseZone("INC-ZS", "Z-404", "alice",
                new ZoneReviseRequest(key(), List.of("A1"), T0, T0.plusSeconds(50))),
                HttpStatus.NOT_FOUND);
    }

    @Test
    void grantExemption_idempotencyAndScope() {
        commanding("INC-ZE", "alice");
        ZoneView zone = zones.registerZone("INC-ZE", "alice",
                new ZoneRegisterRequest(key(), List.of("A1"), T0.plusSeconds(100),
                        T0.plusSeconds(200), "HIGH"));
        // 同任务同区域版本重复签发同内容：幂等返回
        ExemptionView first = zones.grantExemption("INC-ZE", "alice",
                new ExemptionGrantRequest(key(), "T-1", zone.zoneKey(), "理由"));
        ExemptionView again = zones.grantExemption("INC-ZE", "alice",
                new ExemptionGrantRequest(key(), "T-1", zone.zoneKey(), "理由"));
        assertThat(again).isEqualTo(first);
        assertThat(zones.listExemptions("INC-ZE").exemptions()).hasSize(1);
        // 同键不同理由 → 409
        assertApiStatus(() -> zones.grantExemption("INC-ZE", "alice",
                new ExemptionGrantRequest(key(), "T-1", zone.zoneKey(), "另一理由")),
                HttpStatus.CONFLICT);
        // 豁免作用域：T-1 的豁免不覆盖 T-2
        assertThat(zones.listExemptions("INC-ZE").exemptions().get(0).taskKey()).isEqualTo("T-1");
        // 区域不存在 → 404
        assertApiStatus(() -> zones.grantExemption("INC-ZE", "alice",
                new ExemptionGrantRequest(key(), "T-1", "Z-404", "理由")),
                HttpStatus.NOT_FOUND);
    }

    // ---------- 区域生效阻断与结束恢复 ----------

    @Test
    void zoneActivation_blocksOpenHitTasks_withSnapshot_andRecoveryAfterEnd() {
        commanding("INC-ZB", "alice");
        createHighRiskTask("INC-ZB", "alice", "T-1", List.of("A1"), "Z9");
        incidentService.createTask("INC-ZB", "alice",
                new TaskCreateRequest(key(), "T-2", "G", "普通任务", List.of(),
                        false, List.of("B1"), null));

        // 登记未来窗口区域：尚未生效，不阻断
        zones.registerZone("INC-ZB", "alice",
                new ZoneRegisterRequest(key(), List.of("A1", "B1"), T0.plusSeconds(100),
                        T0.plusSeconds(200), "HIGH"));
        assertThat(incidentService.getTask("INC-ZB", "T-1").status()).isEqualTo("OPEN");

        // 进入窗口：未开始命中任务转 EVACUATION_BLOCKED 并固化区域快照
        ((ControllableClock) clock).setInstant(T0.plusSeconds(150));
        TaskView blocked = incidentService.getTask("INC-ZB", "T-1");
        assertThat(blocked.status()).isEqualTo("EVACUATION_BLOCKED");
        assertThat(incidentService.getTask("INC-ZB", "T-2").status())
                .isEqualTo("EVACUATION_BLOCKED");
        IncidentZoneBlocksView blocks = zones.listBlocks("INC-ZB");
        assertThat(blocks.blocks()).hasSize(2);
        assertThat(blocks.blocks()).allSatisfy(b -> {
            assertThat(b.active()).isTrue();
            assertThat(b.zoneVersion()).isEqualTo(1);
            assertThat(b.zoneGrids()).containsExactly("A1", "B1");
            assertThat(b.zoneEffectiveFrom()).isEqualTo(T0.plusSeconds(100));
            assertThat(b.zoneEffectiveTo()).isEqualTo(T0.plusSeconds(200));
            assertThat(b.riskLevel()).isEqualTo("HIGH");
            assertThat(b.releasedAt()).isNull();
        });

        // 阻断中不能开始（422）
        assertApiStatus(() -> zones.startTask("INC-ZB", "T-1", "alice",
                new TaskActionRequest(key())), HttpStatus.UNPROCESSABLE_ENTITY);

        // 区域结束：未开始任务恢复为可开始，但仍须满足其余门禁（非指挥人 409）
        ((ControllableClock) clock).setInstant(T0.plusSeconds(250));
        assertThat(incidentService.getTask("INC-ZB", "T-1").status()).isEqualTo("OPEN");
        assertApiStatus(() -> zones.startTask("INC-ZB", "T-1", "bob",
                new TaskActionRequest(key())), HttpStatus.CONFLICT);
        TaskView started = zones.startTask("INC-ZB", "T-1", "alice",
                new TaskActionRequest(key()));
        assertThat(started.status()).isEqualTo("IN_PROGRESS");
        assertThat(started.startedBy()).isEqualTo("alice");
        // 阻断快照已解除
        assertThat(zones.listBlocks("INC-ZB").blocks())
                .allSatisfy(b -> assertThat(b.active()).isFalse());
    }

    @Test
    void exemption_preventsBlock_andGrantAfterBlock_unblocks() {
        commanding("INC-ZX", "alice");
        createHighRiskTask("INC-ZX", "alice", "T-1", List.of("A1"), null);
        createHighRiskTask("INC-ZX", "alice", "T-2", List.of("A1"), null);
        ZoneView zone = zones.registerZone("INC-ZX", "alice",
                new ZoneRegisterRequest(key(), List.of("A1"), T0.plusSeconds(100),
                        T0.plusSeconds(200), "HIGH"));
        // T-2 预授权豁免
        zones.grantExemption("INC-ZX", "alice",
                new ExemptionGrantRequest(key(), "T-2", zone.zoneKey(), "关键抢修"));

        ((ControllableClock) clock).setInstant(T0.plusSeconds(150));
        // T-1 无豁免被阻断；T-2 持有效豁免不被阻断，可开始并完成
        assertThat(incidentService.getTask("INC-ZX", "T-1").status())
                .isEqualTo("EVACUATION_BLOCKED");
        TaskView started = zones.startTask("INC-ZX", "T-2", "alice",
                new TaskActionRequest(key()));
        assertThat(started.status()).isEqualTo("IN_PROGRESS");
        TaskView done = incidentService.completeTask("INC-ZX", "T-2", "alice",
                new TaskActionRequest(key()));
        assertThat(done.status()).isEqualTo("DONE");

        // 阻断中补发有效豁免：解除阻断恢复 OPEN
        zones.grantExemption("INC-ZX", "alice",
                new ExemptionGrantRequest(key(), "T-1", zone.zoneKey(), "补充豁免"));
        assertThat(incidentService.getTask("INC-ZX", "T-1").status()).isEqualTo("OPEN");
        TaskView startedT1 = zones.startTask("INC-ZX", "T-1", "alice",
                new TaskActionRequest(key()));
        assertThat(startedT1.status()).isEqualTo("IN_PROGRESS");
    }

    // ---------- 高危任务创建/开始门禁 ----------

    @Test
    void createTask_highRiskGate_requiresExemptionForEffectiveZone() {
        commanding("INC-ZG", "alice");
        ZoneView zone = zones.registerZone("INC-ZG", "alice",
                new ZoneRegisterRequest(key(), List.of("A1"), T0.plusSeconds(100),
                        T0.plusSeconds(200), "HIGH"));
        ((ControllableClock) clock).setInstant(T0.plusSeconds(150));

        // 高危任务命中有效区域且无豁免 → 422，且不留任务
        assertThatThrownBy(() -> createHighRiskTask("INC-ZG", "alice", "T-1", List.of("A1"),
                null))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(details(e).toString()).contains(zone.zoneKey());
                });
        assertThat(incidentService.listTasks("INC-ZG").tasks()).isEmpty();

        // 预授权豁免后创建成功
        zones.grantExemption("INC-ZG", "alice",
                new ExemptionGrantRequest(key(), "T-1", zone.zoneKey(), "预授权"));
        TaskView created = createHighRiskTask("INC-ZG", "alice", "T-1", List.of("A1"), null);
        assertThat(created.status()).isEqualTo("OPEN");
        assertThat(created.highRisk()).isTrue();
        assertThat(created.workGrids()).containsExactly("A1");

        // 非高危任务不受豁免门禁约束
        TaskView normal = incidentService.createTask("INC-ZG", "alice",
                new TaskCreateRequest(key(), "T-2", "G", "普通", List.of(),
                        false, List.of("A1"), null));
        assertThat(normal.status()).isEqualTo("OPEN");

        // 高危任务必须指定作业网格
        assertApiStatus(() -> incidentService.createTask("INC-ZG", "alice",
                new TaskCreateRequest(key(), "T-3", "G", "t", List.of(), true, null, null)),
                HttpStatus.BAD_REQUEST);
    }

    @Test
    void startTask_gates() {
        commanding("INC-ZT", "alice");
        createHighRiskTask("INC-ZT", "alice", "T-1", List.of("A1"), null);
        // 区域未生效时可开始
        zones.registerZone("INC-ZT", "alice",
                new ZoneRegisterRequest(key(), List.of("A1"), T0.plusSeconds(100),
                        T0.plusSeconds(200), "HIGH"));
        // 重复开始：IN_PROGRESS 后再开始 → 409
        zones.startTask("INC-ZT", "T-1", "alice", new TaskActionRequest(key()));
        assertApiStatus(() -> zones.startTask("INC-ZT", "T-1", "alice",
                new TaskActionRequest(key())), HttpStatus.CONFLICT);
        // 终态任务不能开始
        incidentService.completeTask("INC-ZT", "T-1", "alice", new TaskActionRequest(key()));
        assertApiStatus(() -> zones.startTask("INC-ZT", "T-1", "alice",
                new TaskActionRequest(key())), HttpStatus.CONFLICT);
        // 任务不存在
        assertApiStatus(() -> zones.startTask("INC-ZT", "T-9", "alice",
                new TaskActionRequest(key())), HttpStatus.NOT_FOUND);
    }

    // ---------- 撤离登记与终态 ----------

    @Test
    void evacuate_onlyInProgressHitTasks_terminalCannotComplete() {
        commanding("INC-ZQ", "alice");
        ZoneView zone = zones.registerZone("INC-ZQ", "alice",
                new ZoneRegisterRequest(key(), List.of("A1"), T0.plusSeconds(100),
                        T0.plusSeconds(200), "HIGH"));
        createHighRiskTask("INC-ZQ", "alice", "T-1", List.of("A1"), null);
        createHighRiskTask("INC-ZQ", "alice", "T-2", List.of("C8"), null);
        zones.grantExemption("INC-ZQ", "alice",
                new ExemptionGrantRequest(key(), "T-1", zone.zoneKey(), "预授权"));

        // 未开始任务不能登记撤离（422）
        assertApiStatus(() -> zones.evacuate("INC-ZQ", "T-1", "alice",
                new TaskActionRequest(key())), HttpStatus.UNPROCESSABLE_ENTITY);

        ((ControllableClock) clock).setInstant(T0.plusSeconds(150));
        zones.startTask("INC-ZQ", "T-1", "alice", new TaskActionRequest(key()));
        zones.startTask("INC-ZQ", "T-2", "alice", new TaskActionRequest(key()));

        // 未命中有效区域的进行中任务不能登记撤离（422）
        assertApiStatus(() -> zones.evacuate("INC-ZQ", "T-2", "alice",
                new TaskActionRequest(key())), HttpStatus.UNPROCESSABLE_ENTITY);

        // 进行中命中任务登记撤离 → EVACUATED 终态，不可完成
        TaskView evacuated = zones.evacuate("INC-ZQ", "T-1", "alice",
                new TaskActionRequest(key()));
        assertThat(evacuated.status()).isEqualTo("EVACUATED");
        assertThat(evacuated.evacuatedBy()).isEqualTo("alice");
        assertThat(evacuated.evacuatedAt()).isNotNull();
        assertApiStatus(() -> incidentService.completeTask("INC-ZQ", "T-1", "alice",
                new TaskActionRequest(key())), HttpStatus.CONFLICT);
        assertApiStatus(() -> zones.evacuate("INC-ZQ", "T-1", "alice",
                new TaskActionRequest(key())), HttpStatus.CONFLICT);

        // EVACUATED 视为已了结：不阻塞事件解决
        incidentService.completeTask("INC-ZQ", "T-2", "alice", new TaskActionRequest(key()));
        incidentService.changeStatus("INC-ZQ", "alice", new StatusRequest(key(), "CONTAINED"));
        IncidentView resolved = incidentService.changeStatus("INC-ZQ", "alice",
                new StatusRequest(key(), "RESOLVED"));
        assertThat(resolved.status()).isEqualTo("RESOLVED");
    }

    // ---------- 批量派工 ----------

    @Test
    void dispatch_success_acquiresLeasesAtomically() {
        commanding("INC-ZD", "alice");
        createHighRiskTask("INC-ZD", "alice", "T-1", List.of("A1"), "P1");
        incidentService.createTask("INC-ZD", "alice",
                new TaskCreateRequest(key(), "T-2", "G", "普通", List.of(),
                        false, List.of(), "P2"));

        DispatchView view = zones.dispatch("INC-ZD", "alice",
                new DispatchRequest(key(), List.of(
                        new DispatchItem("T-1", null, List.of("CRANE-1")),
                        new DispatchItem("T-2", "P9", List.of("CRANE-2", "TRUCK-1")))));
        assertThat(view.tasks()).hasSize(2);
        assertThat(view.tasks()).allSatisfy(t -> {
            assertThat(t.status()).isEqualTo("IN_PROGRESS");
            assertThat(t.startedBy()).isEqualTo("alice");
        });
        // 请求覆盖最终位置
        assertThat(view.tasks().get(1).finalPosition()).isEqualTo("P9");
        assertThat(view.leasedResources()).containsExactlyInAnyOrder("CRANE-1", "CRANE-2",
                "TRUCK-1");
        Integer activeLeases = jdbc.queryForObject(
                "SELECT COUNT(*) FROM resource_leases WHERE status = 'ACTIVE'", Integer.class);
        assertThat(activeLeases).isEqualTo(3);
    }

    @Test
    void dispatch_anyViolation_rollsBackLeasesAndStatuses() {
        commanding("INC-ZF", "alice");
        ZoneView zone = zones.registerZone("INC-ZF", "alice",
                new ZoneRegisterRequest(key(), List.of("A1"), T0.plusSeconds(100),
                        T0.plusSeconds(200), "HIGH"));
        createHighRiskTask("INC-ZF", "alice", "T-1", List.of("A1"), "P1");
        incidentService.createTask("INC-ZF", "alice",
                new TaskCreateRequest(key(), "T-2", "G", "普通", List.of(),
                        false, List.of(), "P2"));
        ((ControllableClock) clock).setInstant(T0.plusSeconds(150));

        // T-1 命中有效区域且无豁免（任一缺失）→ 422，T-2 的租约与状态全部回滚
        assertThatThrownBy(() -> zones.dispatch("INC-ZF", "alice",
                new DispatchRequest(key(), List.of(
                        new DispatchItem("T-1", null, List.of("CRANE-1")),
                        new DispatchItem("T-2", null, List.of("CRANE-2"))))))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(details(e).toString()).contains("T-1");
                });
        assertThat(incidentService.getTask("INC-ZF", "T-2").status()).isEqualTo("OPEN");
        Integer leaseCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM resource_leases", Integer.class);
        assertThat(leaseCount).isZero();

        // 补发豁免后派工成功
        zones.grantExemption("INC-ZF", "alice",
                new ExemptionGrantRequest(key(), "T-1", zone.zoneKey(), "补充豁免"));
        DispatchView ok = zones.dispatch("INC-ZF", "alice",
                new DispatchRequest(key(), List.of(
                        new DispatchItem("T-1", null, List.of("CRANE-1")),
                        new DispatchItem("T-2", null, List.of("CRANE-2")))));
        assertThat(ok.tasks()).hasSize(2);
    }

    @Test
    void dispatch_validation_missingPosition_resourceConflict_batchDuplicate() {
        commanding("INC-ZP", "alice");
        incidentService.createTask("INC-ZP", "alice",
                new TaskCreateRequest(key(), "T-1", "G", "t1", List.of()));
        incidentService.createTask("INC-ZP", "alice",
                new TaskCreateRequest(key(), "T-2", "G", "t2", List.of(),
                        false, null, "P2"));
        // 缺少最终位置 → 422
        assertThatThrownBy(() -> zones.dispatch("INC-ZP", "alice",
                new DispatchRequest(key(), List.of(new DispatchItem("T-1", null, List.of())))))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(details(e).toString()).contains("缺少最终位置");
                });
        // 批次内资源重复 → 422
        assertApiStatus(() -> zones.dispatch("INC-ZP", "alice",
                new DispatchRequest(key(), List.of(
                        new DispatchItem("T-1", "P1", List.of("CRANE-1")),
                        new DispatchItem("T-2", null, List.of("CRANE-1"))))),
                HttpStatus.UNPROCESSABLE_ENTITY);
        // 任务不存在 → 404
        assertApiStatus(() -> zones.dispatch("INC-ZP", "alice",
                new DispatchRequest(key(), List.of(
                        new DispatchItem("T-404", "P1", List.of())))),
                HttpStatus.NOT_FOUND);

        // T-2 占用 CRANE-2 后，T-1 再申请同一资源 → 422 资源被占用
        zones.dispatch("INC-ZP", "alice",
                new DispatchRequest(key(), List.of(
                        new DispatchItem("T-2", null, List.of("CRANE-2")))));
        assertThatThrownBy(() -> zones.dispatch("INC-ZP", "alice",
                new DispatchRequest(key(), List.of(
                        new DispatchItem("T-1", "P1", List.of("CRANE-2"))))))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(details(e).toString()).contains("资源被占用");
                });
        assertThat(incidentService.getTask("INC-ZP", "T-1").status()).isEqualTo("OPEN");
        // 已派工任务不可重复派工
        assertApiStatus(() -> zones.dispatch("INC-ZP", "alice",
                new DispatchRequest(key(), List.of(
                        new DispatchItem("T-2", null, List.of())))),
                HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void dispatch_requiresCommander_andEmptyItemsRejected() {
        commanding("INC-ZW", "alice");
        incidentService.createTask("INC-ZW", "alice",
                new TaskCreateRequest(key(), "T-1", "G", "t1", List.of()));
        assertApiStatus(() -> zones.dispatch("INC-ZW", "bob",
                new DispatchRequest(key(), List.of(new DispatchItem("T-1", "P1", List.of())))),
                HttpStatus.CONFLICT);
        assertApiStatus(() -> zones.dispatch("INC-ZW", "alice",
                new DispatchRequest(key(), List.of())), HttpStatus.BAD_REQUEST);
    }

    // ---------- 解决门禁与进行中任务 ----------

    @Test
    void resolveGate_inProgressAndBlockedTasksBlock() {
        commanding("INC-ZR2", "alice");
        incidentService.createTask("INC-ZR2", "alice",
                new TaskCreateRequest(key(), "T-1", "G", "t1", List.of()));
        zones.startTask("INC-ZR2", "T-1", "alice", new TaskActionRequest(key()));
        incidentService.changeStatus("INC-ZR2", "alice", new StatusRequest(key(), "CONTAINED"));
        // IN_PROGRESS 任务阻塞解决
        assertApiStatus(() -> incidentService.changeStatus("INC-ZR2", "alice",
                new StatusRequest(key(), "RESOLVED")), HttpStatus.CONFLICT);
        // 完成后可解决
        incidentService.completeTask("INC-ZR2", "T-1", "alice", new TaskActionRequest(key()));
        IncidentView resolved = incidentService.changeStatus("INC-ZR2", "alice",
                new StatusRequest(key(), "RESOLVED"));
        assertThat(resolved.status()).isEqualTo("RESOLVED");
    }
}
