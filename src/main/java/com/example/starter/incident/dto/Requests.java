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

    /** 任务完成/取消/启动请求，操作人由 X-Actor-Id 指定且须为当前指挥人。 */
    public record TaskActionRequest(String commandKey) {
    }

    /** 共享资源创建请求：capacity 必须为正整数。操作人由 X-Actor-Id 指定。 */
    public record ResourceCreateRequest(String resourceKey, Integer capacity) {
    }

    /**
     * 租约申请请求：requestId 为调用方幂等键；leaseKey 全局唯一；
     * units 取值 1~资源容量；taskVersion 为提交时的任务版本，不一致返回 409。
     */
    public record LeaseRequest(String requestId, String resourceKey, String leaseKey,
                               Integer units, Long taskVersion) {
    }

    /** 受害租约引用：leaseKey + 提交时的租约版本，版本不一致整单 409。 */
    public record VictimRef(String leaseKey, Long version) {
    }

    /**
     * 抢占计划请求：requestId 为调用方幂等键；victims 为完整受害租约列表
     * （集合语义，换序重放等价）；leaseKey/units/taskVersion 为拟授予新租约的参数。
     */
    public record PreemptRequest(String requestId, String resourceKey, String leaseKey,
                                 Integer units, Long taskVersion, List<VictimRef> victims) {
    }
}
