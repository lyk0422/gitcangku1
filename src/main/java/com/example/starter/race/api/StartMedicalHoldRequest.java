package com.example.starter.race.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 开始医疗暂停请求。时间为绝对 UTC 毫秒时间戳，区间左闭右开。
 *
 * @param holdId          医疗暂停ID，全局唯一
 * @param startAt         暂停开始绝对UTC时刻（含）
 * @param reason          暂停原因，1~256字符
 * @param medicalRole     登记暂停的医疗角色标识
 * @param expectedVersion 客户端所见赛事版本
 * @param requestId       全局唯一请求ID（幂等键）
 */
public record StartMedicalHoldRequest(
        @NotBlank String holdId,
        @NotNull Long startAt,
        @NotBlank @Size(max = 256) String reason,
        @NotBlank @Size(max = 64) String medicalRole,
        @NotNull Integer expectedVersion,
        @NotBlank String requestId
) {
}
