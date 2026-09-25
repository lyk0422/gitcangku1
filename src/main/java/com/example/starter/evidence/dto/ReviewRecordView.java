package com.example.starter.evidence.dto;

import java.time.LocalDateTime;

/**
 * 复核记录视图。记录只追加、不可变。
 *
 * @param intakeKey   所属批次键
 * @param evidenceKey 被复核证物业务键
 * @param reviewerId  复核提交人（批次保管人）
 * @param note        复核说明
 * @param createdAt   复核提交时间（Asia/Shanghai）
 */
public record ReviewRecordView(
        String intakeKey,
        String evidenceKey,
        String reviewerId,
        String note,
        LocalDateTime createdAt) {
}
