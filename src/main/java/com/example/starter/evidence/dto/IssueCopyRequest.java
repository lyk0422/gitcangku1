package com.example.starter.evidence.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

/**
 * 签发阅检副本请求。仅当前保管人可签发；证物须 SEALED 且已登记数字摘要与合成文本。
 * 有效区间为 UTC、左闭右开、最长一小时；validFrom 缺省时取服务端当前 UTC 时刻。
 *
 * @param requestId       幂等请求键
 * @param reviewerId      阅检人，仅本人可在有效期内读取副本
 * @param validFrom       UTC 有效期起点（左闭）；null 表示取服务端当前时刻
 * @param validTo         UTC 有效期终点（右开），须晚于起点且区间不超过一小时
 * @param expectedVersion 期望的证物版本号；null 表示不校验，提供时与当前版本不一致返回 409
 */
public record IssueCopyRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String reviewerId,
        LocalDateTime validFrom,
        @NotNull LocalDateTime validTo,
        Long expectedVersion) {
}