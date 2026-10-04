package com.example.starter.session;

import java.time.Instant;
import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 排练场次与执行回报相关传输对象。
 */
public final class SessionDtos {

    private SessionDtos() {
    }

    /** 创建排练场次请求：绑定一个已冻结的编排版本。 */
    public record CreateSessionRequest(@NotBlank String sessionName, @NotNull Long versionId) {
    }

    /** 排练场次视图；时间均为UTC，未发生的时间为null。 */
    public record SessionView(long id, String sessionName, long versionId, String status,
                              int lastExecutedSeq, Instant createdAt, Instant startedAt, Instant finishedAt) {
    }

    /** 执行回报请求：success为false时failReason必填。 */
    public record ReportRequest(@NotBlank String requestId,
                                @NotNull Integer seqNo,
                                @NotNull Boolean success,
                                String failReason,
                                @NotBlank String operator) {
    }

    /** 执行回报响应：replayed为true表示同参幂等重放。 */
    public record ReportView(long sessionId, int seqNo, String result, String requestId,
                             boolean replayed, Instant reportedAt) {
    }

    /** 冻结序列中单个提示点及其执行结果：SUCCESS成功、FAILED最近失败、PENDING未回报。 */
    public record CueResultView(int seqNo, String cueType, String triggerPhrase, int durationSeconds,
                                String result, String failReason, String operator, Instant reportedAt) {
    }

    /** 场次详情：场次信息加冻结提示点序列及各自执行结果。 */
    public record SessionDetailView(SessionView session, List<CueResultView> cues) {
    }
}