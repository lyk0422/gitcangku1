package com.example.starter.firmware.api;

import java.util.List;

/**
 * 发布单的设备不兼容拦截记录列表。
 */
public record IncompatibleListResponse(long releaseId, List<IncompatibleRecordView> records) {
}
