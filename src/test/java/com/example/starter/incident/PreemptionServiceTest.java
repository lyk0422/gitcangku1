package com.example.starter.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import com.example.starter.incident.dto.Requests.LeaseRequest;
import com.example.starter.incident.dto.Requests.PreemptRequest;
import com.example.starter.incident.dto.Requests.ReportRequest;
import com.example.starter.incident.dto.Requests.ResourceCreateRequest;
import com.example.starter.incident.dto.Requests.TakeoverRequest;
import com.example.starter.incident.dto.Requests.TaskActionRequest;
import com.example.starter.incident.dto.Requests.TaskCreateRequest;
import com.example.starter.incident.dto.Requests.VictimRef;
import com.example.starter.incident.dto.Responses.LeaseView;
import com.example.starter.incident.dto.Responses.PreemptionClosureView;
import com.example.starter.incident.dto.Responses.PreemptionView;
import com.example.starter.incident.dto.Responses.TaskView;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 依赖感知抢占服务测试：覆盖抢占主流程、优先级/版本/容量/闭包校验、
 * STARTED 链保护、整单原子回滚及 requestId 幂等（受害集合换序等价）。
 */
@SpringBootTest
class PreemptionServiceTest {

    @Autowired
    private ResourceService resourceService;

    @Autowired
    private IncidentService incidentService;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM command_keys");
        jdbc.update("DELETE FROM resource_leases");
        jdbc.update("DELETE FROM shared_resources");
        jdbc.update("DELETE FROM incident_status_history");
        jdbc.update("DELETE FROM incident_transfers");
        jdbc.update("DELETE FROM incident_actions");
        jdbc.update("DELETE FROM incident_task_blockers");
        jdbc.update("DELETE FROM incident_tasks");
        jdbc.update("DELETE FROM incidents");
    }

    private static String key() {
        return "REQ-" + UUID.randomUUID();
    }

    private static void assertApiStatus(ThrowingCallable call, HttpStatus status) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.status()).isEqualTo(status));
    }

    private void commanding(String incidentKey, String severity, String commander) {
        incidentService.report(new ReportRequest(incidentKey, severity, "故障", "reporter-1"));
        incidentService.takeover(incidentKey, commander, new TakeoverRequest(key()));
    }

    private TaskView createTask(String incidentKey, String actor, String taskKey,
                                List<String> blockers) {
        return incidentService.createTask(incidentKey, actor,
                new TaskCreateRequest(key(), taskKey, "G", "t", blockers));
    }

    private void createResource(String resourceKey, int capacity) {
        resourceService.createResource("ops", new ResourceCreateRequest(resourceKey, capacity));
    }

    private LeaseView requestLease(String incidentKey, String taskKey, String actor,
                                   String resourceKey, String leaseKey, int units,
                                   long taskVersion) {
        return resourceService.requestLease(incidentKey, taskKey, actor,
                new LeaseRequest(key(), resourceKey, leaseKey, units, taskVersion));
    }

    private static VictimRef v(String leaseKey, long version) {
        return new VictimRef(leaseKey, version);
    }

    private PreemptionView preempt(String incidentKey, String taskKey, String actor,
                                   String resourceKey, String leaseKey, int units,
                                   long taskVersion, List<VictimRef> victims) {
        return resourceService.preempt(incidentKey, taskKey, actor,
                new PreemptRequest(key(), resourceKey, leaseKey, units, taskVersion, victims));
    }

    /**
     * 搭建：RES-P 容量 2，低优先级事件 INC-L（S3）任务 TL 持有租约 LK-L 占 2 单位；
     * 高优先级事件 INC-H（S1）任务 TH 待授予。返回 TH 的版本。
     */
    private long setupBasicPreemption() {
        commanding("INC-L", "S3", "bob");
        commanding("INC-H", "S1", "carol");
        TaskView tl = createTask("INC-L", "bob", "TL", List.of());
        TaskView th = createTask("INC-H", "carol", "TH", List.of());
        createResource("RES-P", 2);
        requestLease("INC-L", "TL", "bob", "RES-P", "LK-L", 2, tl.version());
        return th.version();
    }

    @Test
    void preempt_mainFlow() {
        long thVersion = setupBasicPreemption();

        PreemptionView result = preempt("INC-H", "TH", "carol", "RES-P", "LK-H", 2,
                thVersion, List.of(v("LK-L", 1)));
        // 新租约授予
        assertThat(result.grantedLease().leaseKey()).isEqualTo("LK-H");
        assertThat(result.grantedLease().status()).isEqualTo("ACTIVE");
        assertThat(result.grantedLease().units()).isEqualTo(2);
        assertThat(result.grantedLease().incidentKey()).isEqualTo("INC-H");
        // 受害租约被原子撤销
        assertThat(result.revokedVictims()).hasSize(1);
        LeaseView victim = result.revokedVictims().get(0);
        assertThat(victim.leaseKey()).isEqualTo("LK-L");
        assertThat(victim.status()).isEqualTo("REVOKED");
        assertThat(victim.version()).isEqualTo(2);
        assertThat(victim.revokedAt()).isNotNull();
        // 占用核算：容量不超限
        assertThat(resourceService.getResource("RES-P").activeUnits()).isEqualTo(2);
        // 历史保留全部租约
        assertThat(resourceService.listLeases("RES-P").leases()).hasSize(2);
    }

    @Test
    void preempt_priorityRules() {
        commanding("INC-V", "S2", "bob");
        commanding("INC-EQ", "S2", "carol");
        commanding("INC-LO", "S3", "dave");
        TaskView tv = createTask("INC-V", "bob", "TV", List.of());
        TaskView teq = createTask("INC-EQ", "carol", "TEQ", List.of());
        TaskView tlo = createTask("INC-LO", "dave", "TLO", List.of());
        createResource("RES-PR", 1);
        requestLease("INC-V", "TV", "bob", "RES-PR", "LK-V", 1, tv.version());

        // 同级不得抢占
        assertApiStatus(() -> preempt("INC-EQ", "TEQ", "carol", "RES-PR", "LK-EQ", 1,
                teq.version(), List.of(v("LK-V", 1))), HttpStatus.CONFLICT);
        // 更低级别不得抢占更高级别
        assertApiStatus(() -> preempt("INC-LO", "TLO", "dave", "RES-PR", "LK-LO", 1,
                tlo.version(), List.of(v("LK-V", 1))), HttpStatus.CONFLICT);
        // 受害租约未被撤销
        assertThat(resourceService.getResource("RES-PR").activeUnits()).isEqualTo(1);
        assertThat(resourceService.listLeases("RES-PR").leases().get(0).status())
                .isEqualTo("ACTIVE");
    }

    @Test
    void preempt_victimStateAndVersionChecks() {
        long thVersion = setupBasicPreemption();

        // 受害租约版本不一致 → 409
        assertApiStatus(() -> preempt("INC-H", "TH", "carol", "RES-P", "LK-H", 2,
                thVersion, List.of(v("LK-L", 9))), HttpStatus.CONFLICT);
        // 受害租约不存在 → 404
        assertApiStatus(() -> preempt("INC-H", "TH", "carol", "RES-P", "LK-H", 2,
                thVersion, List.of(v("LK-404", 1))), HttpStatus.NOT_FOUND);
        // 受害任务已 STARTED → 409
        TaskView tl = incidentService.getTask("INC-L", "TL");
        resourceService.startTask("INC-L", "TL", "bob", new TaskActionRequest(key()));
        assertApiStatus(() -> preempt("INC-H", "TH", "carol", "RES-P", "LK-H", 2,
                thVersion, List.of(v("LK-L", 1))), HttpStatus.CONFLICT);
        assertThat(tl).isNotNull();
        // 受害租约已释放（任务完成）→ 409
        incidentService.completeTask("INC-L", "TL", "bob", new TaskActionRequest(key()));
        assertApiStatus(() -> preempt("INC-H", "TH", "carol", "RES-P", "LK-H", 2,
                thVersion, List.of(v("LK-L", 2))), HttpStatus.CONFLICT);
    }

    @Test
    void preempt_capacityRules() {
        commanding("INC-L", "S3", "bob");
        commanding("INC-M", "S3", "emma");
        commanding("INC-H", "S1", "carol");
        TaskView tl = createTask("INC-L", "bob", "TL", List.of());
        TaskView tm = createTask("INC-M", "emma", "TM", List.of());
        TaskView th = createTask("INC-H", "carol", "TH", List.of());
        // 场景 A：容量 4，占用 2，申请 2 → 容量充足，不得抢占
        createResource("RES-CA", 4);
        requestLease("INC-L", "TL", "bob", "RES-CA", "LK-L", 2, tl.version());
        assertApiStatus(() -> preempt("INC-H", "TH", "carol", "RES-CA", "LK-H", 2,
                th.version(), List.of(v("LK-L", 1))), HttpStatus.CONFLICT);
        // 场景 B：容量 3，占用 3（受害 1 + 其他 2），撤销受害 1 单位后仍不足 → 409
        createResource("RES-CB", 3);
        requestLease("INC-L", "TL", "bob", "RES-CB", "LK-L2", 1, tl.version());
        requestLease("INC-M", "TM", "emma", "RES-CB", "LK-M", 2, tm.version());
        assertApiStatus(() -> preempt("INC-H", "TH", "carol", "RES-CB", "LK-H2", 3,
                th.version(), List.of(v("LK-L2", 1))), HttpStatus.CONFLICT);
        // units 超容量 → 400
        assertApiStatus(() -> preempt("INC-H", "TH", "carol", "RES-CB", "LK-H2", 4,
                th.version(), List.of(v("LK-L2", 1))), HttpStatus.BAD_REQUEST);
        // victims 为空 / 重复 → 400
        assertApiStatus(() -> preempt("INC-H", "TH", "carol", "RES-CB", "LK-H2", 3,
                th.version(), List.of()), HttpStatus.BAD_REQUEST);
        assertApiStatus(() -> preempt("INC-H", "TH", "carol", "RES-CB", "LK-H2", 3,
                th.version(), List.of(v("LK-L2", 1), v("LK-L2", 1))), HttpStatus.BAD_REQUEST);
    }

    @Test
    void preempt_requesterStateChecks() {
        long thVersion = setupBasicPreemption();

        // 非当前指挥人 → 409
        assertApiStatus(() -> preempt("INC-H", "TH", "mallory", "RES-P", "LK-H", 2,
                thVersion, List.of(v("LK-L", 1))), HttpStatus.CONFLICT);
        // 任务版本不一致 → 409
        assertApiStatus(() -> preempt("INC-H", "TH", "carol", "RES-P", "LK-H", 2,
                thVersion + 1, List.of(v("LK-L", 1))), HttpStatus.CONFLICT);
        // leaseKey 已被占用 → 409
        assertApiStatus(() -> preempt("INC-H", "TH", "carol", "RES-P", "LK-L", 2,
                thVersion, List.of(v("LK-L", 1))), HttpStatus.CONFLICT);
        // 请求任务已持有本资源 ACTIVE 租约 → 409（先释放部分容量制造另一条租约场景）
        createResource("RES-P2", 1);
        TaskView th = incidentService.getTask("INC-H", "TH");
        requestLease("INC-H", "TH", "carol", "RES-P2", "LK-H2", 1, th.version());
        assertApiStatus(() -> preempt("INC-H", "TH", "carol", "RES-P2", "LK-H3", 1,
                th.version(), List.of(v("LK-H2", 1))), HttpStatus.CONFLICT);
    }

    @Test
    void preempt_closureMissingLease_422() {
        // INC-A 的任务 TA 依赖 INC-B；受害任务 TB 在 INC-B
        commanding("INC-B", "S3", "bob");
        commanding("INC-A", "S3", "alice");
        commanding("INC-H", "S1", "carol");
        TaskView tb = createTask("INC-B", "bob", "TB", List.of());
        TaskView ta = createTask("INC-A", "alice", "TA", List.of("INC-B"));
        TaskView th = createTask("INC-H", "carol", "TH", List.of());
        createResource("RES-CL", 3);
        requestLease("INC-B", "TB", "bob", "RES-CL", "LK-B", 1, tb.version());
        requestLease("INC-A", "TA", "alice", "RES-CL", "LK-A", 1, ta.version());

        // 计划未包含依赖闭包要求的 LK-A → 422，details 给出缺失租约
        assertThatThrownBy(() -> preempt("INC-H", "TH", "carol", "RES-CL", "LK-H", 2,
                th.version(), List.of(v("LK-B", 1))))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat((List<String>) e.details()).containsExactly("LK-A");
                });
        // 422 后无任何变更
        assertThat(resourceService.getResource("RES-CL").activeUnits()).isEqualTo(2);

        // 闭包查询（只读）：同样指出缺失租约
        PreemptionClosureView closure = resourceService.preemptionClosure("RES-CL",
                List.of("LK-B"));
        assertThat(closure.listedVictims()).hasSize(1);
        assertThat(closure.requiredAdditionalLeases())
                .extracting(LeaseView::leaseKey).containsExactly("LK-A");
        assertThat(closure.startedTasks()).isEmpty();

        // 计划包含完整闭包 → 成功，两条受害租约原子撤销
        PreemptionView ok = preempt("INC-H", "TH", "carol", "RES-CL", "LK-H", 2,
                th.version(), List.of(v("LK-B", 1), v("LK-A", 1)));
        assertThat(ok.revokedVictims()).extracting(LeaseView::leaseKey)
                .containsExactlyInAnyOrder("LK-A", "LK-B");
        assertThat(resourceService.getResource("RES-CL").activeUnits()).isEqualTo(2);
    }

    @Test
    void preempt_indirectDependencyClosure() {
        // 链：INC-A → INC-B → INC-C，受害任务在 INC-C，间接依赖方 INC-A 的租约也须列出
        commanding("INC-C", "S3", "carol");
        commanding("INC-B", "S3", "bob");
        commanding("INC-A", "S3", "alice");
        commanding("INC-H", "S1", "hank");
        TaskView tc = createTask("INC-C", "carol", "TC", List.of());
        createTask("INC-B", "bob", "TB", List.of("INC-C"));
        TaskView ta = createTask("INC-A", "alice", "TA", List.of("INC-B"));
        TaskView th = createTask("INC-H", "hank", "TH", List.of());
        createResource("RES-IN", 3);
        requestLease("INC-C", "TC", "carol", "RES-IN", "LK-C", 1, tc.version());
        requestLease("INC-A", "TA", "alice", "RES-IN", "LK-A", 1, ta.version());

        // 间接依赖方 INC-A 的租约未列出 → 422
        assertThatThrownBy(() -> preempt("INC-H", "TH", "hank", "RES-IN", "LK-H", 2,
                th.version(), List.of(v("LK-C", 1))))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat((List<String>) e.details()).containsExactly("LK-A");
                });
        // 闭包查询覆盖传递依赖
        PreemptionClosureView closure = resourceService.preemptionClosure("RES-IN",
                List.of("LK-C"));
        assertThat(closure.requiredAdditionalLeases())
                .extracting(LeaseView::leaseKey).containsExactly("LK-A");
    }

    @Test
    void preempt_startedDependencyChain_rejected() {
        // 依赖方 TA 已 STARTED：即使其租约未被列为受害，也不得抢占
        commanding("INC-B", "S3", "bob");
        commanding("INC-A", "S3", "alice");
        commanding("INC-H", "S1", "carol");
        TaskView tb = createTask("INC-B", "bob", "TB", List.of());
        TaskView ta = createTask("INC-A", "alice", "TA", List.of("INC-B"));
        TaskView th = createTask("INC-H", "carol", "TH", List.of());
        createResource("RES-ST", 3);
        requestLease("INC-B", "TB", "bob", "RES-ST", "LK-B", 1, tb.version());
        requestLease("INC-A", "TA", "alice", "RES-ST", "LK-A", 1, ta.version());
        resourceService.startTask("INC-A", "TA", "alice", new TaskActionRequest(key()));

        assertApiStatus(() -> preempt("INC-H", "TH", "carol", "RES-ST", "LK-H", 2,
                th.version(), List.of(v("LK-B", 1))), HttpStatus.CONFLICT);
        // 闭包查询报告 STARTED 任务
        PreemptionClosureView closure = resourceService.preemptionClosure("RES-ST",
                List.of("LK-B"));
        assertThat(closure.startedTasks()).hasSize(1);
        assertThat(closure.startedTasks().get(0).incidentKey()).isEqualTo("INC-A");
        assertThat(closure.startedTasks().get(0).taskKey()).isEqualTo("TA");
    }

    @Test
    void preempt_atomicRollback_onAnyVictimFailure() {
        commanding("INC-L1", "S3", "bob");
        commanding("INC-L2", "S3", "dave");
        commanding("INC-H", "S1", "carol");
        TaskView t1 = createTask("INC-L1", "bob", "T1", List.of());
        TaskView t2 = createTask("INC-L2", "dave", "T2", List.of());
        TaskView th = createTask("INC-H", "carol", "TH", List.of());
        createResource("RES-RO", 2);
        requestLease("INC-L1", "T1", "bob", "RES-RO", "LK-1", 1, t1.version());
        requestLease("INC-L2", "T2", "dave", "RES-RO", "LK-2", 1, t2.version());

        // 第二个受害租约版本错误：整单 409，第一个租约不得被撤销，新租约不得授予
        String requestId = key();
        assertApiStatus(() -> resourceService.preempt("INC-H", "TH", "carol",
                new PreemptRequest(requestId, "RES-RO", "LK-H", 2, th.version(),
                        List.of(v("LK-1", 1), v("LK-2", 9)))), HttpStatus.CONFLICT);
        assertThat(resourceService.getResource("RES-RO").activeUnits()).isEqualTo(2);
        assertThat(resourceService.listLeases("RES-RO").leases())
                .allSatisfy(l -> assertThat(l.status()).isEqualTo("ACTIVE"));
        assertThat(resourceService.listLeases("RES-RO").leases())
                .extracting(LeaseView::leaseKey).doesNotContain("LK-H");

        // 失败不占键：同 requestId 修正版本后成功
        PreemptionView ok = resourceService.preempt("INC-H", "TH", "carol",
                new PreemptRequest(requestId, "RES-RO", "LK-H", 2, th.version(),
                        List.of(v("LK-1", 1), v("LK-2", 1))));
        assertThat(ok.grantedLease().leaseKey()).isEqualTo("LK-H");
        assertThat(ok.revokedVictims()).hasSize(2);
    }

    @Test
    void preempt_requestIdIdempotency_reorderedVictimsReplay() {
        commanding("INC-L1", "S3", "bob");
        commanding("INC-L2", "S3", "dave");
        commanding("INC-H", "S1", "carol");
        TaskView t1 = createTask("INC-L1", "bob", "T1", List.of());
        TaskView t2 = createTask("INC-L2", "dave", "T2", List.of());
        TaskView th = createTask("INC-H", "carol", "TH", List.of());
        createResource("RES-ID", 2);
        requestLease("INC-L1", "T1", "bob", "RES-ID", "LK-1", 1, t1.version());
        requestLease("INC-L2", "T2", "dave", "RES-ID", "LK-2", 1, t2.version());

        String requestId = key();
        PreemptionView first = resourceService.preempt("INC-H", "TH", "carol",
                new PreemptRequest(requestId, "RES-ID", "LK-H", 2, th.version(),
                        List.of(v("LK-1", 1), v("LK-2", 1))));
        // 同 requestId、受害集合换序重放 → 返回首次响应，不产生新租约
        PreemptionView replay = resourceService.preempt("INC-H", "TH", "carol",
                new PreemptRequest(requestId, "RES-ID", "LK-H", 2, th.version(),
                        List.of(v("LK-2", 1), v("LK-1", 1))));
        assertThat(replay).isEqualTo(first);
        assertThat(resourceService.listLeases("RES-ID").leases()).hasSize(3);
        // 同 requestId 改参 → 409
        assertApiStatus(() -> resourceService.preempt("INC-H", "TH", "carol",
                new PreemptRequest(requestId, "RES-ID", "LK-H", 2, th.version(),
                        List.of(v("LK-1", 1)))), HttpStatus.CONFLICT);
    }
}
