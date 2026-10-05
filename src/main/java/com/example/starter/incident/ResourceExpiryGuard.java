package com.example.starter.incident;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

import com.example.starter.incident.dto.Responses.AuthorizationExpiredDetails;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 资源授权到期拦截器。在开始/继续/完成命令的写事务内以 REQUIRES_NEW 独立事务提交：
 * 到期时把任务置为 AUTHORIZATION_EXPIRED 并固化 EXPIRY_DETECTED 与 EXECUTION_BLOCKED
 * 证据后立即提交；随后命令本身以 409 失败回滚，幂等键不被占用。
 * 到期判定只发生在命令/访问时（注入时钟），不依赖后台扫描。
 */
@Component
public class ResourceExpiryGuard {

    private final IncidentTaskRepository tasks;
    private final TaskResourceRepository resources;
    private final ResourceEvidenceRepository evidence;
    private final Clock clock;

    public ResourceExpiryGuard(IncidentTaskRepository tasks, TaskResourceRepository resources,
                               ResourceEvidenceRepository evidence, Clock clock) {
        this.tasks = tasks;
        this.resources = resources;
        this.evidence = evidence;
        this.clock = clock;
    }

    /**
     * 若任务已分配资源且授权到期（或已处于 AUTHORIZATION_EXPIRED），固化到期/拦截证据
     * 并返回应抛出的 409 异常；否则返回空。任务终态或未分配资源时不拦截。
     *
     * @param taskId 任务主键
     * @param attemptedAction 被拦截的执行动作（START/CONTINUE/COMPLETE）
     * @param actor 操作人
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<ApiException> interceptIfExpired(long taskId, String attemptedAction,
                                                     String actor) {
        Optional<TaskResourceAssignment> assignmentOpt = resources.findByTaskId(taskId);
        if (assignmentOpt.isEmpty()) {
            return Optional.empty();
        }
        IncidentTask task = tasks.findById(taskId)
                .orElseThrow(() -> ApiException.notFound("任务不存在: " + taskId));
        if (task.status() == TaskStatus.DONE || task.status() == TaskStatus.CANCELLED) {
            return Optional.empty();
        }
        TaskResourceAssignment assignment = assignmentOpt.get();
        Instant now = clock.instant();
        boolean expired = assignment.expiredAt(now);
        if (task.status() != TaskStatus.AUTHORIZATION_EXPIRED && !expired) {
            return Optional.empty();
        }
        if (expired && task.status() != TaskStatus.AUTHORIZATION_EXPIRED) {
            tasks.markAuthorizationExpired(task.id(), task.status(), now);
            evidence.insertEvent(new ResourceAuthorizationEvent(0L, ResourceEventType.EXPIRY_DETECTED,
                    task.id(), task.incidentId(), task.taskKey(), assignment.resourceKey(),
                    assignment.authorizationVersion(), null, actor, null, now));
        }
        evidence.insertEvent(new ResourceAuthorizationEvent(0L, ResourceEventType.EXECUTION_BLOCKED,
                task.id(), task.incidentId(), task.taskKey(), assignment.resourceKey(),
                assignment.authorizationVersion(), null, actor, attemptedAction, now));
        return Optional.of(ApiException.conflict("资源授权已到期，任务转入 AUTHORIZATION_EXPIRED，"
                        + "禁止" + attemptedAction + "；须由不同于原分配人的授权角色续期",
                new AuthorizationExpiredDetails(task.taskKey(), assignment.resourceKey(),
                        assignment.authorizationVersion(), assignment.expiresAt(), now,
                        attemptedAction)));
    }
}
