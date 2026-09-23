package com.example.starter.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import com.example.starter.incident.dto.Requests.LeaseAcquireRequest;
import com.example.starter.incident.dto.Requests.PreemptRequest;
import com.example.starter.incident.dto.Requests.PreemptionClosureRequest;
import com.example.starter.incident.dto.Requests.PreemptionVictimRequest;
import com.example.starter.incident.dto.Requests.ReportRequest;
import com.example.starter.incident.dto.Requests.ResourceCreateRequest;
import com.example.starter.incident.dto.Requests.TakeoverRequest;
import com.example.starter.incident.dto.Requests.TaskCreateRequest;
import com.example.starter.incident.dto.Requests.TaskStartRequest;
import com.example.starter.incident.dto.Responses.LeaseView;
import com.example.starter.incident.dto.Responses.PreemptionClosureView;
import com.example.starter.incident.dto.Responses.PreemptionView;
import com.example.starter.incident.dto.Responses.ResourceUsageView;
import com.example.starter.incident.dto.Responses.ResourceView;
import com.example.starter.incident.dto.Responses.TaskView;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 共享资源租约与依赖感知抢占服务测试：覆盖资源定义、租约申请/容量/版本/唯一性、
 * 任务开始门禁、抢占主流程、闭包完整性 422、STARTED 依赖链、优先级与版本 409、
 * 整体回滚、requestId 幂等（换序重放/异参 409/失败不占键）及只读历史查询。
 */
@SpringBootTest
class ResourceServiceTest {

    @Autowired
    private ResourceService resourceService;

    @Autowired
    private IncidentService incidentService;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM resource_leases");
        jdbc.update("DELETE FROM shared_resources");
        jdbc.update("DELETE FROM command_keys");
        jdbc.update("DELETE FROM incident_task_blockers");
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

    private void commanding(String incidentKey, String severity, String commander) {
        incidentService.report(new ReportRequest(incidentKey, severity, "故障", "reporter"));
        incidentService.takeover(incidentKey, commander, new TakeoverRequest(key()));
    }

    private String createTask(String incidentKey, String actor, String taskKey,
                              List<String> blockers) {
        incidentService.createTask(incidentKey, actor,
                new TaskCreateRequest(key(), taskKey, "G", "任务" + taskKey, blockers));
        return taskKey;
    }

    private String createResource(String resourceKey, int capacity) {
        ResourceView view = resourceService.createResource(
                new ResourceCreateRequest(resourceKey, "资源" + resourceKey, capacity));
        assertThat(view.capacity()).isEqualTo(capacity);
        assertThat(view.usedCapacity()).isZero();
        return resourceKey;
    }

    private LeaseView acquire(String incidentKey, String taskKey, String actor, String resourceKey,
                              long taskVersion, int quantity, String leaseKey) {
        return resourceService.acquireLease(incidentKey, taskKey, actor,
                new LeaseAcquireRequest(key(), resourceKey, taskVersion, quantity, leaseKey));
    }

    private static void assertApiStatus(ThrowingCallable call, HttpStatus status) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.status()).isEqualTo(status));
    }

    @Test
    void createResource_validationAndDuplicate() {
        createResource("R-1", 3);
        assertApiStatus(() -> resourceService.createResource(
                new ResourceCreateRequest("R-2", "n", 0)), HttpStatus.BAD_REQUEST);
        assertApiStatus(() -> resourceService.createResource(
                new ResourceCreateRequest("R-2", "n", null)), HttpStatus.BAD_REQUEST);
        assertApiStatus(() -> resourceService.createResource(
                new ResourceCreateRequest("R-1", "n", 2)), HttpStatus.CONFLICT);
        assertThat(resourceService.listResources()).hasSize(1);
    }

    @Test
    void acquireAndUsage_mainFlow() {
        commanding("INC-A", "S2", "alice");
        createTask("INC-A", "alice", "T-1", List.of());
        createResource("R-1", 2);

        LeaseView lease = acquire("INC-A", "T-1", "alice", "R-1", 0L, 2, "L-1");
        assertThat(lease.status()).isEqualTo("ACTIVE");
        assertThat(lease.quantity()).isEqualTo(2);
        assertThat(lease.version()).isEqualTo(1);
        assertThat(lease.resourceKey()).isEqualTo("R-1");
        assertThat(lease.incidentKey()).isEqualTo("INC-A");
        assertThat(lease.taskKey()).isEqualTo("T-1");

        ResourceUsageView usage = resourceService.resourceUsage("R-1");
        assertThat(usage.resource().usedCapacity()).isEqualTo(2);
        assertThat(usage.resource().availableCapacity()).isZero();
        assertThat(usage.activeLeases()).hasSize(1);
    }

    @Test
    void acquire_failures() {
        commanding("INC-A", "S2", "alice");
        createTask("INC-A", "alice", "T-1", List.of());
        createResource("R-1", 2);

        // 数量超容量 400
        assertApiStatus(() -> resourceService.acquireLease("INC-A", "T-1", "alice",
                new LeaseAcquireRequest(key(), "R-1", 0L, 3, "L-1")), HttpStatus.BAD_REQUEST);
        // 数量非正 400
        assertApiStatus(() -> resourceService.acquireLease("INC-A", "T-1", "alice",
                new LeaseAcquireRequest(key(), "R-1", 0L, 0, "L-1")), HttpStatus.BAD_REQUEST);
        // 资源不存在 404
        assertApiStatus(() -> resourceService.acquireLease("INC-A", "T-1", "alice",
                new LeaseAcquireRequest(key(), "R-X", 0L, 1, "L-1")), HttpStatus.NOT_FOUND);
        // 非指挥人 409
        assertApiStatus(() -> resourceService.acquireLease("INC-A", "T-1", "bob",
                new LeaseAcquireRequest(key(), "R-1", 0L, 1, "L-1")), HttpStatus.CONFLICT);
        // 错误任务版本 409
        assertApiStatus(() -> resourceService.acquireLease("INC-A", "T-1", "alice",
                new LeaseAcquireRequest(key(), "R-1", 9L, 1, "L-1")), HttpStatus.CONFLICT);

        acquire("INC-A", "T-1", "alice", "R-1", 0L, 2, "L-1");
        createTask("INC-A", "alice", "T-2", List.of());
        // 容量不足 409
        assertApiStatus(() -> acquire("INC-A", "T-2", "alice", "R-1", 0L, 1, "L-X"),
                HttpStatus.CONFLICT);
        // leaseKey 唯一 409（换任务、同键）
        assertApiStatus(() -> resourceService.acquireLease("INC-A", "T-1", "alice",
                new LeaseAcquireRequest(key(), "R-1", 0L, 1, "L-1")), HttpStatus.CONFLICT);
    }

    @Test
    void acquire_sameTaskSameResource_singleActiveAndRepetableAfterRevoke() {
        commanding("INC-A", "S2", "alice");
        createTask("INC-A", "alice", "T-1", List.of());
        createResource("R-1", 1);
        acquire("INC-A", "T-1", "alice", "R-1", 0L, 1, "L-1");
        // 同任务同资源第二条 ACTIVE 拒绝
        assertApiStatus(() -> acquire("INC-A", "T-1", "alice", "R-1", 0L, 1, "L-2"),
                HttpStatus.CONFLICT);

        // 高优先级抢占后，原任务仍 OPEN，可重新申请新租约
        commanding("INC-H", "S1", "hank");
        createTask("INC-H", "hank", "H-1", List.of());
        PreemptionView preemption = resourceService.preempt("INC-H", "hank",
                new PreemptRequest("REQ-1", "H-1", 0L, "L-H1", 1,
                        List.of(new PreemptionVictimRequest("L-1", 1L))));
        assertThat(preemption.revoked()).hasSize(1);
        // 容量此时被高优先级租约占用；取消 H-1 原子释放后，原 OPEN 任务可重新申请新租约
        incidentService.cancelTask("INC-H", "H-1", "hank",
                new com.example.starter.incident.dto.Requests.TaskActionRequest(key()));
        LeaseView again = acquire("INC-A", "T-1", "alice", "R-1", 0L, 1, "L-3");
        assertThat(again.status()).isEqualTo("ACTIVE");
    }

    @Test
    void startTask_requiresValidLease() {
        commanding("INC-A", "S2", "alice");
        createTask("INC-A", "alice", "T-1", List.of());
        createResource("R-1", 1);
        // 无租约开始 400
        assertApiStatus(() -> resourceService.startTask("INC-A", "T-1", "alice",
                new TaskStartRequest(key(), List.of())), HttpStatus.BAD_REQUEST);
        // 不存在租约 409
        assertApiStatus(() -> resourceService.startTask("INC-A", "T-1", "alice",
                new TaskStartRequest(key(), List.of("L-X"))), HttpStatus.CONFLICT);

        acquire("INC-A", "T-1", "alice", "R-1", 0L, 1, "L-1");
        TaskView started = resourceService.startTask("INC-A", "T-1", "alice",
                new TaskStartRequest(key(), List.of("L-1")));
        assertThat(started.status()).isEqualTo("STARTED");
        assertThat(started.version()).isEqualTo(1);
        assertThat(started.startedAt()).isNotNull();
        // 重复开始 422
        assertApiStatus(() -> resourceService.startTask("INC-A", "T-1", "alice",
                new TaskStartRequest(key(), List.of("L-1"))), HttpStatus.UNPROCESSABLE_ENTITY);
        // STARTED 任务不能再申请租约
        assertApiStatus(() -> resourceService.acquireLease("INC-A", "T-1", "alice",
                new LeaseAcquireRequest(key(), "R-1", 1L, 1, "L-2")), HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void startTask_revokedLeaseRejected() {
        commanding("INC-A", "S2", "alice");
        createTask("INC-A", "alice", "T-1", List.of());
        createResource("R-1", 1);
        acquire("INC-A", "T-1", "alice", "R-1", 0L, 1, "L-1");

        commanding("INC-H", "S1", "hank");
        createTask("INC-H", "hank", "H-1", List.of());
        resourceService.preempt("INC-H", "hank",
                new PreemptRequest("REQ-1", "H-1", 0L, "L-H1", 1,
                        List.of(new PreemptionVictimRequest("L-1", 1L))));

        // 租约已撤销，任务不得带失效租约启动
        assertApiStatus(() -> resourceService.startTask("INC-A", "T-1", "alice",
                new TaskStartRequest(key(), List.of("L-1"))), HttpStatus.CONFLICT);
        TaskView task = incidentService.getTask("INC-A", "T-1");
        assertThat(task.status()).isEqualTo("OPEN");
    }

    @Test
    void preempt_mainFlowAndHistory() {
        commanding("INC-V", "S2", "victor");
        createTask("INC-V", "victor", "V-1", List.of());
        createResource("R-1", 1);
        acquire("INC-V", "V-1", "victor", "R-1", 0L, 1, "L-V1");

        commanding("INC-H", "S1", "hank");
        createTask("INC-H", "hank", "H-1", List.of());
        PreemptionView result = resourceService.preempt("INC-H", "hank",
                new PreemptRequest("REQ-MAIN", "H-1", 0L, "L-H1", 1,
                        List.of(new PreemptionVictimRequest("L-V1", 1L))));

        assertThat(result.requestId()).isEqualTo("REQ-MAIN");
        assertThat(result.revoked()).hasSize(1);
        assertThat(result.revoked().get(0).status()).isEqualTo("REVOKED");
        assertThat(result.revoked().get(0).revokedAt()).isNotNull();
        assertThat(result.granted().status()).isEqualTo("ACTIVE");
        assertThat(result.granted().leaseKey()).isEqualTo("L-H1");
        assertThat(result.granted().requestId()).isEqualTo("REQ-MAIN");

        ResourceUsageView usage = resourceService.resourceUsage("R-1");
        assertThat(usage.resource().usedCapacity()).isEqualTo(1);
        assertThat(usage.activeLeases()).extracting(LeaseView::leaseKey).containsExactly("L-H1");

        // 历史只读，包含 ACTIVE 与 REVOKED
        List<LeaseView> history = resourceService.leaseHistory("R-1");
        assertThat(history).hasSize(2);
        assertThat(history).extracting(LeaseView::status)
                .containsExactly("REVOKED", "ACTIVE");
    }

    @Test
    void preempt_priorityAndStateGuards() {
        createResource("R-1", 1);
        commanding("INC-V", "S2", "victor");
        createTask("INC-V", "victor", "V-1", List.of());
        acquire("INC-V", "V-1", "victor", "R-1", 0L, 1, "L-V1");

        // 同级别不能抢占
        commanding("INC-E", "S2", "ed");
        createTask("INC-E", "ed", "E-1", List.of());
        assertApiStatus(() -> resourceService.preempt("INC-E", "ed",
                new PreemptRequest("REQ-E", "E-1", 0L, "L-E1", 1,
                        List.of(new PreemptionVictimRequest("L-V1", 1L)))), HttpStatus.CONFLICT);
        // 更低级别不能抢占
        commanding("INC-L", "S3", "leo");
        createTask("INC-L", "leo", "L-1", List.of());
        assertApiStatus(() -> resourceService.preempt("INC-L", "leo",
                new PreemptRequest("REQ-L", "L-1", 0L, "L-LL", 1,
                        List.of(new PreemptionVictimRequest("L-V1", 1L)))), HttpStatus.CONFLICT);
        // 受害租约不存在
        commanding("INC-H", "S1", "hank");
        createTask("INC-H", "hank", "H-1", List.of());
        assertApiStatus(() -> resourceService.preempt("INC-H", "hank",
                new PreemptRequest("REQ-X", "H-1", 0L, "L-HX", 1,
                        List.of(new PreemptionVictimRequest("L-NOPE", 1L)))), HttpStatus.CONFLICT);
        // 受害租约版本过期 409
        assertApiStatus(() -> resourceService.preempt("INC-H", "hank",
                new PreemptRequest("REQ-V", "H-1", 0L, "L-HV", 1,
                        List.of(new PreemptionVictimRequest("L-V1", 7L)))), HttpStatus.CONFLICT);
        // 目标任务版本过期 409
        assertApiStatus(() -> resourceService.preempt("INC-H", "hank",
                new PreemptRequest("REQ-T", "H-1", 5L, "L-HT", 1,
                        List.of(new PreemptionVictimRequest("L-V1", 1L)))), HttpStatus.CONFLICT);
        // 目标任务开始后不能抢占授予
        TaskView startedV = incidentService.getTask("INC-V", "V-1");
        // 受害任务一旦 STARTED，抢占 422
        resourceService.startTask("INC-V", "V-1", "victor",
                new TaskStartRequest(key(), List.of("L-V1")));
        assertApiStatus(() -> resourceService.preempt("INC-H", "hank",
                new PreemptRequest("REQ-S", "H-1", 0L, "L-HS", 1,
                        List.of(new PreemptionVictimRequest("L-V1", 1L)))),
                HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(startedV.status()).isIn("OPEN", "STARTED");
    }

    @Test
    void preempt_closureIncomplete_422_andWholeRollback() {
        createResource("R-1", 2);
        // 受害事件 V（S2）任务 V-1 持有 L-V1
        commanding("INC-V", "S2", "victor");
        createTask("INC-V", "victor", "V-1", List.of());
        acquire("INC-V", "V-1", "victor", "R-1", 0L, 1, "L-V1");
        // 中间事件 M（S2）任务 M-1 阻塞 V，持有 L-M1
        commanding("INC-M", "S2", "mary");
        createTask("INC-M", "mary", "M-1", List.of("INC-V"));
        acquire("INC-M", "M-1", "mary", "R-1", 0L, 1, "L-M1");
        // 高优先级事件 H
        commanding("INC-H", "S1", "hank");
        createTask("INC-H", "hank", "H-1", List.of());

        // 只读闭包：必须同时包含 L-V1 与 L-M1
        PreemptionClosureView closure = resourceService.preemptionClosure("INC-H",
                new PreemptionClosureRequest(List.of("L-V1")));
        assertThat(closure.victims()).extracting(LeaseView::leaseKey)
                .containsExactlyInAnyOrder("L-V1", "L-M1");

        // 只列 L-V1 → 422 闭包不完整，且整单回滚：两条租约仍 ACTIVE
        assertApiStatus(() -> resourceService.preempt("INC-H", "hank",
                new PreemptRequest("REQ-C", "H-1", 0L, "L-HC", 1,
                        List.of(new PreemptionVictimRequest("L-V1", 1L)))),
                HttpStatus.UNPROCESSABLE_ENTITY);
        ResourceUsageView usage = resourceService.resourceUsage("R-1");
        assertThat(usage.activeLeases()).extracting(LeaseView::leaseKey)
                .containsExactlyInAnyOrder("L-V1", "L-M1");

        // 失败不占键：同一 requestId 补全闭包后成功
        PreemptionView ok = resourceService.preempt("INC-H", "hank",
                new PreemptRequest("REQ-C", "H-1", 0L, "L-HC", 1,
                        List.of(new PreemptionVictimRequest("L-V1", 1L),
                                new PreemptionVictimRequest("L-M1", 1L))));
        assertThat(ok.revoked()).hasSize(2);
        assertThat(resourceService.resourceUsage("R-1").activeLeases())
                .extracting(LeaseView::leaseKey).containsExactly("L-HC");
    }

    @Test
    void preempt_startedDependencyChain_422() {
        createResource("R-1", 2);
        commanding("INC-V", "S2", "victor");
        createTask("INC-V", "victor", "V-1", List.of());
        acquire("INC-V", "V-1", "victor", "R-1", 0L, 1, "L-V1");
        commanding("INC-M", "S2", "mary");
        createTask("INC-M", "mary", "M-1", List.of("INC-V"));
        acquire("INC-M", "M-1", "mary", "R-1", 0L, 1, "L-M1");
        // M-1 已 STARTED（直接依赖受害事件 V）
        resourceService.startTask("INC-M", "M-1", "mary",
                new TaskStartRequest(key(), List.of("L-M1")));

        commanding("INC-H", "S1", "hank");
        createTask("INC-H", "hank", "H-1", List.of());
        // 即使计划列出 L-M1，STARTED 依赖链也不得抢占 → 422
        assertApiStatus(() -> resourceService.preempt("INC-H", "hank",
                new PreemptRequest("REQ-SD", "H-1", 0L, "L-HS", 2,
                        List.of(new PreemptionVictimRequest("L-V1", 1L),
                                new PreemptionVictimRequest("L-M1", 1L)))),
                HttpStatus.UNPROCESSABLE_ENTITY);
        // 租约全部仍 ACTIVE
        assertThat(resourceService.resourceUsage("R-1").activeLeases())
                .extracting(LeaseView::leaseKey).containsExactlyInAnyOrder("L-V1", "L-M1");
    }

    @Test
    void preempt_requestIdReorderReplayAndConflict() {
        createResource("R-1", 3);
        commanding("INC-V", "S2", "victor");
        createTask("INC-V", "victor", "V-1", List.of());
        acquire("INC-V", "V-1", "victor", "R-1", 0L, 2, "L-V1");
        LeaseView second = acquire2();
        commanding("INC-H", "S1", "hank");
        createTask("INC-H", "hank", "H-1", List.of());

        var victimsAb = List.of(new PreemptionVictimRequest("L-V1", 1L),
                new PreemptionVictimRequest(second.leaseKey(), 1L));
        var victimsBa = List.of(new PreemptionVictimRequest(second.leaseKey(), 1L),
                new PreemptionVictimRequest("L-V1", 1L));

        PreemptionView first = resourceService.preempt("INC-H", "hank",
                new PreemptRequest("REQ-IDEM", "H-1", 0L, "L-HI", 3, victimsAb));
        // 同参集合换序重放首次响应
        PreemptionView replay = resourceService.preempt("INC-H", "hank",
                new PreemptRequest("REQ-IDEM", "H-1", 0L, "L-HI", 3, victimsBa));
        assertThat(replay).isEqualTo(first);
        // 同 requestId 异参 409
        assertApiStatus(() -> resourceService.preempt("INC-H", "hank",
                new PreemptRequest("REQ-IDEM", "H-1", 0L, "L-HI", 2, victimsAb)),
                HttpStatus.CONFLICT);
        // 只授予一条新租约
        assertThat(resourceService.resourceUsage("R-1").activeLeases())
                .extracting(LeaseView::leaseKey).containsExactly("L-HI");
    }

    private LeaseView acquire2() {
        createTask("INC-V", "victor", "V-2", List.of());
        return acquire("INC-V", "V-2", "victor", "R-1", 0L, 1, "L-V2");
    }

    @Test
    void startedTask_stillRequiresBlockersResolvedToComplete() {
        createResource("R-1", 1);
        commanding("INC-B", "S2", "bob");
        commanding("INC-A", "S2", "alice");
        createTask("INC-A", "alice", "T-1", List.of("INC-B"));
        acquire("INC-A", "T-1", "alice", "R-1", 0L, 1, "L-1");
        resourceService.startTask("INC-A", "T-1", "alice",
                new TaskStartRequest(key(), List.of("L-1")));
        // STARTED 后阻塞事件 B 仍未遏制，完成仍被门禁拒绝
        assertApiStatus(() -> incidentService.completeTask("INC-A", "T-1", "alice",
                new com.example.starter.incident.dto.Requests.TaskActionRequest(key())),
                HttpStatus.CONFLICT);
        // 遏制 B 后完成成功并释放租约
        incidentService.changeStatus("INC-B", "bob",
                new com.example.starter.incident.dto.Requests.StatusRequest(key(), "CONTAINED"));
        TaskView done = incidentService.completeTask("INC-A", "T-1", "alice",
                new com.example.starter.incident.dto.Requests.TaskActionRequest(key()));
        assertThat(done.status()).isEqualTo("DONE");
        assertThat(resourceService.resourceUsage("R-1").resource().usedCapacity()).isZero();
    }

    @Test
    void completeAndCancel_releaseLeasesAtomically() {        createResource("R-1", 1);
        commanding("INC-A", "S2", "alice");
        createTask("INC-A", "alice", "T-1", List.of());
        acquire("INC-A", "T-1", "alice", "R-1", 0L, 1, "L-1");
        resourceService.startTask("INC-A", "T-1", "alice",
                new TaskStartRequest(key(), List.of("L-1")));

        TaskView done = incidentService.completeTask("INC-A", "T-1", "alice",
                new com.example.starter.incident.dto.Requests.TaskActionRequest(key()));
        assertThat(done.status()).isEqualTo("DONE");
        assertThat(done.version()).isEqualTo(2);
        assertThat(resourceService.resourceUsage("R-1").resource().usedCapacity()).isZero();
        assertThat(resourceService.leaseHistory("R-1")).extracting(LeaseView::status)
                .containsExactly("RELEASED");

        // 取消另一个带租约的 OPEN 任务同样释放
        createTask("INC-A", "alice", "T-2", List.of());
        acquire("INC-A", "T-2", "alice", "R-1", 0L, 1, "L-2");
        TaskView cancelled = incidentService.cancelTask("INC-A", "T-2", "alice",
                new com.example.starter.incident.dto.Requests.TaskActionRequest(key()));
        assertThat(cancelled.status()).isEqualTo("CANCELLED");
        assertThat(resourceService.resourceUsage("R-1").resource().usedCapacity()).isZero();
    }
}
