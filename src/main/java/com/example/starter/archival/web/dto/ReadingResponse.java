package com.example.starter.archival.web.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 读数登记结果，回显追加的轮次。
 *
 * @param batchNo     批次号
 * @param accessionNo 馆藏号
 * @param roundNo     新追加的轮次序号
 * @param phBefore    处理前 pH
 * @param phAfter     处理后 pH
 * @param reagentLot  药剂批号
 * @param createdAt   登记时间（Asia/Shanghai）
 */
public record ReadingResponse(String batchNo, String accessionNo, int roundNo,
                              BigDecimal phBefore, BigDecimal phAfter,
                              String reagentLot, LocalDateTime createdAt) {
}
