package com.example.starter.batch.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 合批请求：2～5 个 RELEASED 父批合并为一个全新批次。
 * 合批时按新批批号重新校验全部父物料的声明匹配，任一父物料无有效声明整次 422 回滚。
 */
public record MergeBatchesRequest(
        @NotBlank(message = "commandKey 不能为空") String commandKey,
        @NotBlank(message = "batchKey 不能为空") String batchKey,
        @NotBlank(message = "batchNo 不能为空") String batchNo,
        @NotBlank(message = "productCode 不能为空") String productCode,
        @NotNull(message = "parents 不能为空")
        @Size(min = 2, max = 5, message = "parents 必须包含 2～5 个父批")
        List<@NotBlank(message = "父批 batchKey 不能为空") String> parents
) {
}