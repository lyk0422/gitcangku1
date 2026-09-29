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

    /**
     * 遏制逾期检查请求：以注入 Clock 的当前时刻评估，不做定时扫描。
     * agencies 为本次升级（首次产生 OPEN 升级记录）需外部通报的机构清单及各自 UTC 回执
     * 截止时刻；仅在本次请求实际新建升级记录时据此创建通报，可为空表示不通报。
     */
    public record EscalationCheckRequest(String commandKey, List<NotifyAgencyRequest> agencies) {

        /** 不携带通报机构的兼容构造（仅检查升级）。 */
        public EscalationCheckRequest(String commandKey) {
            this(commandKey, null);
        }
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
     * 升级时需外部通报的单个机构。
     * agencyKey 机构业务键；critical=true 为关键机构（逾期未回执阻止降级）；
     * ackDeadline 为该机构的 UTC 回执截止时刻，当前时刻 ≥ 该时刻且无回执即逾期。
     */
    public record NotifyAgencyRequest(String agencyKey, boolean critical, Instant ackDeadline) {
    }

    /**
     * 外部机构回执录入请求：requestId 为回执报文幂等键（全局唯一）；
     * agencyKey 为回执机构；signer 签收人；content 回执内容。
     * 同一 requestId 同机构同内容重放首次响应，换机构或内容返回 409。
     */
    public record NotificationAckRequest(String requestId, String agencyKey,
                                        String signer, String content) {
    }
}
