package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 备降机场预留申请。窗口为 UTC 左闭右开区间 [windowStart, windowEnd)；
 * priority 数值越大优先级越高。windowStart &gt;= windowEnd 或机场不存在返回 422。
 *
 * @param airportId   备降机场标识
 * @param routeId     申请预留的航线标识
 * @param priority    优先级，数值越大越高；高优先级可挤出未起飞的低优先级，同级不得互挤
 * @param windowStart 窗口起点，epoch 毫秒（UTC），左闭
 * @param windowEnd   窗口终点，epoch 毫秒（UTC），右开
 * @param requestId   写操作全局唯一请求标识，用于幂等重放
 */
public record ReservationCreateRequest(
        @NotBlank @Size(max = 64) String airportId,
        @NotBlank @Size(max = 64) String routeId,
        @NotNull Integer priority,
        @NotNull Long windowStart,
        @NotNull Long windowEnd,
        @NotBlank @Size(max = 64) String requestId) {
}
