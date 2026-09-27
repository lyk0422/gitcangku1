package com.example.starter.race.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * 恢复（确认适赛）请求：由不同于登记人的医疗角色确认。
 *
 * @param endAt             暂停结束时刻，UTC Unix毫秒（区间右开），必须晚于开始时刻
 * @param fitnessConclusion 适赛结论
 * @param medicalRole       确认适赛的医疗角色标识，必须与登记人不同
 * @param expectedVersion   客户端所见赛事版本
 * @param requestId         全局唯一请求ID（写操作幂等键）
 */
public record ResumeMedicalHoldRequest(
        @NotNull @PositiveOrZero Long endAt,
        @NotBlank @Size(max = 512) String fitnessConclusion,
        @NotBlank String medicalRole,
        @NotNull Integer expectedVersion,
        @NotBlank String requestId
) {
}
