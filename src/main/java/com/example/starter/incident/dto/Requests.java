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
     * 资源分配请求：为 OPEN 任务分配资源租约（初始版本 1）。
     * resourceKey 全局唯一标识资源；authVersion 为授权版本号；
     * expiresAt 为 UTC 到期时刻，必须严格晚于分配时刻，授权区间 [分配时刻, expiresAt) 左闭右开。
     */
    public record ResourceAssignRequest(String commandKey, String resourceKey, String authVersion,
                                        Instant expiresAt) {
    }

    /**
     * 批量续期请求：taskKeys 必须恰好覆盖事件内资源依赖闭包
     * （同一事件内全部持有活动租约的 OPEN 任务）；newAuthVersion 为不同于各租约当前版本的新版本；
     * authorizerRole 为授权角色（非空），操作人（X-Actor-Id）必须不同于原分配人；
     * expiresAt 为新的 UTC 到期时刻，必须严格晚于续期时刻。
     */
    public record LeaseRenewRequest(String commandKey, List<String> taskKeys, String newAuthVersion,
                                    String authorizerRole, Instant expiresAt) {
    }

    /** 任务执行请求：action 取值 START（开始，每任务至多一次）/ CONTINUE（继续执行，须已 START）。 */
    public record TaskExecutionRequest(String commandKey, String action) {
    }
}
