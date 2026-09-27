package com.example.starter.disclosure.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 创建案件披露包请求。证物集合按证物键去重升序规范化；截至时刻为 UTC，
 * 每件证物的链连续性、位置、封签、待核验与借还状态均按该时刻之前（含）重放判定。
 *
 * @param commandKey   幂等命令键
 * @param caseKey      案件键
 * @param cutoffAt     披露截至时刻（UTC）
 * @param evidenceKeys 证物业务键集合（至少一件，服务端规范化排序）
 */
public record PackageCreateRequest(
        @NotBlank @Size(max = 64) String commandKey,
        @NotBlank @Size(max = 64) String caseKey,
        @NotNull LocalDateTime cutoffAt,
        @NotEmpty List<@NotBlank @Size(max = 64) String> evidenceKeys) {
}
