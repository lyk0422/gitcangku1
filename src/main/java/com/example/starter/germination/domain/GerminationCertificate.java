package com.example.starter.germination.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 发芽率证书；签发时固化当时最近两次有效检测的编号与比例，之后不被改写。
 *
 * @param certNo        证书编号
 * @param batchNo       所属种子批次编号
 * @param firstTestNo   固化的第一次（较早一次）检测编号
 * @param firstRatio    第一次检测发芽率，单位 %，保留两位小数
 * @param secondTestNo  固化的第二次（最近一次）检测编号
 * @param secondRatio   第二次检测发芽率，单位 %，保留两位小数
 * @param status        证书状态：VALID 有效 / INVALID 未放行即失效
 * @param issuedAt      签发时刻（UTC 时间线）
 */
public record GerminationCertificate(String certNo,
                                     String batchNo,
                                     String firstTestNo,
                                     BigDecimal firstRatio,
                                     String secondTestNo,
                                     BigDecimal secondRatio,
                                     CertificateStatus status,
                                     Instant issuedAt) {
}
