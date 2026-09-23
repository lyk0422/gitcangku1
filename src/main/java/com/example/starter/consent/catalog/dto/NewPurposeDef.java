package com.example.starter.consent.catalog.dto;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

/**
 * 新用途定义：用途代码全局唯一，处理范围为记录属性取值的非空集合。
 *
 * @param code        新用途代码
 * @param scopeValues 处理范围（记录属性取值集合，合成字符串）
 */
public record NewPurposeDef(
        @NotBlank @Size(max = 32) String code,
        @NotEmpty @Size(max = 256) List<@NotBlank @Size(max = 256) String> scopeValues) {

    /**
     * 构造时做防御性拷贝，避免外部修改。
     */
    public NewPurposeDef {
        scopeValues = List.copyOf(scopeValues);
    }
}
