package com.example.starter.incident;

import java.time.Instant;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 到期固化器：在独立事务（REQUIRES_NEW）中把授权到期的判定结果落库——
 * 任务置为 AUTHORIZATION_EXPIRED 并追加 EXPIRE/EXECUTE_DENIED 证据。
 * 拦截类命令随后以 422 失败回滚其自身事务（幂等键不占位），
 * 但到期状态与证据作为完整一致的事实保留，不被回滚也不被后续操作覆盖。
 */
@Component
public class ExpiryMarker {

    private final IncidentTaskRepository tasks;
    private final ResourceEvidenceRepository evidence;

    public ExpiryMarker(IncidentTaskRepository tasks, ResourceEvidenceRepository evidence) {
        this.tasks = tasks;
        this.evidence = evidence;
    }

    /**
     * 固化一次到期判定与执行拦截。若任务已被并发判定为到期（本次更新 0 行），
     * 只补写 EXECUTE_DENIED，不重复写 EXPIRE。
     *
     * @param deniedAction 被拦截的操作名（start/execute/complete）
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordExpiryAndDenial(ResourceAssignment assignment, String actor,
                                      String deniedAction, Instant now) {
        int transitioned = tasks.markAuthorizationExpiredIfOpen(assignment.taskId(), now);
        if (transitioned > 0) {
            evidence.insertEvent(new ResourceEvent(0L, assignment.id(), assignment.incidentId(),
                    assignment.taskId(), assignment.resourceKey(), ResourceEventType.EXPIRE,
                    assignment.authorizationVersion(), null, actor, now, null));
        }
        evidence.insertEvent(new ResourceEvent(0L, assignment.id(), assignment.incidentId(),
                assignment.taskId(), assignment.resourceKey(), ResourceEventType.EXECUTE_DENIED,
                assignment.authorizationVersion(), null, actor, now,
                "拦截操作: " + deniedAction));
    }
}
