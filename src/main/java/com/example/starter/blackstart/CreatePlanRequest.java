package com.example.starter.blackstart;

import jakarta.validation.constraints.Size;

/**
 * 创建黑启动计划请求。
 *
 * @param name 计划名称，缺省时使用默认名称
 */
public record CreatePlanRequest(@Size(max = 64) String name) {
}