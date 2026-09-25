package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 告知文本版本登记请求：textKey 与正整数 version 联合唯一，初始状态 DRAFT。
 */
public record NoticeTextRequest(
        @NotBlank String textKey,
        @Positive int version,
        @NotBlank @Size(max = 2048) String content,
        @NotEmpty List<@NotBlank String> regions) {
}
