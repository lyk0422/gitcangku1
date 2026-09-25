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
     * blockerIncidentKeys 为 0~5 个阻塞事件键，必须存在且不能是自身，重复键按去重处理；
     * priority 为 NORMAL/HIGH，缺省 NORMAL；HIGH 任务受外部机构回执门禁约束。
     */
    public record TaskCreateRequest(String commandKey, String taskKey, String groupCode,
                                    String title, List<String> blockerIncidentKeys,
                                    String priority) {

        /** 兼容缺省优先级（NORMAL）的创建请求。 */
        public TaskCreateRequest(String commandKey, String taskKey, String groupCode,
                                 String title, List<String> blockerIncidentKeys) {
            this(commandKey, taskKey, groupCode, title, blockerIncidentKeys, null);
        }
    }

    /** 任务完成/取消请求，操作人由 X-Actor-Id 指定且须为当前指挥人。 */
    public record TaskActionRequest(String commandKey) {
    }

    /**
     * 外部机构配置请求：expectedVersion 为期望的当前配置版本（无配置时为 0），
     * 不一致返回 409；agencyCodes 为 0~5 个必需机构代码，去重排序后保存，空集合合法。
     * 操作人由 X-Actor-Id 指定且须为当前指挥人；已关闭事件不可修改（409）。
     */
    public record AgencyConfigRequest(Long expectedVersion, List<String> agencyCodes) {
    }

    /**
     * 外部机构回执请求：ackKey 为机构侧幂等键（指纹含机构、配置版本、回执类型与说明，
     * 成功重放首个响应，失败不占键）；configVersion 须等于当前生效配置版本；
     * type 为 CONFIRM/REJECT；reason 在 REJECT 时必填非空。
     */
    public record AgencyAckRequest(String ackKey, String agencyCode, Long configVersion,
                                   String type, String reason) {
    }
}
