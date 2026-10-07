package com.example.starter.evidence.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 污染登记请求。指定一件操作人当前持有的证物及一个已有接触事件序号作为污染起点。
 *
 * @param requestId   全局请求标识（幂等键，同时作为污染登记业务键）
 * @param evidenceKey 污染起点证物业务键
 * @param startSeq    污染起点事件序号，必须已存在；从该序号（含）起向后传播
 */
public record ContaminationCreateRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String evidenceKey,
        @Positive long startSeq) {
}
