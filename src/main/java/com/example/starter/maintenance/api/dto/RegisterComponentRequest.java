package com.example.starter.maintenance.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 登记部件请求。寿命分钟登记后不可修改。
 *
 * @param requestId        全局唯一请求标识（幂等键）
 * @param operator         操作者标识，计入幂等指纹
 * @param componentId      部件合成编号，全局唯一
 * @param lifespanMinutes  寿命上限（分钟），正整数
 */
public record RegisterComponentRequest(
        @NotBlank String requestId,
        @NotBlank String operator,
        @NotBlank String componentId,
        @NotNull @Positive Long lifespanMinutes) {
}