package com.example.starter.plan.web.dto;

import java.util.List;

/**
 * 批量原子改签响应：与请求顺序一致的各项改签结果（旧计划已取消、新计划已发布）。
 * 任一项失败时整体回滚，不会返回本响应。
 */
public record BatchRescheduleResponse(List<RescheduleResponse> results) {
}
