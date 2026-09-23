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

    /** 共享资源创建请求：capacity 为正整数容量，resourceKey 全局唯一。 */
    public record ResourceCreateRequest(String resourceKey, String name, Integer capacity) {
    }

    /**
     * 租约申请请求：为 OPEN 且未开始的任务申请 1~容量 单位租约；
     * taskVersion 必须为任务当前版本，leaseKey 全局唯一，commandKey 为幂等键。
     */
    public record LeaseAcquireRequest(String commandKey, String resourceKey, Long taskVersion,
                                     Integer quantity, String leaseKey) {
    }

    /**
     * 任务开始请求：leaseKeys 为任务据以启动的完整 ACTIVE 租约键集合（至少一条），
     * 服务端在资源域锁内复核全部仍 ACTIVE 且属于该任务，任一失效即 409。
     */
    public record TaskStartRequest(String commandKey, List<String> leaseKeys) {
    }

    /** 抢占计划中的受害租约条目：leaseKey + 提交时租约版本。 */
    public record PreemptionVictimRequest(String leaseKey, Long version) {
    }

    /**
     * 抢占请求：requestId 即幂等键（同参集合换序可重放首次响应，异参 409，失败不占键）；
     * taskKey 为本事件下尚未 STARTED 的 OPEN 任务，taskVersion 为其当前版本；
     * newLeaseKey 为抢占成功后授予新租约的唯一键；quantity 为申请份额（1~容量）；
     * victims 为计划抢占的完整受害租约及版本，顺序不影响规范化摘要。
     */
    public record PreemptRequest(String requestId, String taskKey, Long taskVersion,
                                 String newLeaseKey, Integer quantity,
                                 List<PreemptionVictimRequest> victims) {
    }

    /**
     * 抢占闭包只读计算请求：victimLeaseKeys 为计划直接抢占的受害租约键集合（无序），
     * 返回必须同时包含的完整闭包；遇 STARTED 依赖链返回 422。
     */
    public record PreemptionClosureRequest(List<String> victimLeaseKeys) {
    }
}
