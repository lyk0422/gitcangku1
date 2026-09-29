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
     * 触发升级时可携带 agencies 确定本次需外部通报的机构清单及各自 UTC 回执截止时刻；
     * 为 null 或空时沿用旧行为（仅创建升级记录、不产生通报）。
     */
    public record EscalationCheckRequest(String commandKey, List<NotifiedAgencyRequest> agencies) {

        /** 不含通报机构的兼容构造。 */
        public EscalationCheckRequest(String commandKey) {
            this(commandKey, null);
        }
    }

    /**
     * 被通报机构：agencyCode 机构标识；critical 是否关键机构（关键机构逾期未回执阻止降级，
     * 普通机构逾期只进待跟进清单）；ackDeadline 为 UTC 回执截止时刻，恰好到期视为逾期。
     */
    public record NotifiedAgencyRequest(String agencyCode, boolean critical, Instant ackDeadline) {
    }

    /**
     * 外部通报回执请求：requestId 为回执报文幂等键（同键原报文重放原响应，
     * 换机构或内容返回 409）；signer 签收人；content 回执内容；agencyCode 签收机构。
     */
    public record NotificationReceiptRequest(String agencyCode, String requestId,
                                             String signer, String content) {
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
