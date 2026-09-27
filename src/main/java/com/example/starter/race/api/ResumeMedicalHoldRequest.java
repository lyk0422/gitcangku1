package com.example.starter.race.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 恢复医疗暂停（确认适赛）请求。
 *
 * @param confirmRole       确认适赛的医疗角色标识，必须与登记暂停的角色不同
 * @param endedAt           声明的暂停结束时刻，Unix毫秒时间戳（UTC），区间右端点（不含），必须晚于开始时刻
 * @param fitnessConclusion 适赛结论
 * @param expectedVersion   客户端所见赛事版本
 * @param requestId         全局唯一请求ID（写操作幂等键）
 */
public record ResumeMedicalHoldRequest(
        @NotBlank String confirmRole,
        @NotNull Long endedAt,
        @NotBlank String fitnessConclusion,
        @NotNull Integer expectedVersion,
        @NotBlank String requestId
) {
}
