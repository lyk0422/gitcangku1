package com.example.starter.firmware.domain;

/**
 * 发布类型：STANDARD 普通灰度；TOPOLOGY 拓扑波次（绑定设备无向图，按最大非相邻集合分批停机升级）。
 */
public enum ReleaseType {
    STANDARD,
    TOPOLOGY
}
