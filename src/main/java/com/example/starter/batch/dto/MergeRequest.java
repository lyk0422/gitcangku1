package com.example.starter.batch.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 合批请求体。2～5 个当前 RELEASED 且无未处置偏差的父批合并为一个 QUARANTINED 子批；
 * 父批必须属于同一产品编码，任一父批存在未处置偏差时整单失败、不落任何数据。
 */
public record MergeRequest(
        @NotBlank(message = "commandKey 不能为空") String commandKey,
        @NotBlank(message = "childKey 不能为空") String childKey,
        @NotBlank(message = "childBatchNo 不能为空") String childBatchNo,
        @NotNull(message = "parents 不能为空")
        @Size(min = 2, max = 5, message = "parents 必须包含 2～5 个父批")
        List<@NotBlank(message = "父批 batchKey 不能为空") String> parents
) {
}
