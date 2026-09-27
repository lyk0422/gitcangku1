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

    /** 任务修订请求：title 为新标题，修订后任务版本 +1，旧版本投票转 STALE。 */
    public record TaskReviseRequest(String commandKey, String title) {
    }

    /**
     * 决策提案登记请求：proposalKey 事件内唯一；taskKey 为提案针对的任务；
     * proposedTitle 为生效后原子写入的任务新标题；requiredRoles 为可投票角色集合
     * （去重去空白后非空）；quorum 为法定人数（1..角色数）；expiresAt 为 UTC 到期时刻，
     * 不得早于服务端当前时刻。
     */
    public record ProposalCreateRequest(String commandKey, String proposalKey, String taskKey,
                                        String proposedTitle, List<String> requiredRoles,
                                        Integer quorum, Instant expiresAt) {
    }

    /** 投票请求：vote 取值 SUPPORT / OPPOSE，投票人由 X-Actor-Id 指定且须在角色集合内。 */
    public record VoteRequest(String commandKey, String vote) {
    }

    /** 撤回投票请求，操作人由 X-Actor-Id 指定，仅可撤回自己的有效票。 */
    public record WithdrawRequest(String commandKey) {
    }
}
