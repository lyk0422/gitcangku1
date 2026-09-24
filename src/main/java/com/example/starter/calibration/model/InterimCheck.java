package com.example.starter.calibration.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 仪器期间核查记录。核查记录不可修改删除；同一仪器同一核查时刻只允许一条。
 *
 * @param id            核查记录 ID（自增）
 * @param checkKey      核查键，全局唯一（业务幂等键）
 * @param instrumentId  仪器 ID
 * @param checkedAt     核查时刻（UTC）
 * @param standardValue 标准值，最多 6 位小数
 * @param measuredValue 实测值，最多 6 位小数
 * @param tolerance     容差，非负，最多 6 位小数
 * @param verdict       核查判定：PASS / FAIL
 * @param checkedBy     核查人
 * @param requestId     写操作请求幂等键
 * @param createdAt     记录创建时间（UTC）
 */
public record InterimCheck(
        long id,
        String checkKey,
        String instrumentId,
        Instant checkedAt,
        BigDecimal standardValue,
        BigDecimal measuredValue,
        BigDecimal tolerance,
        CheckVerdict verdict,
        String checkedBy,
        String requestId,
        Instant createdAt) {
}
