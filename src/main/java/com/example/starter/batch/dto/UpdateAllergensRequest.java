package com.example.starter.batch.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 保存批次过敏原请求。过敏原集合在服务端规范化：逐项 trim、丢弃空白项、去重并按字典序稳定排序；
 * 空集合表示该批次不含任何登记过敏原。
 */
public record UpdateAllergensRequest(
        @NotBlank(message = "commandKey 不能为空") String commandKey,
        @NotNull(message = "allergens 不能为 null，不含过敏原请传空数组")
        @Size(max = 20, message = "allergens 至多包含 20 个过敏原")
        List<@NotBlank(message = "过敏原名称不能为空") String> allergens
) {
}
