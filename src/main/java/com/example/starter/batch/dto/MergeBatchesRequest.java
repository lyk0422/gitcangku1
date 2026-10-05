package com.example.starter.batch.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 合批请求：2～5 个 RELEASED 父批合并为一个全新批次；新批绑定指定声明版本，
 * 该声明物料须与全部父批物料一致（合批重新校验所有父物料），批号须命中允许前缀，
 * 且声明在合批时点有效。任一校验失败整次回滚，父批不变。
 */
public record MergeBatchesRequest(
        @NotBlank(message = "commandKey 不能为空") String commandKey,
        @NotBlank(message = "batchKey 不能为空") String batchKey,
        @NotBlank(message = "batchNo 不能为空") String batchNo,
        @NotNull(message = "parents 不能为空")
        @Size(min = 2, max = 5, message = "parents 必须包含 2～5 个父批")
        List<@NotBlank(message = "父批 batchKey 不能为空") String> parents,
        @NotBlank(message = "declarationKey 不能为空") String declarationKey,
        @NotNull(message = "version 不能为空") Integer version
) {
}
