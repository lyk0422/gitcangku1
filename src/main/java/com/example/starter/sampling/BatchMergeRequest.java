package com.example.starter.sampling;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 批量离线合并请求：先按完整最终计数、坐标边界和冲突簇预校验，
 * 任一无许可、命中不唯一或超限即 422，全部观测与簇变化回滚。
 *
 * @param batchId      批量合并标识（幂等键）：同键同参重放，异参 409，失败不占键
 * @param observations 批量观测条目，每条语义与单次提交一致
 */
public record BatchMergeRequest(
        @NotBlank @Size(max = 128) String batchId,
        @NotNull @Size(min = 1, max = 500) List<@Valid SubmitObservationRequest> observations) {
}
