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
     * 整案排程提案请求：requestId 为全局幂等键；baseInstant 为提案固定 UTC 基准零点；
     * expectedScheduleVersion 在替换已有提案时必填且须等于当前最新版本（首版可省略或为 0）；
     * tasks 为 1~10 个 OPEN 任务的时间窗登记，constraints 为 0~20 条有向时间约束。
     */
    public record ScheduleProposeRequest(String requestId, Instant baseInstant,
                                         Long expectedScheduleVersion,
                                         List<ScheduleTaskRequest> tasks,
                                         List<ScheduleConstraintRequest> constraints) {
    }

    /**
     * 排程任务项：durationMinutes 为正整数持续分钟数；
     * earliestStartOffset/latestFinishOffset 为相对基准零点的非负分钟偏移。
     */
    public record ScheduleTaskRequest(String taskKey, Long durationMinutes,
                                      Long earliestStartOffset, Long latestFinishOffset) {
    }

    /**
     * 有向时间约束：toTaskKey 开始 − fromTaskKey 完成 ∈ [lowerBound, upperBound]（分钟），
     * 0 ≤ lowerBound ≤ upperBound，两端任务必须属于本提案任务集合。
     */
    public record ScheduleConstraintRequest(String fromTaskKey, String toTaskKey,
                                            Long lowerBound, Long upperBound) {
    }
}
