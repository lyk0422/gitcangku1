package com.example.starter.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;

/**
 * 新建花序请求。
 *
 * @param code 花序业务编号，全局唯一
 * @param firstOpenAt 首次开放时刻（UTC，ISO-8601），24 小时授粉窗口起点
 */
public record CreateInflorescenceRequest(
        @NotBlank String code,
        @NotNull Instant firstOpenAt) {
}