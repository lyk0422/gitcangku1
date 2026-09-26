package com.example.starter.batch.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 批量封箱请求：一次提交 1～100 个封箱；任一标签校验失败整次回滚，不留下部分占用。
 */
public record SealCartonsRequest(
        @NotBlank(message = "commandKey 不能为空") String commandKey,
        @NotNull(message = "cartons 不能为空")
        @Size(min = 1, max = 100, message = "cartons 必须包含 1～100 个封箱")
        List<@NotNull(message = "封箱不能为空") CartonSpec> cartons
) {
    /**
     * 单个封箱：标签号必须落在批次号段内，数量为正整数。
     */
    public record CartonSpec(
            @NotBlank(message = "cartonKey 不能为空") String cartonKey,
            @NotNull(message = "labelNo 不能为空") Long labelNo,
            @NotNull(message = "quantity 不能为空")
            @Min(value = 1, message = "quantity 必须为正整数") Integer quantity
    ) {
    }
}
