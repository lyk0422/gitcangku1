package com.example.starter.batch.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 受控拆批请求体。父批由路径给出；expectedVersion 为父批期望版本（乐观并发控制）；
 * children 为 2～5 个全新子批，各自指定序号区间集合；全部子批区间的并集必须
 * 精确等于父批当前区间且两两不交，否则整次 422，不建任何子批或血缘。
 */
public record SerialSplitRequest(
        @NotBlank(message = "requestId 不能为空") String requestId,
        @NotBlank(message = "operator 不能为空") String operator,
        @NotNull(message = "expectedVersion 不能为空") Integer expectedVersion,
        @NotNull(message = "children 不能为空")
        @Size(min = 2, max = 5, message = "children 必须包含 2～5 个子批")
        List<@Valid ChildSpec> children
) {

    /**
     * 子批规格：batchKey 全局唯一，batchNo 为子批批号；serialIntervals 为该子批
     * 分得的序号区间集合（1～10 个，相邻先合并），子批数量按区间总长度生成。
     */
    public record ChildSpec(
            @NotBlank(message = "子批 batchKey 不能为空") String batchKey,
            @NotBlank(message = "子批 batchNo 不能为空") String batchNo,
            @NotNull(message = "子批 serialIntervals 不能为空")
            @Size(min = 1, message = "子批 serialIntervals 至少包含 1 个区间")
            List<@Valid SerialIntervalSpec> serialIntervals
    ) {
    }
}
