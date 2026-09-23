package com.example.starter.firmware.api;

/**
 * 迁移预览设备项视图：迁移前后队列与指令代次（预览不写数据）。
 */
public record PreviewItemView(String deviceId, long fromCohortId, long toCohortId,
                              int currentGeneration, int newGeneration) {
}
