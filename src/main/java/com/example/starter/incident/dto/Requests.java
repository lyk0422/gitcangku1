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
     * 资源台账登记请求：由事件当前指挥人为本事件登记可用资源；
     * (incidentKey, resourceCode) 唯一，重复登记不同总量返回 409。
     */
    public record ResourceRegisterRequest(String commandKey, String resourceCode, long quantity) {
    }

    /**
     * 跨事件互助资源征调请求：从 sourceIncidentKey 向目标事件（路径事件）征调 resourceCode
     * 共 quantity 件，租约时长 leaseMinutes 分钟（UTC 到期时刻 = 落库时刻 + 时长）。
     * requestId 为全局幂等键：同键同参重放首次双侧账务结果，同键改数量或目标返回 409。
     */
    public record MutualAidRequest(String requestId, String sourceIncidentKey,
                                   String resourceCode, long quantity, long leaseMinutes) {
    }

    /**
     * 租约归还请求：按实际数量 quantity 分次归还，归还人由 X-Actor-Id 指定
     * 且须为目标事件当前指挥人；累计归还超过未归还量返回 422。
     */
    public record LeaseReturnRequest(long quantity) {
    }
}
