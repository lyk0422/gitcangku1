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

    /** 资源登记请求：来源事件由路径指定，resourceKey 全局唯一，label 非空。 */
    public record ResourceRegisterRequest(String commandKey, String resourceKey, String label) {
    }

    /** 接收代理人登记请求：delegate 为被授权人，与当前指挥人同具接收权限。 */
    public record DelegateRegisterRequest(String commandKey, String delegate) {
    }

    /**
     * 单条资源交接项：handoffKey 为该项幂等键；leaseStart/leaseEnd 为 UTC 左闭右开租约，
     * 结束必须晚于开始；同一请求内同一资源只能出现一次。
     */
    public record HandoffItemRequest(String handoffKey, String resourceKey,
                                    Instant leaseStart, Instant leaseEnd) {
    }

    /**
     * 批量互助交接请求：来源事件由路径指定（X-Actor-Id 为来源当前指挥人/操作者）；
     * targetIncidentKey 为接收目标事件；receiver 为目标侧接收人，须为目标当前指挥人或其登记代理人。
     * 每项以 handoffKey 为幂等键。先统一校验资源最终归属与重叠租约，任一冲突 422，整批回滚。
     */
    public record HandoffCreateRequest(String targetIncidentKey, String receiver,
                                      List<HandoffItemRequest> items) {
    }

    /** 任务开始请求，操作人由 X-Actor-Id 指定且须为当前指挥人。 */
    public record TaskStartRequest(String commandKey) {
    }

    /**
     * 将借入资源分配给目标事件任务的请求：handoffKey 指定通过哪条交接借入；
     * 任务须属于目标事件且未终态，当前时刻须落在租约 [leaseStart, leaseEnd) 内。
     */
    public record TaskAssignResourceRequest(String commandKey, String handoffKey) {
    }

    /** 租约结算检查请求，以注入 Clock 的当前时刻评估，不做定时扫描。 */
    public record HandoffSettleRequest(String commandKey) {
    }
}
