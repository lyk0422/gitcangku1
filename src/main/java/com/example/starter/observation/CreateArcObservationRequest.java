package com.example.starter.observation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 创建圆弧观测记录请求。
 *
 * @param requestId     全局唯一请求标识（幂等去重键）
 * @param observationId 圆弧观测记录唯一标识
 * @param operator      操作者标识（非空）
 * @param arcs          圆弧分量列表，1 至 16 条；每条起始角 0 至 359999、弧长 1 至 180000（千分之一度）
 */
public record CreateArcObservationRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 64) String observationId,
        @NotBlank @Size(max = 128) String operator,
        @NotNull @Size(min = 1, max = 16) List<ArcComponent> arcs) {
}
