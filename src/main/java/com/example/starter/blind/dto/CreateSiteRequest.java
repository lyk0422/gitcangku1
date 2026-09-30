package com.example.starter.blind.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 创建研究中心请求体。
 *
 * @param leadActorId 中心负责人操作者编号；启用申请必须由此人先确认
 */
public record CreateSiteRequest(
        @NotBlank(message = "leadActorId 不能为空")
        @Size(max = 64, message = "leadActorId 最长 64 字符")
        String leadActorId
) {
}
