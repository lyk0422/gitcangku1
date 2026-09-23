package com.example.starter.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import com.example.starter.incident.dto.Requests.LeaseRequest;
import com.example.starter.incident.dto.Requests.ReportRequest;
import com.example.starter.incident.dto.Requests.ResourceCreateRequest;
import com.example.starter.incident.dto.Requests.StatusRequest;
import com.example.starter.incident.dto.Requests.TakeoverRequest;
import com.example.starter.incident.dto.Requests.TaskActionRequest;
import com.example.starter.incident.dto.Requests.TaskCreateRequest;
import com.example.starter.incident.dto.Responses.LeaseHistoryView;
import com.example.starter.incident.dto.Responses.LeaseView;
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
 * 共享资源与租约服务测试：覆盖资源创建校验、租约申请主流程与失败分支、
 * 容量与版本边界、leaseKey/requestId 幂等、STARTED 门禁及完成/取消原子释放。
 */
@SpringBootTest
class ResourceLeaseServiceTest {

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

    private TaskView createTask(String incidentKey, String actor, String taskKey) {
        return incidentService.createTask(incidentKey, actor,
                new TaskCreateRequest(key(), taskKey, "G", "t", List.of()));
    }

    private ResourceView createResource(String resourceKey, int capacity) {
        return resourceService.createResource("ops",
                new ResourceCreateRequest(resourceKey, capacity));
    }

    private LeaseView requestLease(String incidentKey, String taskKey, String actor,
                                   String resourceKey, String leaseKey, int units,
                                   long taskVersion) {
        return resourceService.requestLease(incidentKey, taskKey, actor,
                new LeaseRequest(key(), resourceKey, leaseKey, units, taskVersion));
    }

    @Test
    void createResource_validationAndDuplicate() {
        ResourceView view = createResource("RES-1", 3);
        assertThat(view.resourceKey()).isEqualTo("RES-1");
        assertThat(view.capacity()).isEqualTo(3);
        assertThat(view.activeUnits()).isZero();
        assertThat(view.availableUnits()).isEqualTo(3);
        assertThat(view.activeLeases()).isEmpty();

        // 容量必须为正整数
        assertApiStatus(() -> resourceService.createResource("ops",
                new ResourceCreateRequest("RES-2", 0)), HttpStatus.BAD_REQUEST);
        assertApiStatus(() -> resourceService.createResource("ops",
                new ResourceCreateRequest("RES-2", -1)), HttpStatus.BAD_REQUEST);
        assertApiStatus(() -> resourceService.createResource("ops",
                new ResourceCreateRequest("RES-2", null)), HttpStatus.BAD_REQUEST);
        // resourceKey 非空且唯一
        assertApiStatus(() -> resourceService.createResource("ops",
                new ResourceCreateRequest(" ", 1)), HttpStatus.BAD_REQUEST);
        assertApiStatus(() -> resourceService.createResource("ops",
                new ResourceCreateRequest("RES-1", 5)), HttpStatus.CONFLICT);
        // 资源不存在
        assertApiStatus(() -> resourceService.getResource("RES-404"), HttpStatus.NOT_FOUND);
        assertApiStatus(() -> resourceService.listLeases("RES-404"), HttpStatus.NOT_FOUND);
    }

    @Test
    void requestLease_mainFlowAndOccupancy() {
        commanding("INC-L1", "S2", "alice");
        TaskView task = createTask("INC-L1", "alice", "T-1");
        createResource("RES-L", 5);

        LeaseView lease = requestLease("INC-L1", "T-1", "alice", "RES-L", "LK-1", 2,
                task.version());
        assertThat(lease.leaseKey()).isEqualTo("LK-1");
        assertThat(lease.resourceKey()).isEqualTo("RES-L");
        assertThat(lease.incidentKey()).isEqualTo("INC-L1");
        assertThat(lease.taskKey()).isEqualTo("T-1");
        assertThat(lease.units()).isEqualTo(2);
        assertThat(lease.status()).isEqualTo("ACTIVE");
        assertThat(lease.version()).isEqualTo(1);
        assertThat(lease.createdBy()).isEqualTo("alice");
        assertThat(lease.releasedAt()).isNull();
        assertThat(lease.revokedAt()).isNull();

        ResourceView occupancy = resourceService.getResource("RES-L");
        assertThat(occupancy.activeUnits()).isEqualTo(2);
        assertThat(occupancy.availableUnits()).isEqualTo(3);
        assertThat(occupancy.activeLeases()).containsExactly(lease);

        LeaseHistoryView history = resourceService.listLeases("RES-L");
        assertThat(history.resourceKey()).isEqualTo("RES-L");
        assertThat(history.leases()).containsExactly(lease);
    }

    @Test
    void requestLease_validationFailures() {
        commanding("INC-V1", "S2", "alice");
        TaskView task = createTask("INC-V1", "alice", "T-1");
        createResource("RES-V", 2);

        // 非当前指挥人
        assertApiStatus(() -> requestLease("INC-V1", "T-1", "bob", "RES-V", "LK-1", 1,
                task.version()), HttpStatus.CONFLICT);
        // 任务/事件/资源不存在
        assertApiStatus(() -> requestLease("INC-V1", "T-9", "alice", "RES-V", "LK-1", 1,
                task.version()), HttpStatus.NOT_FOUND);
        assertApiStatus(() -> requestLease("INC-404", "T-1", "alice", "RES-V", "LK-1", 1,
                task.version()), HttpStatus.NOT_FOUND);
        assertApiStatus(() -> requestLease("INC-V1", "T-1", "alice", "RES-404", "LK-1", 1,
                task.version()), HttpStatus.NOT_FOUND);
        // units 边界：0、负数、超容量、空
        assertApiStatus(() -> requestLease("INC-V1", "T-1", "alice", "RES-V", "LK-1", 0,
                task.version()), HttpStatus.BAD_REQUEST);
        assertApiStatus(() -> requestLease("INC-V1", "T-1", "alice", "RES-V", "LK-1", 3,
                task.version()), HttpStatus.BAD_REQUEST);
        assertApiStatus(() -> resourceService.requestLease("INC-V1", "T-1", "alice",
                new LeaseRequest(key(), "RES-V", "LK-1", null, task.version())),
                HttpStatus.BAD_REQUEST);
        // 任务版本不一致
        assertApiStatus(() -> requestLease("INC-V1", "T-1", "alice", "RES-V", "LK-1", 1,
                task.version() + 1), HttpStatus.CONFLICT);
        // 必填字段为空
        assertApiStatus(() -> resourceService.requestLease("INC-V1", "T-1", "alice",
                new LeaseRequest(null, "RES-V", "LK-1", 1, task.version())),
                HttpStatus.BAD_REQUEST);
        assertApiStatus(() -> resourceService.requestLease("INC-V1", "T-1", "alice",
                new LeaseRequest(key(), " ", "LK-1", 1, task.version())),
                HttpStatus.BAD_REQUEST);
        assertApiStatus(() -> resourceService.requestLease("INC-V1", "T-1", "alice",
                new LeaseRequest(key(), "RES-V", " ", 1, task.version())),
                HttpStatus.BAD_REQUEST);
        assertApiStatus(() -> resourceService.requestLease("INC-V1", "T-1", "alice",
                new LeaseRequest(key(), "RES-V", "LK-1", 1, null)),
                HttpStatus.BAD_REQUEST);
    }

    @Test
    void requestLease_capacityAndDuplicateActive() {
        commanding("INC-C1", "S2", "alice");
        commanding("INC-C2", "S2", "bob");
        TaskView t1 = createTask("INC-C1", "alice", "T-1");
        TaskView t2 = createTask("INC-C2", "bob", "T-1");
        createResource("RES-C", 3);

        requestLease("INC-C1", "T-1", "alice", "RES-C", "LK-1", 2, t1.version());
        // 同任务同资源第二条 ACTIVE 租约 → 409
        assertApiStatus(() -> requestLease("INC-C1", "T-1", "alice", "RES-C", "LK-2", 1,
                t1.version()), HttpStatus.CONFLICT);
        // 剩余容量不足 → 409
        assertApiStatus(() -> requestLease("INC-C2", "T-1", "bob", "RES-C", "LK-3", 2,
                t2.version()), HttpStatus.CONFLICT);
        // 恰好占满容量成功
        LeaseView second = requestLease("INC-C2", "T-1", "bob", "RES-C", "LK-4", 1,
                t2.version());
        assertThat(second.status()).isEqualTo("ACTIVE");
        ResourceView occupancy = resourceService.getResource("RES-C");
        assertThat(occupancy.activeUnits()).isEqualTo(3);
        assertThat(occupancy.availableUnits()).isZero();
    }

    @Test
    void requestLease_leaseKeyIdempotency() {
        commanding("INC-K1", "S2", "alice");
        TaskView task = createTask("INC-K1", "alice", "T-1");
        createResource("RES-K", 5);

        LeaseView first = requestLease("INC-K1", "T-1", "alice", "RES-K", "LK-1", 2,
                task.version());
        // 不同 requestId、同 leaseKey 同内容：幂等返回既有租约
        LeaseView again = requestLease("INC-K1", "T-1", "alice", "RES-K", "LK-1", 2,
                task.version());
        assertThat(again).isEqualTo(first);
        assertThat(resourceService.listLeases("RES-K").leases()).hasSize(1);
        // 同 leaseKey 不同内容（单位数/资源）→ 409
        assertApiStatus(() -> requestLease("INC-K1", "T-1", "alice", "RES-K", "LK-1", 3,
                task.version()), HttpStatus.CONFLICT);
        createResource("RES-K2", 5);
        assertApiStatus(() -> requestLease("INC-K1", "T-1", "alice", "RES-K2", "LK-1", 2,
                task.version()), HttpStatus.CONFLICT);
    }

    @Test
    void requestLease_requestIdIdempotencyAndFailureNotConsume() {
        commanding("INC-I1", "S2", "alice");
        TaskView task = createTask("INC-I1", "alice", "T-1");
        TaskView task2 = createTask("INC-I1", "alice", "T-2");
        createResource("RES-I", 3);

        // 同 requestId 同参重放首次响应
        String requestId = key();
        LeaseView first = resourceService.requestLease("INC-I1", "T-1", "alice",
                new LeaseRequest(requestId, "RES-I", "LK-1", 1, task.version()));
        LeaseView replay = resourceService.requestLease("INC-I1", "T-1", "alice",
                new LeaseRequest(requestId, "RES-I", "LK-1", 1, task.version()));
        assertThat(replay).isEqualTo(first);
        assertThat(resourceService.listLeases("RES-I").leases()).hasSize(1);
        // 同 requestId 改参 → 409
        assertApiStatus(() -> resourceService.requestLease("INC-I1", "T-1", "alice",
                new LeaseRequest(requestId, "RES-I", "LK-2", 1, task.version())),
                HttpStatus.CONFLICT);
        // 业务失败不占键：参数非法失败后，同 requestId 修正参数可成功
        String failId = key();
        assertApiStatus(() -> resourceService.requestLease("INC-I1", "T-2", "alice",
                new LeaseRequest(failId, "RES-I", "LK-9", 5, task2.version())),
                HttpStatus.BAD_REQUEST);
        LeaseView recovered = resourceService.requestLease("INC-I1", "T-2", "alice",
                new LeaseRequest(failId, "RES-I", "LK-9", 1, task2.version()));
        assertThat(recovered.status()).isEqualTo("ACTIVE");
    }

    @Test
    void startTask_requiresActiveLease() {
        commanding("INC-S1", "S2", "alice");
        TaskView task = createTask("INC-S1", "alice", "T-1");
        createResource("RES-S", 2);

        // 无 ACTIVE 租约不能启动
        assertApiStatus(() -> resourceService.startTask("INC-S1", "T-1", "alice",
                new TaskActionRequest(key())), HttpStatus.CONFLICT);
        // 非当前指挥人不能启动
        requestLease("INC-S1", "T-1", "alice", "RES-S", "LK-1", 1, task.version());
        assertApiStatus(() -> resourceService.startTask("INC-S1", "T-1", "bob",
                new TaskActionRequest(key())), HttpStatus.CONFLICT);
        // 持有 ACTIVE 租约可启动，版本加 1
        TaskView started = resourceService.startTask("INC-S1", "T-1", "alice",
                new TaskActionRequest(key()));
        assertThat(started.status()).isEqualTo("STARTED");
        assertThat(started.startedBy()).isEqualTo("alice");
        assertThat(started.startedAt()).isNotNull();
        assertThat(started.version()).isEqualTo(task.version() + 1);
        // 重复启动 → 409；STARTED 任务不能再申请租约
        assertApiStatus(() -> resourceService.startTask("INC-S1", "T-1", "alice",
                new TaskActionRequest(key())), HttpStatus.CONFLICT);
        assertApiStatus(() -> requestLease("INC-S1", "T-1", "alice", "RES-S", "LK-2", 1,
                started.version()), HttpStatus.CONFLICT);
    }

    @Test
    void completeTask_releasesLeaseAtomically() {
        commanding("INC-D1", "S2", "alice");
        TaskView task = createTask("INC-D1", "alice", "T-1");
        createResource("RES-D", 2);
        requestLease("INC-D1", "T-1", "alice", "RES-D", "LK-1", 2, task.version());
        resourceService.startTask("INC-D1", "T-1", "alice", new TaskActionRequest(key()));

        // STARTED 任务完成后租约原子释放
        TaskView done = incidentService.completeTask("INC-D1", "T-1", "alice",
                new TaskActionRequest(key()));
        assertThat(done.status()).isEqualTo("DONE");
        assertThat(done.startedBy()).isEqualTo("alice");

        ResourceView occupancy = resourceService.getResource("RES-D");
        assertThat(occupancy.activeUnits()).isZero();
        LeaseView released = resourceService.listLeases("RES-D").leases().get(0);
        assertThat(released.status()).isEqualTo("RELEASED");
        assertThat(released.version()).isEqualTo(2);
        assertThat(released.releasedAt()).isNotNull();
        assertThat(released.revokedAt()).isNull();
    }

    @Test
    void cancelTask_releasesLeaseAtomically() {
        commanding("INC-X1", "S2", "alice");
        TaskView task = createTask("INC-X1", "alice", "T-1");
        createResource("RES-X", 2);
        requestLease("INC-X1", "T-1", "alice", "RES-X", "LK-1", 1, task.version());

        incidentService.cancelTask("INC-X1", "T-1", "alice", new TaskActionRequest(key()));
        assertThat(resourceService.getResource("RES-X").activeUnits()).isZero();
        LeaseView released = resourceService.listLeases("RES-X").leases().get(0);
        assertThat(released.status()).isEqualTo("RELEASED");
        // 释放后同 leaseKey 仍被占用（全局唯一），新 leaseKey 可重新申请
        TaskView anyTask = incidentService.getTask("INC-X1", "T-1");
        assertThat(anyTask.status()).isEqualTo("CANCELLED");
    }

    @Test
    void startedTask_blocksResolve() {
        commanding("INC-R1", "S2", "alice");
        TaskView task = createTask("INC-R1", "alice", "T-1");
        createResource("RES-R", 1);
        requestLease("INC-R1", "T-1", "alice", "RES-R", "LK-1", 1, task.version());
        resourceService.startTask("INC-R1", "T-1", "alice", new TaskActionRequest(key()));

        incidentService.changeStatus("INC-R1", "alice", new StatusRequest(key(), "CONTAINED"));
        // STARTED 任务同样触发解决门禁
        assertApiStatus(() -> incidentService.changeStatus("INC-R1", "alice",
                new StatusRequest(key(), "RESOLVED")), HttpStatus.CONFLICT);
        // 完成后可解决
        incidentService.completeTask("INC-R1", "T-1", "alice", new TaskActionRequest(key()));
        assertThat(incidentService.changeStatus("INC-R1", "alice",
                new StatusRequest(key(), "RESOLVED")).status()).isEqualTo("RESOLVED");
    }
}
