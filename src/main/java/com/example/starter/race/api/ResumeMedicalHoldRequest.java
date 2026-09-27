package com.example.starter.race.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 恢复（确认适赛）请求：结束指定医疗暂停。
 *
 * @param endMs           暂停结束时刻（UTC Unix毫秒），区间右端点（不含），必须晚于开始
 * @param fitnessNote     适赛结论
 * @param medicalRole     确认适赛的医疗角色，必须与登记暂停的角色不同
 * @param expectedVersion 客户端所见赛事版本
 * @param requestId       全局唯一请求ID（写操作幂等键）
 */
public record ResumeMedicalHoldRequest(
        @NotNull @Positive Long endMs,
        @NotBlank String fitnessNote,
        @NotBlank String medicalRole,
        @NotNull Integer expectedVersion,
        @NotBlank String requestId
) {
}
