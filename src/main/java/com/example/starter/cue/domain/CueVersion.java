package com.example.starter.cue.domain;

import java.time.LocalDateTime;

/**
 * 剧目提示点编排版本 PO；历史版本不可变，编排修改只追加新行。
 *
 * @param id        版本 ID
 * @param playCode  剧目编码
 * @param versionNo 剧目内递增版本号，从 1 开始
 * @param createdAt 版本创建时间（Asia/Shanghai）
 */
public record CueVersion(
        Long id,
        String playCode,
        Integer versionNo,
        LocalDateTime createdAt) {
}
