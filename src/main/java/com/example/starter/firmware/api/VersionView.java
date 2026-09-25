package com.example.starter.firmware.api;

/**
 * 固件版本视图：版本标识与直接前置版本（null 表示链起点）。
 */
public record VersionView(String version, String predecessor) {
}
