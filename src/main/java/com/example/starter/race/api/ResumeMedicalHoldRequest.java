package com.example.starter.race.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 确认适赛并恢复医疗暂停请求。时间统一 UTC，endAt 为暂停区间右端点（不含），
 * 必须晚于暂停开始时刻；确认医疗角色必须与登记角色不同。
 *
 * @param endAt           暂停结束时刻，Unix毫秒时间戳（UTC）
 * @param medicalOfficer  确认适赛的医疗角色ID（不得等于暂停登记角色）
 * @param fitConclusion   适赛结论，恢复后固化
 * @param expectedVersion 客户端所见赛事版本
 * @param requestId       全局唯一请求ID（幂等键）
 */
public record ResumeMedicalHoldRequest(
        @NotNull Long endAt,
        @NotBlank String medicalOfficer,
        @NotBlank @Size(max = 512) String fitConclusion,
        @NotNull Integer expectedVersion,
        @NotBlank String requestId
) {
}
