package com.example.starter.blind.dto;

/**
 * 单个区组的剩余额度视图：分配方视角，只能看到还可分配多少。
 *
 * @param blockCode 区组编号，不表示处理组
 * @param remaining 剩余可分配名额，单位：人次，最小为 0
 */
public record BlockRemainingView(String blockCode, int remaining) {
}
