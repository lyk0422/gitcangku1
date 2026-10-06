package com.example.starter.firmware.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 拓扑无向邻接边：deviceA 与 deviceB 互为相邻，两端必须都在绑定设备集合内，不允许自环。
 */
public record DeviceEdge(
        @NotBlank @Size(max = 64) String deviceA,
        @NotBlank @Size(max = 64) String deviceB) {
}
