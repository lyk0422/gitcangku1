package com.example.starter.plan.web.dto;

/**
 * 车底最小周转参数登记/修改结果。
 */
public record RollingStockView(String stockNo, int minTurnaroundMinutes, int version) {
}
