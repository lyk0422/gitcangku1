package com.example.starter.plan.web.dto;

import java.util.List;

/**
 * 批量改签响应：按请求顺序返回每一项改签后的旧/新计划快照。
 * 整批原子：要么全部完成，要么全部回滚（返回 4xx，不产生本响应）。
 */
public record BatchRescheduleResponse(List<RescheduleResponse> items) {
}
