package com.example.starter.firmware.api;

import java.util.List;

/**
 * 标签变更历史查询响应。records 按变更记录ID升序（提交顺序），稳定排序。
 */
public record TagHistoryResponse(List<TagChangeRecordView> records) {
}
