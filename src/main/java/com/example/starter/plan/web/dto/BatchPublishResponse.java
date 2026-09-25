package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * 批量发布响应：按请求顺序返回各计划发布后的最终明细。
 */
public record BatchPublishResponse(List<PlanResponse> plans) {
}
