package com.example.starter.firmware.domain;

/**
 * 阻断明细：缺失或低版本模块，按模块名升序稳定排序。
 *
 * @param module        缺失或低版本模块名
 * @param deviceVersion 设备当前模块版本，未安装为 null
 * @param minVersion    最低要求版本
 */
public record ModuleBlockItem(String module, String deviceVersion, String minVersion) {
}
