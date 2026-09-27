package com.example.starter.batch.dto;

import com.example.starter.batch.BatchStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 拆分响应。父批状态落定 SPLIT；子批初始 QUARANTINED，继承父批产品编码、
 * 生产 UTC 时间与必做检验项，不继承检验或批准记录。splitAt 为 UTC instant。
 *
 * <p>每个子批的初始效价取父批在拆分时刻的当前效价（单父权重为 1），
 * 阈值与日衰减率继承父批当时模型，子批模型版本从 1 开始并以 splitAt 为基准时刻。
 */
public record SplitResponse(
        String parentBatchKey,
        BatchStatus parentStatus,
        List<SplitChild> children,
        Instant splitAt
) {

    /**
     * 拆分产生的子批概要；potency 为效价继承明细，父批未登记模型时为 null。
     */
    public record SplitChild(
            String batchKey,
            String batchNo,
            String productCode,
            Instant producedAt,
            BatchStatus status,
            List<String> requiredTests,
            PotencyInheritance potency,
            Instant createdAt
    ) {
    }

    /**
     * 拆分子批效价继承明细：实际父批时刻效价成为子批初始效价，阈值/衰减率为要求继承值。
     *
     * @param elapsedDays      实际时长：父批模型基准时刻到拆分时刻的 UTC 日
     * @param decayedPotency   实际衰减量
     * @param parentPotency    实际值：父批拆分时刻当前效价（即子批初始效价）
     * @param initialPotency   子批登记初始效价
     * @param minPotency       要求值：子批最低放行效价（继承父批）
     * @param difference       差额：父批时刻效价 - 阈值（负值表示拆分时刻已低于阈值，仅记录不拦截拆分）
     */
    public record PotencyInheritance(
            int parentModelVersion,
            int childModelVersion,
            BigDecimal decayRate,
            BigDecimal elapsedDays,
            BigDecimal decayedPotency,
            BigDecimal parentPotency,
            BigDecimal initialPotency,
            BigDecimal minPotency,
            BigDecimal difference,
            String decision
    ) {
    }
}
