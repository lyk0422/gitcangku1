package com.example.starter.evidence.dto;

import java.time.LocalDateTime;

/**
 * 箱封条核验记录视图。
 *
 * @param containerKey 关联箱业务键
 * @param inspectorId  提交核验的当前保管人
 * @param passed       核验结果
 * @param note         核验备注；NULL 表示未填写
 * @param createdAt    核验提交时间（Asia/Shanghai）
 */
public record ContainerInspectionView(
        String containerKey,
        String inspectorId,
        boolean passed,
        String note,
        LocalDateTime createdAt) {
}
