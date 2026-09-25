package com.example.starter.observation;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 撤销观测更正附页请求：只允许撤销当前最新有效附页，撤销后恢复上一个有效版本。
 *
 * @param requestId   全局唯一请求标识（幂等去重键）
 * @param corrVersion 待撤销的附页版本号，必须是当前最新有效附页
 * @param operator    执行撤销的操作者标识
 */
public record CorrigendumRevokeRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotNull @Min(1) Integer corrVersion,
        @NotBlank @Size(max = 128) String operator) {
}
