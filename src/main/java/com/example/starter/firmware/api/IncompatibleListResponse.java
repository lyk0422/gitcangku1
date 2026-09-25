package com.example.starter.firmware.api;

import java.util.List;

/**
 * 设备拉取不兼容记录列表。
 */
public record IncompatibleListResponse(List<IncompatibleRecordView> records) {
}
