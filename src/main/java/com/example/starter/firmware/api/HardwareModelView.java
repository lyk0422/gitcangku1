package com.example.starter.firmware.api;

/**
 * 已知硬件型号登记视图。registered=false 表示型号此前已登记（幂等）。
 */
public record HardwareModelView(String hardwareModel, boolean registered) {
}
