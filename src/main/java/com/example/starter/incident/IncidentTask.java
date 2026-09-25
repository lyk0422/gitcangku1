package com.example.starter.incident;

import java.time.Instant;
import java.util.List;

/**
 * 处置任务实体，对应 incident_tasks 表。
 * (incidentId, taskKey) 唯一，同键同内容幂等，同键不同内容冲突；
 * 每事件至多 20 个任务。doneBy/doneAt 仅 DONE 有值，cancelledBy/cancelledAt 仅 CANCELLED 有值，
 * startedBy/startedAt 仅 IN_PROGRESS 及之后状态有值，evacuatedBy/evacuatedAt 仅 EVACUATED 有值。
 * workGrids 为规范化排序后的作业网格集合（高危任务必填）；finalPosition 为最终位置网格码，
 * 批量派工前必须就位。时间均为 UTC。
 */
public record IncidentTask(
        long id,
        long incidentId,
        String taskKey,
        String groupCode,
        String title,
        TaskStatus status,
        boolean highRisk,
        List<String> workGrids,
        String finalPosition,
        String createdBy,
        String startedBy,
        Instant startedAt,
        String doneBy,
        Instant doneAt,
        String cancelledBy,
        Instant cancelledAt,
        String evacuatedBy,
        Instant evacuatedAt,
        Instant createdAt,
        Instant updatedAt) {

    /**
     * 判断两条任务的业务内容是否一致（用于 taskKey 幂等比对；阻塞事件集合另行比对）。
     */
    public boolean sameContent(String groupCode, String title, boolean highRisk,
                               List<String> workGrids, String finalPosition) {
        return this.groupCode.equals(groupCode) && this.title.equals(title)
                && this.highRisk == highRisk && this.workGrids.equals(workGrids)
                && java.util.Objects.equals(this.finalPosition, finalPosition);
    }
}
