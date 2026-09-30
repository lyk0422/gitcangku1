package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 备降机场预留申请请求。按优先级（数值越大越高）申请机场与 UTC 左闭右开窗口。
 * 窗口语义非法（start &ge; end）或机场不存在在服务层返回 422；
 * 窗口重叠且无法合法挤出返回 409。
 *
 * @param requestId   写操作全局唯一请求标识，用于幂等重放与替换清单回放
 * @param routeId     申请预留的航线标识
 * @param airportId   申请占用的备降机场标识
 * @param priority    预留优先级，数值越大优先级越高；相同优先级不得互相挤出
 * @param windowStart UTC 窗口开始（含），epoch 毫秒
 * @param windowEnd   UTC 窗口结束（不含），epoch 毫秒，必须大于 windowStart
 */
public record ReservationRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String routeId,
        @NotBlank @Size(max = 64) String airportId,
        @NotNull Integer priority,
        @NotNull Long windowStart,
        @NotNull Long windowEnd) {
}
