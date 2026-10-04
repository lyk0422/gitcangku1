package com.example.starter.evidence.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

/**
 * 检查点签到请求。仅指定运送人可提交；须按点号顺序签到，不允许跳点；
 * 到达时刻不得早于上一点，等于截止时刻仍合法。
 *
 * @param requestId       幂等请求键
 * @param checkpointNo    签到检查点号，须等于当前期望点号
 * @param sealNo          签到时提交的封条编号，须与交接冻结封条一致
 * @param arrivedAt       到达时刻（UTC）
 * @param expectedVersion 期望的交接版本号，须与当前版本一致
 */
public record RegulatedCheckinRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotNull Integer checkpointNo,
        @NotBlank @Size(max = 64) String sealNo,
        @NotNull LocalDateTime arrivedAt,
        @NotNull Long expectedVersion) {
}