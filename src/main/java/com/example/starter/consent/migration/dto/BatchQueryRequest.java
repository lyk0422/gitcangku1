package com.example.starter.consent.migration.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 批量查询请求：必须携带一个已签发的查询代次，整批固定同一目录代次，不允许混读旧新用途。
 *
 * @param queryGeneration 查询代次号；其固定的目录代次必须仍为最新，否则整批拒绝
 * @param items           查询条目（建议不超过 100 条）
 */
public record BatchQueryRequest(
        @NotNull Long queryGeneration,
        @NotNull @Valid @Size(min = 1, max = 100) List<BatchQueryItem> items) {

    /**
     * 单条查询条目。
     */
    public record BatchQueryItem(
            @NotBlank @Size(max = 128) String subjectKey,
            @NotBlank @Size(max = 64) String purpose,
            @NotBlank @Size(max = 128) String recordKey) {
    }
}
