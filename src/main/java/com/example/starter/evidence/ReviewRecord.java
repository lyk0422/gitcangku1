package com.example.starter.evidence;

import java.time.LocalDateTime;

/**
 * 复核记录实体，对应 review_record 表。只追加、不可变。
 *
 * @param id          主键
 * @param intakeKey   所属批次键
 * @param evidenceKey 被复核证物业务键
 * @param reviewerId  复核提交人（批次保管人）
 * @param note        复核说明
 * @param createdAt   复核提交时间（Asia/Shanghai）
 */
public record ReviewRecord(
        Long id,
        String intakeKey,
        String evidenceKey,
        String reviewerId,
        String note,
        LocalDateTime createdAt) {
}
