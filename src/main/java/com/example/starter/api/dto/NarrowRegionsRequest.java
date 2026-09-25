package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * 缩窄告知文本地区覆盖请求：新集合必须是当前覆盖的非空子集，仅影响后续发布。
 */
public record NarrowRegionsRequest(
        @NotEmpty List<@NotBlank String> regions) {
}
