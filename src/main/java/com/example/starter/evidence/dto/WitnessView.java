package com.example.starter.evidence.dto;

import java.time.LocalDateTime;

/**
 * 单条摘要见证视图。
 *
 * @param witnessId 见证保管人标识
 * @param versionNo 被见证的封存版本号
 * @param current   是否为证物当前最新版本的见证（false 表示所见证版本已因重封存失效）
 * @param createdAt 见证提交时间（Asia/Shanghai）
 */
public record WitnessView(
        String witnessId,
        int versionNo,
        boolean current,
        LocalDateTime createdAt) {
}
