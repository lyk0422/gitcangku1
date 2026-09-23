package com.example.starter.calibration.api.dto;

/**
 * 创建标准器版本请求。
 *
 * @param versionKey      版本业务键，全局唯一
 * @param standardId      所属标准器业务 ID
 * @param parentVersionKey 上级标准器版本业务键；空表示血缘根版本
 * @param validFrom       有效期起点，ISO-8601（UTC，含）
 * @param validTo         有效期终点，ISO-8601（UTC，不含）
 * @param certificateNo   校准证书号
 */
public record CreateStandardVersionRequest(
        String versionKey,
        String standardId,
        String parentVersionKey,
        String validFrom,
        String validTo,
        String certificateNo) {
}
