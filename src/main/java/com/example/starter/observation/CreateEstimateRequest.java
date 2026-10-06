package com.example.starter.observation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 创建分层样方密度估计成果请求：2 至 5 层，输入总计最多 20 个观测。
 *
 * @param requestId  全局唯一请求标识（幂等去重键）
 * @param estimateId 密度估计成果唯一标识
 * @param operator   操作者标识（非空）
 * @param layers     估计层列表（2 至 5 层）；层与成员的顺序不影响同参判定（换序同参）
 */
public record CreateEstimateRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 128) String estimateId,
        @NotBlank @Size(max = 128) String operator,
        @NotNull @Size(min = 2, max = 5) List<@Valid EstimateLayerRequest> layers) {
}
