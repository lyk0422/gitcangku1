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
     * 残余风险模型首次设置请求：initialRisk 为 R（1~1000 整数），
     * residualLimit 为 L（非负整数且小于 R）；仅可设置一次，此后不可修改。
     */
    public record RiskModelSetupRequest(String commandKey, Integer initialRisk, Integer residualLimit) {
    }

    /**
     * 屏障登记请求：barrierKey 事件内唯一；taskKey 须为本事件 OPEN 任务；
     * 削减率 numerator/denominator（p/q），0≤p≤q，q≥1，均不超 1000。
     */
    public record BarrierRegisterRequest(String commandKey, String barrierKey, String taskKey,
                                         Integer numerator, Integer denominator) {
    }

    /**
     * 屏障修改请求：expectedVersion 须等于模型当前版本，成功后版本加一；
     * taskKey 变更时新任务须为本事件 OPEN 任务；削减率约束同登记。
     */
    public record BarrierUpdateRequest(String commandKey, String taskKey, Integer numerator,
                                       Integer denominator, Long expectedVersion) {
    }

    /** 屏障撤销请求：expectedVersion 须等于模型当前版本，成功后版本加一。 */
    public record BarrierRevokeRequest(String commandKey, Long expectedVersion) {
    }
}
