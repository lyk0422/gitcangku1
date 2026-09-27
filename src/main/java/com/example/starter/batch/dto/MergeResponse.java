package com.example.starter.batch.dto;

import com.example.starter.batch.BatchStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 合批响应：子批初始 QUARANTINED，初始效价为全部父批在合批操作时刻当前效价的数量加权值。
 * totalQuantity 为实际合批总量；parents 按规范化（batchKey 升序）顺序返回，
 * 每项含实际投入数量、实际权重、父批操作时刻输入效价与要求阈值。
 */
public record MergeResponse(
        String childBatchKey,
        String childBatchNo,
        String productCode,
        BatchStatus status,
        List<String> requiredTests,
        BigDecimal totalQuantity,
        BigDecimal childInitialPotency,
        BigDecimal childMinPotency,
        BigDecimal childDecayRate,
        int childModelVersion,
        List<ParentContribution> parents,
        Instant mergedAt
) {

    /**
     * 单个父批的合批贡献证据。
     *
     * @param quantity       实际投入数量
     * @param weight         实际权重（数量/总量，10 位小数舍入），全部父批权重守恒为 1
     * @param currentPotency 父批在合批操作时刻的输入效价（6 位小数舍入结果）
     * @param minPotency     父批当时最低放行效价要求值
     * @param difference     差额：父批操作时刻效价 - 其要求阈值
     */
    public record ParentContribution(
            int seq,
            String batchKey,
            BigDecimal quantity,
            BigDecimal weight,
            BigDecimal currentPotency,
            BigDecimal minPotency,
            BigDecimal difference
    ) {
    }
}
