package com.example.starter.firmware.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 任务取消请求：PENDING 直接取消；INSTALLING 转 CANCEL_REQUESTED 并生成新取消代次（保留安装槽）。
 * expectedVersion 为期望的发布单版本号，用于乐观并发校验。
 */
public record TaskCancelRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String operator,
        @NotNull Integer expectedVersion) {
}
