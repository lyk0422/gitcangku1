package com.example.starter.maintenance.domain;

/**
 * 可拆卸部件：以合成编号唯一，在多台设备间轮换使用；寿命按装拆区间累计，与设备保养无关。
 *
 * @param componentId        部件合成编号，全局唯一
 * @param lifespanMinutes    寿命上限（分钟），正整数，登记后不可修改
 * @param accumulatedMinutes 已结算累计分钟：各已拆卸区间（拆卸累计-安装累计）之和
 * @param version            部件版本号，初始 1，每次安装/拆卸校验并加一
 */
public record Component(String componentId, long lifespanMinutes, long accumulatedMinutes, long version) {
}