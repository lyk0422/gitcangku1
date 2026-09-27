package com.example.starter.firmware.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 任务撤销请求：仅未安装任务（接收中/完整性失败/已判定可安装但未安装）可撤销。
 */
public record CancelTaskRequest(@NotBlank @Size(max = 64) String requestId) {
}
