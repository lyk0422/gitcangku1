package com.example.starter.firmware.domain;

/**
 * 投放排除记录：不兼容设备及原因，不计入失败率。
 *
 * @param id             排除记录ID
 * @param launchId       所属投放单ID
 * @param deviceId       设备ID
 * @param profileVersion 评估时画像版本，设备无画像为 null
 * @param reason         排除原因（无画像、缺字段、型号不匹配、内存不足、缺必需标签）
 */
public record ProfileExclusion(long id, long launchId, String deviceId, Integer profileVersion,
                               String reason) {
}
