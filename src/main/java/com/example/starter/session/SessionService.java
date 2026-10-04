package com.example.starter.session;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.common.BusinessException;
import com.example.starter.cue.CueDtos.CueView;
import com.example.starter.cue.CueVersionRepository;
import com.example.starter.cue.CueVersionRepository.VersionRow;
import com.example.starter.cue.CueVersionService;
import com.example.starter.session.SessionDtos.CreateSessionRequest;
import com.example.starter.session.SessionDtos.CueResultView;
import com.example.starter.session.SessionDtos.ReportRequest;
import com.example.starter.session.SessionDtos.ReportView;
import com.example.starter.session.SessionDtos.SessionDetailView;
import com.example.starter.session.SessionDtos.SessionView;
import com.example.starter.session.SessionRepository.FailureRow;
import com.example.starter.session.SessionRepository.SessionRow;
import com.example.starter.session.SessionRepository.SuccessRow;

/**
 * 排练场次：绑定版本、开始冻结快照、按序回报（幂等）、结束判定与结果查询。
 * 并发通过场次行锁（SELECT ... FOR UPDATE）加唯一约束兜底保证。
 */
@Service
public class SessionService {

    private final SessionRepository sessionRepository;
    private final CueVersionRepository versionRepository;
    private final CueVersionService versionService;

    public SessionService(SessionRepository sessionRepository,
                          CueVersionRepository versionRepository,
                          CueVersionService versionService) {
        this.sessionRepository = sessionRepository;
        this.versionRepository = versionRepository;
        this.versionService = versionService;
    }

    /** 创建场次：只能绑定已冻结版本。 */
    @Transactional
    public SessionView createSession(CreateSessionRequest request) {
        VersionRow version = versionRepository.findVersion(request.versionId())
                .orElseThrow(() -> BusinessException.notFound("编排版本不存在: " + request.versionId()));
        if (!"FROZEN".equals(version.status())) {
            throw BusinessException.conflict("只能绑定已冻结的编排版本");
        }
        long id = sessionRepository.insertSession(request.sessionName(), version.id(), Instant.now());
        return toView(sessionRepository.findById(id).orElseThrow());
    }

    /** 未开始场次重新绑定该剧目最新冻结版本，并重新校验全部提示点。 */
    @Transactional
    public SessionView rebind(long sessionId) {
        SessionRow session = lockSession(sessionId);
        if (!"NOT_STARTED".equals(session.status())) {
            throw BusinessException.conflict("场次已开始，不能重新绑定版本");
        }
        VersionRow current = versionRepository.findVersion(session.versionId()).orElseThrow();
        VersionRow latest = versionRepository.findLatestFrozen(current.playCode())
                .orElseThrow(() -> BusinessException.conflict("剧目没有已冻结的编排版本"));
        List<String> problems = versionService.validateAll(versionRepository.findItems(latest.id()));
        if (!problems.isEmpty()) {
            throw BusinessException.unprocessable("最新版本校验未通过: " + String.join("; ", problems));
        }
        sessionRepository.updateVersionId(sessionId, latest.id());
        return toView(sessionRepository.findById(sessionId).orElseThrow());
    }

    /** 开始排练：把绑定版本的提示点复制为场次快照，之后编排修改不影响本场。 */
    @Transactional
    public SessionView start(long sessionId) {
        SessionRow session = lockSession(sessionId);
        if (!"NOT_STARTED".equals(session.status())) {
            throw BusinessException.conflict("场次已开始或已结束，不能重复开始");
        }
        List<CueView> cues = versionRepository.findItems(session.versionId());
        for (CueView cue : cues) {
            sessionRepository.insertSessionCue(sessionId, cue);
        }
        sessionRepository.markStarted(sessionId, Instant.now());
        return toView(sessionRepository.findById(sessionId).orElseThrow());
    }

    /**
     * 执行回报：同requestId同参重放原回报、异参409；成功必须按冻结顺序逐条推进，
     * 跳号409；失败只记录原因不推进序号，也不消耗幂等键。
     */
    @Transactional
    public ReportView report(long sessionId, ReportRequest request) {
        SessionRow session = lockSession(sessionId);
        if (!"IN_PROGRESS".equals(session.status())) {
            throw BusinessException.conflict("场次不在排练中状态，不能回报");
        }
        String fingerprint = fingerprint(request);
        var existing = sessionRepository.findSuccessByRequestId(sessionId, request.requestId());
        if (existing.isPresent()) {
            SuccessRow done = existing.get();
            if (!done.paramsFingerprint().equals(fingerprint)) {
                throw BusinessException.conflict("requestId已使用且参数不一致");
            }
            return new ReportView(sessionId, done.seqNo(), "SUCCESS", done.requestId(), true, done.reportedAt());
        }
        int expected = session.lastExecutedSeq() + 1;
        if (request.seqNo() != expected) {
            throw BusinessException.conflict("必须按顺序回报，下一提示点序号为 " + expected
                    + "，收到 " + request.seqNo());
        }
        boolean cueExists = sessionRepository.findSessionCues(sessionId).stream()
                .anyMatch(c -> c.seqNo() == request.seqNo());
        if (!cueExists) {
            throw BusinessException.conflict("提示点不存在于本场冻结序列: " + request.seqNo());
        }
        Instant now = Instant.now();
        if (!request.success()) {
            if (request.failReason() == null || request.failReason().isBlank()) {
                throw BusinessException.badRequest("失败回报必须填写原因");
            }
            sessionRepository.insertFailure(sessionId, request.seqNo(), request.requestId(),
                    request.operator(), request.failReason(), now);
            return new ReportView(sessionId, request.seqNo(), "FAILED", request.requestId(), false, now);
        }
        try {
            sessionRepository.insertSuccess(sessionId, request.seqNo(), request.requestId(),
                    request.operator(), fingerprint, now);
        } catch (DuplicateKeyException e) {
            throw BusinessException.conflict("该提示点已被其他操作员成功回报");
        }
        sessionRepository.advanceSeq(sessionId, request.seqNo());
        return new ReportView(sessionId, request.seqNo(), "SUCCESS", request.requestId(), false, now);
    }

    /** 结束场次：仅当冻结序列全部成功执行才允许结束，判定与写入在同一事务内完成。 */
    @Transactional
    public SessionView finish(long sessionId) {
        SessionRow session = lockSession(sessionId);
        if (!"IN_PROGRESS".equals(session.status())) {
            throw BusinessException.conflict("场次不在排练中状态，不能结束");
        }
        int total = sessionRepository.countSessionCues(sessionId);
        int done = sessionRepository.countSuccesses(sessionId);
        if (done < total) {
            throw BusinessException.conflict("尚有未成功执行的提示点: " + done + "/" + total);
        }
        sessionRepository.markFinished(sessionId, Instant.now());
        return toView(sessionRepository.findById(sessionId).orElseThrow());
    }

    /** 场次详情：冻结序列逐条给出执行结果（成功/最近失败/未回报）。 */
    public SessionDetailView detail(long sessionId) {
        SessionRow session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> BusinessException.notFound("排练场次不存在: " + sessionId));
        Map<Integer, SuccessRow> successes = sessionRepository.findSuccesses(sessionId).stream()
                .collect(Collectors.toMap(SuccessRow::seqNo, Function.identity()));
        Map<Integer, FailureRow> latestFailures = sessionRepository.findFailures(sessionId).stream()
                .collect(Collectors.toMap(FailureRow::seqNo, Function.identity(), (a, b) -> b));
        List<CueResultView> cues = new ArrayList<>();
        for (CueView cue : sessionRepository.findSessionCues(sessionId)) {
            SuccessRow ok = successes.get(cue.seqNo());
            FailureRow fail = latestFailures.get(cue.seqNo());
            if (ok != null) {
                cues.add(new CueResultView(cue.seqNo(), cue.cueType(), cue.triggerPhrase(),
                        cue.durationSeconds(), "SUCCESS", null, ok.operator(), ok.reportedAt()));
            } else if (fail != null) {
                cues.add(new CueResultView(cue.seqNo(), cue.cueType(), cue.triggerPhrase(),
                        cue.durationSeconds(), "FAILED", fail.failReason(), fail.operator(), fail.reportedAt()));
            } else {
                cues.add(new CueResultView(cue.seqNo(), cue.cueType(), cue.triggerPhrase(),
                        cue.durationSeconds(), "PENDING", null, null, null));
            }
        }
        return new SessionDetailView(toView(session), cues);
    }

    private SessionRow lockSession(long sessionId) {
        return sessionRepository.lockById(sessionId)
                .orElseThrow(() -> BusinessException.notFound("排练场次不存在: " + sessionId));
    }

    private SessionView toView(SessionRow row) {
        return new SessionView(row.id(), row.sessionName(), row.versionId(), row.status(),
                row.lastExecutedSeq(), row.createdAt(), row.startedAt(), row.finishedAt());
    }

    /** 回报参数指纹：同一requestId参数一致才可重放。 */
    private static String fingerprint(ReportRequest request) {
        String raw = request.seqNo() + "|" + request.success() + "|"
                + String.valueOf(request.failReason()) + "|" + request.operator();
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256不可用", e);
        }
    }
}