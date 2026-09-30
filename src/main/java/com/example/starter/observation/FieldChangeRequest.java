package com.example.starter.observation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 更正附录中的单字段差异：明确给出字段旧值与新值，不允许删除字段（新值不可为空串）。
 *
 * @param field    字段名，仅允许 location / reading / note
 * @param oldValue 字段旧值；读数字段为十进制原文，按数值与前置版本有效值核对
 * @param newValue 字段新值；读数字段为十进制字符串，最多三位小数；禁止为空（不允许删除既有字段）
 */
public record FieldChangeRequest(
        @NotBlank @Pattern(regexp = "location|reading|note", message = "field must be one of location/reading/note")
        String field,
        @NotNull @Size(max = 1024) String oldValue,
        @NotNull @Size(max = 1024) String newValue) {
}
