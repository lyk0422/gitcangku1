package com.example.starter.plan.web.dto;

/**
 * 封锁切换映射视图：旧计划业务键到替代计划业务键的一对一关系。
 */
public record DisruptionMappingView(String oldScheduleKey, String replacementScheduleKey) {
}
