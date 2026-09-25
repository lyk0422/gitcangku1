package com.example.starter.batch.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

/**
 * 批次产率登记/修订请求。一个请求可含多批次：先校验最终分配，再单事务写入，
 * 任一失败整单回滚。yieldKey 为幂等键，指纹含操作者、expectedVersion、
 * 规范化批次集合和全部数值；同键成功重放首次快照，失败不占键。
 */
public record YieldSubmitRequest(
        @NotBlank(message = "yieldKey 不能为空") String yieldKey,
        @NotBlank(message = "operator 不能为空") String operator,
        @NotNull(message = "items 不能为空")
        @Size(min = 1, max = 20, message = "items 必须包含 1～20 个批次产率项")
        List<@Valid Item> items
) {

    /**
     * 单批次产率项。expectedVersion 为空表示首次登记；非空表示修订，须等于当前版本。
     * inputQty/outputQty 均须大于零且最多三位小数。
     */
    public record Item(
            @NotBlank(message = "batchKey 不能为空") String batchKey,
            @NotNull(message = "inputQty 不能为空") BigDecimal inputQty,
            @NotNull(message = "outputQty 不能为空") BigDecimal outputQty,
            Long expectedVersion
    ) {
    }
}
