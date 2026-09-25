package com.example.starter.firmware.api;

import java.util.List;

/**
 * 已知硬件型号目录列表。
 */
public record HardwareModelListResponse(List<String> hardwareModels) {
}
