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
    public record TaskActionRequest(String commandKey, Integer expectedTaskVersion) {

        /** 兼容构造：不携带期望版本（不校验任务版本）。 */
        public TaskActionRequest(String commandKey) {
            this(commandKey, null);
        }
    }

    /**
     * 择一前置组配置请求：requestId 为调用方幂等键；expectedTaskVersion 为目标任务期望版本；
     * groups 为 1~3 组前置，每组 2~5 个成员，组内成员顺序无关（换序视为同参）。
     */
    public record ConfigureGroupsRequest(String requestId, Integer expectedTaskVersion,
                                         List<GroupSpec> groups) {
    }

    /** 前置组：2~5 个成员任务，完成判定要求组内至少一个 DONE。 */
    public record GroupSpec(List<MemberSpec> members) {
    }

    /**
     * 前置组成员：以事件号与任务号标识，可跨事件；
     * expectedTaskVersion 为配置时声明的成员任务期望版本，不一致则配置 409。
     */
    public record MemberSpec(String incidentKey, String taskKey, Integer expectedTaskVersion) {
    }
}
