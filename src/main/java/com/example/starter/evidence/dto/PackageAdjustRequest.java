package com.example.starter.evidence.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 披露包批量增删证物请求。在基准包集合上先增后删得到完整最终集合并整体校验，
 * 任一证物失败则不生成修订包、不改变已有包版本。
 *
 * @param commandKey        幂等命令键
 * @param addEvidenceKeys   新增证物业务键集合（可为空）
 * @param removeEvidenceKeys 删除证物业务键集合（可为空）
 */
public record PackageAdjustRequest(
        @NotBlank @Size(max = 64) String commandKey,
        @NotNull List<@NotBlank @Size(max = 64) String> addEvidenceKeys,
        @NotNull List<@NotBlank @Size(max = 64) String> removeEvidenceKeys) {
}
