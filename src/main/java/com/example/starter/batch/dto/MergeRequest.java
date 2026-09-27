package com.example.starter.batch.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

/**
 * 合批请求体：2～5 个已放行（RELEASED）父批按投入数量加权产生一个全新批次。
 *
 * <p>守恒与前置条件（任一不满足整次 422 回滚，不留部分结果）：
 * 全部父批均须登记数量与效价模型；投入数量为正、键不重复，且必须整体投入父批全部现有数量；
 * 新批数量必须严格等于全部父批投入数量之和；全部父批必须已检验放行；
 * 每个父批在固化时刻的当前效价均不得低于其自身最低放行阈值，否则整次回滚。
 */
public record MergeRequest(
        @NotBlank(message = "commandKey 不能为空") String commandKey,
        @NotNull(message = "parents 不能为空")
        @Size(min = 2, max = 5, message = "parents 必须包含 2～5 个父批")
        List<@Valid ParentInput> parents,
        @NotNull(message = "child 不能为空") @Valid ChildSpec child
) {

    /**
     * 父批投入：batchKey 为已放行父批，quantity 为投入数量（正数，必须等于该父批现有数量，
     * 合批整体消耗父批）。
     */
    public record ParentInput(
            @NotBlank(message = "父批 batchKey 不能为空") String batchKey,
            @NotNull(message = "父批投入 quantity 不能为空") BigDecimal quantity
    ) {
    }

    /**
     * 合批产生的新批：batchKey 全局唯一，batchNo 为新批号，quantity 为新批数量
     * （必须等于全部父批投入数量之和）。
     */
    public record ChildSpec(
            @NotBlank(message = "新批 batchKey 不能为空") String batchKey,
            @NotBlank(message = "新批 batchNo 不能为空") String batchNo,
            @NotNull(message = "新批 quantity 不能为空") BigDecimal quantity
    ) {
    }
}
