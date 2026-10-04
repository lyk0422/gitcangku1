package com.example.starter.evidence.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

/**
 * 检查点签到请求。仅运送人（指定接收人）可提交；必须按点号依次签到，不允许跳点；
 * 到达时刻不得早于上一签到点，等于该点 UTC 截止时刻仍合法。
 *
 * @param requestId     幂等请求键
 * @param checkpointSeq 签到的检查点序号（从 1 开始）
 * @param sealNo        到达时提交的封条编号，须与证物封条一致
 * @param arrivedAt     到达时刻（UTC）
 */
public record CheckpointSigninRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotNull @Min(1) @Max(8) Integer checkpointSeq,
        @NotBlank @Size(max = 64) String sealNo,
        @NotNull LocalDateTime arrivedAt) {
}