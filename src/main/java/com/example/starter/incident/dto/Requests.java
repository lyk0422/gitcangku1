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
     * requiredCredentials 非空时为高危任务：集合按代码去重排序规范化（换序视为同参），
     * 且 plannedCompleteAt（计划完成 UTC 时刻）必填；非高危任务两者均须为空。
     */
    public record TaskCreateRequest(String commandKey, String taskKey, String groupCode,
                                    String title, List<String> blockerIncidentKeys,
                                    List<String> requiredCredentials, Instant plannedCompleteAt) {

        /** 兼容非高危任务的五参构造。 */
        public TaskCreateRequest(String commandKey, String taskKey, String groupCode,
                                 String title, List<String> blockerIncidentKeys) {
            this(commandKey, taskKey, groupCode, title, blockerIncidentKeys, null, null);
        }
    }

    /** 任务开始/完成/取消请求，操作人由 X-Actor-Id 指定且须为当前指挥人。 */
    public record TaskActionRequest(String commandKey) {
    }

    /** 共享资源登记请求。 */
    public record ResourceRegisterRequest(String commandKey, String resourceKey) {
    }

    /**
     * 资源资质登记请求：validFrom/validUntil 为 UTC 半开区间 [起, 止)，
     * 严格覆盖要求 validUntil 晚于任务计划完成时刻；同代码已撤销的资质可重新登记（覆盖有效期）。
     */
    public record CredentialRegisterRequest(String commandKey, String credentialCode,
                                            Instant validFrom, Instant validUntil) {
    }

    /** 资源资质提前撤销请求。 */
    public record CredentialRevokeRequest(String commandKey) {
    }

    /** 租约目标任务引用：事件键 + 任务键。 */
    public record LeaseTaskRef(String incidentKey, String taskKey) {
    }

    /**
     * 批量租约分配请求：为多个高危任务分配同一资源，单事务全部创建，任一失败整单回滚。
     * leaseKey 为幂等键，指纹含操作者、资源版本、规范化任务集合、租约时段与资质集合；
     * 时段为 UTC 半开区间 [leaseStart, leaseEnd)。
     */
    public record LeaseAssignRequest(String leaseKey, String resourceKey, List<LeaseTaskRef> tasks,
                                     Instant leaseStart, Instant leaseEnd) {
    }

    /**
     * 租约替换请求：以合格资源替换 CREDENTIAL_RISK 任务的当前租约，
     * 沿用原租约时段；leaseKey 为新租约的幂等键。
     */
    public record LeaseReplaceRequest(String leaseKey, String resourceKey) {
    }
}
