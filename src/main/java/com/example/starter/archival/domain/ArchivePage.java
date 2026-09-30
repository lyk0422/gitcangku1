package com.example.starter.archival.domain;

import java.time.LocalDateTime;

/**
 * 档案页。馆藏号全局唯一。
 *
 * @param id          主键
 * @param accessionNo 馆藏号
 * @param createdAt   登记时间（Asia/Shanghai）
 */
public record ArchivePage(Long id, String accessionNo, LocalDateTime createdAt) {
}
