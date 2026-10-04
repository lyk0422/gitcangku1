package com.example.starter.evidence;

import java.time.LocalDateTime;

/**
 * 交接异常记录实体，对应 regulated_exception 表。记录只追加；记录后交接进入 FROZEN。
 *
 * @param id           主键
 * @param transferKey  关联受监管交接业务键
 * @param checkpointNo 发生异常的检查点号
 * @param reason       异常原因：TIMEOUT 超时 / SEAL_MISMATCH 封条不一致
 * @param detail       异常说明，含提交值与期望值
 * @param createdAt    异常记录时间（UTC）
 */
public record RegulatedExceptionRecord(
        Long id,
        String transferKey,
        int checkpointNo,
        RegulatedExceptionReason reason,
        String detail,
        LocalDateTime createdAt) {
}