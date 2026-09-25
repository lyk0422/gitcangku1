package com.example.starter.calibration.model;

import java.time.Instant;

/**
 * calcKey 幂等记录：固化首次成功响应，供同键重放。
 *
 * @param calcKey      客户端提供的幂等键
 * @param operation   操作类型：SUBMIT / RELEASE / REJECT / RECALC / PUBLISH_COEFFICIENT
 * @param fingerprint  SHA-256 指纹（含测量/批次版本、环境、系数版本与全部输入）
 * @param responseJson 首次成功响应 JSON 快照
 * @param createdAt   首次成功时间（UTC）
 */
public record CalcRecord(
        String calcKey,
        String operation,
        String fingerprint,
        String responseJson,
        Instant createdAt) {
}
