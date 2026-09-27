package com.example.starter.disclosure.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 批量增删证物修订请求：以目标包为基础，并入新增键、剔除删除键得到完整最终集合，
 * 先对最终集合按新截至时刻整包校验，任一失败则不生成修订版本且不改变已有包。
 *
 * @param commandKey         幂等命令键
 * @param cutoffAt           修订包披露截至时刻（UTC）
 * @param addEvidenceKeys    待新增证物键；null 表示不新增；不得与基础包成员重复
 * @param removeEvidenceKeys 待剔除证物键；null 表示不剔除；必须是基础包成员
 */
public record PackageAmendRequest(
        @NotBlank @Size(max = 64) String commandKey,
        @NotNull LocalDateTime cutoffAt,
        List<@NotBlank @Size(max = 64) String> addEvidenceKeys,
        List<@NotBlank @Size(max = 64) String> removeEvidenceKeys) {
}
