package com.example.starter.incident;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

/**
 * 资源授权生命周期协作组件：到期门禁、执行事件记录与任务终态自动回收。
 * 到期只在命令或访问时按注入时钟判定（左闭右开 [assignedAt, expiresAt)），
 * 不依赖后台扫描；判定时同事务固化任务 AUTHORIZATION_EXPIRED、租约 EXPIRED
 * 及证据事件，随后抛 AuthorizationExpiredException，由调用方以
 * dontRollbackOn 提交证据（失败不占 commandKey）。
 */
@Component
public class ResourceLifecycle {

    private final ResourceLeaseRepository leases;
    private final IncidentTaskRepository tasks;
    private final Clock clock;

    public ResourceLifecycle(ResourceLeaseRepository leases, IncidentTaskRepository tasks,
                             Clock clock) {
        this.leases = leases;
        this.tasks = tasks;
        this.clock = clock;
    }

    /**
     * 到期门禁：任务持有 ACTIVE 租约且当前时刻已进入到期点时，固化到期状态与
     * 证据事件并抛 AuthorizationExpiredException；否则无操作。
     *
     * @param interceptedOperation 被拦截的操作名（start/continue/complete/renew），
     *                             非空时追加 EXECUTE_INTERCEPT 证据事件
     */
    public void assertAuthorizationValid(IncidentTask task, String actor,
                                         String interceptedOperation) {
        Optional<ResourceLease> active = leases.findActiveByTask(task.id());
        if (active.isEmpty()) {
            return;
        }
        ResourceLease lease = active.get();
        Instant now = clock.instant();
        if (!lease.expiredAt(now)) {
            return;
        }
        tasks.markAuthorizationExpired(task.id(), now);
        leases.markExpired(lease.id(), now);
        leases.insertEvent(new ResourceEvent(0L, task.incidentId(), task.id(), lease.resourceKey(),
                ResourceEventType.EXPIRE, lease.authVersion(), null, actor, now,
                "授权到期判定", now));
        if (interceptedOperation != null) {
            leases.insertEvent(new ResourceEvent(0L, task.incidentId(), task.id(),
                    lease.resourceKey(), ResourceEventType.EXECUTE_INTERCEPT,
                    lease.authVersion(), null, actor, now,
                    "操作被到期拦截: " + interceptedOperation, now));
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("taskKey", task.taskKey());
        details.put("resourceKey", lease.resourceKey());
        details.put("authVersion", lease.authVersion());
        details.put("expiresAt", lease.expiresAt());
        details.put("evaluatedAt", now);
        details.put("overdueSeconds", ChronoUnit.SECONDS.between(lease.expiresAt(), now));
        throw new AuthorizationExpiredException("资源授权已到期，任务转为 AUTHORIZATION_EXPIRED: "
                + lease.resourceKey(), details);
    }

    /**
     * 任务完成：持有 ACTIVE 租约时记录 EXECUTE_COMPLETE 执行事件并自动回收
     * （RELEASE 事件，原因为任务完成）。无租约时无操作。
     */
    public void onTaskCompleted(IncidentTask task, String actor) {
        Optional<ResourceLease> active = leases.findActiveByTask(task.id());
        if (active.isEmpty()) {
            return;
        }
        ResourceLease lease = active.get();
        Instant now = clock.instant();
        leases.insertEvent(new ResourceEvent(0L, task.incidentId(), task.id(), lease.resourceKey(),
                ResourceEventType.EXECUTE_COMPLETE, lease.authVersion(), null, actor, now,
                null, now));
        leases.markReleased(lease.id(), actor, now);
        leases.insertEvent(new ResourceEvent(0L, task.incidentId(), task.id(), lease.resourceKey(),
                ResourceEventType.RELEASE, lease.authVersion(), null, actor, now,
                "任务完成自动回收", now));
    }

    /**
     * 任务取消：持有 ACTIVE 租约时自动回收（RELEASE 事件，原因为任务取消）。
     * 无租约时无操作。
     */
    public void onTaskCancelled(IncidentTask task, String actor) {
        Optional<ResourceLease> active = leases.findActiveByTask(task.id());
        if (active.isEmpty()) {
            return;
        }
        ResourceLease lease = active.get();
        Instant now = clock.instant();
        leases.markReleased(lease.id(), actor, now);
        leases.insertEvent(new ResourceEvent(0L, task.incidentId(), task.id(), lease.resourceKey(),
                ResourceEventType.RELEASE, lease.authVersion(), null, actor, now,
                "任务取消自动回收", now));
    }

    /**
     * 记录执行类事件（EXECUTE_START / EXECUTE_CONTINUE），仅在任务持有 ACTIVE 租约时调用。
     */
    public void recordExecution(IncidentTask task, ResourceLease lease, ResourceEventType type,
                                String actor) {
        Instant now = clock.instant();
        leases.insertEvent(new ResourceEvent(0L, task.incidentId(), task.id(), lease.resourceKey(),
                type, lease.authVersion(), null, actor, now, null, now));
    }
}
