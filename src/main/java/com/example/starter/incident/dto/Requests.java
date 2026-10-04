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

    /**
     * 交接发起请求：toCommander 必须不同于当前指挥人；
     * note 为交出人补充说明（可空），随首版简报冻结并参与摘要计算。
     */
    public record TransferRequest(String commandKey, String toCommander, String note) {
        /** 兼容无补充说明的发起请求。 */
        public TransferRequest(String commandKey, String toCommander) {
            this(commandKey, toCommander, null);
        }
    }

    /**
     * 交接接受请求：操作人由 X-Actor-Id 指定且须为待接受目标人；
     * briefingId 必须引用当前待交接记录内已回读的最新简报版本。
     */
    public record TransferAcceptRequest(String commandKey, Long briefingId) {
    }

    /**
     * 简报回读请求：requestId 为调用方幂等键；briefingId 为期望回读的简报版本；
     * digest 为提交方按完整结构化简报计算的摘要，与冻结摘要不一致则不产生确认。
     */
    public record ReadbackRequest(String requestId, Long briefingId, String digest) {
    }

    /**
     * 简报刷新请求：requestId 为调用方幂等键；note 非空时替换补充说明，
     * 为 null 时沿用上一版说明。
     */
    public record BriefingRefreshRequest(String requestId, String note) {
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
}
