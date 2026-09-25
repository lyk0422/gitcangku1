package com.example.starter.incident.plan.dto;

import java.time.Instant;
import java.util.List;

import com.example.starter.incident.plan.dto.PlanRequests.MergeResolutionItem;

/**
 * 方案合并读接口与写接口响应体集合。时间字段均为 UTC ISO-8601；
 * 差异与合并证据查询只读，列表均按稳定键排序。
 */
public final class PlanResponses {

    private PlanResponses() {
    }

    /**
     * 方案任务视图：status 为执行态（无执行记录时 PENDING）；
     * startedBy/startedAt、completedBy/completedAt 仅对应状态有值。
     */
    public record PlanTaskView(String taskId, String title, String assignee, String status,
                               String startedBy, Instant startedAt,
                               String completedBy, Instant completedAt) {
    }

    /** 方案依赖边视图：fromTaskId 依赖方 → toIncidentKey/toTaskId 前置任务。 */
    public record PlanEdgeView(String fromTaskId, String toIncidentKey, String toTaskId) {
    }

    /**
     * 方案版本视图（含任务与边，均按稳定键排序）。branchSide 仅 DRAFT 有值；
     * revision 为草稿修订计数；left/rightVersionId 仅合并产生版本有值。
     */
    public record PlanVersionView(long id, String incidentKey, int versionNo, String status,
                                  Long baseVersionId, String branchSide, int revision,
                                  Long leftVersionId, Long rightVersionId,
                                  String createdBy, Instant createdAt, Instant publishedAt,
                                  List<PlanTaskView> tasks, List<PlanEdgeView> edges) {
    }

    /** 差异条目中的任务字段快照：title + assignee。 */
    public record TaskFieldsView(String title, String assignee) {
    }

    /**
     * 自动采用的任务变化：action 为 ADDED / REMOVED / MODIFIED；
     * base/left/right/adopted 为各方字段快照，任务不存在的一侧为 null。
     */
    public record TaskDiffEntry(String taskId, String action, TaskFieldsView base,
                                TaskFieldsView left, TaskFieldsView right, TaskFieldsView adopted) {
    }

    /** 自动采用的边变化：edge 为规范字符串 fromTaskId>toIncidentKey/toTaskId。 */
    public record EdgeDiffEntry(String edge, String action, String adoptedBy) {
    }

    /**
     * 显式冲突：type 为 FIELD_DIVERGENCE / BOTH_ADDED / DELETE_VS_MODIFY（任务）
     * 或 OPPOSITE_DIRECTION（边）；任务冲突给 base/left/right 快照（删除侧为 null），
     * 边冲突给 leftEdge/rightEdge 规范字符串。
     */
    public record ConflictView(String conflictId, String type, String taskId,
                               TaskFieldsView base, TaskFieldsView left, TaskFieldsView right,
                               String leftEdge, String rightEdge) {
    }

    /** 三方差异视图：自动采用项与全部显式冲突，均按稳定键排序。 */
    public record PlanDiffView(long baseVersionId, long leftVersionId, long rightVersionId,
                               List<TaskDiffEntry> taskChanges, List<EdgeDiffEntry> edgeChanges,
                               List<ConflictView> conflicts) {
    }

    /** 合并成功响应：resultVersionId/resultVersionNo 为新生成的 PUBLISHED 版本。 */
    public record MergeView(String mergeKey, String incidentKey,
                            long baseVersionId, long leftVersionId, long rightVersionId,
                            long resultVersionId, int resultVersionNo, String status,
                            int resolvedConflictCount, int taskCount, int edgeCount,
                            Instant mergedAt) {
    }

    /**
     * 合并证据视图：冻结的三方差异、全部冲突解决、最终任务集与边集。
     * 只读查询，列表均按稳定键排序。
     */
    public record MergeEvidenceView(String mergeKey, String requestId, String incidentKey,
                                    long baseVersionId, long leftVersionId, long rightVersionId,
                                    long resultVersionId, PlanDiffView diff,
                                    List<MergeResolutionItem> resolutions,
                                    List<PlanTaskView> finalTasks, List<PlanEdgeView> finalEdges,
                                    String createdBy, Instant createdAt) {
    }
}
