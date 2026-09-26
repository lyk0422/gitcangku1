package com.example.starter.race.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 恢复适赛（结束医疗暂停）请求；必须由与登记暂停不同的医疗角色确认。
 *
 * @param endAt             声明的暂停结束时刻，Unix毫秒时间戳（UTC），区间右开，必须晚于开始时刻
 * @param fitnessConclusion 适赛结论（恢复后固化不可改写）
 * @param medicalRole       确认适赛的医疗角色标识，必须与登记暂停的角色不同
 * @param expectedVersion   客户端所见赛事版本
 * @param requestId         全局唯一请求ID（写操作幂等键）
 */
public record ResumeMedicalHoldRequest(
        @NotNull Long endAt,
        @NotBlank @Size(max = 512) String fitnessConclusion,
        @NotBlank String medicalRole,
        @NotNull Integer expectedVersion,
        @NotBlank String requestId
) {
}
