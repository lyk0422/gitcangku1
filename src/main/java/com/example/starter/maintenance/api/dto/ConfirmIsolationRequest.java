package com.example.starter.maintenance.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 隔离令确认请求：两名不同维护角色各提交一次；第二名角色确认后隔离生效。
 *
 * @param requestId        全局唯一请求标识（幂等键）
 * @param expectedVersion  设备期望版本号，与当前版本不一致时返回 409
 * @param operatorId       确认操作人
 * @param role             维护角色；与已确认角色相同返回 422
 */
public record ConfirmIsolationRequest(
        @NotBlank String requestId,
        @NotNull Long expectedVersion,
        @NotBlank String operatorId,
        @NotBlank String role) {
}
