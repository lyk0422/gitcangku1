package com.example.starter.observation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 墓碑恢复时单个必填字段的来源：取该观测末个存活版本（BASE）或显式新值（VALUE）。
 *
 * @param field  必填字段名（location/reading/note）
 * @param source 来源：BASE（末个存活版本）或 VALUE（显式新值）
 * @param value  来源为 VALUE 时的显式新值；BASE 时必须为空
 */
public record BundleRestoreField(
        @NotBlank String field,
        @NotBlank String source,
        @Size(max = 1024) String value) {
}
