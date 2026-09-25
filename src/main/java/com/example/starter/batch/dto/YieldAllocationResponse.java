package com.example.starter.batch.dto;

import java.util.List;

/**
 * 批次父子分配汇总。数量为十进制字符串；未登记产率时对应字段为 null。
 * allocatedQty 为全部直接子批投入量之和；remainingQty = outputQty - allocatedQty。
 */
public record YieldAllocationResponse(
        String batchKey,
        String outputQty,
        String allocatedQty,
        String remainingQty,
        List<ChildAllocation> children,
        List<ParentAllocation> parents
) {

    /**
     * 直接子批的分配明细。
     */
    public record ChildAllocation(
            String batchKey,
            String inputQty,
            String outputQty,
            String yieldRate
    ) {
    }

    /**
     * 直接父批的分配汇总：父批产出、父批全部子批已分配投入、父批剩余可分配量，
     * 以及本批次占用的投入量（未登记产率时为 null）。
     */
    public record ParentAllocation(
            String parentKey,
            String parentOutputQty,
            String allocatedQty,
            String remainingQty,
            String thisInputQty
    ) {
    }
}
