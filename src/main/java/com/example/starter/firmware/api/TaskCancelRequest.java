package com.example.starter.firmware.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 任务取消请求：INSTALLING 转 CANCEL_REQUESTED 并生成取消代次（仍占槽）；
 * 未开始的 PENDING 任务直接取消为 CANCELLED。
 * operator 为非空操作者；expectedVersion 为期望的发布单版本，不一致返回 409。
 */
public record TaskCancelRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String operator,
        @NotNull @Min(1) Integer expectedVersion) {
}
