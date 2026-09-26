package com.example.starter.race.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 登记医疗暂停请求；暂停区间起止均按 UTC Unix 毫秒时间戳、左闭右开解释。
 *
 * @param holdId          全局唯一医疗暂停ID
 * @param startAt         声明的暂停开始时刻，Unix毫秒时间戳（UTC），区间左闭
 * @param reason          医疗暂停原因（登记后固化不可改写）
 * @param medicalRole     登记暂停的医疗角色标识
 * @param expectedVersion 客户端所见赛事版本
 * @param requestId       全局唯一请求ID（写操作幂等键）
 */
public record StartMedicalHoldRequest(
        @NotBlank String holdId,
        @NotNull Long startAt,
        @NotBlank @Size(max = 512) String reason,
        @NotBlank String medicalRole,
        @NotNull Integer expectedVersion,
        @NotBlank String requestId
) {
}
