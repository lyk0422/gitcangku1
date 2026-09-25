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
     * priority 为 HIGH/NORMAL，缺省（null）按 NORMAL；仅 HIGH 任务受外部机构回执门禁。
     * 保留不含 priority 的构造器，未显式指定优先级时按 NORMAL 处理。
     */
    public static final class TaskCreateRequest {

        private final String commandKey;
        private final String taskKey;
        private final String groupCode;
        private final String title;
        private final List<String> blockerIncidentKeys;
        private final String priority;

        @com.fasterxml.jackson.annotation.JsonCreator
        public TaskCreateRequest(
                @com.fasterxml.jackson.annotation.JsonProperty("commandKey") String commandKey,
                @com.fasterxml.jackson.annotation.JsonProperty("taskKey") String taskKey,
                @com.fasterxml.jackson.annotation.JsonProperty("groupCode") String groupCode,
                @com.fasterxml.jackson.annotation.JsonProperty("title") String title,
                @com.fasterxml.jackson.annotation.JsonProperty("blockerIncidentKeys")
                List<String> blockerIncidentKeys,
                @com.fasterxml.jackson.annotation.JsonProperty("priority") String priority) {
            this.commandKey = commandKey;
            this.taskKey = taskKey;
            this.groupCode = groupCode;
            this.title = title;
            this.blockerIncidentKeys = blockerIncidentKeys;
            this.priority = priority;
        }

        /** 不含优先级的便捷构造器：优先级缺省为 NORMAL。 */
        public TaskCreateRequest(String commandKey, String taskKey, String groupCode, String title,
                                 List<String> blockerIncidentKeys) {
            this(commandKey, taskKey, groupCode, title, blockerIncidentKeys, null);
        }

        public String commandKey() {
            return commandKey;
        }

        public String taskKey() {
            return taskKey;
        }

        public String groupCode() {
            return groupCode;
        }

        public String title() {
            return title;
        }

        public List<String> blockerIncidentKeys() {
            return blockerIncidentKeys;
        }

        public String priority() {
            return priority;
        }
    }

    /** 任务完成/取消请求，操作人由 X-Actor-Id 指定且须为当前指挥人。 */
    public record TaskActionRequest(String commandKey) {
    }

    /**
     * 外部机构必需回执配置修改请求：expectedVersion 为指挥人上次见到的配置版本，
     * 首次配置传 0（或 null）；agencyCodes 去重后按字典序落库，空集合合法，至多 5 个。
     */
    public record AgencyConfigRequest(String commandKey, Integer expectedVersion,
                                      List<String> agencyCodes) {
    }

    /**
     * 外部机构回执提交请求：ackKey 为机构侧重放键；ackType 为 CONFIRM/REJECT；
     * REJECT 必须携带非空 reason，CONFIRM 忽略 reason。提交人由 X-Actor-Id 指定。
     */
    public record AgencyAckRequest(String ackKey, String agencyCode, String ackType, String reason) {
    }
}
