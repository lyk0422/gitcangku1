package com.example.starter.plan.web.dto;

import java.util.List;

/**
 * 批量原子改签响应：按提交顺序返回每一项的旧计划（已取消）与新计划（已发布）快照。
 */
public record BatchRescheduleResponse(List<RescheduleResponse> items) {
}
