package com.example.starter.cue.service;

import com.example.starter.cue.domain.CueExecution;
import com.example.starter.cue.domain.CueItem;
import com.example.starter.cue.domain.CueVersion;
import com.example.starter.cue.domain.RehearsalSession;
import com.example.starter.cue.domain.SessionCue;
import com.example.starter.cue.repo.CueVersionRepository;
import com.example.starter.cue.repo.SessionRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 排练场次：开始时冻结提示点快照；操作员按序回报，跳号 409；
 * 失败记录原因但不推进序号、不消耗幂等键；结束与回报通过场次行锁串行化。
 */
@Service
public class RehearsalService {

    private final SessionRepository sessions;
    private final CueVersionRepository versions;
    private final CueVersionService versionService;
    private final Clock clock;

    public RehearsalService(SessionRepository sessions, CueVersionRepository versions,
                            CueVersionService versionService, Clock cueClock) {
        this.sessions = sessions;
        this.versions = versions;
        this.versionService = versionService;
        this.clock = cueClock;
    }

    /** 新建未开始场次；不指定版本时绑定剧目最新冻结版本。 */
    @Transactional
    public long createSession(String production, Long versionId) {
        CueVersion version = resolveVersion(production, versionId);
        return sessions.insertSession(version.production(), version.id(), LocalDateTime.now(clock));
    }

    private CueVersion resolveVersion(String production, Long versionId) {
        if (versionId != null) {
            CueVersion version = versions.findVersion(versionId)
                    .orElseThrow(() -> BusinessException.notFound("版本不存在: " + versionId));
            if (!CueVersion.STATUS_FROZEN.equals(version.status())) {
                throw BusinessException.conflict("版本未冻结，不能绑定场次: " + versionId);
            }
            return version;
        }
        if (production == null || production.isBlank()) {
            throw BusinessException.badRequest("剧目名称与版本 ID 不能同时为空");
        }
        return versions.findLatestFrozen(production)
                .orElseThrow(() -> BusinessException.conflict("剧目没有已冻结版本: " + production));
    }

    /** 未开始场次重新绑定剧目最新冻结版本，并重新整体校验全部提示点。 */
    @Transactional
    public RehearsalSession rebind(long sessionId) {
        RehearsalSession session = sessions.findSessionForUpdate(sessionId)
                .orElseThrow(() -> BusinessException.notFound("场次不存在: " + sessionId));
        if (!RehearsalSession.STATUS_PENDING.equals(session.status())) {
            throw BusinessException.conflict("场次已开始或已结束，不能重新绑定版本");
        }
        CueVersion latest = versions.findLatestFrozen(session.production())
                .orElseThrow(() -> BusinessException.conflict("剧目没有已冻结版本"));
        List<CueItem> items = versions.findItems(latest.id());
        List<String> violations = versionService.validateAll(items);
        if (!violations.isEmpty()) {
            throw BusinessException.conflict("最新版本校验失败，不能重新绑定: "
                    + String.join("; ", violations));
        }
        sessions.rebindVersion(sessionId, latest.id());
        return sessions.findSession(sessionId).orElseThrow();
    }

    /** 开始排练：冻结所绑定版本的提示点快照及顺序。 */
    @Transactional
    public RehearsalSession start(long sessionId) {
        RehearsalSession session = sessions.findSessionForUpdate(sessionId)
                .orElseThrow(() -> BusinessException.notFound("场次不存在: " + sessionId));
        if (!RehearsalSession.STATUS_PENDING.equals(session.status())) {
            throw BusinessException.conflict("场次不是未开始状态，不能开始: " + session.status());
        }
        sessions.copyCuesToSession(sessionId, session.versionId());
        sessions.markStarted(sessionId, LocalDateTime.now(clock));
        return sessions.findSession(sessionId).orElseThrow();
    }

    /**
     * 回报提示点执行结果。同一 requestId 同参重放返回原回报，异参 409；
     * 跳号 409；失败记录原因但不推进已执行序号、不消耗幂等键。
     */
    @Transactional
    public CueExecution report(long sessionId, String requestId, int seq, String operator,
                               String result, String failReason) {
        if (requestId == null || requestId.isBlank()) {
            throw BusinessException.badRequest("requestId 不能为空");
        }
        if (!CueExecution.RESULT_SUCCESS.equals(result) && !CueExecution.RESULT_FAILED.equals(result)) {
            throw BusinessException.badRequest("result 必须为 SUCCESS 或 FAILED");
        }
        if (CueExecution.RESULT_FAILED.equals(result)
                && (failReason == null || failReason.isBlank())) {
            throw BusinessException.badRequest("失败回报必须给出原因");
        }
        RehearsalSession session = sessions.findSessionForUpdate(sessionId)
                .orElseThrow(() -> BusinessException.notFound("场次不存在: " + sessionId));

        Optional<CueExecution> existing = sessions.findSuccessByRequestId(sessionId, requestId);
        if (existing.isPresent()) {
            CueExecution prior = existing.get();
            boolean sameParams = prior.seq() == seq
                    && prior.result().equals(result)
                    && Objects.equals(prior.operator(), operator)
                    && Objects.equals(prior.failReason(), failReason);
            if (sameParams) {
                return prior;
            }
            throw BusinessException.conflict("requestId 已使用且参数不一致: " + requestId);
        }
        if (!RehearsalSession.STATUS_RUNNING.equals(session.status())) {
            throw BusinessException.conflict("场次不在排练中，不能回报: " + session.status());
        }
        int nextSeq = sessions.maxSuccessSeq(sessionId) + 1;
        if (seq != nextSeq) {
            throw BusinessException.conflict(
                    "跳号回报被拒绝：期望序号 " + nextSeq + "，实际 " + seq);
        }
        if (sessions.findSessionCues(sessionId).stream().noneMatch(c -> c.seq() == seq)) {
            throw BusinessException.conflict("序号超出本场冻结提示点范围: " + seq);
        }
        try {
            long id = sessions.insertExecution(sessionId, seq, requestId, operator, result,
                    CueExecution.RESULT_FAILED.equals(result) ? failReason : null,
                    LocalDateTime.now(clock));
            return sessions.findExecutions(sessionId).stream()
                    .filter(e -> e.id() == id).findFirst().orElseThrow();
        } catch (DuplicateKeyException e) {
            throw BusinessException.conflict("并发回报冲突：该序号或幂等键已有成功记录");
        }
    }

    /**
     * 结束排练：与回报共用场次行锁，只按已提交序号和完整性决定结束结论。
     */
    @Transactional
    public RehearsalSession end(long sessionId) {
        RehearsalSession session = sessions.findSessionForUpdate(sessionId)
                .orElseThrow(() -> BusinessException.notFound("场次不存在: " + sessionId));
        if (!RehearsalSession.STATUS_RUNNING.equals(session.status())) {
            throw BusinessException.conflict("场次不在排练中，不能结束: " + session.status());
        }
        int executed = sessions.countSuccess(sessionId);
        int total = sessions.findSessionCues(sessionId).size();
        boolean completed = executed == total;
        sessions.markEnded(sessionId, completed, LocalDateTime.now(clock));
        return sessions.findSession(sessionId).orElseThrow();
    }

    /** 查询场次冻结序列及每个提示点的执行或失败结果。 */
    @Transactional(readOnly = true)
    public Map<String, Object> describeSession(long sessionId) {
        RehearsalSession session = sessions.findSession(sessionId)
                .orElseThrow(() -> BusinessException.notFound("场次不存在: " + sessionId));
        List<SessionCue> cues = sessions.findSessionCues(sessionId);
        List<CueExecution> executions = sessions.findExecutions(sessionId);
        Map<Integer, List<CueExecution>> bySeq = new HashMap<>();
        for (CueExecution execution : executions) {
            bySeq.computeIfAbsent(execution.seq(), k -> new ArrayList<>()).add(execution);
        }
        List<Map<String, Object>> cueViews = new ArrayList<>();
        for (SessionCue cue : cues) {
            Map<String, Object> view = new HashMap<>();
            view.put("cue", cue);
            view.put("executions", bySeq.getOrDefault(cue.seq(), List.of()));
            cueViews.add(view);
        }
        Map<String, Object> result = new HashMap<>();
        result.put("session", session);
        result.put("cues", cueViews);
        return result;
    }
}