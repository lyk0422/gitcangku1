package com.example.starter.observation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 冲突簇合并请求：对一个观测记录下尚未消费的提交簇执行合并裁决。
 * 受影响观测不可自动胜出；簇内只含受影响观测时结果为待人工裁决。
 *
 * @param requestId     全局唯一请求标识（幂等去重键，亦作为合并记录 mergeId）
 * @param observationId 观测记录唯一标识
 * @param submissionIds 本次纳入冲突簇的提交标识有序列表（须恰好等于该记录全部未消费提交）
 */
public record MergeClusterRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 64) String observationId,
        @NotEmpty List<@NotBlank @Size(max = 128) String> submissionIds) {
}
