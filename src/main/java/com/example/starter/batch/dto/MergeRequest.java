package com.example.starter.batch.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 合批请求体：将 2～5 个当前可用（RELEASED）父批合为一个全新子批。
 * parentBatchKeys 为集合语义：请求内不得重复，服务端排序去重后作为幂等指纹，换序视为同参。
 * cleanGeneration 为本次合批声明使用的生产线清洁代次，所有父批当前代次必须与其完全一致。
 */
public record MergeRequest(
        @NotBlank(message = "commandKey 不能为空") String commandKey,
        @NotNull(message = "parentBatchKeys 不能为空")
        @Size(min = 2, max = 5, message = "parentBatchKeys 必须包含 2～5 个父批")
        List<@NotBlank(message = "父批 batchKey 不能为空") String> parentBatchKeys,
        @NotBlank(message = "childBatchKey 不能为空") String childBatchKey,
        @NotBlank(message = "childBatchNo 不能为空") String childBatchNo,
        @NotNull(message = "cleanGeneration 不能为空")
        @Positive(message = "cleanGeneration 必须为正整数") Integer cleanGeneration
) {
}
