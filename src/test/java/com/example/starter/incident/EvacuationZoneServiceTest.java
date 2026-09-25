package com.example.starter.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.example.starter.incident.dto.Requests.ExemptionGrantRequest;
import com.example.starter.incident.dto.Requests.ReportRequest;
import com.example.starter.incident.dto.Requests.TakeoverRequest;
import com.example.starter.incident.dto.Requests.TaskActionRequest;
import com.example.starter.incident.dto.Requests.TaskBatchDispatchRequest;
import com.example.starter.incident.dto.Requests.TaskCreateRequest;
import com.example.starter.incident.dto.Requests.ZoneRegisterRequest;
import com.example.starter.incident.dto.Responses.ExemptionView;
import com.example.starter.incident.dto.Responses.TaskBlockStatusListView;
import com.example.starter.incident.dto.Responses.TaskView;
import com.example.starter.incident.dto.Responses.ZoneListView;
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
 * 疏散区域与高危任务门禁服务测试：覆盖空间范围（网格规范化/同等级窗口重叠）、
 * 任务状态（生效阻断与快照、豁免放行）、区域结束恢复、撤离终态、豁免版本作用域、
 * 批量派工事务全回滚、开始/完成门禁与 zoneKey 指纹/commandKey 幂等。
 * 全程使用真实 H2（MODE=MySQL）内存库与可控时钟。
 */
@SpringBootTest
@Import(ControllableClock.Config.class)
class EvacuationZoneServiceTest {

    private static final Instant T0 = Instant.parse("2026-09-26T00:00:00Z");

    @Autowired
    private IncidentService service;
    @Autowired
    private EvacuationService evacuation;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private Clock clock;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM evacuation_exemptions");
        jdbc.update("DELETE FROM evacuation_zones");
        jdbc.update("DELETE FROM zone_command_keys");
        jdbc.update("DELETE FROM task_dispatch_leases");
        jdbc.update("DELETE FROM command_keys");
        jdbc.update("DELETE FROM incident_task_blockers");
        jdbc.update("DELETE FROM incident_tasks");
        jdbc.update("DELETE FROM incident_status_history");
        jdbc.update("DELETE FROM incident_transfers");
        jdbc.update("DELETE FROM incident_actions");
        jdbc.update("DELETE FROM incident_escalations");
        jdbc.update("DELETE FROM incidents");
        ((ControllableClock) clock).setInstant(T0);
    }

    private static String key() {
        return "CMD-" + UUID.randomUUID();
    }

    private void setClock(Instant at) {
        ((ControllableClock) clock).setInstant(at);
    }

    private void commanding(String incidentKey, String commander) {
        service.report(new ReportRequest(incidentKey, "S2", "疏散演练", "reporter-1"));
        service.takeover(incidentKey, commander, new TakeoverRequest(key()));
    }

    private TaskView task(String incident, String taskKey, String grid) {
        return service.createTask(incident, "alice",
                new TaskCreateRequest(key(), taskKey, "G", "高危任务", grid, List.of()));
    }

    private ZoneView zone(String incident, String zoneKey, String risk, List<String> grids,
                          Instant from, Instant to) {
        return evacuation.registerZone(incident, "alice",
                new ZoneRegisterRequest(key(), zoneKey, risk, grids, from, to));
    }

    private static void assertStatus(ThrowingCallable call, HttpStatus status, String code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.status()).isEqualTo(status);
            assertThat(e.code()).isEqualTo(code);
        });
    }

    // ---------- 空间范围：规范化与重叠 ----------

    @Test
    void registerZone_normalizesGrids_andBlocksSameLevelOverlap() {
        commanding("INC-Z1", "alice");
        // 网格去空白、去重并字典序排序
        ZoneView z = zone("INC-Z1", "Z-A", "HIGH", List.of("X3", "x1", "X1", " X2 "),
                T0, T0.plusSeconds(3600));
        assertThat(z.grids()).containsExactly("X1", "X2", "X3", "x1");
        assertThat(z.version()).isEqualTo(1);
        assertThat(z.effective()).isTrue();
        assertThat(z.status()).isEqualTo("REGISTERED");

        // 同等级、窗口相交、网格相交 → 422 且给出冲突区域
        assertThatThrownBy(() -> zone("INC-Z1", "Z-B", "HIGH", List.of("X2", "X9"),
                T0.plusSeconds(10), T0.plusSeconds(100)))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(e.code()).isEqualTo("ZONE_WINDOW_OVERLAP");
                    assertThat(((Map<?, ?>) e.details()).get("conflictZoneKey")).isEqualTo("Z-A");
                });
    }

    @Test
    void registerZone_differentLevelOrDisjointWindowOrGridAllowed() {
        commanding("INC-Z2", "alice");
        zone("INC-Z2", "Z-A", "HIGH", List.of("X1"), T0, T0.plusSeconds(3600));
        // 不同等级即使窗口网格重叠也允许
        ZoneView medium = zone("INC-Z2", "Z-M", "MEDIUM", List.of("X1"),
                T0, T0.plusSeconds(3600));
        assertThat(medium.version()).isEqualTo(2);
        // 同等级但窗口不相交
        ZoneView later = zone("INC-Z2", "Z-L", "HIGH", List.of("X1"),
                T0.plusSeconds(3600), T0.plusSeconds(7200));
        assertThat(later.version()).isEqualTo(3);
        // 同等级同窗口但网格不相交
        ZoneView otherGrid = zone("INC-Z2", "Z-G", "HIGH", List.of("X2"),
                T0, T0.plusSeconds(3600));
        assertThat(otherGrid.version()).isEqualTo(4);
    }

    @Test
    void registerZone_validationFailures() {
        commanding("INC-Z3", "alice");
        // 空网格
        assertStatus(() -> zone("INC-Z3", "Z", "HIGH", List.of("  "), T0, T0.plusSeconds(60)),
                HttpStatus.BAD_REQUEST, "BAD_REQUEST");
        // 非法风险等级
        assertStatus(() -> zone("INC-Z3", "Z", "CRITICAL", List.of("X1"),
                T0, T0.plusSeconds(60)), HttpStatus.BAD_REQUEST, "BAD_REQUEST");
        // 窗口非左闭右开（from >= to）
        assertStatus(() -> zone("INC-Z3", "Z", "HIGH", List.of("X1"),
                T0.plusSeconds(60), T0), HttpStatus.BAD_REQUEST, "BAD_REQUEST");
        // 非指挥人
        assertThatThrownBy(() -> evacuation.registerZone("INC-Z3", "bob",
                new ZoneRegisterRequest(key(), "Z", "HIGH", List.of("X1"),
                        T0, T0.plusSeconds(60))))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT));
        // 事件不存在
        assertStatus(() -> zone("INC-404", "Z", "HIGH", List.of("X1"),
                T0, T0.plusSeconds(60)), HttpStatus.NOT_FOUND, "NOT_FOUND");
    }

    // ---------- 区域生效：未开始命中任务阻断并固化快照 ----------

    @Test
    void effectiveZone_blocksUnstartedHitTask_andFreezesSnapshot() {
        commanding("INC-B1", "alice");
        task("INC-B1", "T-HIT", "X1");
        task("INC-B1", "T-SAFE", "Y1");
        zone("INC-B1", "Z-A", "HIGH", List.of("X1"), T0.minusSeconds(10), T0.plusSeconds(3600));

        TaskView hit = service.getTask("INC-B1", "T-HIT");
        assertThat(hit.status()).isEqualTo("EVACUATION_BLOCKED");
        assertThat(hit.blocked()).isNotNull();
        assertThat(hit.blocked().zoneKey()).isEqualTo("Z-A");
        assertThat(hit.blocked().version()).isEqualTo(1);
        assertThat(hit.blocked().riskLevel()).isEqualTo("HIGH");
        assertThat(hit.blocked().grids()).containsExactly("X1");
        // 未命中网格的任务不受影响
        assertThat(service.getTask("INC-B1", "T-SAFE").status()).isEqualTo("OPEN");

        // 阻断态不能开始/完成/取消
        assertStatus(() -> service.startTask("INC-B1", "T-HIT", "alice", new TaskActionRequest(key())),
                HttpStatus.UNPROCESSABLE_ENTITY, "TASK_EVACUATION_BLOCKED");
        assertStatus(() -> service.completeTask("INC-B1", "T-HIT", "alice",
                new TaskActionRequest(key())), HttpStatus.UNPROCESSABLE_ENTITY,
                "TASK_EVACUATION_BLOCKED");
        assertStatus(() -> service.cancelTask("INC-B1", "T-HIT", "alice", new TaskActionRequest(key())),
                HttpStatus.UNPROCESSABLE_ENTITY, "TASK_EVACUATION_BLOCKED");
    }

    @Test
    void futureZone_blocksWhenWindowStarts_onNextWrite() {
        commanding("INC-B2", "alice");
        task("INC-B2", "T-1", "X1");
        // 未来窗口：登记时不阻断
        zone("INC-B2", "Z-F", "HIGH", List.of("X1"), T0.plusSeconds(100), T0.plusSeconds(3600));
        assertThat(service.getTask("INC-B2", "T-1").status()).isEqualTo("OPEN");
        // 时间推进到窗口内，下一次写裁决（结束裁决入口）使任务阻断
        setClock(T0.plusSeconds(200));
        evacuation.endZones("INC-B2", key());
        assertThat(service.getTask("INC-B2", "T-1").status()).isEqualTo("EVACUATION_BLOCKED");
    }

    // ---------- 区域结束恢复 ----------

    @Test
    void zoneEnd_reopensBlockedTask_whichCanThenStart() {
        commanding("INC-R1", "alice");
        task("INC-R1", "T-1", "X1");
        zone("INC-R1", "Z-A", "HIGH", List.of("X1"), T0, T0.plusSeconds(300));
        assertThat(service.getTask("INC-R1", "T-1").status()).isEqualTo("EVACUATION_BLOCKED");

        setClock(T0.plusSeconds(300)); // 窗口右开边界：at == to 即结束
        evacuation.endZones("INC-R1", key());

        TaskView reopened = service.getTask("INC-R1", "T-1");
        assertThat(reopened.status()).isEqualTo("OPEN");
        assertThat(reopened.blocked()).isNull();
        ZoneListView zones = evacuation.listZones("INC-R1");
        assertThat(zones.zones().get(0).status()).isEqualTo("ENDED");
        assertThat(zones.zones().get(0).effective()).isFalse();
        // 恢复后满足其余门禁即可开始
        TaskView started = service.startTask("INC-R1", "T-1", "alice", new TaskActionRequest(key()));
        assertThat(started.status()).isEqualTo("IN_PROGRESS");
    }

    @Test
    void zoneEnd_repointsWhenStillHitByAnotherEffectiveZone() {
        commanding("INC-R2", "alice");
        task("INC-R2", "T-1", "X1");
        zone("INC-R2", "Z-SHORT", "HIGH", List.of("X1"), T0, T0.plusSeconds(300));
        // 另一不同等级区域窗口更晚结束，仍命中同一网格
        zone("INC-R2", "Z-LONG", "MEDIUM", List.of("X1"), T0, T0.plusSeconds(900));
        // 登记时任务被先登记的 Z-SHORT 阻断（版本1）
        assertThat(service.getTask("INC-R2", "T-1").blocked().zoneKey()).isEqualTo("Z-SHORT");

        setClock(T0.plusSeconds(400));
        evacuation.endZones("INC-R2", key());
        // 仍被 Z-LONG 命中：保持阻断但改挂
        TaskView stillBlocked = service.getTask("INC-R2", "T-1");
        assertThat(stillBlocked.status()).isEqualTo("EVACUATION_BLOCKED");
        assertThat(stillBlocked.blocked().zoneKey()).isEqualTo("Z-LONG");

        setClock(T0.plusSeconds(900));
        evacuation.endZones("INC-R2", key());
        assertThat(service.getTask("INC-R2", "T-1").status()).isEqualTo("OPEN");
    }

    // ---------- 创建/开始高危门禁与豁免 ----------

    @Test
    void createHighRiskTask_requiresExemption_thenPreGrantAllows() {
        commanding("INC-H1", "alice");
        zone("INC-H1", "Z-A", "HIGH", List.of("X1"), T0, T0.plusSeconds(3600));
        // 无豁免直接创建命中网格任务 → 422 且不留任务
        assertStatus(() -> task("INC-H1", "T-1", "X1"),
                HttpStatus.UNPROCESSABLE_ENTITY, "EXEMPTION_REQUIRED");
        assertThat(service.listTasks("INC-H1").tasks()).isEmpty();
        // 失败不占 commandKey：同键先失败（无豁免），预授权后同键重试成功
        String ck = key();
        assertStatus(() -> service.createTask("INC-H1", "alice",
                new TaskCreateRequest(ck, "T-1", "G", "t", "X1", List.of())),
                HttpStatus.UNPROCESSABLE_ENTITY, "EXEMPTION_REQUIRED");
        // 预授权（任务尚未创建，显式 workGrid）
        evacuation.grantExemption("INC-H1", "Z-A", "alice",
                new ExemptionGrantRequest(key(), "T-1", "X1"));
        TaskView created = service.createTask("INC-H1", "alice",
                new TaskCreateRequest(ck, "T-1", "G", "t", "X1", List.of()));
        assertThat(created.taskKey()).isEqualTo("T-1");
        assertThat(created.status()).isEqualTo("OPEN");
    }

    @Test
    void exemption_allowsStartAndComplete_ofInProgressTask() {
        commanding("INC-H2", "alice");
        // 任务在区域登记前已存在（OPEN，网格 X1）
        task("INC-H2", "T-1", "X1");
        zone("INC-H2", "Z-A", "HIGH", List.of("X1"), T0.plusSeconds(100), T0.plusSeconds(3600));
        setClock(T0.plusSeconds(200));
        // 无豁免开始：任务先被阻断 → 422
        assertStatus(() -> service.startTask("INC-H2", "T-1", "alice", new TaskActionRequest(key())),
                HttpStatus.UNPROCESSABLE_ENTITY, "TASK_EVACUATION_BLOCKED");
        // 授予版本豁免后阻断解除，可开始
        evacuation.grantExemption("INC-H2", "Z-A", "alice",
                new ExemptionGrantRequest(key(), "T-1", null));
        assertThat(service.getTask("INC-H2", "T-1").status()).isEqualTo("OPEN");
        TaskView inProgress = service.startTask("INC-H2", "T-1", "alice",
                new TaskActionRequest(key()));
        assertThat(inProgress.status()).isEqualTo("IN_PROGRESS");
        // 持有效豁免的进行中任务可继续并完成
        TaskView done = service.completeTask("INC-H2", "T-1", "alice",
                new TaskActionRequest(key()));
        assertThat(done.status()).isEqualTo("DONE");
    }

    @Test
    void exemption_isScopedToZoneVersionAndGrid() {
        commanding("INC-H3", "alice");
        task("INC-H3", "T-1", "X1");
        ZoneView z = zone("INC-H3", "Z-A", "HIGH", List.of("X1"), T0, T0.plusSeconds(300));
        // 网格不在区域内 → 豁免无作用域
        assertStatus(() -> evacuation.grantExemption("INC-H3", "Z-A", "alice",
                        new ExemptionGrantRequest(key(), "T-9", "Z9")),
                HttpStatus.UNPROCESSABLE_ENTITY, "EXEMPTION_SCOPE_GRID");
        // 授予 Z-A 豁免不能解除另一区域 Z-B 的阻断
        evacuation.grantExemption("INC-H3", "Z-A", "alice",
                new ExemptionGrantRequest(key(), "T-1", null));
        zone("INC-H3", "Z-B", "MEDIUM", List.of("X1"), T0, T0.plusSeconds(900));
        // Z-A 先到期；任务改挂 Z-B（无 Z-B 豁免）
        setClock(T0.plusSeconds(400));
        evacuation.endZones("INC-H3", key());
        assertThat(service.getTask("INC-H3", "T-1").blocked().zoneKey()).isEqualTo("Z-B");
        // 版本号正确记录
        ExemptionView ex = evacuation.listExemptions("INC-H3").exemptions().get(0);
        assertThat(ex.version()).isEqualTo(z.version());
        assertThat(ex.zoneKey()).isEqualTo("Z-A");
    }

    // ---------- 撤离终态 ----------

    @Test
    void inProgressHitTask_evacuates_andCannotComplete() {
        commanding("INC-E1", "alice");
        task("INC-E1", "T-1", "X1");
        // 先开始（区域尚未生效）
        service.startTask("INC-E1", "T-1", "alice", new TaskActionRequest(key()));
        // 区域生效，进行中任务命中
        zone("INC-E1", "Z-A", "HIGH", List.of("X1"), T0, T0.plusSeconds(3600));
        // 无豁免不能完成
        assertStatus(() -> service.completeTask("INC-E1", "T-1", "alice",
                new TaskActionRequest(key())), HttpStatus.UNPROCESSABLE_ENTITY, "EVACUATE_OR_EXEMPT");
        // 登记撤离 → EVACUATED 终态
        TaskView evacuated = service.evacuateTask("INC-E1", "T-1", "alice",
                new TaskActionRequest(key()));
        assertThat(evacuated.status()).isEqualTo("EVACUATED");
        assertStatus(() -> service.completeTask("INC-E1", "T-1", "alice",
                new TaskActionRequest(key())), HttpStatus.CONFLICT, "CONFLICT");
        assertStatus(() -> service.evacuateTask("INC-E1", "T-1", "alice",
                new TaskActionRequest(key())), HttpStatus.CONFLICT, "CONFLICT");
    }

    @Test
    void evacuate_rejectedWhenNotInProgressOrNotHit() {
        commanding("INC-E2", "alice");
        task("INC-E2", "T-1", "X1");
        zone("INC-E2", "Z-A", "HIGH", List.of("X1"), T0, T0.plusSeconds(3600));
        // 任务被阻断（未开始）：不能登记撤离
        assertStatus(() -> service.evacuateTask("INC-E2", "T-1", "alice",
                new TaskActionRequest(key())), HttpStatus.UNPROCESSABLE_ENTITY,
                "EVACUATION_ONLY_IN_PROGRESS");

        commanding("INC-E3", "alice");
        task("INC-E3", "T-1", "Y1");
        service.startTask("INC-E3", "T-1", "alice", new TaskActionRequest(key()));
        zone("INC-E3", "Z-A", "HIGH", List.of("X1"), T0, T0.plusSeconds(3600));
        // 进行中但未命中区域：不能撤离（应继续完成）
        assertStatus(() -> service.evacuateTask("INC-E3", "T-1", "alice",
                new TaskActionRequest(key())), HttpStatus.UNPROCESSABLE_ENTITY, "NOT_IN_EVACUATION_ZONE");
        TaskView done = service.completeTask("INC-E3", "T-1", "alice",
                new TaskActionRequest(key()));
        assertThat(done.status()).isEqualTo("DONE");
    }

    // ---------- 批量派工：先全量校验，任一缺失整体 422 全回滚 ----------

    @Test
    void batchDispatch_validatesAllAndRollsBack() {
        commanding("INC-D1", "alice");
        commanding("INC-DEP", "bob");
        task("INC-D1", "T-OK", "Y1");
        task("INC-D1", "T-BLOCKED", "X1");
        TaskView withDep = service.createTask("INC-D1", "alice",
                new TaskCreateRequest(key(), "T-DEP", "G", "依赖任务", "Y1", List.of("INC-DEP")));
        assertThat(withDep.status()).isEqualTo("OPEN");
        zone("INC-D1", "Z-A", "HIGH", List.of("X1"), T0, T0.plusSeconds(3600));

        // 一批内：T-OK 合格；T-BLOCKED 缺豁免；T-DEP 资源依赖未解除；T-NO 不存在
        assertThatThrownBy(() -> service.batchDispatch("INC-D1", "alice",
                new TaskBatchDispatchRequest(key(),
                        List.of("T-OK", "T-BLOCKED", "T-DEP", "T-NO"))))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(e.code()).isEqualTo("DISPATCH_VALIDATION_FAILED");
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> failures = (List<Map<String, Object>>) e.details();
                    assertThat(failures).hasSize(3);
                    assertThat(failures).extracting(f -> f.get("taskKey"))
                            .containsExactly("T-BLOCKED", "T-DEP", "T-NO");
                });

        // 全部回滚：任务仍 OPEN，无租约
        assertThat(service.getTask("INC-D1", "T-OK").status()).isEqualTo("OPEN");
        Integer leases = jdbc.queryForObject("SELECT COUNT(*) FROM task_dispatch_leases",
                Integer.class);
        assertThat(leases).isZero();

        // 仅派合格任务：成功并写租约、置 DISPATCHED
        var result = service.batchDispatch("INC-D1", "alice",
                new TaskBatchDispatchRequest(key(), List.of("T-OK")));
        assertThat(result.dispatched()).containsExactly("T-OK");
        assertThat(result.tasks().get(0).status()).isEqualTo("DISPATCHED");
        assertThat(service.getTask("INC-D1", "T-OK").status()).isEqualTo("DISPATCHED");
    }

    @Test
    void batchDispatch_successThenStartConsumesLease_andReplay() {
        commanding("INC-D2", "alice");
        task("INC-D2", "T-1", "Y1");
        task("INC-D2", "T-2", "Y2");
        String ck = key();
        var first = service.batchDispatch("INC-D2", "alice",
                new TaskBatchDispatchRequest(ck, List.of("T-1", "T-2")));
        assertThat(first.dispatched()).hasSize(2);
        // 同键重放首次结果
        var replay = service.batchDispatch("INC-D2", "alice",
                new TaskBatchDispatchRequest(ck, List.of("T-1", "T-2")));
        assertThat(replay.dispatched()).containsExactlyElementsOf(first.dispatched());
        // 开始派工任务消费租约
        service.startTask("INC-D2", "T-1", "alice", new TaskActionRequest(key()));
        assertThat(service.getTask("INC-D2", "T-1").status()).isEqualTo("IN_PROGRESS");
        java.sql.Timestamp consumed = jdbc.queryForObject(
                "SELECT consumed_at FROM task_dispatch_leases l JOIN incident_tasks t"
                        + " ON t.id = l.task_id WHERE t.task_key = 'T-1'",
                java.sql.Timestamp.class);
        assertThat(consumed).isNotNull();
    }

    // ---------- 已派工任务被阻断：租约回退，区域结束恢复后可重新派工 ----------

    @Test
    void dispatchedTaskBlockedByZone_leaseRolledBack_andRedispatchAfterEnd() {
        commanding("INC-DZ", "alice");
        task("INC-DZ", "T-1", "X1");
        // 先派工（区域尚未生效）
        service.batchDispatch("INC-DZ", "alice",
                new TaskBatchDispatchRequest(key(), List.of("T-1")));
        assertThat(service.getTask("INC-DZ", "T-1").status()).isEqualTo("DISPATCHED");
        // 未来窗口区域登记
        zone("INC-DZ", "Z-A", "HIGH", List.of("X1"),
                T0.plusSeconds(100), T0.plusSeconds(400));
        // 推进到窗口内并裁决：已派工任务被阻断，租约回退
        setClock(T0.plusSeconds(200));
        evacuation.endZones("INC-DZ", key());
        assertThat(service.getTask("INC-DZ", "T-1").status()).isEqualTo("EVACUATION_BLOCKED");
        Integer blockedLeaseCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM task_dispatch_leases l JOIN incident_tasks t"
                        + " ON t.id = l.task_id WHERE t.task_key='T-1'", Integer.class);
        assertThat(blockedLeaseCount).isZero();

        // 区域结束：任务恢复 OPEN
        setClock(T0.plusSeconds(400));
        evacuation.endZones("INC-DZ", key());
        assertThat(service.getTask("INC-DZ", "T-1").status()).isEqualTo("OPEN");

        // 恢复后可重新派工并开始（无残留租约冲突）
        service.batchDispatch("INC-DZ", "alice",
                new TaskBatchDispatchRequest(key(), List.of("T-1")));
        assertThat(service.getTask("INC-DZ", "T-1").status()).isEqualTo("DISPATCHED");
        TaskView started = service.startTask("INC-DZ", "T-1", "alice",
                new TaskActionRequest(key()));
        assertThat(started.status()).isEqualTo("IN_PROGRESS");
    }

    // ---------- 查询：区域 / 阻断 / 豁免版本 ----------
    @Test
    void queries_returnZonesBlockStatusAndExemptions() {
        commanding("INC-Q1", "alice");
        task("INC-Q1", "T-1", "X1");
        task("INC-Q1", "T-2", "X1");
        zone("INC-Q1", "Z-A", "HIGH", List.of("X1"), T0, T0.plusSeconds(3600));
        // T-2 持豁免：授予后阻断解除（任务存在，网格以任务 X1 为准）
        evacuation.grantExemption("INC-Q1", "Z-A", "alice",
                new ExemptionGrantRequest(key(), "T-2", null));

        TaskBlockStatusListView blocks = evacuation.taskBlockStatus("INC-Q1");
        var t1 = blocks.tasks().stream().filter(t -> t.taskKey().equals("T-1")).findFirst().orElseThrow();
        var t2 = blocks.tasks().stream().filter(t -> t.taskKey().equals("T-2")).findFirst().orElseThrow();
        assertThat(t1.blockedBy()).containsExactly("Z-A");
        assertThat(t1.exemptions()).isEmpty();
        // T-2 持豁免，即使网格命中也不算阻断
        assertThat(t2.blockedBy()).isEmpty();
        assertThat(t2.exemptions()).hasSize(1);
        assertThat(t2.exemptions().get(0).version()).isEqualTo(1);

        assertThat(evacuation.listZones("INC-Q1").zones()).hasSize(1);
        assertThat(evacuation.listExemptions("INC-Q1").exemptions()).hasSize(1);
    }

    // ---------- 幂等：zoneKey 指纹与 commandKey ----------

    @Test
    void zoneFingerprintAndCommandKey_idempotent() {
        commanding("INC-I1", "alice");
        // 同事件版本+网格+窗口+等级+操作者 → 同指纹，即便 zoneKey 不同也重放同一区域
        ZoneView first = zone("INC-I1", "Z-A", "HIGH", List.of("X1"),
                T0, T0.plusSeconds(3600));
        ZoneView replayByShape = evacuation.registerZone("INC-I1", "alice",
                new ZoneRegisterRequest(key(), "Z-OTHER", "HIGH", List.of("X1"),
                        T0, T0.plusSeconds(3600)));
        assertThat(replayByShape.zoneKey()).isEqualTo("Z-A");
        assertThat(replayByShape.version()).isEqualTo(first.version());
        assertThat(evacuation.listZones("INC-I1").zones()).hasSize(1);

        // 同 zoneKey 不同内容 → 409
        assertStatus(() -> zone("INC-I1", "Z-A", "HIGH", List.of("X2"),
                T0, T0.plusSeconds(3600)), HttpStatus.CONFLICT, "CONFLICT");

        // commandKey 同键同参重放
        String ck = key();
        ZoneRegisterRequest req = new ZoneRegisterRequest(ck, "Z-B", "LOW", List.of("Q1"),
                T0, T0.plusSeconds(600));
        ZoneView b1 = evacuation.registerZone("INC-I1", "alice", req);
        ZoneView b2 = evacuation.registerZone("INC-I1", "alice", req);
        assertThat(b2).isEqualTo(b1);
        // 同 commandKey 改参 → 409
        assertStatus(() -> evacuation.registerZone("INC-I1", "alice",
                new ZoneRegisterRequest(ck, "Z-C", "LOW", List.of("Q2"),
                        T0, T0.plusSeconds(600))), HttpStatus.CONFLICT, "CONFLICT");
    }
}
