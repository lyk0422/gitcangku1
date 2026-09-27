package com.example.starter.evidence.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 批量增删披露包证物请求。服务端先校验完整最终集合（现有 − 移除 + 新增），
 * 任一证物校验失败则整批不生效，已有包版本不变。仅 FROZEN 包可增删。
 *
 * @param commandKey         幂等命令键
 * @param addEvidenceKeys    待新增证物键列表；NULL 或空表示不新增
 * @param removeEvidenceKeys 待移除证物键列表；NULL 或空表示不移除
 */
public record PackageItemsUpdateRequest(
        @NotBlank @Size(max = 64) String commandKey,
        List<@NotBlank @Size(max = 64) String> addEvidenceKeys,
        List<@NotBlank @Size(max = 64) String> removeEvidenceKeys) {
}
