package com.example.starter.firmware.domain;

/**
 * 固件版本登记信息。
 *
 * @param id          固件版本ID
 * @param model       适用设备型号
 * @param version     固件版本号，同型号内唯一
 * @param packageUri  固件包下载地址（合成元数据）
 */
public record FirmwareVersion(long id, String model, String version, String packageUri) {
}
