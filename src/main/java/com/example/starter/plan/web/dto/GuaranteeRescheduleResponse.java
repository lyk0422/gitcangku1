package com.example.starter.plan.web.dto;

import java.util.List;

/**
 * 批量保障改签响应，items 顺序与请求 scheduleKeys 一致。
 */
public record GuaranteeRescheduleResponse(List<GuaranteeRescheduleItem> items) {
}