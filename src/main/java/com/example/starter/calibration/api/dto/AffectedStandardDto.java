package com.example.starter.calibration.api.dto;

import java.time.Instant;

/**
 * 失效闭包中受影响的标准器版本。
 *
 * @param standardId       标准器版本业务键
 * @param parentStandardId 上级标准器版本业务键；无上级为 null
 * @param validFrom        有效窗口起点（UTC，含）
 * @param validTo          有效窗口终点（UTC，不含）
 * @param status           状态：VALID / INVALID
 * @param version          版本号
 */
public record AffectedStandardDto(
        String standardId,
        String parentStandardId,
        Instant validFrom,
        Instant validTo,
        String status,
        int version) {
}
