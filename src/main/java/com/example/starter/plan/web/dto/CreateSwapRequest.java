package com.example.starter.plan.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;

/**
 * 创建容量交换单（仅预览）请求：同一运营日内 2～20 个已发布计划的闭环容量交换。
 * swapKey 为交换单业务键，跨请求全局唯一；交换项顺序不影响语义。
 */
public record CreateSwapRequest(
        @NotBlank String swapKey,
        @NotNull LocalDate opDate,
        @NotNull @Size(min = 2, max = 20) List<@Valid SwapItemRequest> items) {
}
