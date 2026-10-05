package com.example.starter.calibration.model;

import java.math.BigDecimal;

/**
 * 曲线证书节点。随证书创建写入，之后不可修改。
 *
 * @param certificateId 所属曲线证书 ID
 * @param nodeIndex     节点序号，从 0 开始，按原始值严格递增
 * @param raw           节点原始值，最多 6 位小数
 * @param corrected     节点校正值，最多 6 位小数
 */
public record CertificateNode(
        long certificateId,
        int nodeIndex,
        BigDecimal raw,
        BigDecimal corrected) {
}
