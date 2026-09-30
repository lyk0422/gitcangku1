package com.example.starter.blind.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 提交中心配置版本请求体；区组编号与名额在版本提交时固定，版本内容不可变。
 *
 * @param blocks 区组名额清单，至少 1 个区组，至多 100 个，同一请求内区组编号不得重复
 */
public record SubmitSiteConfigRequest(
        @NotEmpty(message = "blocks 不能为空")
        @Size(max = 100, message = "单个配置版本最多 100 个区组")
        @Valid
        List<SiteBlockConfigRequest> blocks
) {
}
