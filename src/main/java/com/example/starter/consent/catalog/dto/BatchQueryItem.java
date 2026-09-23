package com.example.starter.consent.catalog.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 批量查询单条目。
 *
 * @param subjectKey 主体标识
 * @param purpose    用途代码
 * @param recordKey  记录键
 */
public record BatchQueryItem(
        @NotBlank @Size(max = 128) String subjectKey,
        @NotBlank @Size(max = 32) String purpose,
        @NotBlank @Size(max = 128) String recordKey) {
}
