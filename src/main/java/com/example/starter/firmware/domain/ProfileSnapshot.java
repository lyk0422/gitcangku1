package com.example.starter.firmware.domain;

import java.util.List;

/**
 * 设备硬件画像的一个不可变版本快照。画像更新只追加新版本，历史版本不可改，
 * 已下发任务持续引用其创建时冻结的版本。
 *
 * @param id        画像版本记录ID
 * @param deviceId  设备ID
 * @param version   画像版本号，从1开始，只增不改
 * @param model     画像登记型号；null 表示该版本画像缺少型号字段，门禁按不兼容处理
 * @param memoryMb  画像登记运行内存，单位MB；null 表示缺少内存字段，门禁按不兼容处理
 * @param tags      该版本具备的能力标签，按字典序保存
 */
public record ProfileSnapshot(long id, String deviceId, int version, String model, Integer memoryMb,
                              List<String> tags) {
}
