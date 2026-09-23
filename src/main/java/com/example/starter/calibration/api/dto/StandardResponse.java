package com.example.starter.calibration.api.dto;

import java.time.Instant;

/**
 * 标准器版本响应。
 *
 * @param id               标准器版本记录 ID
 * @param standardId       标准器版本业务键
 * @param parentStandardId 上级标准器版本业务键；无上级为 null
 * @param validFrom        有效窗口起点（UTC，含）
 * @param validTo          有效窗口终点（UTC，不含）
 * @param certificateNo    校准证书号
 * @param status           状态：VALID / INVALID
 * @param version          版本号，失效时递增
 * @param createdAt        创建时间（UTC）
 */
public record StandardResponse(
        long id,
        String standardId,
        String parentStandardId,
        Instant validFrom,
        Instant validTo,
        String certificateNo,
        String status,
        int version,
        Instant createdAt) {
}
