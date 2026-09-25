package com.example.starter.api.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 缩窄告知文本地区覆盖请求；新地区集合必须是当前集合的子集。
 */
public record NarrowRegionsRequest(
        @NotEmpty @Size(max = 32) List<@jakarta.validation.constraints.NotBlank String> regions) {

    public NarrowRegionsRequest {
        regions = List.copyOf(regions);
    }
}
