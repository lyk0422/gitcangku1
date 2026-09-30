package com.example.starter.incident;

import java.time.Instant;

/**
 * 任务派发记录实体，对应 task_dispatches 表。
 * 每次成功派发插入一条，冻结派发当时的凭据版本快照（credentialVersion/type/region/expiresAt），
 * 历史执行依据保持派发时的区域与凭据版本，不随后续凭据更新而改变。
 * requestId 为派发幂等键，(task_id, request_id) 唯一：同键重放首次任务与凭据版本，
 * 同键换资源/改参数返回 409。
 * status 为 DISPATCHED/BLOCKED/SUPERSEDED；blockReason 仅 BLOCKED 有值，
 * 取值 CREDENTIAL_EXPIRED（有效期已结束）或 CREDENTIAL_REVOKED（凭据已撤销）；
 * 类型/区域不匹配属派发期参数校验失败（409），不产生派发记录。
 * 时间均为 UTC。
 */
public record TaskDispatch(
        long id,
        long incidentId,
        long taskId,
        String requestId,
        String resourceKey,
        long credentialId,
        int credentialVersion,
        String credentialType,
        String region,
        Instant credentialExpiresAt,
        DispatchStatus status,
        String blockReason,
        String dispatchedBy,
        Instant dispatchedAt,
        Instant blockedAt,
        Instant supersededAt,
        Instant createdAt,
        Instant updatedAt) {

    /** 阻断原因：派发所依据的凭据在执行前复检时已过 UTC 有效期。 */
    public static final String REASON_EXPIRED = "CREDENTIAL_EXPIRED";

    /** 阻断原因：派发所依据的凭据在执行前复检时已被撤销。 */
    public static final String REASON_REVOKED = "CREDENTIAL_REVOKED";
}
