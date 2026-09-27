package com.example.starter.maintenance.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 确认单个锁定点请求。每个锁定点须两名不同维护角色各确认一次。
 *
 * @param requestId   全局唯一请求标识（幂等键）
 * @param lockPointId 被确认的锁定点标识（须属于该隔离令，否则 404）
 * @param operatorId  执行确认的维护人员标识（操作人，固化为证据）
 * @param roleId      维护角色标识；同一锁定点同一角色重复确认返回 422
 */
public record ConfirmLockPointRequest(
        @NotBlank String requestId,
        @NotBlank String lockPointId,
        @NotBlank String operatorId,
        @NotBlank String roleId) {
}
