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
     * requiredCredentials 为高危任务必需资质代码集合，null/缺省为非高危任务，集合换序视为同参。
     */
    public record TaskCreateRequest(String commandKey, String taskKey, String groupCode,
                                    String title, List<String> blockerIncidentKeys,
                                    List<String> requiredCredentials) {

        /** 兼容旧调用：不显式声明必需资质时视为非高危任务。 */
        public TaskCreateRequest(String commandKey, String taskKey, String groupCode,
                                 String title, List<String> blockerIncidentKeys) {
            this(commandKey, taskKey, groupCode, title, blockerIncidentKeys, List.of());
        }
    }

    /** 任务开始/完成/取消请求，操作人由 X-Actor-Id 指定且须为当前指挥人。 */
    public record TaskActionRequest(String commandKey) {
    }

    /**
     * 资源资质登记请求：resourceId、credentialCode 非空；validUntil 为 UTC 有效期截止时刻；
     * validFrom 可空表示登记即生效。同键重登（续期）使资源版本 +1。
     */
    public record CredentialRegisterRequest(String commandKey, String resourceId,
                                            String credentialCode, Instant validFrom,
                                            Instant validUntil) {
    }

    /** 资质提前撤销请求：reason 为非空撤销原因。 */
    public record CredentialRevokeRequest(String commandKey, String reason) {
    }

    /** 批量分配内的单个高危任务租约项；leaseStart/leaseEnd 为 UTC 租约时段。 */
    public record LeaseItem(String incidentKey, String taskKey, String resourceId,
                            Instant leaseStart, Instant leaseEnd) {
    }

    /**
     * 批量租约分配请求：一次为多个高危任务分配资源；先统一校验租约冲突、依赖门禁与
     * 全部资质后态，再单事务创建全部租约，任一任务失败整单回滚。
     */
    public record LeaseAllocateRequest(String commandKey, List<LeaseItem> items) {
    }

    /** 租约替换请求：以新资源替换任务当前（可能处于资质风险的）租约。 */
    public record LeaseReplaceRequest(String commandKey, String resourceId,
                                      Instant leaseStart, Instant leaseEnd) {
    }
}
