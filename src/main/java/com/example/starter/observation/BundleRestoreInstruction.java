package com.example.starter.observation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * 墓碑恢复指令：对指定待恢复成员给出全部必填字段的来源；恢复与字段裁决在同一事务内完成。
 *
 * @param observationId 待恢复墓碑成员观测标识
 * @param fields        全部必填字段（location/reading/note）的来源列表，不得遗漏或重复
 */
public record BundleRestoreInstruction(
        @NotBlank String observationId,
        @NotEmpty @Valid List<BundleRestoreField> fields) {
}
