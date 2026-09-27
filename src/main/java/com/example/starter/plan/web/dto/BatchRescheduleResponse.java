package com.example.starter.plan.web.dto;

import java.util.List;

/**
 * 批量改签响应：按请求项顺序返回每项改签后旧/新计划快照；整批成功才返回，任一非法整体回滚。
 */
public record BatchRescheduleResponse(int count, List<RescheduleResponse> results) {
}
