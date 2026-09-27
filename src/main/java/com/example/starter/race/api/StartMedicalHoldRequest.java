package com.example.starter.race.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * 登记医疗暂停请求。
 *
 * @param holdId          全局唯一医疗暂停ID
 * @param startAt         暂停开始时刻，UTC Unix毫秒（区间左闭）
 * @param reason          暂停原因
 * @param medicalRole     登记暂停的医疗角色标识
 * @param expectedVersion 客户端所见赛事版本
 * @param requestId       全局唯一请求ID（写操作幂等键）
 */
public record StartMedicalHoldRequest(
        @NotBlank String holdId,
        @NotNull @PositiveOrZero Long startAt,
        @NotBlank @Size(max = 512) String reason,
        @NotBlank String medicalRole,
        @NotNull Integer expectedVersion,
        @NotBlank String requestId
) {
}
