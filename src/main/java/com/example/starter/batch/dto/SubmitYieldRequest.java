package com.example.starter.batch.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

/**
 * 批次产率登记/修订请求。一个请求可含多个批次条目：先校验全部条目应用后的最终分配，
 * 再在单事务写入，任一失败整单回滚。yieldKey 为幂等键，指纹含操作者、expectedVersion、
 * 规范化批次集合和全部数值；同键成功重放首次快照，失败不占键。
 */
public record SubmitYieldRequest(
        @NotBlank(message = "yieldKey 不能为空") String yieldKey,
        @NotNull(message = "entries 不能为空")
        @Size(min = 1, max = 20, message = "entries 必须包含 1～20 个批次条目")
        List<@Valid YieldEntry> entries
) {

    /**
     * 单批次产率条目。expectedVersion 为空表示新登记（批次不得已有产率记录）；
     * 非空表示修订，必须与当前版本一致。投入/产出均大于零且最多三位小数。
     */
    public record YieldEntry(
            @NotBlank(message = "batchKey 不能为空") String batchKey,
            @Min(value = 1, message = "expectedVersion 必须大于等于 1") Long expectedVersion,
            @NotNull(message = "inputQuantity 不能为空")
            @DecimalMin(value = "0", inclusive = false, message = "inputQuantity 必须大于零")
            @Digits(integer = 13, fraction = 3, message = "inputQuantity 最多三位小数")
            BigDecimal inputQuantity,
            @NotNull(message = "outputQuantity 不能为空")
            @DecimalMin(value = "0", inclusive = false, message = "outputQuantity 必须大于零")
            @Digits(integer = 13, fraction = 3, message = "outputQuantity 最多三位小数")
            BigDecimal outputQuantity
    ) {
    }
}
