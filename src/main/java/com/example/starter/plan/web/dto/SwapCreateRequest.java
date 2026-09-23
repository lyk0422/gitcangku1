package com.example.starter.plan.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;

/**
 * 创建容量交换单（预览）请求。
 *
 * <p>requestKey 为激活幂等键；swapKey 为交换单业务键，跨请求全局唯一；
 * 参与项 2～20 个，列表顺序不影响语义（服务端按 scheduleKey 规范化排序）。
 */
public record SwapCreateRequest(
        @NotBlank String requestKey,
        @NotBlank String swapKey,
        @NotNull LocalDate opDate,
        @NotNull @Size(min = 2, max = 20) List<@Valid SwapItemRequest> items) {
}
