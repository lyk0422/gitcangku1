package com.example.starter.evidence.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 迁移撤销请求。仅首人确认人可撤销，且迁移单仍处于待第二人确认状态。
 *
 * @param commandKey 幂等命令键
 * @param moveKey    迁移业务键
 */
public record MoveCancelRequest(
        @NotBlank @Size(max = 64) String commandKey,
        @NotBlank @Size(max = 64) String moveKey) {
}
