package com.example.starter.maintenance.domain;

import java.time.Instant;

/**
 * 锁定点确认记录。
 *
 * @param confirmationId  确认记录标识
 * @param orderId         所属隔离令标识
 * @param lockPointId     被确认的锁定点标识
 * @param operatorId      执行确认的维护人员标识
 * @param roleId          维护角色标识
 * @param requestId       确认请求 requestId
 * @param confirmedAt     确认生效时刻（UTC）
 */
public record IsolationConfirmation(
        long confirmationId,
        String orderId,
        String lockPointId,
        String operatorId,
        String roleId,
        String requestId,
        Instant confirmedAt) {
}
