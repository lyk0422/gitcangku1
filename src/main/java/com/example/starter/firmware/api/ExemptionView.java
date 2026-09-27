package com.example.starter.firmware.api;

import java.util.List;

/**
 * 设备组模块豁免当前态视图。
 *
 * @param releaseId       所属发布单ID
 * @param groupName       豁免设备组
 * @param version         豁免版本号，从 1 开始，每次修订加一
 * @param modules         显式豁免模块名（按模块名排序）
 * @param expiresAtUtc    到期时刻，UTC，ISO-8601
 * @param createdAtUtc    当前版本创建时刻，UTC，ISO-8601
 * @param active          当前时刻是否仍在有效期内（UTC 左闭右开）；到期或不存在的豁免不产生放行
 * @param durationSeconds 要求的豁免时长（秒）= 到期时刻 - 创建时刻
 * @param remainingSeconds 剩余时长差额（秒）= 到期时刻 - 当前 UTC 时刻；到期后为负数或 0
 */
public record ExemptionView(long releaseId, String groupName, int version, List<String> modules,
                            String expiresAtUtc, String createdAtUtc, boolean active,
                            long durationSeconds, long remainingSeconds) {
}
