package com.example.starter.firmware.domain;

/**
 * PATH_BLOCKED 判定历史记录：拉取时因前置链存在未安装中间版本而被拦截，只增不改。
 *
 * @param id            记录ID
 * @param releaseId     判定所属发布单ID
 * @param deviceId      设备ID
 * @param deviceVersion 判定时刻设备当前固件版本
 * @param targetVersion 发布单目标固件版本
 * @param nextVersion   下一个必须安装的中间版本
 * @param blockedAtUtc  判定时刻，UTC，ISO-8601 格式
 */
public record PathBlockedRecord(long id, long releaseId, String deviceId, String deviceVersion,
                                String targetVersion, String nextVersion, String blockedAtUtc) {
}
