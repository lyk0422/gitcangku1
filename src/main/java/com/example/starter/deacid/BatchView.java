package com.example.starter.deacid;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 批次查询视图。
 *
 * @param batchId   批次主键
 * @param batchNo   批次编号
 * @param status    批次状态：IN_PROGRESS=进行中，SEALED=已封存（只读）
 * @param members   批内档案页馆藏号列表
 * @param createdAt 创建时间，Asia/Shanghai
 * @param sealedAt  封存时间，未封存为 null
 */
public record BatchView(long batchId, String batchNo, String status, List<String> members,
                        LocalDateTime createdAt, LocalDateTime sealedAt) {
}