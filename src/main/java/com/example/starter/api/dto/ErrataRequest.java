package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 放行后勘误请求：只追加，不修改已冻结交付数据。 */
public record ErrataRequest(@NotBlank @Size(max = 1000) String note) {
}