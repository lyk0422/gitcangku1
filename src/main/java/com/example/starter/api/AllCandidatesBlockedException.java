package com.example.starter.api;

import com.example.starter.api.dto.CandidateResultDto;

import java.util.List;

/**
 * 全部改航候选均为 BLOCKED 时抛出，映射 HTTP 422。
 * 响应体需要给出逐候选命中集合，供调用方按命中区域调整后重试。
 */
public class AllCandidatesBlockedException extends RuntimeException {

    /** 逐候选命中集合（与请求声明顺序一致）。 */
    private final List<CandidateResultDto> candidates;

    public AllCandidatesBlockedException(String message, List<CandidateResultDto> candidates) {
        super(message);
        this.candidates = List.copyOf(candidates);
    }

    public List<CandidateResultDto> candidates() {
        return candidates;
    }
}
