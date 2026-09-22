package com.example.starter.plan.web.dto;

import java.util.List;

/**
 * 改签链查询结果：plans 按改签先后从最早前驱到最后后继有序返回，
 * 未参与过改签的计划返回仅含自身的单元素链。
 */
public record RescheduleChainResponse(List<PlanResponse> plans) {
}
