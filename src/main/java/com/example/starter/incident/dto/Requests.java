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

    /** 资源登记请求：resourceKey 全局唯一，操作人须为当前指挥人。 */
    public record ResourceAcquireRequest(String commandKey, String resourceKey) {
    }

    /** 代理人登记请求：delegate 事件内唯一，操作人须为当前指挥人。 */
    public record DelegateRegisterRequest(String commandKey, String delegate) {
    }

    /**
     * 交接资源项请求：resourceKey 为借出资源；taskKeys 为引用该资源的目标事件任务键（0~N 个）。
     */
    public record HandoffItemRequest(String resourceKey, List<String> taskKeys) {
    }

    /**
     * 互助交接创建请求：handoffKey 全局唯一（同键同参重放，失败不占键）；
     * sourceVersion/targetVersion 为创建时双方事件乐观版本号；
     * receiver 须为目标事件当前指挥人或其已登记代理人；
     * 租约为 UTC 左闭右开 [leaseStart, leaseEnd)，leaseEnd 必须晚于 leaseStart；
     * items 至少一项且 resourceKey 不重复，任一冲突整体回滚。
     */
    public record HandoffCreateRequest(String handoffKey, String targetIncidentKey, String receiver,
                                       Long sourceVersion, Long targetVersion,
                                       Instant leaseStart, Instant leaseEnd,
                                       List<HandoffItemRequest> items) {
    }

    /** 租约到期结算请求：以注入 Clock 的当前时刻评估，结算本事件作为目标的已到期交接。 */
    public record HandoffSettleRequest(String commandKey) {
    }
}
