package com.example.starter.firmware.domain;

/**
 * 固件版本链登记项。前置链接构成从基础版本出发的有向无环链。
 *
 * @param version             固件版本标识，登记后不可修改
 * @param predecessorVersion  直接前置版本（至多一个），null 表示版本链起点
 */
public record FirmwareVersion(String version, String predecessorVersion) {
}
