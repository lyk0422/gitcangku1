package com.example.starter.archival.web.dto;

import java.time.LocalDateTime;

/**
 * 档案页登记结果。
 *
 * @param accessionNo 馆藏号
 * @param createdAt   登记时间（Asia/Shanghai）
 */
public record PageResponse(String accessionNo, LocalDateTime createdAt) {
}
