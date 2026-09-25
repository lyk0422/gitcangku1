package com.example.starter.calibration.model;

import java.time.Instant;

/**
 * 批量测量提交幂等台账。仅在整批成功提交时写入；失败不占键。
 * 相同 batchId 重放返回首次结果；同键不同载荷（最终引用或输入摘要变化）判为冲突。
 *
 * @param batchId    客户端提供的批量提交幂等键
 * @param submittedBy 首次成功提交人
 * @param itemCount  首次成功提交的测量条数
 * @param fingerprint 整批最终引用与输入的 SHA-256 摘要
 * @param createdAt  首次成功提交时间（UTC）
 */
public record BatchSubmit(
        String batchId,
        String submittedBy,
        int itemCount,
        String fingerprint,
        Instant createdAt) {
}
