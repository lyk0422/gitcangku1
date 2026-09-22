package com.example.starter.baggage.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 登记行李请求。
 *
 * @param requestId 全局唯一请求标识（幂等键）
 * @param bagTag    行李牌号，全局唯一
 * @param legIds    有序行程航段（1~5 个、不重复、相邻航段首尾站衔接）
 */
public record RegisterBagRequest(
        @NotBlank(message = "requestId 不能为空") String requestId,
        @NotBlank(message = "bagTag 不能为空") String bagTag,
        @NotNull(message = "legIds 不能为空")
        @Size(min = 1, max = 5, message = "行程航段数量须为 1~5") List<@NotBlank(message = "航段标识不能为空") String> legIds) {
}
