package com.example.starter.incident.dto;

import java.time.Instant;
import java.util.List;

import com.example.starter.incident.dto.Responses.HandoverSummaryView;

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
     * 联合交接发起请求：handoverKey 全局唯一；toCommander 为指定接收人；
     * incidentKeys 为当前指挥人选择的 2~20 个未解决事件，提交集合必须恰好覆盖
     * 从这些事件 OPEN 任务沿未完成阻塞关系计算出的依赖闭包。
     */
    public record HandoverInitiateRequest(String commandKey, String handoverKey, String toCommander,
                                          List<String> incidentKeys) {
    }

    /**
     * 联合交接接受请求：操作人由 X-Actor-Id 指定且须为交接单指定接收人；
     * expectedHandoverVersion 须等于发起返回的 handoverVersion；
     * summary 为发起返回的完整冻结摘要，逐字段比对，任一事件/任务/依赖/升级变化返回 409。
     */
    public record HandoverAcceptRequest(String commandKey, String expectedHandoverVersion,
                                        HandoverSummaryView summary) {
    }
}
