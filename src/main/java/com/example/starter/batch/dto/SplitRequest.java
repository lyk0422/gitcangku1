package com.example.starter.batch.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 批次拆分请求：仅当前 RELEASED 批次可一次拆成 2～5 个全新子批；
 * 子批键重复或已存在时整次请求 409，父批保持不变。
 */
public record SplitRequest(
        @NotBlank(message = "commandKey 不能为空") String commandKey,
        @NotNull(message = "children 不能为空")
        @Size(min = 2, max = 5, message = "一次拆分必须包含 2～5 个子批")
        List<@Valid SplitChildRequest> children
) {
}
