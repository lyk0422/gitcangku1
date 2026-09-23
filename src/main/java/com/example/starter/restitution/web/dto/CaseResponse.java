package com.example.starter.restitution.web.dto;

import java.util.List;

/**
 * 案件视图：含案件编号、状态、当前版本与藏品清单。
 */
public record CaseResponse(
        String caseKey,
        String status,
        long version,
        List<String> artifactNos
) {
}
