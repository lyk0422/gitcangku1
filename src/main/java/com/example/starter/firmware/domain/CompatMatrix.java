package com.example.starter.firmware.domain;

import java.util.List;

/**
 * 固件硬件兼容矩阵当前版本。
 *
 * @param firmwareVersion 固件版本（发布单 to_version）
 * @param version         矩阵版本号，从1开始，仅当允许型号集合内容变化时加一；换序不产生新版本
 * @param models          允许的硬件型号集合（按字典序）；空列表表示兼容全部硬件型号
 */
public record CompatMatrix(String firmwareVersion, int version, List<String> models) {
}
