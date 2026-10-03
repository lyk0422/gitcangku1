package com.example.starter.service.view;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 放电读数视图。
 *
 * @param id 读数编号
 * @param cellCode 电芯编号
 * @param voltage 测量电压，单位伏特(V)
 * @param measuredAt 测量时刻，UTC
 * @param status 读数状态：VALID/VOID
 * @param requestId 测量请求幂等键
 */
public record ReadingView(long id, String cellCode, BigDecimal voltage, Instant measuredAt,
                          String status, String requestId) {
}