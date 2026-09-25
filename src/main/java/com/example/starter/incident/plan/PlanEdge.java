package com.example.starter.incident.plan;

import java.time.Instant;

/**
 * 方案版本内的依赖边：fromTaskId 为前置任务（本版本内 taskId）；
 * toIncidentKey 为空串表示本事件内部边，否则为跨事件边（指向目标事件活动版本的 toTaskId）。
 * 边语义：to 依赖 from，from 完成后 to 才可启动。
 */
public record PlanEdge(long id, long versionId, String fromTaskId, String toIncidentKey,
                       String toTaskId, Instant createdAt) {

    /** 内部边的 toIncidentKey 哨兵值（空串），保证唯一约束对内部边同样生效。 */
    public static final String INTERNAL = "";

    /**
     * 边身份：from + 目标事件 + to，三方合并按它对齐。
     */
    public String identity() {
        return fromTaskId + "->" + toIncidentKey + ":" + toTaskId;
    }

    /**
     * 同身份判断。
     */
    public boolean sameIdentity(PlanEdge other) {
        return other != null && fromTaskId.equals(other.fromTaskId)
                && toIncidentKey.equals(other.toIncidentKey) && toTaskId.equals(other.toTaskId);
    }
}
