package com.example.starter.firmware.domain;

/**
 * 设备安装状态：NONE 未确认；SUCCESS 已确认安装成功（不可再迁移）。
 */
public enum InstallStatus {
    NONE,
    SUCCESS
}
