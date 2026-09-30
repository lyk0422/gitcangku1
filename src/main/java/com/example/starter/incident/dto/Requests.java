package com.example.starter.incident.dto;

import java.time.Instant;
import java.util.List;

/**
 * 写接口请求体集合。commandKey/requestId 为调用方幂等键；occurredAt、expiresAt 为 UTC 时间。
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
     * 处置任务创建请求：taskKey 事件内唯一；groupCode、title、resourceKey、region 非空；
     * resourceKey 为执行所需资源，region 为任务执行区域，派发凭据须同类型并覆盖该区域；
     * blockerIncidentKeys 为 0~5 个阻塞事件键，必须存在且不能是自身，重复键按去重处理。
     */
    public record TaskCreateRequest(String commandKey, String taskKey, String groupCode,
                                    String title, String resourceKey, String region,
                                    List<String> blockerIncidentKeys) {
    }

    /**
     * 任务派发请求：requestId 为派发幂等键，(任务, requestId) 唯一；
     * resourceKey 必须与任务创建时的资源一致，否则 409；凭据取该资源最新版本，
     * 派发时校验凭据类型、覆盖区域与 UTC 有效期。
     */
    public record TaskDispatchRequest(String requestId, String resourceKey, String credentialType) {
    }

    /** 任务执行/完成、取消请求，操作人由 X-Actor-Id 指定且须为当前指挥人。 */
    public record TaskActionRequest(String commandKey) {
    }

    /**
     * 资源凭据登记请求：同一 resourceKey 每次登记生成递增版本；
     * credentialType 为凭据类型，region 为该凭据覆盖的区域，expiresAt 为 UTC 失效时刻。
     */
    public record CredentialRegisterRequest(String commandKey, String resourceKey,
                                            String credentialType, String region,
                                            Instant expiresAt) {
    }

    /** 凭据撤销请求：撤销指定资源的指定版本，撤销不可逆。 */
    public record CredentialRevokeRequest(String commandKey, String resourceKey, Integer version) {
    }
}
