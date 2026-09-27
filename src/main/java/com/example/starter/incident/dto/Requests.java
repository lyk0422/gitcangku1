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
     * 处置提案登记请求：proposalKey 事件内唯一；taskKey 标识提案涉及的任务；
     * decisionType 为 TASK_COMPLETE/TASK_CANCEL；expiresAt 为 UTC 到期时刻，不得早于当前时刻；
     * requiredRoles 为所需有效角色集合，不能为空；quorum 为生效所需支持票法定人数，
     * 不能超过 requiredRoles 的角色数量。
     */
    public record ProposalCreateRequest(String commandKey, String proposalKey, String taskKey,
                                        String decisionType, Instant expiresAt,
                                        List<String> requiredRoles, Integer quorum) {
    }

    /**
     * 投票请求：roleCode 必须属于提案登记的所需角色集合，同一提案同一角色至多一张有效票；
     * choice 为 SUPPORT/OPPOSE。
     */
    public record VoteRequest(String commandKey, String roleCode, String choice) {
    }

    /** 撤票请求：仅投票人本人可撤回自己当前版本的有效票。 */
    public record VoteWithdrawRequest(String commandKey) {
    }

    /**
     * 任务修订请求：仅当前指挥人可发起，OPEN 任务每修订一次版本号 +1，
     * 同事务将引用该任务且仍 OPEN 的提案的当前版本票全部置 STALE；note 为修订说明。
     * title 非空时同时更新任务标题，为空则仅升版本。
     */
    public record TaskReviseRequest(String commandKey, String title, String note) {
    }
}
