package com.example.starter.film.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 交付冻结请求；只引用母版编号，不读取工作版。
 *
 * @param masterNo  审核通过生成的不可变母版编号
 * @param requestId 幂等请求ID，非空，最长 64
 */
public record DeliveryRequest(
        @NotBlank @Size(max = 64)
        String masterNo,

        @NotBlank @Size(max = 64)
        String requestId
) {
}
