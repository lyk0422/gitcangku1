package com.example.starter.plan.web.dto;

/**
 * 车底响应，含当前最小周转分钟数与参数版本。
 */
public record RollingStockResponse(String stockKey, int minTurnaroundMinutes, int version) {
}
