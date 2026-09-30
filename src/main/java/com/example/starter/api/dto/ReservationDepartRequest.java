package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 预留起飞登记请求。只有仍 ACTIVE（未被挤出）的预留可以起飞；
 * 已起飞预留占用保留且后续不可被任何申请挤出。
 *
 * @param reservationId 预留唯一标识
 * @param requestId     写操作全局唯一请求标识，用于幂等重放
 */
public record ReservationDepartRequest(
        @NotBlank @Size(max = 64) String reservationId,
        @NotBlank @Size(max = 64) String requestId) {
}
