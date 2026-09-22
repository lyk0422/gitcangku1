package com.example.starter.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 批准揭盲申请请求；必须由非申请人的 REVIEWER 执行。
 *
 * @param requestId 全局唯一写操作请求编号
 */
public record ApproveUnblindRequest(
        @NotBlank String requestId
) {
}
