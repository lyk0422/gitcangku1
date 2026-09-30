package com.example.starter.blind.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 配置版本内单个区组的名额。
 *
 * @param blockCode 区组编号，1~16 位字母数字下划线短横线（如 A），仅为中心内区组标识，不表示处理组
 * @param quota     该区组可分配名额，1~100000，单位：人次
 */
public record SiteBlockConfigRequest(
        @NotBlank(message = "blockCode 不能为空")
        @Size(max = 16, message = "blockCode 最长 16 个字符")
        @Pattern(regexp = "[A-Za-z0-9_-]+", message = "blockCode 仅允许字母、数字、下划线与短横线")
        String blockCode,

        @NotNull(message = "quota 不能为空")
        @Min(value = 1, message = "quota 取值范围 1~100000")
        @Max(value = 100000, message = "quota 取值范围 1~100000")
        Integer quota
) {
}
