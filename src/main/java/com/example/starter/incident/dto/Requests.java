package com.example.starter.incident.dto;

import java.time.Instant;
import java.util.List;

/**
 * 写接口请求体集合。commandKey 为调用方幂等键；occurredAt 为 UTC 时间。
 * drillKey 非空表示创建演练事件（DRILL 域）；drillBatch 为演练批次标识，缺省取 drillKey。
 */
public final class Requests {

    private Requests() {
    }

    /**
     * 事件上报请求。drillKey 非空时事件进入演练沙盘域；
     * drillBatch 指定演练批次，为空时默认与 drillKey 相同。
     */
    public record ReportRequest(String incidentKey, String severity, String summary, String reporter,
                                String drillKey, String drillBatch) {

        /** 真实事件上报的便捷构造器（无演练标记）。 */
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

    /** 取消请求：事件从任一非终态进入 CANCELLED 终态。 */
    public record CancelRequest(String commandKey) {
    }

    /** 升级请求：仅当前指挥人可升级；演练域升级不触发真实通知。 */
    public record EscalateRequest(String commandKey, String escalateTo, String reason) {
    }

    /**
     * 处置任务创建请求。blockerIncidentKeys 为前置阻塞事件的业务键，
     * 必须与所属事件处于同一域；跨域引用返回 422。
     */
    public record TaskRequest(String commandKey, String taskKey, String title,
                              List<String> blockerIncidentKeys) {
    }

    /** 处置任务完成请求。 */
    public record TaskCompleteRequest(String commandKey) {
    }

    /** 演练批次清理请求：cleanupKey 为幂等键，batchKey 为演练批次标识。 */
    public record CleanupRequest(String cleanupKey, String batchKey) {
    }
}
