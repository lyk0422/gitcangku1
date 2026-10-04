package com.example.starter.evidence.dto;

import com.example.starter.evidence.RegulatedExceptionReason;

import java.time.LocalDateTime;

/**
 * 交接异常记录视图。
 *
 * @param checkpointNo 发生异常的检查点号
 * @param reason       异常原因
 * @param detail       异常说明
 * @param createdAt    异常记录时间（UTC）
 */
public record ExceptionView(
        int checkpointNo,
        RegulatedExceptionReason reason,
        String detail,
        LocalDateTime createdAt) {
}