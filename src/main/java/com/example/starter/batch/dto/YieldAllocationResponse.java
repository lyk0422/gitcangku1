package com.example.starter.batch.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 父子分配汇总查询响应。作为父批：outputQuantity 为本批已登记产出量（未登记为 null），
 * allocatedQuantity 为全部直接子批已登记投入量之和，remainingQuantity 为产出减已分配
 * （本批未登记产出时为 null）。作为子批：parent 给出直接父批的同一组汇总，无父批为 null。
 */
public record YieldAllocationResponse(
        String batchKey,
        BigDecimal outputQuantity,
        BigDecimal allocatedQuantity,
        BigDecimal remainingQuantity,
        ParentAllocation parent,
        List<ChildAllocation> children
) {

    /**
     * 直接父批的分配汇总。
     */
    public record ParentAllocation(
            String batchKey,
            BigDecimal outputQuantity,
            BigDecimal allocatedQuantity,
            BigDecimal remainingQuantity
    ) {
    }

    /**
     * 直接子批的已登记投入量；未登记产率的子批 inputQuantity 为 null。
     */
    public record ChildAllocation(
            String batchKey,
            BigDecimal inputQuantity
    ) {
    }
}
