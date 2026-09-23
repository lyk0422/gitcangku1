package com.example.starter.api.dto;

/**
 * 时间窗口传输对象：UTC 毫秒起止时刻，区间左闭右开。
 *
 * @param windowStart 窗口开始时刻，epoch 毫秒（UTC），区间含；与 windowEnd 同为 null 表示全时
 * @param windowEnd   窗口结束时刻，epoch 毫秒（UTC），区间不含；必须严格晚于 windowStart
 */
public record TimeWindowDto(Long windowStart, Long windowEnd) {
}
