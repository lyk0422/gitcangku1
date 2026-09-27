package com.example.starter.maintenance.api.dto;

import java.time.Instant;

/**
 * 锁定点确认明细视图。
 *
 * @param operatorId   执行确认的维护人员标识
 * @param roleId       维护角色标识
 * @param requestId    确认请求 requestId
 * @param confirmedAt  确认生效时刻（UTC）
 */
public record ConfirmationView(
        String operatorId,
        String roleId,
        String requestId,
        Instant confirmedAt) {
}
