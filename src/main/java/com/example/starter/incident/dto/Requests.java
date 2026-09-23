package com.example.starter.incident.dto;

import java.time.Instant;
import java.util.List;

/**
 * 写接口请求体集合。commandKey 为调用方幂等键；occurredAt 为 UTC 时间。
 */
public final class Requests {

    private Requests() {
    }

    /** 事件上报请求。 */
    public record ReportRequest(String incidentKey, String severity, String summary, String reporter) {
    }

    /** 接管请求。 */
    public record TakeoverRequest(String commandKey) {
    }

    /** 交接发起请求：toCommander 必须不同于当前指挥人。 */
    public record TransferRequest(String commandKey, String toCommander) {
    }

    /** 交接接受请求，操作人由 X-Actor-Id 指定且须为待接受目标人。 */
    public record TransferAcceptRequest(String commandKey) {
    }

    /** 处置记录追加请求。 */
    public record ActionRequest(String commandKey, String actionKey, String actionType,
                                String note, Instant occurredAt) {
    }

    /** 状态变更请求：targetStatus 只允许 CONTAINED / RESOLVED / CLOSED。 */
    public record StatusRequest(String commandKey, String targetStatus) {
    }

    /** 遏制逾期检查请求：以注入 Clock 的当前时刻评估，不做定时扫描。 */
    public record EscalationCheckRequest(String commandKey) {
    }

    /** 升级确认请求：note 为非空处置说明，操作人由 X-Actor-Id 指定且须为当前指挥人。 */
    public record EscalationAckRequest(String commandKey, String note) {
    }

    /**
     * 处置任务创建请求：taskKey 事件内唯一；groupCode、title 非空；
     * blockerIncidentKeys 为 0~5 个阻塞事件键，必须存在且不能是自身，重复键按去重处理。
     */
    public record TaskCreateRequest(String commandKey, String taskKey, String groupCode,
                                    String title, List<String> blockerIncidentKeys) {
    }

    /** 任务完成/取消请求，操作人由 X-Actor-Id 指定且须为当前指挥人。 */
    public record TaskActionRequest(String commandKey) {
    }

    /**
     * 提案边条目：operation 为 ADD/REMOVE；from/to 为事件业务键。
     * 边集合按 (operation, from, to) 结构化去重，换序等价。
     */
    public record GraphProposalEdgeRequest(String operation, String fromIncidentKey,
                                           String toIncidentKey) {
    }

    /**
     * 依赖图变更提案创建请求：requestId 为调用方幂等键；proposalKey 全局唯一；
     * expectedGraphVersion 为提案基于的图版本；edges 去重后 1~50 条。
     */
    public record GraphProposalCreateRequest(String requestId, String proposalKey,
                                             Long expectedGraphVersion, String rationale,
                                             String safetyReviewer,
                                             List<GraphProposalEdgeRequest> edges) {
    }

    /** 提案投票请求：decision 为 APPROVE/REJECT，操作人由 X-Actor-Id 指定且须为名册成员。 */
    public record GraphVoteRequest(String requestId, String decision) {
    }

    /** 提案激活请求，操作人由 X-Actor-Id 指定且须为名册成员。 */
    public record GraphActivateRequest(String requestId) {
    }
}
