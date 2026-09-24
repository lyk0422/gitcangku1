package com.example.starter.calibration.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 仪器期间核查记录。提交后不可修改、不可删除；同一仪器同一核查时刻只允许一条。
 * 判定规则：|actualValue - standardValue| &lt;= tolerance 为 PASS，否则 FAIL。
 *
 * @param id            核查记录 ID（自增）
 * @param checkKey      核查业务键，全局唯一
 * @param instrumentId  仪器 ID
 * @param checkedAt     UTC 核查时刻
 * @param standardValue 标准值，最多 6 位小数
 * @param actualValue   实测值，最多 6 位小数
 * @param tolerance     容差，非负，最多 6 位小数
 * @param result        判定结果：PASS / FAIL
 * @param checkedBy     核查人
 * @param createdAt     记录提交时间（UTC）
 */
public record InterimCheck(
        long id,
        String checkKey,
        String instrumentId,
        Instant checkedAt,
        BigDecimal standardValue,
        BigDecimal actualValue,
        BigDecimal tolerance,
        CheckResult result,
        String checkedBy,
        Instant createdAt) {
}
