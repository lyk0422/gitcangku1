package com.example.starter.firmware.domain;

/**
 * 固件版本链节点：每个版本至多登记一个直接前置版本，无前置版本为链起点。
 *
 * @param version     固件版本标识
 * @param predecessor 直接前置版本，null 表示版本链起点
 */
public record FirmwareVersion(String version, String predecessor) {
}
