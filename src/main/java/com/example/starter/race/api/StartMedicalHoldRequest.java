package com.example.starter.race.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 登记医疗暂停开始请求。时间统一 UTC，startAt 为暂停区间左闭端点。
 *
 * @param holdId          医疗暂停ID，全局唯一
 * @param startAt         暂停开始时刻，Unix毫秒时间戳（UTC）
 * @param reason          医疗暂停原因，登记后固化
 * @param medicalOfficer  登记暂停的医疗角色ID
 * @param expectedVersion 客户端所见赛事版本
 * @param requestId       全局唯一请求ID（幂等键）
 */
public record StartMedicalHoldRequest(
        @NotBlank String holdId,
        @NotNull Long startAt,
        @NotBlank @Size(max = 512) String reason,
        @NotBlank String medicalOfficer,
        @NotNull Integer expectedVersion,
        @NotBlank String requestId
) {
}
