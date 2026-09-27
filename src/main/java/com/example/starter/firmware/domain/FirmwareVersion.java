package com.example.starter.firmware.domain;

/**
 * 固件版本登记信息。固件版本一旦登记即纳入签名证书链管理。
 *
 * @param model     固件适用设备型号
 * @param version   固件版本号
 * @param createdBy 登记操作者
 */
public record FirmwareVersion(String model, String version, String createdBy) {
}
