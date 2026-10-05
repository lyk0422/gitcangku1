package com.example.starter.maintenance.domain;

/**
 * 可拆卸部件：以合成编号唯一，在多台设备间轮换使用，寿命按装拆区间累计。
 *
 * @param componentId         部件合成编号（全局唯一）
 * @param lifetimeMinutes     寿命上限（分钟），固定正整数，登记后不可修改
 * @param accumulatedMinutes  已累计寿命（分钟）：各已拆卸区间（结束累计 - 起始累计）之和
 * @param version             部件版本号，初始 1，每次安装/拆卸校验并加一
 */
public record Component(String componentId, long lifetimeMinutes,
                        long accumulatedMinutes, long version) {
}
