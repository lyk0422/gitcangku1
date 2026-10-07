package com.example.starter.batch.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 批量绑定合格证声明请求。任一声明失效（不存在、已撤回、不在有效期、
 * 物料或批号前缀不匹配）整次回滚，不产生任何绑定。
 */
public record BindCertificatesRequest(
        @NotBlank(message = "commandKey 不能为空") String commandKey,
        @NotNull(message = "bindings 不能为空")
        @Size(min = 1, max = 5, message = "bindings 必须包含 1～5 个声明引用")
        List<@Valid DeclarationRef> bindings
) {
}
