package com.example.starter.race.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 封榜请求。
 *
 * @param requestId       全局唯一请求ID
 * @param expectedVersion 期望的赛事当前版本，不一致返回409
 */
public record SealRaceRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotNull Long expectedVersion) {
}
