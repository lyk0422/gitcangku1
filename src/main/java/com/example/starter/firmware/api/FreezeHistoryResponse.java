package com.example.starter.firmware.api;

import java.util.List;

/**
 * 单个版本的冻结记录与审计流水视图（只读）。current 为当前生效冻结，无生效冻结时为 null；
 * records 按时间顺序包含该版本全部冻结区间（含已恢复），events 为只增审计流水。
 */
public record FreezeHistoryResponse(String version, FreezeView current, List<FreezeView> records,
                                    List<FreezeEventView> events) {
}
