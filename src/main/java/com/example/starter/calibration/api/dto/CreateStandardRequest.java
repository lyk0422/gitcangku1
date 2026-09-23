package com.example.starter.calibration.api.dto;

/**
 * 创建标准器版本请求。
 *
 * @param standardId       标准器版本业务键，全局唯一
 * @param parentStandardId 上级（校准方）标准器版本业务键；无上级为 null
 * @param validFrom        有效窗口起点，ISO-8601（按 UTC 归一，左闭）
 * @param validTo          有效窗口终点，ISO-8601（按 UTC 归一，右开）
 * @param certificateNo    上级出具的校准证书号
 */
public record CreateStandardRequest(
        String standardId,
        String parentStandardId,
        String validFrom,
        String validTo,
        String certificateNo) {
}
