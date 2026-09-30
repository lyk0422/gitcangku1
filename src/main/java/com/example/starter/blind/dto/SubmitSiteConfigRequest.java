package com.example.starter.blind.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 中心启用配置版本请求体；每次提交生成一个新版本申请，版本号由服务端递增。
 *
 * @param blocks 区组名额列表，区组号必须从 1 开始连续且不重复
 */
public record SubmitSiteConfigRequest(
        @NotEmpty(message = "blocks 不能为空")
        @Valid
        List<BlockQuotaRequest> blocks
) {

    /**
     * 单个区组的可分配名额（冻结量），非负整数。
     *
     * @param blockNo 区组号，从 1 开始
     * @param quota   该区组可分配名额，0 表示该区组不接受分配
     */
    public record BlockQuotaRequest(
            @NotNull(message = "blockNo 不能为空")
            @Min(value = 1, message = "blockNo 必须从 1 开始")
            @Max(value = 64, message = "区组数量不能超过 64")
            Integer blockNo,

            @NotNull(message = "quota 不能为空")
            @Min(value = 0, message = "quota 不能为负")
            @Max(value = 1_000_000, message = "quota 超出上限")
            Integer quota
) {
    }
}
