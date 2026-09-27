package com.example.starter.firmware.api;

import java.util.List;

/**
 * 设备标签视图：标签按规范化字典序升序返回，无标签为空列表。
 */
public record DeviceTagsView(String deviceId, List<String> tags) {
}
