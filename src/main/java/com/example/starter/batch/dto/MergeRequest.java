package com.example.starter.batch.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 合批请求体：从当前可用的 RELEASED 批次中选择 2～5 个不同父批，创建一个全新批次。
 * 父批必须产品编码相同、必做检验项集合相同；父批集合顺序不影响命令幂等判定。
 */
public record MergeRequest(
        @NotBlank(message = "commandKey 不能为空") String commandKey,
        @NotBlank(message = "newBatchKey 不能为空") String newBatchKey,
        @NotBlank(message = "newBatchNo 不能为空") String newBatchNo,
        @NotNull(message = "parentBatchKeys 不能为空")
        @Size(min = 2, max = 5, message = "parentBatchKeys 必须包含 2～5 个父批")
        List<@NotBlank(message = "父批 batchKey 不能为空") String> parentBatchKeys
) {
}
