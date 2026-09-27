package com.example.starter.firmware.domain;

import java.util.List;

/**
 * 设备登记信息。
 *
 * @param deviceId       设备唯一标识
 * @param model          设备型号，登记后不可修改
 * @param currentVersion 设备当前固件版本
 * @param bucketNo       灰度分桶号，取值0~99，登记后不可修改
 * @param labels         规范化标签集合（已 trim、去重、字典序排序，不可变）；无标签为空列表。
 *                       标签后续修改不追溯改变任何既有发布单的目标快照
 */
public record Device(String deviceId, String model, String currentVersion, int bucketNo, List<String> labels) {

    public Device(String deviceId, String model, String currentVersion, int bucketNo) {
        this(deviceId, model, currentVersion, bucketNo, List.of());
    }
}
