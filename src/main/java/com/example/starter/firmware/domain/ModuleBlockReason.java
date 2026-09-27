package com.example.starter.firmware.domain;

/**
 * 模块阻断原因：MISSING 设备清单中缺失该模块（设备版本为 null）；
 * VERSION_TOO_LOW 设备模块版本低于最低要求。
 */
public enum ModuleBlockReason {
    MISSING,
    VERSION_TOO_LOW
}
