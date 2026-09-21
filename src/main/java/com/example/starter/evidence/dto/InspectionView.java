package com.example.starter.evidence.dto;

import java.time.LocalDateTime;

/**
 * 封条核验记录视图。
 *
 * @param evidenceKey 关联证物业务键
 * @param inspectorId 提交核验的保管人
 * @param passed      核验结果
 * @param note        核验备注；null 表示未填写
 * @param createdAt   核验提交时间（Asia/Shanghai）
 */
public record InspectionView(
        String evidenceKey,
        String inspectorId,
        boolean passed,
        String note,
        LocalDateTime createdAt) {
}
