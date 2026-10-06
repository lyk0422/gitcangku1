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
     * 执行时段安排请求（创建或替换，仅 OPEN 任务）：requestId 为调用方幂等键；
     * expectedTaskVersion 为乐观并发校验的期望任务版本；assignee 为合成人员标识；
     * startAt/endAt 为 UTC 整分钟左闭右开时段，长度 1~240 分钟。
     */
    public record TaskScheduleRequest(String requestId, Integer expectedTaskVersion,
                                      String assignee, Instant startAt, Instant endAt) {
    }

    /** 任务开始请求：以服务端 Clock 校验当前时刻处于安排区间且原依赖已满足。 */
    public record TaskStartRequest(String requestId, Integer expectedTaskVersion) {
    }

    /** 安排撤销请求：仅未开始安排可撤销，撤销不改变任务版本。 */
    public record TaskScheduleRevokeRequest(String requestId, Integer expectedTaskVersion) {
    }
}
