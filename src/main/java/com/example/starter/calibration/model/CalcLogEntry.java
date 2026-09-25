package com.example.starter.calibration.model;

import java.time.Instant;

/**
 * 计算指纹（calcKey）幂等日志条目。仅记录成功结果；失败回滚不占键。
 *
 * @param calcKey     计算指纹键，全局唯一
 * @param operation   操作类型：SUBMIT/RECALCULATE/RELEASE/REJECT/UPDATE_PROFILE
 * @param httpStatus  首次成功响应的 HTTP 状态码，重放时沿用
 * @param fingerprint 规范化输入指纹：测量/批次版本、环境、系数版本与全部输入
 * @param resultJson  首次成功响应体 JSON，重放时原样返回
 * @param createdAt   首次成功提交时间（UTC）
 */
public record CalcLogEntry(
        String calcKey,
        String operation,
        int httpStatus,
        String fingerprint,
        String resultJson,
        Instant createdAt) {
}
