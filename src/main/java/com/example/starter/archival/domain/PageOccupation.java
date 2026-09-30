package com.example.starter.archival.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 页面处理占用。page_id 即主键，数据库唯一约束保证同一页同一时刻只被一个进行中批次占用。
 *
 * @param pageId    被占用的档案页ID
 * @param batchId   占用该页的批次ID
 * @param heldSince 占用开始时间（Asia/Shanghai）
 */
public record PageOccupation(Long pageId, Long batchId, LocalDateTime heldSince) {
}
