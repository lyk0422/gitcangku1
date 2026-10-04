package com.example.starter.cue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 排练场次服务：绑定版本、开始冻结、按序回报、结束与查询。 */
@Service
public class SessionService {

    private final CueService cueService;
    private final SessionRepository sessionRepository;

    public SessionService(CueService cueService, SessionRepository sessionRepository) {
        this.cueService = cueService;
        this.sessionRepository = sessionRepository;
    }

    /** 创建未开始场次并绑定版本，绑定时重新校验全部提示点。 */
    @Transactional
    public long createSession(CueDtos.CreateSessionRequest request) {
        cueService.requireVersion(request.versionId());
        cueService.validatedItems(request.versionId());
        return sessionRepository.insertSession(request.versionId());
    }

    /** 未开始场次重新绑定最新版本，必须重新校验全部提示点。 */
    @Transactional
    public RehearsalSession rebindToLatest(long sessionId) {
        RehearsalSession session = requireSession(sessionId);
        if (session.status() != SessionStatus.SCHEDULED) {
            throw new ConflictException("场次已开始，不能重新绑定版本");
        }
        long latestId = cueService.latestVersionId()
                .orElseThrow(() -> new ConflictException("尚无可绑定的版本"));
        cueService.validatedItems(latestId);
        sessionRepository.rebind(sessionId, latestId);
        return requireSession(sessionId);
    }

    /** 开始排练：冻结当前绑定版本的提示点及顺序为快照，之后的版本修改不影响本场。 */
    @Transactional
    public RehearsalSession start(long sessionId) {
        RehearsalSession session = requireSession(sessionId);
        if (session.status() != SessionStatus.SCHEDULED) {
            throw new ConflictException("场次已开始或已结束，不能重复开始");
        }
        List<CueItem> items = cueService.validatedItems(session.versionId());
        List<SessionCue> snapshot = items.stream()
                .map(item -> new SessionCue(sessionId, item.seq(), item.cueType(),
                        item.triggerPhrase(), item.durationSeconds()))
                .toList();
        if (sessionRepository.markRunning(sessionId) != 1) {
            throw new ConflictException("场次状态已变化，开始失败");
        }
        sessionRepository.insertSnapshot(snapshot);
        return requireSession(sessionId);
    }

    /**
     * 执行回报：只可按顺序回报；跳号返回409；失败记录原因但不推进已执行序号、不占用幂等键；
     * 同一requestId同参重放原回报、异参返回409。
     */
    @Transactional
    public CueDtos.ReportResponse report(long sessionId, CueDtos.ReportRequest request) {
        RehearsalSession session = requireSession(sessionId);
        if (session.status() != SessionStatus.RUNNING) {
            throw new ConflictException("场次未在进行中，不能回报执行");
        }
        if (request.requestId() == null || request.requestId().isBlank()) {
            throw new ConflictException("requestId不能为空");
        }
        if (!request.success()) {
            return recordFailure(session, request);
        }
        String payloadHash = payloadHash(request);
        var existing = sessionRepository.findExecutionByRequestId(sessionId, request.requestId());
        if (existing.isPresent()) {
            CueExecution committed = existing.get();
            if (!committed.payloadHash().equals(payloadHash)) {
                throw new ConflictException("requestId已使用且参数不一致");
            }
            return new CueDtos.ReportResponse("replayed", sessionId,
                    committed.seq(), requireSession(sessionId).lastExecutedSeq());
        }
        if (sessionRepository.advanceLastExecuted(sessionId, request.seq()) != 1) {
            throw new ConflictException("跳号或并发冲突：当前应执行序号 "
                    + (requireSession(sessionId).lastExecutedSeq() + 1) + "，收到 " + request.seq());
        }
        try {
            sessionRepository.insertExecution(sessionId, request.seq(), request.requestId(), payloadHash);
        } catch (DuplicateKeyException ex) {
            throw new ConflictException("提示点序号或requestId已被占用");
        }
        return new CueDtos.ReportResponse("recorded", sessionId, request.seq(), request.seq());
    }

    /** 失败回报：校验顺序后记录原因，不推进序号、不消耗幂等键。 */
    private CueDtos.ReportResponse recordFailure(RehearsalSession session, CueDtos.ReportRequest request) {
        if (request.reason() == null || request.reason().isBlank()) {
            throw new ConflictException("失败回报必须给出原因");
        }
        int expected = session.lastExecutedSeq() + 1;
        if (request.seq() != expected) {
            throw new ConflictException("跳号回报：当前应执行序号 " + expected + "，收到 " + request.seq());
        }
        sessionRepository.insertFailure(session.id(), request.seq(), request.requestId(), request.reason());
        return new CueDtos.ReportResponse("failed", session.id(), request.seq(), session.lastExecutedSeq());
    }

    /** 结束场次：只按已提交序号和快照完整性决定，未执行完返回409。 */
    @Transactional
    public RehearsalSession finish(long sessionId) {
        requireSession(sessionId);
        if (sessionRepository.finishIfComplete(sessionId) != 1) {
            throw new ConflictException("场次未在进行中或提示点未全部执行完成，不能结束");
        }
        return requireSession(sessionId);
    }

    /** 场次详情：冻结序列与每个提示点的执行或失败结果。 */
    @Transactional(readOnly = true)
    public CueDtos.SessionDetail detail(long sessionId) {
        RehearsalSession session = requireSession(sessionId);
        List<SessionCue> snapshot = sessionRepository.findSnapshot(sessionId);
        Map<Integer, CueExecution> executions = sessionRepository.findExecutions(sessionId).stream()
                .collect(Collectors.toMap(CueExecution::seq, Function.identity()));
        Map<Integer, List<CueFailure>> failures = sessionRepository.findFailures(sessionId).stream()
                .collect(Collectors.groupingBy(CueFailure::seq));
        List<CueDtos.CueLine> lines = snapshot.stream()
                .map(cue -> new CueDtos.CueLine(cue, executions.get(cue.seq()),
                        failures.getOrDefault(cue.seq(), List.of())))
                .toList();
        return new CueDtos.SessionDetail(session, lines);
    }

    private RehearsalSession requireSession(long sessionId) {
        return sessionRepository.findSession(sessionId)
                .orElseThrow(() -> new NotFoundException("场次不存在: " + sessionId));
    }

    /** 回报参数指纹：同requestId同参重放原回报，异参判重。 */
    static String payloadHash(CueDtos.ReportRequest request) {
        String payload = request.seq() + "|" + request.success() + "|"
                + (request.reason() == null ? "" : request.reason());
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}