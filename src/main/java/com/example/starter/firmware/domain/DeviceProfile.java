package com.example.starter.firmware.domain;

import java.util.Set;

/**
 * 设备硬件画像。型号、内存、能力标签均可为 null，表示缺少该画像字段，
 * 投放筛选时按不兼容处理并给出原因。更新只影响后续新任务，不追改已下发任务。
 *
 * @param deviceId       设备ID，对应 device 表
 * @param model          画像型号，null 表示缺少该字段
 * @param memoryMb       画像内存，单位MB，null 表示缺少该字段
 * @param tags           能力标签集合，null 表示缺少该字段
 * @param profileVersion 画像版本，从1开始，每次更新加一；任务只引用一个完整画像版本
 */
public record DeviceProfile(String deviceId, String model, Integer memoryMb, Set<String> tags,
                            int profileVersion) {
}
