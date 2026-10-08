package com.example.starter.incident.dto;

import java.time.Instant;
import java.util.List;

/**
 * 读接口与写接口响应体集合。时间字段均为 UTC ISO-8601。
 */
public final class Responses {

    private Responses() {
    }

    /** 事件当前视图：pendingTransferTo 为待接受的交接目标人，无则 null。 */
    public record IncidentView(String incidentKey, String severity, String summary, String reporter,
                               String status, String commander, String pendingTransferTo,
                               Instant createdAt, Instant updatedAt, Instant deadlineAt) {
    }

    /** 交接单视图。 */
    public record TransferView(long id, String fromCommander, String toCommander,
                               String status, Instant createdAt, Instant acceptedAt) {
    }

    /** 处置记录视图。 */
    public record ActionView(String actionKey, String actionType, String note,
                             Instant occurredAt, String actor, Instant createdAt) {
    }

    /** 状态流转历史视图：fromStatus 在创建记录中为 null。 */
    public record StatusChangeView(String fromStatus, String toStatus, String actor, Instant occurredAt) {
    }

    /**
     * 遏制逾期升级记录视图：deadlineAt 为接管时确定的遏制期限（UTC）；
     * triggeredAt 为触发时刻；note/acknowledgedBy/acknowledgedAt 仅 ACKNOWLEDGED 有值。
     */
    public record EscalationView(Long id, Instant deadlineAt, Instant triggeredAt,
                                 String triggeredCommander, String status, String note,
                                 String acknowledgedBy, Instant acknowledgedAt, Instant createdAt) {
    }

    /** 完整历史：事件本体 + 状态流转 + 处置记录 + 交接记录 + 升级记录。 */
    public record HistoryView(IncidentView incident, List<StatusChangeView> statusHistory,
                              List<ActionView> actions, List<TransferView> transfers,
                              List<EscalationView> escalations) {
    }

    /**
     * 升级视图：遏制期限 deadlineAt（REPORTED 为空）、当前升级 current（无则 null）
     * 及该事件完整升级历史 history（当前每事件至多一条）。
     * 检查入口与升级查询均返回该结构；只读查询不会隐式写入。
     */
    public record EscalationHistoryView(Instant deadlineAt, EscalationView current,
                                        List<EscalationView> history) {
    }

    /**
     * 任务阻塞事件视图：incidentStatus 为查询时目标事件的当前状态（不写回依赖任务）；
     * resolved 表示该事件已进入 CONTAINED/RESOLVED/CLOSED，阻塞已解除。
     */
    public record TaskBlockerView(String incidentKey, String incidentStatus, boolean resolved) {
    }

    /**
     * 处置任务视图：blockers 按阻塞事件键排序；doneBy/doneAt 仅 DONE 有值，
     * cancelledBy/cancelledAt 仅 CANCELLED 有值。
     */
    public record TaskView(String taskKey, String groupCode, String title, String status,
                           List<TaskBlockerView> blockers, String createdBy, Instant createdAt,
                           String doneBy, Instant doneAt, String cancelledBy, Instant cancelledAt) {
    }

    /** 按事件分组的任务列表视图：tasks 按创建顺序返回。 */
    public record IncidentTasksView(String incidentKey, List<TaskView> tasks) {
    }

    /** 解决门禁未完成项：仍有 OPEN 任务时按 groupCode、taskKey 返回。 */
    public record UnfinishedTaskView(String groupCode, String taskKey) {
    }

    /** 解决门禁未终结计划：存在 RUNNING/COMPENSATING 计划时按 planId、status 返回。 */
    public record UnfinishedPlanView(long planId, String status) {
    }

    /**
     * 单次补偿尝试视图（只追加、只读）：evidence 仅成功有值，
     * failureDiagnostic 仅失败有值；requestId 为该次写入携带的全局请求 id。
     */
    public record CompensationAttemptView(long id, int stepIndex, int attemptNo, String status,
                                          String evidence, String failureDiagnostic,
                                          String actor, String requestId, java.time.Instant createdAt) {
    }

    /**
     * 计划步骤视图：effectDesc 为原合成效果说明（补偿后仍保留）；
     * attempts 为该步骤全部补偿尝试（成功/失败均含），按尝试顺序返回；
     * appliedBy/appliedAt 仅 APPLIED/COMPENSATED 有值，compensatedBy/compensatedAt 仅 COMPENSATED 有值。
     */
    public record PlanStepView(int stepIndex, String stepKey, String effectDesc,
                               String compensationDesc, String status,
                               String appliedBy, java.time.Instant appliedAt,
                               String compensatedBy, java.time.Instant compensatedAt,
                               List<CompensationAttemptView> attempts) {
    }

    /**
     * 指令计划视图：steps 按执行顺序返回。
     * nextStepKey 为执行阶段（RUNNING）当前唯一允许执行的下一步，无（已全部执行或非 RUNNING）为 null；
     * compensationStepKey 为 COMPENSATING 时当前唯一允许补偿的步骤（序号最大的 APPLIED 步骤），
     * 其余情况为 null。status=COMPENSATING 且 compensationStepKey 为 null 表示尚有失败诊断待重试的边界不会出现
     * ——只要存在 APPLIED 步骤该字段必非 null。
     */
    public record PlanView(long planId, String incidentKey, String status, long version,
                           String createdBy, java.time.Instant createdAt, java.time.Instant updatedAt,
                           List<PlanStepView> steps, String nextStepKey,
                           String compensationStepKey) {
    }
}
