package com.example.starter.cue.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 场次详情：冻结版本、冻结序列，以及每个提示点的执行或失败结果。
 *
 * @param session         场次基本信息
 * @param frozenSequence  冻结的提示点序列（按序号升序），未冻结为空列表
 * @param cueResults      每个冻结提示点的执行/失败结果（按序号升序）
 */
public record SessionDetailResponse(
        SessionResponse session,
        List<FrozenCueView> frozenSequence,
        List<CueResultView> cueResults) {

    /**
     * 冻结快照中的单个提示点。
     *
     * @param seqNo           序号
     * @param cueType         提示点类型
     * @param triggerWord     触发口令
     * @param durationSeconds 持续秒数，单位：秒
     */
    public record FrozenCueView(
            Integer seqNo,
            String cueType,
            String triggerWord,
            Integer durationSeconds) {
    }

    /**
     * 单个提示点的回报结果视图。
     *
     * @param seqNo        提示点序号
     * @param state        PENDING 未回报 / SUCCESS 已成功 / FAILED 有失败记录（尚未成功）
     * @param success      是否存在成功记录
     * @param failReasons  该序号全部失败回报的原因列表（按受理时间升序），无失败为空
     * @param successAt    成功受理时间（Asia/Shanghai），未成功为空
     */
    public record CueResultView(
            Integer seqNo,
            String state,
            boolean success,
            List<FailureView> failReasons,
            LocalDateTime successAt) {
    }

    /**
     * 一次失败回报。
     *
     * @param failReason   失败原因
     * @param requestId    幂等键
     * @param operatorCode 操作员标识，可空
     * @param reportedAt   回报受理时间（Asia/Shanghai）
     */
    public record FailureView(
            String failReason,
            String requestId,
            String operatorCode,
            LocalDateTime reportedAt) {
    }
}
