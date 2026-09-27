package com.example.starter.maintenance.api.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 创建/修订保养模板请求：每次提交生成一个新的不可变模板版本。
 *
 * @param requestId                全局唯一请求标识（幂等键）
 * @param expectedVersion          设备期望版本号，与当前版本不一致时返回 409
 * @param overdueThresholdMinutes  OVERDUE 阈值（分钟），须严格大于保养周期
 * @param items                    规范排序的必检项列表（按提交顺序作为 ordinal，不可为空）
 */
public record SaveTemplateRequest(
        @NotBlank String requestId,
        @NotNull Long expectedVersion,
        @NotNull @Positive Long overdueThresholdMinutes,
        @NotEmpty @Valid List<TemplateItemInput> items) {

    /**
     * 模板必检项输入。
     *
     * @param itemCode  检查项编码，同一请求内不可重复
     * @param itemTitle 检查项名称
     */
    public record TemplateItemInput(
            @NotBlank String itemCode,
            @NotBlank String itemTitle) {
    }
}
