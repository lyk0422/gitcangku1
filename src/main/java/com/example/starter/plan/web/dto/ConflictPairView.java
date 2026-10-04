package com.example.starter.plan.web.dto;

/**
 * 进路冲突对视图，按字典序规范化输出（routeA <= routeB）。
 */
public record ConflictPairView(String routeA, String routeB) {
}