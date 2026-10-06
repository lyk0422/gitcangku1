package com.example.starter.firmware.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 拓扑无向边请求：a、b 为相邻设备ID，无向（a-b 与 b-a 等价），不允许自环。
 */
public record TopologyEdgeRequest(
        @NotBlank @Size(max = 64) String a,
        @NotBlank @Size(max = 64) String b) {
}
