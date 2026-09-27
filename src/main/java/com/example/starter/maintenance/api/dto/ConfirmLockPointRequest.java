package com.example.starter.maintenance.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * 锁定点确认请求：一名操作人以一个维护角色确认一个锁定点（锁定点标识由路径给出）。
 * 同一角色对同一锁定点重复确认返回 422；每个锁定点须经 ISOLATOR、VERIFIER 两个不同角色
 * 分别确认，全部锁定点双角色确认完成后隔离令生效。
 *
 * @param requestId        全局唯一请求标识（幂等键）
 * @param expectedVersion  设备期望版本号（确认不推进版本，仅作乐观并发校验）
 * @param operatorId       确认操作人
 * @param lockRole         维护角色：ISOLATOR 上锁人 / VERIFIER 验证人
 */
public record ConfirmLockPointRequest(
        @NotBlank String requestId,
        @NotNull Long expectedVersion,
        @NotBlank String operatorId,
        @NotBlank @Pattern(regexp = "ISOLATOR|VERIFIER") String lockRole) {
}
