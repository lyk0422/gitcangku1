package com.example.starter.cue;

import java.util.List;

/** API 请求与响应 DTO 集合。 */
public final class CueDtos {

    private CueDtos() {
    }

    /**
     * 提示点输入。
     *
     * @param seq 场次内递增序号
     * @param cueType 类型：LIGHT/SOUND
     * @param triggerPhrase 触发口令
     * @param durationSeconds 持续秒数，冻结/绑定时校验为1至600
     */
    public record CueInput(int seq, CueType cueType, String triggerPhrase, int durationSeconds) {
    }

    /** 创建版本请求，每次编排修改产生一个新版本。 */
    public record CreateVersionRequest(List<CueInput> cues) {
    }

    /** 创建场次请求，绑定指定版本。 */
    public record CreateSessionRequest(long versionId) {
    }

    /**
     * 执行回报请求。
     *
     * @param requestId 幂等请求ID
     * @param seq 回报的提示点序号
     * @param success 是否执行成功
     * @param reason 失败原因，success为false时必填
     */
    public record ReportRequest(String requestId, int seq, boolean success, String reason) {
    }

    /** 回报结果：recorded新记录/replayed幂等重放。 */
    public record ReportResponse(String outcome, long sessionId, int seq, int lastExecutedSeq) {
    }

    /** 单个提示点的变更对比。 */
    public record CueChange(int seq, CueItem from, CueItem to) {
    }

    /** 版本差异：新增、删除、变更的提示点。 */
    public record VersionDiff(long fromVersionId, long toVersionId,
                              List<CueItem> added, List<CueItem> removed,
                              List<CueChange> changed) {
    }

    /** 冻结序列中单个提示点及其执行/失败结果。 */
    public record CueLine(SessionCue cue, CueExecution execution, List<CueFailure> failures) {
    }

    /** 场次详情：场次状态、冻结序列与每个提示点的执行结果。 */
    public record SessionDetail(RehearsalSession session, List<CueLine> cues) {
    }
}