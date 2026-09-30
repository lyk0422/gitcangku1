package com.example.starter.cue.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.example.starter.cue.domain.CueItem;
import com.example.starter.cue.domain.CueReport;
import com.example.starter.cue.domain.CueSnapshot;
import com.example.starter.cue.domain.CueVersion;
import com.example.starter.cue.domain.RehearsalSession;
import com.example.starter.cue.domain.SessionStatus;
import com.example.starter.cue.dto.BindVersionRequest;
import com.example.starter.cue.dto.CreateSessionRequest;
import com.example.starter.cue.dto.CreateVersionRequest;
import com.example.starter.cue.dto.CuePointRequest;
import com.example.starter.cue.dto.ReportCueRequest;
import com.example.starter.cue.dto.ReportResponse;
import com.example.starter.cue.dto.SessionDetailResponse;
import com.example.starter.cue.dto.SessionResponse;
import com.example.starter.cue.dto.VersionDiffResponse;
import com.example.starter.cue.dto.VersionResponse;
import com.example.starter.cue.exception.CueConflictException;
import com.example.starter.cue.exception.CueNotFoundException;
import com.example.starter.cue.exception.CueValidationException;
import com.example.starter.cue.repo.CueReportRepository;
import com.example.starter.cue.repo.CueSnapshotRepository;
import com.example.starter.cue.repo.CueVersionRepository;
import com.example.starter.cue.repo.RehearsalSessionRepository;
import com.example.starter.cue.support.ClockConfig;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 剧目提示点编排、排练冻结、执行回报与查询的核心业务服务。
 *
 * <p>关键规则：
 * <ul>
 *   <li>编排修改只产生不可变新版本，不影响已开始排练的冻结快照；</li>
 *   <li>未开始场次绑定/开始前都整版本重新校验全部提示点，任一无效则整体拒绝；</li>
 *   <li>运行中只接受 executedSeq+1 的回报，跳号与重复序号返回 409；</li>
 *   <li>失败回报记录原因但不推进序号；成功回报推进序号，最后一条成功自动结束本场；</li>
 *   <li>同 requestId 同参重放原回报、异参 409；被 409 拒绝的请求不落库，不消耗幂等键；</li>
 *   <li>同一提示点并发成功回报由唯一约束保证只剩一条，结束判定只认已提交序号与完整性。</li>
 * </ul>
 */
@Service
public class CueService {

    private final CueVersionRepository versionRepository;
    private final RehearsalSessionRepository sessionRepository;
    private final CueSnapshotRepository snapshotRepository;
    private final CueReportRepository reportRepository;
    private final CuePointValidator validator;
    private final Clock clock;

    public CueService(CueVersionRepository versionRepository,
                      RehearsalSessionRepository sessionRepository,
                      CueSnapshotRepository snapshotRepository,
                      CueReportRepository reportRepository,
                      CuePointValidator validator,
                      Clock clock) {
        this.versionRepository = versionRepository;
        this.sessionRepository = sessionRepository;
        this.snapshotRepository = snapshotRepository;
        this.reportRepository = reportRepository;
        this.validator = validator;
        this.clock = clock;
    }

    /**
     * 创建编排版本：整版本校验通过后写入不可变新版本与全部提示点。
     */
    @Transactional
    public VersionResponse createVersion(CreateVersionRequest request) {
        List<CuePointRequest> cues = validator.validate(request.cues());
        LocalDateTime now = ClockConfig.now(clock);

        // 并发创建同剧目版本时版本号可能撞唯一约束，重新取号后有限重试。
        DuplicateKeyException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            int versionNo = versionRepository.nextVersionNo(request.playCode());
            long versionId;
            try {
                versionId = versionRepository.insertVersion(request.playCode(), versionNo, now);
            } catch (DuplicateKeyException e) {
                last = e;
                continue;
            }
            for (CuePointRequest cue : cues) {
                versionRepository.insertItem(versionId, cue.seqNo(), cue.cueType(),
                        cue.triggerWord(), cue.durationSeconds());
            }
            return toVersionResponseFromRequests(
                    new CueVersion(versionId, request.playCode(), versionNo, now), cues);
        }
        throw last;
    }

    /**
     * 查询版本详情（含按序号升序的全部提示点）。
     */
    @Transactional(readOnly = true)
    public VersionResponse getVersion(String playCode, int versionNo) {
        CueVersion version = requireVersion(playCode, versionNo);
        return toVersionResponse(version, versionRepository.findItems(version.id()));
    }

    /**
     * 对比同一剧目两个版本的差异，按序号对齐。
     */
    @Transactional(readOnly = true)
    public VersionDiffResponse diffVersions(String playCode, int fromVersionNo, int toVersionNo) {
        CueVersion from = requireVersion(playCode, fromVersionNo);
        CueVersion to = requireVersion(playCode, toVersionNo);
        Map<Integer, VersionResponse.CuePointView> fromMap = toMap(versionRepository.findItems(from.id()));
        Map<Integer, VersionResponse.CuePointView> toMap = toMap(versionRepository.findItems(to.id()));

        List<VersionResponse.CuePointView> added = new ArrayList<>();
        List<VersionDiffResponse.CuePair> removed = new ArrayList<>();
        List<VersionDiffResponse.CuePair> changed = new ArrayList<>();
        int unchanged = 0;

        for (Map.Entry<Integer, VersionResponse.CuePointView> entry : toMap.entrySet()) {
            VersionResponse.CuePointView base = fromMap.get(entry.getKey());
            if (base == null) {
                added.add(entry.getValue());
            } else if (!base.equals(entry.getValue())) {
                changed.add(new VersionDiffResponse.CuePair(entry.getKey(), base, entry.getValue()));
            } else {
                unchanged++;
            }
        }
        for (Map.Entry<Integer, VersionResponse.CuePointView> entry : fromMap.entrySet()) {
            if (!toMap.containsKey(entry.getKey())) {
                removed.add(new VersionDiffResponse.CuePair(entry.getKey(), entry.getValue(), null));
            }
        }
        return new VersionDiffResponse(playCode, fromVersionNo, toVersionNo,
                List.copyOf(added), List.copyOf(removed), List.copyOf(changed), unchanged);
    }

    /**
     * 创建未开始排练场次，可选立即绑定版本；绑定时同样整版本校验。
     */
    @Transactional
    public SessionResponse createSession(CreateSessionRequest request) {
        Long versionId = null;
        if (request.versionNo() != null) {
            CueVersion version = requireVersion(request.playCode(), request.versionNo());
            revalidate(version);
            versionId = version.id();
        }
        LocalDateTime now = ClockConfig.now(clock);
        try {
            long sessionId = sessionRepository.insert(
                    request.playCode(), request.sessionCode(), versionId, now);
            return toSessionResponse(requireSessionEntity(sessionId));
        } catch (DuplicateKeyException e) {
            throw new CueConflictException("场次编码已存在: " + request.sessionCode());
        }
    }

    /**
     * 未开始场次重新绑定最新（或指定）版本；必须重新校验全部提示点，已开始场次返回 409。
     */
    @Transactional
    public SessionResponse bindVersion(String playCode, String sessionCode, BindVersionRequest request) {
        RehearsalSession session = requireSession(playCode, sessionCode);
        // 加行锁，与并发的开始/改绑串行，状态判定只基于持锁后的已提交值。
        session = sessionRepository.findByIdForUpdate(session.id());
        if (session.status() != SessionStatus.NOT_STARTED) {
            throw new CueConflictException("排练已开始，冻结快照不可更改");
        }
        CueVersion target = request.versionNo() == null
                ? versionRepository.findLatest(playCode)
                : requireVersion(playCode, request.versionNo());
        if (target == null) {
            throw new CueNotFoundException("剧目尚无编排版本: " + playCode);
        }
        revalidate(target);
        sessionRepository.updateVersion(session.id(), target.id());
        return toSessionResponse(requireSessionEntity(session.id()));
    }

    /**
     * 开始排练：冻结当前绑定版本的全部提示点与顺序。未绑定版本、已开始、整版本校验失败均拒绝。
     */
    @Transactional
    public SessionResponse startSession(String playCode, String sessionCode) {
        RehearsalSession session = requireSession(playCode, sessionCode);
        // 行锁串行化并发开始，避免两个事务同时写冻结快照。
        session = sessionRepository.findByIdForUpdate(session.id());
        if (session.status() != SessionStatus.NOT_STARTED) {
            throw new CueConflictException("排练已开始或已结束，不能重复冻结");
        }
        if (session.versionId() == null) {
            throw new CueConflictException("场次尚未绑定编排版本，不能开始");
        }
        CueVersion version = versionRepository.findById(session.versionId());
        if (version == null) {
            throw new CueNotFoundException("绑定的编排版本不存在: " + session.versionId());
        }
        List<CueItem> items = versionRepository.findItems(version.id());
        revalidate(version, items);

        LocalDateTime now = ClockConfig.now(clock);
        for (CueItem item : items) {
            snapshotRepository.insert(session.id(), item.seqNo(), item.cueType(),
                    item.triggerWord(), item.durationSeconds());
        }
        int updated = sessionRepository.freeze(session.id(), version.id(), items.size(), now);
        if (updated == 0) {
            // 并发开始：另一个事务已冻结，行级更新未命中，整事务回滚。
            throw new CueConflictException("排练已被并发开始，冻结版本以先提交者为准");
        }
        return toSessionResponse(requireSessionEntity(session.id()));
    }

    /**
     * 操作员回报提示点执行结果。整段在单事务内对场次行加 FOR UPDATE 锁，保证并发串行判定。
     *
     * @return 回报受理结果；同 requestId 同参重放时 replayed=TRUE
     */
    @Transactional
    public ReportResponse report(String playCode, String sessionCode, ReportCueRequest request) {
        RehearsalSession session = requireSession(playCode, sessionCode);

        // 行锁串行化：并发回报/结束在此排队，后续所有判定只基于持锁读到的已提交状态。
        RehearsalSession locked = sessionRepository.findByIdForUpdate(session.id());
        if (locked == null) {
            throw new CueNotFoundException("场次不存在: " + sessionCode);
        }

        // 幂等优先：同 requestId 已受理时，同参返回原回报，异参 409。
        CueReport existing = reportRepository.findByRequestId(locked.id(), request.requestId());
        if (existing != null) {
            if (sameParameters(existing, request)) {
                return toReportResponse(existing, true);
            }
            throw new CueConflictException("requestId 已用于不同参数的回报，拒绝异参重放");
        }

        validateReportPayload(request);

        if (locked.status() == SessionStatus.FINISHED) {
            throw new CueConflictException("本场排练已结束，不再接受回报");
        }
        if (locked.status() != SessionStatus.RUNNING) {
            throw new CueConflictException("排练尚未开始，不能回报执行");
        }
        int expectedSeq = locked.executedSeq() + 1;
        if (request.seqNo() != expectedSeq) {
            if (request.seqNo() <= locked.executedSeq()) {
                throw new CueConflictException("序号 " + request.seqNo() + " 已执行，当前应回报序号 " + expectedSeq);
            }
            throw new CueConflictException("跳号回报被拒：当前应回报序号 " + expectedSeq
                    + "，实际回报序号 " + request.seqNo());
        }
        if (request.seqNo() > locked.totalCues()) {
            throw new CueConflictException("序号 " + request.seqNo() + " 超出冻结提示点总数 " + locked.totalCues());
        }

        LocalDateTime now = ClockConfig.now(clock);
        if (Boolean.FALSE.equals(request.success())) {
            // 执行失败：记录原因，success_seq 留空，不推进已执行序号；幂等键正常占用。
            long id = reportRepository.insert(locked.id(), request.seqNo(), false,
                    request.failReason(), request.requestId(), request.operatorCode(), now);
            return toReportResponse(requireReport(id), false);
        }

        try {
            long id = reportRepository.insert(locked.id(), request.seqNo(), true, null,
                    request.requestId(), request.operatorCode(), now);
            int newExecutedSeq = locked.executedSeq() + 1;
            sessionRepository.advanceSeq(locked.id(), newExecutedSeq);
            // 完整性决定结束：最后一条成功提交后自动结束本场。
            if (newExecutedSeq == locked.totalCues()) {
                sessionRepository.finish(locked.id(), now);
            }
            return toReportResponse(requireReport(id), false);
        } catch (DuplicateKeyException e) {
            // 并发成功回报同一序号：唯一约束拒绝，事务回滚，requestId 不入库、不被消耗。
            throw new CueConflictException("序号 " + request.seqNo() + " 已被并发回报成功，仅保留一条成功记录");
        }
    }

    /**
     * 结束本场；仅当冻结序列全部成功执行才可结束。与末条回报并发时，以持锁后读到的
     * 已提交序号与完整性为准：已结束则幂等返回，未完成则 409。
     */
    @Transactional
    public SessionResponse finishSession(String playCode, String sessionCode) {
        RehearsalSession session = requireSession(playCode, sessionCode);
        RehearsalSession locked = sessionRepository.findByIdForUpdate(session.id());
        if (locked == null) {
            throw new CueNotFoundException("场次不存在: " + sessionCode);
        }
        if (locked.status() == SessionStatus.FINISHED) {
            return toSessionResponse(locked);
        }
        if (locked.status() != SessionStatus.RUNNING) {
            throw new CueConflictException("排练尚未开始，不能结束");
        }
        if (locked.executedSeq() != locked.totalCues()) {
            throw new CueConflictException("尚未执行完整场，已执行 " + locked.executedSeq()
                    + " / 共 " + locked.totalCues() + " 条提示点，不能结束");
        }
        sessionRepository.finish(locked.id(), ClockConfig.now(clock));
        return toSessionResponse(requireSessionEntity(locked.id()));
    }

    /**
     * 查询场次：冻结版本、冻结序列及每个提示点的成功/失败结果。
     */
    @Transactional(readOnly = true)
    public SessionDetailResponse getSessionDetail(String playCode, String sessionCode) {
        RehearsalSession session = requireSession(playCode, sessionCode);
        List<CueSnapshot> snapshots = snapshotRepository.findBySession(session.id());
        List<CueReport> reports = reportRepository.findBySession(session.id());

        Map<Integer, List<CueReport>> reportsBySeq = new LinkedHashMap<>();
        for (CueSnapshot snapshot : snapshots) {
            reportsBySeq.put(snapshot.seqNo(), new ArrayList<>());
        }
        for (CueReport report : reports) {
            reportsBySeq.computeIfAbsent(report.seqNo(), key -> new ArrayList<>()).add(report);
        }

        List<SessionDetailResponse.CueResultView> results = new ArrayList<>();
        for (CueSnapshot snapshot : snapshots) {
            List<CueReport> seqReports = reportsBySeq.getOrDefault(snapshot.seqNo(), List.of());
            CueReport successReport = seqReports.stream().filter(CueReport::success).findFirst().orElse(null);
            List<SessionDetailResponse.FailureView> failures = seqReports.stream()
                    .filter(r -> !r.success())
                    .map(r -> new SessionDetailResponse.FailureView(
                            r.failReason(), r.requestId(), r.operatorCode(), r.reportedAt()))
                    .toList();
            String state = successReport != null ? "SUCCESS" : failures.isEmpty() ? "PENDING" : "FAILED";
            results.add(new SessionDetailResponse.CueResultView(
                    snapshot.seqNo(),
                    state,
                    successReport != null,
                    failures,
                    successReport == null ? null : successReport.reportedAt()));
        }

        List<SessionDetailResponse.FrozenCueView> frozenSequence = snapshots.stream()
                .map(s -> new SessionDetailResponse.FrozenCueView(
                        s.seqNo(), s.cueType(), s.triggerWord(), s.durationSeconds()))
                .toList();
        return new SessionDetailResponse(toSessionResponse(session), frozenSequence, List.copyOf(results));
    }

    // ------------------------------------------------------------------
    // 私有辅助方法
    // ------------------------------------------------------------------

    private CueVersion requireVersion(String playCode, int versionNo) {
        CueVersion version = versionRepository.find(playCode, versionNo);
        if (version == null) {
            throw new CueNotFoundException("编排版本不存在: " + playCode + "#" + versionNo);
        }
        return version;
    }

    private RehearsalSession requireSession(String playCode, String sessionCode) {
        RehearsalSession session = sessionRepository.find(playCode, sessionCode);
        if (session == null) {
            throw new CueNotFoundException("排练场次不存在: " + playCode + "/" + sessionCode);
        }
        return session;
    }

    private RehearsalSession requireSessionEntity(long sessionId) {
        RehearsalSession session = sessionRepository.findById(sessionId);
        if (session == null) {
            throw new CueNotFoundException("排练场次不存在: " + sessionId);
        }
        return session;
    }

    private CueReport requireReport(long id) {
        CueReport report = reportRepository.findById(id);
        if (report == null) {
            throw new CueNotFoundException("回报记录不存在: " + id);
        }
        return report;
    }

    /**
     * 对版本内全部提示点重新执行整版本校验；任一无效都抛出异常使版本不能绑定/冻结。
     */
    private void revalidate(CueVersion version) {
        revalidate(version, versionRepository.findItems(version.id()));
    }

    private void revalidate(CueVersion version, List<CueItem> items) {
        List<CuePointRequest> cues = items.stream()
                .map(item -> new CuePointRequest(item.seqNo(), item.cueType(),
                        item.triggerWord(), item.durationSeconds()))
                .toList();
        try {
            validator.validate(cues);
        } catch (CueValidationException e) {
            throw new CueValidationException(List.of(
                    "版本 " + version.playCode() + "#" + version.versionNo() + " 重新校验未通过: "
                            + String.join("; ", e.getErrors())));
        }
    }

    private void validateReportPayload(ReportCueRequest request) {
        List<String> errors = new ArrayList<>();
        if (Boolean.FALSE.equals(request.success())
                && (request.failReason() == null || request.failReason().isBlank())) {
            errors.add("失败回报必须填写失败原因");
        }
        if (Boolean.TRUE.equals(request.success())
                && request.failReason() != null && !request.failReason().isBlank()) {
            errors.add("成功回报不能携带失败原因");
        }
        if (!errors.isEmpty()) {
            throw new CueValidationException(errors);
        }
    }

    private boolean sameParameters(CueReport existing, ReportCueRequest request) {
        return Objects.equals(existing.seqNo(), request.seqNo())
                && existing.success() == Boolean.TRUE.equals(request.success())
                && Objects.equals(trimToNull(existing.failReason()), trimToNull(request.failReason()))
                && Objects.equals(trimToNull(existing.operatorCode()), trimToNull(request.operatorCode()));
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private Map<Integer, VersionResponse.CuePointView> toMap(List<CueItem> items) {
        Map<Integer, VersionResponse.CuePointView> map = new LinkedHashMap<>();
        for (CueItem item : items) {
            map.put(item.seqNo(), new VersionResponse.CuePointView(
                    item.seqNo(), item.cueType(), item.triggerWord(), item.durationSeconds()));
        }
        return map;
    }

    private VersionResponse toVersionResponse(CueVersion version, List<CueItem> items) {
        List<VersionResponse.CuePointView> views = items.stream()
                .map(item -> new VersionResponse.CuePointView(
                        item.seqNo(), item.cueType(), item.triggerWord(), item.durationSeconds()))
                .toList();
        return new VersionResponse(version.id(), version.playCode(), version.versionNo(),
                views, version.createdAt());
    }

    private VersionResponse toVersionResponseFromRequests(CueVersion version, List<CuePointRequest> cues) {
        List<VersionResponse.CuePointView> views = cues.stream()
                .map(cue -> new VersionResponse.CuePointView(
                        cue.seqNo(), cue.cueType(), cue.triggerWord(), cue.durationSeconds()))
                .toList();
        return new VersionResponse(version.id(), version.playCode(), version.versionNo(),
                views, version.createdAt());
    }

    private SessionResponse toSessionResponse(RehearsalSession session) {
        return new SessionResponse(
                session.id(),
                session.playCode(),
                session.sessionCode(),
                session.status().name(),
                session.versionId(),
                session.frozenVersionId(),
                session.totalCues(),
                session.executedSeq(),
                session.frozenAt(),
                session.completedAt(),
                session.createdAt());
    }

    private ReportResponse toReportResponse(CueReport report, boolean replayed) {
        return new ReportResponse(
                report.id(),
                report.seqNo(),
                report.success(),
                report.failReason(),
                report.requestId(),
                report.operatorCode(),
                replayed,
                report.reportedAt());
    }
}
