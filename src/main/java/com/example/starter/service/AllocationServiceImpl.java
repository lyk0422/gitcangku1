package com.example.starter.service;

import com.example.starter.domain.OperationType;
import com.example.starter.domain.RequestStatus;
import com.example.starter.model.IdempotentCall;
import com.example.starter.model.ObservationRequest;
import com.example.starter.model.SuccessionEvent;
import com.example.starter.repository.IdempotentCallRepository;
import com.example.starter.repository.ObservationRequestRepository;
import com.example.starter.repository.SuccessionEventRepository;
import com.example.starter.web.ConflictException;
import com.example.starter.web.NotFoundException;
import com.example.starter.web.UnprocessableException;
import com.example.starter.web.dto.ApplyRequest;
import com.example.starter.web.dto.RequestView;
import com.example.starter.web.dto.SuccessionView;
import com.example.starter.web.dto.TimelineView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 观测窗口分配服务：申请、批准、撤销与候补接替。
 *
 * <p>并发裁决：申请在全局申请锁上串行取号；批准、撤销与候补接替在仪器锁上串行执行，
 * 仪器已分配窗口的区间唯一性由事务内重读最新数据保证，状态推进再以条件更新兜底。
 * 幂等记录与业务变更同事务写入，业务失败回滚即不占用幂等键。
 */
@Service
public class AllocationServiceImpl implements AllocationService {

    /** 申请事务串行取号使用的固定锁行。 */
    private static final String APPLY_LOCK = "__APPLY__";

    private final Clock clock;
    private final ObservationRequestRepository requestRepository;
    private final SuccessionEventRepository successionEventRepository;
    private final IdempotentCallRepository idempotentCallRepository;

    public AllocationServiceImpl(Clock clock,
                                 ObservationRequestRepository requestRepository,
                                 SuccessionEventRepository successionEventRepository,
                                 IdempotentCallRepository idempotentCallRepository) {
        this.clock = clock;
        this.requestRepository = requestRepository;
        this.successionEventRepository = successionEventRepository;
        this.idempotentCallRepository = idempotentCallRepository;
    }

    @Override
    @Transactional
    public RequestView apply(ApplyRequest request) {
        Instant now = Instant.now(clock);
        validateApplyRequest(request, now);

        // 申请串行取号：申请时间与申请编号对所有申请保持相同的全局先后顺序。
        requestRepository.lockInstrument(APPLY_LOCK);
        Optional<IdempotentCall> existing = findIdempotent(
                request.requestId(), OperationType.APPLY, fingerprintOf(request));
        if (existing.isPresent()) {
            return replay(existing.get());
        }

        long seq = requestRepository.nextSequence();
        ObservationRequest entity = new ObservationRequest();
        entity.setRequestNo(String.format("REQ-%06d", seq));
        entity.setProject(request.project());
        entity.setInstrumentId(request.instrumentId());
        entity.setResearchGroup(request.researchGroup());
        entity.setWindowStart(request.windowStart());
        entity.setWindowEnd(request.windowEnd());
        entity.setDurationMinutes(request.durationMinutes());
        entity.setStatus(RequestStatus.PENDING);
        entity.setRequestedAt(now);
        requestRepository.insert(entity);

        saveIdempotent(request.requestId(), OperationType.APPLY, fingerprintOf(request),
                entity.getRequestNo(), entity.getStatus(), now);
        return RequestView.of(entity);
    }

    @Override
    @Transactional
    public RequestView approve(String requestNo, String requestId) {
        requestRepository.lockInstrument(resolveInstrument(requestNo));
        ObservationRequest request = requireRequest(requestNo);

        String fingerprint = Fingerprints.sha256("APPROVE", requestNo);
        Optional<IdempotentCall> existing = findIdempotent(requestId, OperationType.APPROVE, fingerprint);
        if (existing.isPresent()) {
            return replay(existing.get());
        }

        Instant now = Instant.now(clock);
        if (request.getStatus() == RequestStatus.ALLOCATED) {
            throw new ConflictException("申请 " + requestNo + " 已分配，不能重复批准");
        }
        if (request.getStatus() == RequestStatus.CANCELLED) {
            throw new ConflictException("申请 " + requestNo + " 已撤销，不能批准");
        }
        if (!request.getWindowStart().isAfter(now)) {
            throw new UnprocessableException("窗口已开始或已结束，不能批准");
        }
        List<ObservationRequest> allocated = requestRepository.findAllocatedByInstrument(
                request.getInstrumentId());
        for (ObservationRequest other : allocated) {
            if (TimeWindows.overlaps(request.getWindowStart(), request.getWindowEnd(),
                    other.getWindowStart(), other.getWindowEnd())) {
                throw new ConflictException("与已分配窗口 " + other.getRequestNo()
                        + " 的时间区间重叠，仪器同一时刻最多一个已分配申请");
            }
        }

        if (requestRepository.markAllocated(requestNo, now, null) == 0) {
            throw new ConflictException("申请状态已被并发事务改变，批准失败");
        }
        ObservationRequest updated = requireRequest(requestNo);
        saveIdempotent(requestId, OperationType.APPROVE, fingerprint, requestNo,
                updated.getStatus(), now);
        return RequestView.of(updated);
    }

    @Override
    @Transactional
    public RequestView cancel(String requestNo, String researchGroup, String requestId) {
        requestRepository.lockInstrument(resolveInstrument(requestNo));
        ObservationRequest request = requireRequest(requestNo);

        String fingerprint = Fingerprints.sha256("CANCEL", requestNo, researchGroup);
        Optional<IdempotentCall> existing = findIdempotent(requestId, OperationType.CANCEL, fingerprint);
        if (existing.isPresent()) {
            return replay(existing.get());
        }

        Instant now = Instant.now(clock);
        if (!request.getResearchGroup().equals(researchGroup)) {
            throw new ConflictException("仅归属研究组可撤销自己的申请");
        }
        if (request.getStatus() == RequestStatus.CANCELLED) {
            throw new ConflictException("申请 " + requestNo + " 已撤销，重复撤销不会再次推进候补");
        }
        if (!request.getWindowStart().isAfter(now)) {
            throw new UnprocessableException("窗口已开始或已结束，不得撤销回收");
        }

        // 条件更新兜底：已被并发撤销时返回冲突，保证只推进一次候补。
        if (requestRepository.markCancelled(requestNo, now) == 0) {
            throw new ConflictException("申请状态已被并发事务改变，撤销失败");
        }

        promoteFirstFittingWaiter(request, now);

        ObservationRequest updated = requireRequest(requestNo);
        saveIdempotent(requestId, OperationType.CANCEL, fingerprint, requestNo,
                updated.getStatus(), now);
        return RequestView.of(updated);
    }

    @Override
    @Transactional(readOnly = true)
    public RequestView getRequest(String requestNo) {
        return RequestView.of(requireRequest(requestNo));
    }

    @Override
    @Transactional(readOnly = true)
    public TimelineView getTimeline(String instrumentId) {
        List<RequestView> allocated = requestRepository.findAllocatedByInstrument(instrumentId)
                .stream().map(RequestView::of).toList();
        List<RequestView> requests = requestRepository.findByInstrument(instrumentId)
                .stream().map(RequestView::of).toList();
        List<RequestView> waitingQueue = requestRepository.findWaitingQueue(instrumentId)
                .stream().map(RequestView::of).toList();
        List<SuccessionView> successions = successionEventRepository.findByInstrument(instrumentId)
                .stream().map(SuccessionView::of).toList();
        return new TimelineView(instrumentId, allocated, requests, waitingQueue, successions);
    }

    /**
     * 按申请时间、申请编号升序扫描候补队列，找到第一个能完整落入空档、
     * 且与当前已分配时间轴不重叠的申请接替；不合格的更早申请保留在队列中，
     * 不会跳过更早且同样可用的申请。持仪器锁执行，已分配列表即提交顺序裁决后的最新状态，
     * 因此并发批准空档内其他窗口后，候补不会造成双重占用。找不到合格候补时空档保持为空，
     * 撤销结果不受影响。
     */
    private void promoteFirstFittingWaiter(ObservationRequest vacated, Instant now) {
        List<ObservationRequest> waiters = requestRepository.findWaitingQueue(
                vacated.getInstrumentId());
        List<ObservationRequest> allocated = requestRepository.findAllocatedByInstrument(
                vacated.getInstrumentId());
        for (ObservationRequest waiter : waiters) {
            if (!waiter.getWindowStart().isAfter(now)) {
                // 窗口已经开始的候补无法再锁定，保持候补状态不参与接替。
                continue;
            }
            if (!TimeWindows.fitsWithin(waiter.getWindowStart(), waiter.getWindowEnd(),
                    vacated.getWindowStart(), vacated.getWindowEnd())) {
                continue;
            }
            if (overlapsAny(waiter, allocated)) {
                // 空档内已有并发提交的批准窗口，该候补会双重占用，跳过继续寻找。
                continue;
            }
            if (requestRepository.markAllocated(waiter.getRequestNo(), now,
                    vacated.getRequestNo()) == 0) {
                throw new ConflictException("候补申请状态已被并发事务改变，接替失败");
            }
            SuccessionEvent event = new SuccessionEvent();
            event.setInstrumentId(vacated.getInstrumentId());
            event.setVacatedRequestNo(vacated.getRequestNo());
            event.setPromotedRequestNo(waiter.getRequestNo());
            event.setGapStart(vacated.getWindowStart());
            event.setGapEnd(vacated.getWindowEnd());
            event.setCreatedAt(now);
            successionEventRepository.insert(event);
            return;
        }
    }

    private boolean overlapsAny(ObservationRequest candidate, List<ObservationRequest> allocated) {
        for (ObservationRequest other : allocated) {
            if (TimeWindows.overlaps(candidate.getWindowStart(), candidate.getWindowEnd(),
                    other.getWindowStart(), other.getWindowEnd())) {
                return true;
            }
        }
        return false;
    }

    private void validateApplyRequest(ApplyRequest request, Instant now) {
        if (!request.windowEnd().isAfter(request.windowStart())) {
            throw new UnprocessableException("窗口结束时刻必须晚于开始时刻（左闭右开）");
        }
        long windowMinutes = Duration.between(request.windowStart(), request.windowEnd()).toMinutes();
        if (request.durationMinutes() > windowMinutes) {
            throw new UnprocessableException("所需分钟数超过窗口时长，无法锁定整段窗口");
        }
        if (!request.windowStart().isAfter(now)) {
            throw new UnprocessableException("窗口开始时刻必须晚于当前时间，不接受已开始的窗口申请");
        }
    }

    private ObservationRequest requireRequest(String requestNo) {
        ObservationRequest request = requestRepository.findByRequestNo(requestNo);
        if (request == null) {
            throw new NotFoundException("申请 " + requestNo + " 不存在");
        }
        return request;
    }

    private String resolveInstrument(String requestNo) {
        ObservationRequest request = requestRepository.findByRequestNo(requestNo);
        if (request == null) {
            throw new NotFoundException("申请 " + requestNo + " 不存在");
        }
        return request.getInstrumentId();
    }

    /**
     * 读取幂等记录；若同 (requestId, operation) 已存在但参数指纹不同则 409，
     * 相同则由调用方重放首次结果。
     */
    private Optional<IdempotentCall> findIdempotent(String requestId, OperationType operation,
                                                    String fingerprint) {
        if (requestId == null || requestId.isBlank()) {
            return Optional.empty();
        }
        IdempotentCall call = idempotentCallRepository.find(requestId, operation);
        if (call == null) {
            return Optional.empty();
        }
        if (!call.getRequestFingerprint().equals(fingerprint)) {
            throw new ConflictException("requestId=" + requestId + " 已用于不同参数的"
                    + operation.name() + "请求，换参数需使用新的 requestId");
        }
        return Optional.of(call);
    }

    private RequestView replay(IdempotentCall call) {
        return RequestView.of(requireRequest(call.getResultRequestNo()));
    }

    private void saveIdempotent(String requestId, OperationType operation, String fingerprint,
                                String resultRequestNo, RequestStatus resultStatus, Instant now) {
        if (requestId == null || requestId.isBlank()) {
            return;
        }
        IdempotentCall call = new IdempotentCall();
        call.setRequestId(requestId);
        call.setOperation(operation);
        call.setRequestFingerprint(fingerprint);
        call.setResultRequestNo(resultRequestNo);
        call.setResultStatus(resultStatus);
        call.setCreatedAt(now);
        try {
            idempotentCallRepository.insert(call);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // 与无共同仪器锁的并发请求竞争同一 requestId：键已被占用，整事务回滚并返回 409。
            throw new ConflictException("requestId=" + requestId + " 已被并发请求占用，"
                    + "换参数需使用新的 requestId");
        }
    }

    private String fingerprintOf(ApplyRequest request) {
        return Fingerprints.sha256(
                "APPLY",
                nullToEmpty(request.project()),
                nullToEmpty(request.instrumentId()),
                nullToEmpty(request.researchGroup()),
                request.windowStart() == null ? "" : request.windowStart().toString(),
                request.windowEnd() == null ? "" : request.windowEnd().toString(),
                Integer.toString(request.durationMinutes()));
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
