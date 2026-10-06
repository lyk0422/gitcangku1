package com.example.starter.firmware.domain;

/**
 * 发布类型：GRAY 灰度按比例逐个投放；TOPOLOGY 拓扑波次投放，相邻设备不能同时停机升级。
 */
public enum ReleaseType {
    GRAY,
    TOPOLOGY
}
