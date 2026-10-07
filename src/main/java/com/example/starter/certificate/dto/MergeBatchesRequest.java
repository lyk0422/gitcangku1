package com.example.starter.certificate.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 合批请求：2～5 个已放行父批合并为一个全新子批；父批数量由服务层校验并返回实际值与要求值。
 */
public record MergeBatchesRequest(
        @NotBlank(message = "commandKey 不能为空") String commandKey,
        @NotBlank(message = "childBatchKey 不能为空") String childBatchKey,
        @NotBlank(message = "childBatchNo 不能为空") String childBatchNo,
        @NotNull(message = "parentKeys 不能为空") List<@NotBlank(message = "父批键不能为空") String> parentKeys
) {
}
