package com.example.starter.calibration.api.dto;

import java.time.Instant;

/**
 * 修订链中的一个版本（只读）。
 *
 * @param id            测量记录 ID
 * @param version       版本号：原始提交为 0
 * @param status        状态：PENDING / RELEASED / REJECTED
 * @param reading       原始读数（十进制字符串）
 * @param note          测量说明；未填写为 null
 * @param computedValue 未舍入计算值（十进制字符串）
 * @param displayValue  显示值，HALF_UP 4 位小数（十进制字符串）
 * @param passed        是否合格（基于未舍入值，含端点）
 * @param predecessorId 前驱测量记录 ID；原始提交为 null
 * @param createdAt     提交时间（UTC）
 */
public record RevisionChainItem(long id, int version, String status, String reading, String note,
                                String computedValue, String displayValue, boolean passed,
                                Long predecessorId, Instant createdAt) {
}
