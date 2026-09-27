package com.example.starter.maintenance.api.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

/**
 * 保养模板修订请求：以完整检查项列表替换当前模板，生成递增且不可变的新版本。
 * 列表顺序即检查项的规范排序位置（从 1 开始）。
 *
 * @param requestId        全局唯一请求标识（幂等键）
 * @param expectedVersion  设备期望版本号，与当前版本不一致时返回 409
 * @param items            完整检查项列表（按规范排序），不可为空
 */
public record TemplateRevisionRequest(
        @NotBlank String requestId,
        @NotNull Long expectedVersion,
        @NotEmpty @Valid List<TemplateItemInput> items) {

    /**
     * 模板检查项输入。
     *
     * @param itemCode  检查项编码，同次修订内不可重复
     * @param itemName  检查项名称
     * @param required  是否必检项；仅必检项纳入完工门禁
     */
    public record TemplateItemInput(
            @NotBlank String itemCode,
            @NotBlank String itemName,
            @NotNull Boolean required) {
    }
}
