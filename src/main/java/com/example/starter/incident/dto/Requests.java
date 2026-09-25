package com.example.starter.incident.dto;

import java.time.Instant;

/**
 * 写接口请求体集合。commandKey 为调用方幂等键；occurredAt 为 UTC 时间。
 * 演练域请求额外携带 drillKey（非空即标记为演练沙盘）与 batchKey（演练批次标识）。
 */
public final class Requests {

    private Requests() {
    }

    /**
     * 事件上报请求。
     * drillKey 非空表示演练沙盘事件（DRILL 域），此时 batchKey 必填；为空表示真实事件（REAL 域）。
     */
    public record ReportRequest(String incidentKey, String severity, String summary, String reporter,
                                String drillKey, String batchKey) {

        /** 真实事件上报的便捷构造器。 */
        public ReportRequest(String incidentKey, String severity, String summary, String reporter) {
            this(incidentKey, severity, summary, reporter, null, null);
        }
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

    /** 升级请求：toSeverity 必须严格高于当前等级（S4&lt;S3&lt;S2&lt;S1）。 */
    public record EscalateRequest(String commandKey, String toSeverity, String reason) {
    }

    /**
     * 处置任务依赖请求：blockedByIncidentKey 为阻塞事件业务键，必须与当前事件同域，
     * 该键仅存在于另一域时返回 422（跨域引用）。
     */
    public record DependencyRequest(String commandKey, String blockedByIncidentKey) {
    }

    /** 演练事件取消请求；仅演练域 REPORTED 事件可由上报人取消。 */
    public record CancelRequest(String commandKey) {
    }

    /** 演练批次批量清理请求；cleanupKey 为幂等键，batchKey 也可放在路径上。 */
    public record CleanupRequest(String cleanupKey, String batchKey) {
    }
}
