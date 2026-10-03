package com.example.starter.repo;

/**
 * 气象区域最低标准窗口（UTC 时段，左闭右开）。
 *
 * @param regionId       所属气象区域标识
 * @param windowStart    时段起点（epoch 毫秒，UTC，左闭）
 * @param windowEnd      时段终点（epoch 毫秒，UTC，右开）
 * @param visibilityMinM 能见度最低标准（米，非负）
 * @param cloudBaseMinM  云底最低标准（米，非负）
 * @param dutyVersion    该窗口生效时的气象值班版本
 */
public record MinimaWindowPo(String regionId, long windowStart, long windowEnd,
                             int visibilityMinM, int cloudBaseMinM, long dutyVersion) {
}