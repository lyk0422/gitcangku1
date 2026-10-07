package com.example.starter.batch.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 序号区间拆批请求。父批由路径给出；children 为 2～5 个全新子批，
 * 每个子批指定 1～10 个闭区间；全部子批区间的并集必须精确等于父批当前序号域
 * 且两两不交，遗漏、重叠、区间外均整次 422，不建任何子批或血缘。
 */
public record SerialSplitRequest(
        @NotBlank(message = "requestId 不能为空") String requestId,
        @NotBlank(message = "operator 不能为空") String operator,
        @NotNull(message = "expectedVersion 不能为空") Long expectedVersion,
        @NotNull(message = "children 不能为空")
        @Size(min = 2, max = 5, message = "children 必须包含 2～5 个子批")
        List<@Valid ChildSpec> children
) {

    /**
     * 子批规格：batchKey 全局唯一，batchNo 为子批批号，intervals 为该子批获得的
     * 闭区间集合（1～10 个）；子批数量按区间总长度生成。
     */
    public record ChildSpec(
            @NotBlank(message = "子批 batchKey 不能为空") String batchKey,
            @NotBlank(message = "子批 batchNo 不能为空") String batchNo,
            @NotNull(message = "子批 intervals 不能为空")
            @Size(min = 1, max = 10, message = "子批 intervals 必须包含 1～10 个区间")
            List<@Valid IntervalSpec> intervals
    ) {
    }
}
