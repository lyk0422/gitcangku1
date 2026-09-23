package com.example.starter.consent.catalog.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 批量查询请求：整批固定一个 catalogGeneration（由令牌携带），不能混读旧新用途。
 *
 * @param token 查询代次令牌
 * @param items 查询条目，用途必须全部处于令牌所固定的目录代次中
 */
public record BatchQueryRequest(
        @NotBlank @Size(max = 64) String token,
        @NotEmpty @Size(max = 500) @Valid List<@Valid BatchQueryItem> items) {

    public BatchQueryRequest {
        items = List.copyOf(items);
    }
}
