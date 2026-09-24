package com.example.starter.blind.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 区组扩容请求体（盲法区组扩容）。
 *
 * @param extensionKey    全局唯一扩容键，同键同参重放首次结果；失败不占键
 * @param expectedVersion 期望实验版本（扩容前版本）；与当前版本不符返回 409
 * @param blocks          1～4 个追加区组；每个区组 4 个席位，按提交顺序两个 A 两个 B
 */
public record ExtendBlocksRequest(
        @NotNull(message = "extensionKey 不能为空")
        String extensionKey,

        @NotNull(message = "expectedVersion 不能为空")
        Integer expectedVersion,

        @NotNull(message = "blocks 不能为空")
        @Size(min = 1, max = 4, message = "每次追加区组数量必须在 1~4 之间")
        @Valid
        List<ExtensionBlockRequest> blocks
) {
}
