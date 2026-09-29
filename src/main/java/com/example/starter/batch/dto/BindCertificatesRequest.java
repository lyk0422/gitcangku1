package com.example.starter.batch.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 批量绑定合格证声明请求：一次为 1～20 个批次分别指定声明版本。
 * 多对象写入先校验全部最终状态，再在同一事务提交；任一绑定失效（声明撤回/过期/
 * 物料不匹配/批号不命中前缀）整次回滚，不产生任何半成品绑定。
 */
public record BindCertificatesRequest(
        @NotBlank(message = "commandKey 不能为空") String commandKey,
        @NotEmpty(message = "bindings 不能为空")
        @Size(max = 20, message = "bindings 最多包含 20 个绑定")
        List<@Valid BindingSpec> bindings
) {

    /**
     * 单条绑定：批次业务键 + 声明业务键（指向具体版本）。
     */
    public record BindingSpec(
            @NotBlank(message = "batchKey 不能为空") String batchKey,
            @NotBlank(message = "declarationKey 不能为空") String declarationKey
    ) {
    }
}
