package com.example.starter.plan.web.dto;

import java.util.List;

/**
 * 批量改签成功响应：每项给出原计划（CANCELLED，历史占用保留）与后继新计划（PUBLISHED）快照，顺序与请求一致。
 */
public record BatchRescheduleResponse(List<RescheduleResponse> results) {
}
