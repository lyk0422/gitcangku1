package com.example.starter.certificate.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 批量绑定合格证请求：1～10 条绑定；任一失效或不匹配则整次回滚，不产生半成品。
 */
public record BindCertificatesRequest(
        @NotBlank(message = "commandKey 不能为空") String commandKey,
        @NotNull(message = "bindings 不能为空")
        @Size(min = 1, max = 10, message = "bindings 必须包含 1～10 条绑定")
        List<@Valid BindingSpec> bindings
) {
    /**
     * 单条绑定规格：批次 + 目标声明版本。
     */
    public record BindingSpec(
            @NotBlank(message = "batchKey 不能为空") String batchKey,
            @NotBlank(message = "declarationKey 不能为空") String declarationKey,
            @NotNull(message = "declarationVersion 不能为空")
            @Min(value = 1, message = "declarationVersion 必须 >= 1") Integer declarationVersion
    ) {
    }
}
