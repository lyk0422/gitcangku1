package com.example.starter.plan.web.dto;

import java.util.List;

/**
 * 改签链查询响应：包含指定计划在内的完整有序前后继链；
 * 无改签历史的计划链中仅含自身一个节点。
 */
public record RescheduleChainResponse(String scheduleKey, List<RescheduleChainItem> chain) {
}
