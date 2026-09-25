package com.example.starter.incident.dto;

import java.time.Instant;
import java.util.List;

/**
 * 读接口与写接口响应体集合。时间字段均为 UTC ISO-8601。
 */
public final class Responses {

    private Responses() {
    }

    /**
     * 事件当前视图：pendingTransferTo 为待接受的交接目标人，无则 null；
     * blockedFrom 为进入 EXTERNAL_BLOCKED 前的状态，仅 EXTERNAL_BLOCKED 有值。
     */
    public record IncidentView(String incidentKey, String severity, String summary, String reporter,
                               String status, String commander, String pendingTransferTo,
                               Instant createdAt, Instant updatedAt, Instant deadlineAt,
                               String blockedFrom) {
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
     * cancelledBy/cancelledAt 仅 CANCELLED 有值；priority 为 NORMAL/HIGH；
     * agencyGate 为外部机构回执门禁原因（仅 HIGH 任务可能 gated）。
     */
    public record TaskView(String taskKey, String groupCode, String title, String status,
                           String priority, List<TaskBlockerView> blockers,
                           TaskGateView agencyGate, String createdBy, Instant createdAt,
                           String doneBy, Instant doneAt, String cancelledBy, Instant cancelledAt) {
    }

    /** 按事件分组的任务列表视图：tasks 按创建顺序返回。 */
    public record IncidentTasksView(String incidentKey, List<TaskView> tasks) {
    }

    /** 解决门禁未完成项：仍有 OPEN 任务时按 groupCode、taskKey 返回。 */
    public record UnfinishedTaskView(String groupCode, String taskKey) {
    }

    /**
     * 任务外部机构门禁视图：gated 表示该 HIGH 任务当前被门禁拦截；
     * reason 为 EXTERNAL_BLOCKED（机构拒绝阻断）或 AGENCY_ACK_PENDING（机构未确认齐全），
     * 未拦截时为 null；pendingAgencies 为尚无回执的必需机构，rejectedAgencies 为已拒绝机构。
     */
    public record TaskGateView(boolean gated, String reason, List<String> pendingAgencies,
                               List<String> rejectedAgencies) {
    }

    /**
     * 外部机构回执视图：configVersion 为回执归属的配置版本（旧版本回执不随配置替换迁移）；
     * reason 仅 REJECT 有值。
     */
    public record AgencyReceiptView(String agencyCode, int configVersion, String type,
                                    String reason, Instant createdAt) {
    }

    /**
     * 外部机构配置查询视图：version 为当前生效配置版本（从未配置为 0）；
     * agencyCodes 为去重排序后的必需机构代码；receipts 为当前版本已接收的回执；
     * pendingAgencies 为当前版本尚无回执的必需机构，rejectedAgencies 为当前版本已拒绝机构。
     */
    public record AgencyConfigView(String incidentKey, int version, List<String> agencyCodes,
                                   String incidentStatus, List<AgencyReceiptView> receipts,
                                   List<String> pendingAgencies, List<String> rejectedAgencies) {
    }
}
