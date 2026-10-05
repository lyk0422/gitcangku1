package com.example.starter.maintenance.domain;

/**
 * 可拆卸部件：以合成编号唯一，可在多台设备间轮换安装；寿命只随拆卸累计，设备保养不重置。
 *
 * @param componentId      部件合成编号，全局唯一
 * @param lifetimeMinutes  寿命上限（分钟），固定正整数
 * @param usedMinutes      已累计寿命（分钟），仅拆卸时按装拆区间差值累加
 * @param version          部件版本号，初始 1，安装/拆卸校验并加一
 */
public record Component(String componentId, long lifetimeMinutes, long usedMinutes, long version) {
}
