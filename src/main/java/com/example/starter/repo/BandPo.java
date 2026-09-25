package com.example.starter.repo;

/**
 * 区域高度带持久化记录（左闭右开，单位米）。
 *
 * @param zoneId    所属禁飞区唯一标识
 * @param bandLower 高度带下限（含）
 * @param bandUpper 高度带上限（不含），bandLower < bandUpper
 * @param capacity  同时容量，1~50，只允许上调
 */
public record BandPo(String zoneId, int bandLower, int bandUpper, int capacity) {
}
