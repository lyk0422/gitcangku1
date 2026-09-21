package com.example.starter.water.dto;

/**
 * 创建供水窗口命令。
 *
 * @param commandKey    幂等命令键
 * @param windowKey     窗口业务键，全局唯一
 * @param channelId     渠道 ID
 * @param startUtc      窗口开始时刻，ISO-8601 UTC 字符串，如 2026-09-21T00:00:00Z
 * @param endUtc        窗口结束时刻，ISO-8601 UTC 字符串，必须晚于开始时刻
 * @param plannedVolume 计划水量，十进制字符串，单位立方米，最多 3 位小数且大于 0
 */
public record CreateWindowCommand(
        String commandKey,
        String windowKey,
        String channelId,
        String startUtc,
        String endUtc,
        String plannedVolume) {
}
