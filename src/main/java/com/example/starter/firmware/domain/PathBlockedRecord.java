package com.example.starter.firmware.domain;

/**
 * PATH_BLOCKED 拦截历史记录。只增不改；不建任务、不计失败率样本、不改设备状态。
 *
 * @param id              拦截记录ID
 * @param releaseId       所属发布单ID
 * @param deviceId        被拦截设备ID
 * @param currentVersion  拉取判定时刻的设备当前版本
 * @param requiredVersion 下一个必须安装的中间版本
 * @param targetVersion   本次不下发的目标任务版本
 * @param blockedAtUtc    拦截时刻，UTC，ISO-8601 格式
 */
public record PathBlockedRecord(long id, long releaseId, String deviceId, String currentVersion,
                                String requiredVersion, String targetVersion, String blockedAtUtc) {
}
