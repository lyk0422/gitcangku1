package com.example.starter.water.dto;

/**
 * 供水窗口视图。时间与水量均以字符串输出：时间为 ISO-8601 UTC，水量为 3 位小数十进制字符串（立方米）。
 */
public record WindowView(
        long id,
        String windowKey,
        String channelId,
        String startUtc,
        String endUtc,
        String plannedVolume,
        String createdAt) {
}
