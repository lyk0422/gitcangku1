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
     * 事件资源可用量登记请求：resourceCode 事件内唯一；quantity 为初始可用数量（件，非负整数）。
     * 同一事件同一资源重复登记相同数量幂等返回，登记不同数量返回 409。
     */
    public record ResourceRegisterRequest(String resourceCode, Integer quantity) {
    }

    /**
     * 跨事件互助资源征调请求。
     * requestId 为调用方征调幂等键，全局唯一：同键同内容重放首次双侧账务响应，
     * 同键改数量或改目标事件（及其他任一参数）返回 409。
     * quantity 为征调数量（件，正整数）；exclusive 为 true 时排他占用该资源；
     * expiresAt 为租约 UTC 到期时刻，必须晚于提交时刻；到期不自动虚构归还。
     */
    public record AidRequestRequest(String requestId, String sourceIncidentKey,
                                    String targetIncidentKey, String resourceCode, Integer quantity,
                                    Boolean exclusive, Instant expiresAt) {
    }

    /**
     * 租约归还请求：commandKey 为归还操作幂等键；quantity 为本次实际归还数量（件，正整数），
     * 超过未归还量返回 422 且不留下部分变更。
     */
    public record AidReturnRequest(String commandKey, Integer quantity) {
    }
}
