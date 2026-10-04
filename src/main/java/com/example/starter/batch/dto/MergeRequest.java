package com.example.starter.batch.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 合批请求：将 2～5 个当前可用的 RELEASED 父批合并为一个全新批次。
 * 新批次绑定 declarationKey/declarationVersion 指定的声明版本，
 * 所有父批物料必须与该声明物料一致；任一校验失败整次回滚。
 * 操作人通过 X-Actor-Id 请求头提供。
 */
public record MergeRequest(
        @NotBlank(message = "commandKey 不能为空") String commandKey,
        @NotBlank(message = "targetBatchKey 不能为空") String targetBatchKey,
        @NotBlank(message = "targetBatchNo 不能为空") String targetBatchNo,
        @NotNull(message = "parents 不能为空")
        @Size(min = 2, max = 5, message = "parents 必须包含 2～5 个父批")
        List<@NotBlank(message = "父批 batchKey 不能为空") String> parents,
        @NotBlank(message = "declarationKey 不能为空") String declarationKey,
        @NotNull(message = "declarationVersion 不能为空") Integer declarationVersion
) {
}