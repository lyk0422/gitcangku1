package com.example.starter.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 发芽率证书，签发时固化两次检测的编号、样本数、发芽粒数与比例以及签发时刻。
 * 证书行一经插入不可变；失效仅变更 status，已放行批次的证书保留 VALID 快照。
 *
 * @param id                    数据库主键
 * @param certificateNo         证书编号，业务唯一
 * @param batchId               所属批次主键
 * @param firstTestNo           第一次（较早一次）检测编号
 * @param firstSampleSize       第一次检测样本数快照，单位粒
 * @param firstGerminatedCount  第一次检测发芽粒数快照，单位粒
 * @param firstRatio            第一次检测发芽率快照，0-1 之间四位小数
 * @param secondTestNo          第二次（最近一次）检测编号
 * @param secondSampleSize      第二次检测样本数快照，单位粒
 * @param secondGerminatedCount 第二次检测发芽粒数快照，单位粒
 * @param secondRatio           第二次检测发芽率快照，0-1 之间四位小数
 * @param status                证书状态
 * @param requestId             签发请求幂等键
 * @param issuedAt              签发时刻（Asia/Shanghai）
 * @param createdAt             入库时刻（Asia/Shanghai）
 */
public record GerminationCertificate(
        Long id,
        String certificateNo,
        Long batchId,
        String firstTestNo,
        int firstSampleSize,
        int firstGerminatedCount,
        BigDecimal firstRatio,
        String secondTestNo,
        int secondSampleSize,
        int secondGerminatedCount,
        BigDecimal secondRatio,
        CertificateStatus status,
        String requestId,
        LocalDateTime issuedAt,
        LocalDateTime createdAt
) {
}
