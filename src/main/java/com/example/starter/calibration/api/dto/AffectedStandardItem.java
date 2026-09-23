package com.example.starter.calibration.api.dto;

import java.time.Instant;

/**
 * 闭包内受影响标准器版本项。
 *
 * @param versionId 版本自增 ID
 * @param versionKey 版本业务键
 * @param standardId 所属标准器业务 ID
 * @param status    创建快照时的状态：VALID / INVALID
 */
public record AffectedStandardItem(
        long versionId,
        String versionKey,
        String standardId,
        String status) {
}
