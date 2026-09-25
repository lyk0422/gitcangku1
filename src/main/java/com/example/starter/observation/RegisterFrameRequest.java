package com.example.starter.observation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 登记坐标基准版本请求：携带公开的固定偏移参数，登记后参数不可改写。
 *
 * @param requestId    全局唯一请求标识（幂等去重键）
 * @param frameVersion 坐标基准版本标识
 * @param offsetLatDeg 纬度固定偏移量（度，正数向北）
 * @param offsetLonDeg 经度固定偏移量（度，正数向东）
 */
public record RegisterFrameRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 64) String frameVersion,
        @NotNull Double offsetLatDeg,
        @NotNull Double offsetLonDeg) {
}
