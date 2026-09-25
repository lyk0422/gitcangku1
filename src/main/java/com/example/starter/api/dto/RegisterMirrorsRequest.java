package com.example.starter.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 镜像源登记请求：一次登记 1～3 个镜像，同一制品版本累计不超过 3 个。
 */
public record RegisterMirrorsRequest(
        @NotNull @Size(min = 1, max = 3) List<@Valid MirrorSpec> mirrors) {

    public RegisterMirrorsRequest {
        if (mirrors != null) {
            mirrors = List.copyOf(mirrors);
        }
    }
}
