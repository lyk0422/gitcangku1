package com.example.starter.blind.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 区组扩容请求体。
 * 追加区组按提交顺序处理：每个区组固定 4 个席位，且必须恰好含两个 A、两个 B 处理代码；
 * 区组顺序属于请求参数，换序重放视为异参。
 *
 * @param extensionKey    扩容幂等键，全局唯一；同键同参重放首次结果
 * @param expectedVersion 期望的扩容前实验版本号，与库内不一致返回 409
 * @param blocks          追加区组列表，1～4 个，顺序即区组追加顺序
 */
public record ExtendBlocksRequest(
        @NotNull(message = "extensionKey 不能为空")
        String extensionKey,

        @NotNull(message = "expectedVersion 不能为空")
        Integer expectedVersion,

        @NotEmpty(message = "至少追加 1 个区组")
        @Size(max = 4, message = "单次最多追加 4 个区组")
        @Valid
        List<BlockSeats> blocks
) {

    /**
     * 单个追加区组的 4 个席位处理代码；按席位顺序必须恰好两个 A、两个 B。
     *
     * @param treatments 4 个席位的处理代码，仅允许 A/B，长度必须为 4
     */
    public record BlockSeats(
            @NotNull(message = "区组席位处理代码不能为空")
            @Size(min = 4, max = 4, message = "每个区组固定 4 个席位")
            List<@NotNull String> treatments
    ) {
    }
}
