package com.example.starter.evidence.dto;

import com.example.starter.evidence.ChunkIntakeStatus;

import java.time.LocalDateTime;

/**
 * 分块接收视图。
 *
 * @param intakeKey      分块接收业务键
 * @param caseKey        所属案件键
 * @param category       证物类别
 * @param sealNo         封条编号
 * @param custodianId    入库人（完成前唯一可操作人，完成后成为证物保管人）
 * @param totalLength    固定总字节数（1..65536），单位字节
 * @param sha256         完整字节流 SHA-256 摘要（小写十六进制）
 * @param status         接收状态：RECEIVING / SEALED / ABANDONED
 * @param version        当前已接收的非重复块数；完全相同范围同内容的重传不递增
 * @param receivedLength 已接收范围覆盖的字节数，单位字节；完成时必须等于 totalLength
 * @param createdAt      创建时间（Asia/Shanghai）
 * @param updatedAt      最近一次补块或状态变更时间（Asia/Shanghai）
 */
public record ChunkIntakeView(
        String intakeKey,
        String caseKey,
        String category,
        String sealNo,
        String custodianId,
        int totalLength,
        String sha256,
        ChunkIntakeStatus status,
        int version,
        int receivedLength,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
