package com.example.starter.spectrum.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 建网请求：一次定义 1~20 个唯一台站及有向干扰边；网络配置创建后不可修改。
 */
public record CreateNetworkRequest(
        @NotBlank(message = "networkId 不能为空")
        @Size(max = 64, message = "networkId 长度不能超过64")
        String networkId,
        @Size(max = 128, message = "name 长度不能超过128")
        String name,
        @NotEmpty(message = "stations 至少包含1个台站")
        @Valid
        List<StationInput> stations,
        @Valid
        List<EdgeInput> edges
) {
    /**
     * 台站定义：干扰预算为 0~1000 的整数。
     */
    public record StationInput(
            @NotBlank(message = "stationId 不能为空")
            @Size(max = 64, message = "stationId 长度不能超过64")
            String stationId,
            @NotNull(message = "interferenceBudget 不能为空")
            @Min(value = 0, message = "interferenceBudget 范围为0~1000")
            @Max(value = 1000, message = "interferenceBudget 范围为0~1000")
            Integer interferenceBudget
    ) {
    }

    /**
     * 有向干扰边定义：from->to 同频干扰量 0~1000；禁止自环与重复边。
     */
    public record EdgeInput(
            @NotBlank(message = "fromStationId 不能为空")
            String fromStationId,
            @NotBlank(message = "toStationId 不能为空")
            String toStationId,
            @NotNull(message = "interference 不能为空")
            @Min(value = 0, message = "interference 范围为0~1000")
            @Max(value = 1000, message = "interference 范围为0~1000")
            Integer interference
    ) {
    }
}
