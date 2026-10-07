package com.example.starter.batch.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 合批请求：将 2～5 个 RELEASED 父批合并为一个全新子批。
 * 必须提供覆盖全部父批物料的声明绑定（按子批批号前缀匹配且在合批时点有效），
 * 任一父物料未被覆盖整次 422 回滚；父批全部置为 MERGED。
 */
public record MergeBatchesRequest(
        @NotBlank(message = "commandKey 不能为空") String commandKey,
        @NotBlank(message = "childBatchKey 不能为空") String childBatchKey,
        @NotBlank(message = "childBatchNo 不能为空") String childBatchNo,
        @NotBlank(message = "productCode 不能为空") String productCode,
        @NotNull(message = "requiredTests 不能为空")
        @Size(min = 1, max = 5, message = "requiredTests 必须包含 1～5 个检验项")
        List<@NotBlank(message = "检验项不能为空") String> requiredTests,
        @NotNull(message = "parentKeys 不能为空")
        @Size(min = 2, max = 5, message = "parentKeys 必须包含 2～5 个父批")
        List<@NotBlank(message = "父批 batchKey 不能为空") String> parentKeys,
        @NotNull(message = "bindings 不能为空")
        @Size(min = 1, max = 5, message = "bindings 必须包含 1～5 个声明引用")
        List<@Valid DeclarationRef> bindings
) {
}
