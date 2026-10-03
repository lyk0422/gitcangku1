package com.example.starter.certificate.dto;

import java.time.Instant;
import java.util.List;

/**
 * 声明匹配诊断响应：对给定物料与批号，在给定时点逐个评估全部声明版本。
 * totalDeclarations 为评估总数，matchedCount 为实际匹配数（实际值）；
 * winner 为优先级最高的匹配版本（无匹配为 null）；ambiguous 表示最高优先级存在并列。
 * candidates 稳定排序：匹配项在前（优先级降序、业务键升序、版本降序），未匹配项在后。
 */
public record DiagnosticsResponse(
        String materialCode,
        String batchNo,
        Instant evaluatedAt,
        int totalDeclarations,
        int matchedCount,
        boolean ambiguous,
        Winner winner,
        List<Candidate> candidates
) {

    /**
     * 诊断胜出版本：优先级最高的匹配声明版本及其匹配前缀。
     */
    public record Winner(String declarationKey, int version, int priority, String matchedPrefix) {
    }

    /**
     * 单个声明版本的诊断结果：matched 为是否匹配；matchedPrefix 未匹配为 null；
     * reasons 为全部不匹配原因码（MATERIAL_MISMATCH/WITHDRAWN/NOT_YET_VALID/EXPIRED/PREFIX_MISMATCH），
     * 匹配时为空列表。
     */
    public record Candidate(String declarationKey, int version, int priority, String status,
                            boolean matched, String matchedPrefix, List<String> reasons) {
    }
}