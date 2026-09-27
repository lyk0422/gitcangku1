package com.example.starter.race.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 恢复医疗暂停（确认适赛）请求。必须由与开始暂停不同的医疗角色提交；
 * endAt 为暂停结束绝对UTC时刻（不含），必须严格晚于 startAt。
 *
 * @param holdId          医疗暂停ID
 * @param endAt           暂停结束绝对UTC时刻（不含），必须晚于开始
 * @param resumeMedicalRole 确认恢复的医疗角色标识（须与登记角色不同）
 * @param fitConclusion   适赛结论，1~256字符
 * @param expectedVersion 客户端所见赛事版本
 * @param requestId       全局唯一请求ID（幂等键）
 */
public record ResumeMedicalHoldRequest(
        @NotBlank String holdId,
        @NotNull Long endAt,
        @NotBlank @Size(max = 64) String resumeMedicalRole,
        @NotBlank @Size(max = 256) String fitConclusion,
        @NotNull Integer expectedVersion,
        @NotBlank String requestId
) {
}
