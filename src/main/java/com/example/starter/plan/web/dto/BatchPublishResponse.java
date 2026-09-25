package com.example.starter.plan.web.dto;

import java.util.List;

/**
 * 整批发布结果：按请求顺序返回各计划最新明细。整批原子，全部成功才有此响应。
 */
public record BatchPublishResponse(List<PlanResponse> plans) {
}
