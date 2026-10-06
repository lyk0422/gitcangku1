package com.example.starter.evidence.dto;

import java.time.LocalDateTime;

/**
 * 箱封条核验记录视图。
 *
 * @param containerKey 被核验箱业务键
 * @param inspectorId  提交核验的保管人
 * @param passed       核验结果
 * @param note         核验备注
 * @param createdAt    核验提交时间（Asia/Shanghai）
 */
public record ContainerInspectionView(
        String containerKey,
        String inspectorId,
        boolean passed,
        String note,
        LocalDateTime createdAt) {
}
