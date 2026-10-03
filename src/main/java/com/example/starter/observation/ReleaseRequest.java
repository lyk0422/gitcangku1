package com.example.starter.observation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 到期发布请求：禁运到期后首次发布生成不可变披露快照；同簇重复发布返回既有快照。
 *
 * @param requestId 全局唯一请求标识（幂等去重键）
 * @param operator  操作者标识
 */
public record ReleaseRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 128) String operator) {
}