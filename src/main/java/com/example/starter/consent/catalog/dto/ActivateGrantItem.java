package com.example.starter.consent.catalog.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 激活时回传的有效授权确认项。
 *
 * @param subjectKey      主体标识，必须与预览一致
 * @param epoch           授权代次，必须与预览一致
 * @param expectedVersion 预览给出的授权行版本
 */
public record ActivateGrantItem(
        @NotNull String subjectKey,
        @NotNull Integer epoch,
        @NotNull Long expectedVersion) {
}
