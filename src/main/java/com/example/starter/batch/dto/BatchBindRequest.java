package com.example.starter.batch.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 批量绑定请求：任一绑定校验失败整次回滚，不产生半成品数据，失败不占 commandKey。
 */
public record BatchBindRequest(
        @NotBlank(message = "commandKey 不能为空") String commandKey,
        @NotNull(message = "bindings 不能为空")
        @Size(min = 1, max = 10, message = "bindings 必须包含 1～10 条绑定")
        List<@Valid BindItem> bindings
) {

    /**
     * 单条绑定项：批次业务键 + 目标声明版本。
     */
    public record BindItem(
            @NotBlank(message = "batchKey 不能为空") String batchKey,
            @NotBlank(message = "declarationKey 不能为空") String declarationKey,
            @NotNull(message = "declarationVersion 不能为空") Integer declarationVersion
    ) {
    }
}