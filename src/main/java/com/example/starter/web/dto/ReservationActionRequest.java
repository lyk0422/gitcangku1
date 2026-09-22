package com.example.starter.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 预占终态操作（确认/取消）请求。
 */
public class ReservationActionRequest {

    /** 客户端提供的全局唯一请求编号，用于写操作幂等。 */
    @NotBlank
    @Size(max = 64)
    private String requestId;

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }
}
