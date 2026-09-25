package com.example.starter.incident.plan.dto;

import java.time.Instant;
import java.util.List;

/**
 * 方案版本、差异与合并证据的读模型集合。时间字段均为 UTC ISO-8601。
 * 所有列表稳定排序：任务按 taskId、边按 from/目标事件/to、冲突按 conflictId。
 */
public final class PlanResponses {

    private PlanResponses() {
    }

    /**
     * 计划任务视图：status/startedBy/startedAt/completedBy/completedAt 仅活动版本
     * 从运行时状态带出，非活动版本与草稿为 null。
     */
    public record PlanTaskView(String taskId, String groupCode, String title, String assignee,
                               String status, String startedBy, Instant startedAt,
                               String completedBy, Instant completedAt) {
    }

    /** 依赖边视图：toIncidentKey 为空串表示本事件内部边。 */
    public record PlanEdgeView(String fromTaskId, String toTaskId, String toIncidentKey) {
    }

    /** 方案版本视图：tasks 按 taskId、edges 按 from/to 稳定排序。 */
    public record PlanVersionView(String incidentKey, int versionNo, String status,
                                  Integer baseVersionNo, String branch, int revision,
                                  List<PlanTaskView> tasks, List<PlanEdgeView> edges,
                                  String createdBy, Instant createdAt, Instant publishedAt) {
    }

    /** 自动采用的单侧（或双侧相同）变更视图。 */
    public record DiffChangeView(String kind, String source, String taskId,
                                 PlanEdgeView edge, PlanTaskView content) {
    }

    /** 显式冲突视图：任务冲突带三侧内容，EDGE_OPPOSITE 冲突带两侧反向边。 */
    public record DiffConflictView(String conflictId, String type, String taskId,
                                   PlanEdgeView edge, PlanEdgeView reverseEdge,
                                   PlanTaskView baseTask, PlanTaskView leftTask,
                                   PlanTaskView rightTask, boolean leftPresent,
                                   boolean rightPresent) {
    }

    /** 三方差异视图：changes 与 conflicts 均稳定排序，只读。 */
    public record PlanDiffView(int baseVersion, int leftVersion, int rightVersion,
                               List<DiffChangeView> changes, List<DiffConflictView> conflicts) {
    }

    /** 冲突解决回执视图。 */
    public record MergeResolutionView(String conflictId, String choice,
                                      PlanTaskView manualTask, Boolean manualEdgePresent) {
    }

    /** 合并结果视图：resultVersionNo 为新发布的方案版本号。 */
    public record MergeResultView(String mergeKey, String requestId, int baseVersion,
                                  int leftVersion, int rightVersion, int resultVersionNo,
                                  int conflictsResolved, Instant createdAt) {
    }

    /**
     * 合并证据视图（冻结、只读）：三方差异、全部冲突解决、最终任务集与边集。
     */
    public record MergeEvidenceView(String mergeKey, String requestId, int baseVersion,
                                    int leftVersion, int rightVersion, int resultVersionNo,
                                    PlanDiffView diff, List<MergeResolutionView> resolutions,
                                    List<PlanTaskView> tasks, List<PlanEdgeView> edges,
                                    String createdBy, Instant createdAt) {
    }
}
