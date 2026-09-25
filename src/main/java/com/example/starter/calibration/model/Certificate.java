package com.example.starter.calibration.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 校准标准器证书。创建后不可修改，仅可撤销；有效区间为 UTC 左闭右开 [validFrom, validTo)，
 * 端点 validTo 时刻即到期无效。
 *
 * <p>证书按 (standardId, version) 显式标识一个标准器证书版本；测量可显式引用该二元组，
 * 也可沿用旧行为按仪器自动匹配。证书同时携带补偿系数 (a,b)、标准不确定度及其版本，
 * 放行时随测量版本快照完整可追溯。singleBatchOnly 为 TRUE 的证书仅允许被一个放行批次引用。
 *
 * @param id                 证书 ID（自增）
 * @param standardId         标准器 ID
 * @param instrumentId       仪器 ID（历史兼容字段；未显式指定标准器时与 standardId 相同）
 * @param version            证书版本，同一标准器内唯一
 * @param validFrom          有效期起点（UTC，含）
 * @param validTo            有效期终点（UTC，不含；端点时刻即到期）
 * @param a                  补偿系数 a，最多 6 位小数
 * @param b                  补偿偏移 b，最多 6 位小数
 * @param uncertainty        标准器标准不确定度，非负，最多 9 位小数，单位与读数一致
 * @param uncertaintyVersion 不确定度版本
 * @param singleBatchOnly    是否仅允许单个放行批次引用
 * @param revoked            是否已撤销
 * @param revokedAt          撤销时间（UTC），未撤销为 null
 * @param createdAt          创建时间（UTC）
 */
public record Certificate(
        long id,
        String standardId,
        String instrumentId,
        String version,
        Instant validFrom,
        Instant validTo,
        BigDecimal a,
        BigDecimal b,
        BigDecimal uncertainty,
        String uncertaintyVersion,
        boolean singleBatchOnly,
        boolean revoked,
        Instant revokedAt,
        Instant createdAt) {

    /**
     * 证书在给定测量时刻是否有效：未撤销且 validFrom &lt;= at &lt; validTo（左闭右开，端点到期即无效）。
     */
    public boolean validAt(Instant at) {
        return !revoked
                && at.compareTo(validFrom) >= 0
                && at.compareTo(validTo) < 0;
    }
}
