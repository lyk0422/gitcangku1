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
     * 资源分配请求：必须携带 UTC 到期时刻（必须晚于分配时刻）与初始授权版本（>= 1）。
     */
    public record ResourceAssignRequest(String commandKey, String resourceKey,
                                        Integer authorizationVersion, Instant expiresAt) {
    }

    /**
     * 续期请求：一次可包含多个依赖任务；先验证完整依赖闭包中所有资源与授权版本，
     * 任一冲突 422 且不改变任何任务状态或租约。续期人由 X-Actor-Id 指定，
     * 必须不同于各租约的原分配人。
     */
    public record ResourceRenewRequest(String commandKey, List<RenewItem> items) {
    }

    /**
     * 单任务续期项：expectedVersion 为调用方持有的当前授权版本（乐观校验），
     * newVersion 必须大于当前版本，newExpiresAt 为新的 UTC 到期时刻（必须晚于续期时刻）。
     */
    public record RenewItem(String taskKey, Integer expectedVersion, Integer newVersion,
                            Instant newExpiresAt) {
    }
}
