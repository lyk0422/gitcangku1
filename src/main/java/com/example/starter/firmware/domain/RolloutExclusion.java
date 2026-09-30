package com.example.starter.firmware.domain;

/**
 * 发布单维度的硬件不兼容排除记录。被排除设备不创建任务、不计入失败率；
 * 设备在后续投放中转为兼容并创建任务后，对应记录移除。
 *
 * @param id             排除记录ID
 * @param releaseId      所属发布单ID
 * @param deviceId       被排除设备ID
 * @param reason         排除原因码
 * @param profileVersion 判定时使用的设备画像版本；设备无画像时为 null
 */
public record RolloutExclusion(long id, long releaseId, String deviceId, ExclusionReason reason,
                               Integer profileVersion) {
}
