package com.example.starter.batch.dto;

import com.example.starter.batch.BatchStatus;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 效价固化计算视图：放行、拆分与合批均固化计算时刻、模型版本、输入效价与舍入结果。
 * 历史结论绑定 modelVersion，后续模型修订不改写本记录。
 *
 * @param batchKey        结论所属批次：放行为本批，拆分为子批，合批为新批
 * @param operationType   固化场景：APPROVE/SPLIT/MERGE
 * @param commandKey      触发计算的命令幂等键
 * @param modelVersion    单父模型版本（放行为本批版本；合批新批无单一版本时为首个输入版本，详见 inputs）
 * @param calculatedAt    计算固化时刻（UTC instant）
 * @param inputs          每个父批的输入明细（权重数量、模型版本、锚点、输入效价、经过 UTC 日数）
 * @param resultPotency   加权并舍入（HALF_UP，6 位）后的结果效价
 * @param thresholdPotency 最低放行阈值（继承/加权固化）
 * @param eligible        结果效价是否不低于阈值
 */
public record PotencyCalculationView(
        String batchKey,
        String operationType,
        String commandKey,
        int modelVersion,
        Instant calculatedAt,
        java.util.List<CalculationInput> inputs,
        BigDecimal resultPotency,
        BigDecimal thresholdPotency,
        boolean eligible
) {

    /**
     * 单个父批的固化输入；anchorAt/elapsedDays 在多父加权时区分各父批衰减进度。
     */
    public record CalculationInput(
            String batchKey,
            BigDecimal weight,
            int modelVersion,
            Instant anchorAt,
            BigDecimal inputPotency,
            BigDecimal elapsedDays
    ) {
    }
}
