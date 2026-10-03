package com.example.starter.certificate.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 合批请求体：将 2～5 个当前可用的 RELEASED 父批合并为一个全新子批，
 * 并重新校验全部父批物料与批号是否被目标声明版本覆盖。操作者由 X-Actor-Id 请求头给出。
 */
public record MergeRequest(
        @NotBlank(message = "commandKey 不能为空") String commandKey,
        @NotNull(message = "parentBatchKeys 不能为空")
        @Size(min = 2, max = 5, message = "parentBatchKeys 必须包含 2～5 个父批")
        List<@NotBlank(message = "父批 batchKey 不能为空") String> parentBatchKeys,
        @NotBlank(message = "childBatchKey 不能为空") String childBatchKey,
        @NotBlank(message = "childBatchNo 不能为空") String childBatchNo,
        @NotBlank(message = "declarationKey 不能为空") String declarationKey,
        @Min(value = 1, message = "declarationVersion 必须不小于 1") int declarationVersion
) {
}