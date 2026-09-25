package com.example.starter.batch.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 召回解除批准请求体；审批人通过 X-Actor-Id 请求头提供，必须与申请指定审批人一致。
 */
public record ApproveReleaseRequest(
        @NotBlank(message = "commandKey 不能为空") String commandKey
) {
}
