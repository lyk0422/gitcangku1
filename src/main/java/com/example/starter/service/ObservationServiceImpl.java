package com.example.starter.service;

import com.example.starter.domain.ActionType;
import com.example.starter.domain.RequestStatus;
import com.example.starter.model.IdempotentCall;
import com.example.starter.model.ObservationRequest;
import com.example.starter.repository.IdempotentCallRepository;
import com.example.starter.repository.InstrumentRepository;
import com.example.starter.repository.InstrumentSlotRepository;
import com.example.starter.repository.ObservationRequestRepository;
import com.example.starter.web.dto.ApplyRequest;
import com.example.starter.web.dto.CancelResponse;
import com.example.starter.web.dto.PromotionView;
import com.example.starter.web.dto.RequestView;
import com.example.starter.web.dto.SlotView;
import com.example.starter.web.dto.WaitlistView;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * 观测窗口服务实现。
 *
 * <p>并发模型：所有写操作在事务内先对 instrument 行 {@code SELECT ... FOR UPDATE}，
 * 同一仪器的批准/撤销/接替因此严格按提交顺序裁决，配合重叠查询保证区间唯一。
 * 幂等键记录与业务变更同事务提交：业务失败整体回滚，不占用幂等键；
 * 相同 requestId 重放首次响应，换参数返回 409。
 */
@Service
public class ObservationServiceImpl implements ObservationService {

    /** 幂等记录初始态：业务尚未完成（仅同事务内可见，失败随回滚删除）。 */
    private static final String OUTCOME_RUNNING = "RUNNING";

    private final ObservationRequestRepository requestRepository;
    private final InstrumentRepository instrumentRepository;
    private final InstrumentSlotRepository slotRepository;
    private final IdempotentCallRepository idempotentCallRepository;
    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    public ObservationServiceImpl(ObservationRequestRepository requestRepository,
                                  InstrumentRepository instrumentRepository,
                                  InstrumentSlotRepository slotRepository,
                                  IdempotentCallRepository idempotentCallRepository,
                                  Clock clock,
                                  ObjectMapper objectMapper,
                                  PlatformTransactionManager transactionManager) {
        this.requestRepository = requestRepository;
        this.instrumentRepository = instrumentRepository;
        this.slotRepository = slotRepository;
        this.idempotentCallRepository = idempotentCallRepository;
        this.clock = clock;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public RequestView apply(String idempotencyKey, ApplyRequest request) {
        String fingerprint = "APPLY|" + writeJson(ActionType.APPLY, request);
        return runIdempotent(idempotencyKey, ActionType.APPLY, fingerprint, request.requestNo(),
                RequestView.class, () -> {
                    Instant now = clock.instant();
                    validateWindow(request, now);
                    instrumentRepository.lockAndEnsure(request.instrumentId(), now);
                    ObservationRequest saved;
                    try {
                        saved = requestRepository.insert(new ObservationRequest(
                                null, request.requestNo(), request.projectId(),
                                request.instrumentId(), request.startAt(), request.endAt(),
                                request.durationMinutes(), RequestStatus.PENDING, now, null, null));
                    } catch (DuplicateKeyException duplicateNo) {
                        throw new ConflictException("REQUEST_NO_DUPLICATE",
                                "申请编号已存在: " + request.requestNo());
                    }
                    RequestView view = toView(saved, Map.of());
                    return new Execution<>(view, RequestStatus.PENDING.name(), "申请已进入候补", null);
                });
    }

    @Override
    public RequestView approve(String idempotencyKey, String requestNo) {
        String fingerprint = "APPROVE|" + requestNo;
        return runIdempotent(idempotencyKey, ActionType.APPROVE, fingerprint, requestNo,
                RequestView.class, () -> {
                    Instant now = clock.instant();
                    ObservationRequest request = requireRequest(requestNo);
                    instrumentRepository.lockAndEnsure(request.instrumentId(), now);
                    // 锁获取期间可能有并发事务先行提交，必须按提交顺序重读最新状态
                    request = requestRepository.findByNo(requestNo).orElseThrow();
                    if (request.status() == RequestStatus.ALLOCATED
                            || request.status() == RequestStatus.PROMOTED) {
                        throw new ConflictException("ALREADY_ALLOCATED",
                                "申请已分配，不能重复批准: " + requestNo);
                    }
                    if (request.status() == RequestStatus.CANCELLED) {
                        throw new ConflictException("REQUEST_CANCELLED",
                                "申请已撤销，不能批准: " + requestNo);
                    }
                    if (slotRepository.existsOverlapping(
                            request.instrumentId(), request.startAt(), request.endAt())) {
                        throw new ConflictException("SLOT_OVERLAP",
                                "与已分配窗口重叠: instrument=" + request.instrumentId()
                                        + ", window=[" + request.startAt() + ", " + request.endAt() + ")");
                    }
                    requestRepository.updateStatus(request.id(), RequestStatus.ALLOCATED.name());
                    slotRepository.insert(request.instrumentId(), request.id(),
                            request.startAt(), request.endAt());
                    RequestView view = toView(
                            requestRepository.findById(request.id()).orElseThrow(), Map.of());
                    return new Execution<>(view, RequestStatus.ALLOCATED.name(), "已锁定整段窗口", null);
                });
    }

    @Override
    public CancelResponse cancel(String idempotencyKey, String requestNo, String projectId) {
        String fingerprint = "CANCEL|" + requestNo + "|" + (projectId == null ? "" : projectId);
        return runIdempotent(idempotencyKey, ActionType.CANCEL, fingerprint, requestNo,
                CancelResponse.class, () -> {
                    Instant now = clock.instant();
                    if (projectId == null || projectId.isBlank()) {
                        throw new ValidationException("MISSING_PROJECT", "撤销必须提供项目标识");
                    }
                    ObservationRequest request = requireRequest(requestNo);
                    if (!projectId.equals(request.projectId())) {
                        throw new ForbiddenException("NOT_OWNER",
                                "只能撤销本项目的申请: requestNo=" + requestNo);
                    }
                    instrumentRepository.lockAndEnsure(request.instrumentId(), now);
                    // 锁获取期间可能有并发事务先行提交，必须按提交顺序重读最新状态
                    request = requestRepository.findByNo(requestNo).orElseThrow();
                    if (request.status() == RequestStatus.CANCELLED) {
                        throw new ConflictException("ALREADY_CANCELLED",
                                "申请已撤销，不能重复撤销: " + requestNo);
                    }
                    boolean liveWindow = request.status() == RequestStatus.ALLOCATED
                            || request.status() == RequestStatus.PROMOTED;
                    if (liveWindow && !request.startAt().isAfter(now)) {
                        throw new ConflictException("WINDOW_STARTED",
                                "窗口已开始，不得回收: requestNo=" + requestNo
                                        + ", startAt=" + request.startAt());
                    }

                    Instant gapStart = null;
                    Instant gapEnd = null;
                    ObservationRequest promoted = null;
                    if (liveWindow) {
                        gapStart = request.startAt();
                        gapEnd = request.endAt();
                        slotRepository.deleteByRequestId(request.id());
                        promoted = promoteFromWaitlist(request, now);
                    }
                    requestRepository.updateStatus(request.id(), RequestStatus.CANCELLED.name());
                    ObservationRequest cancelled =
                            requestRepository.findById(request.id()).orElseThrow();

                    Map<Long, String> sourceNoById =
                            sourceNoMap(nonNullList(cancelled, promoted));
                    CancelResponse response = new CancelResponse(
                            toView(cancelled, sourceNoById),
                            promoted == null ? null : toView(promoted, sourceNoById),
                            gapStart, gapEnd);
                    return new Execution<>(response, RequestStatus.CANCELLED.name(),
                            promoted == null ? "已撤销，无合格候补接替" : "已撤销，候补已接替",
                            promoted == null ? null : promoted.requestNo());
                });
    }

    @Override
    public List<SlotView> timeline(String instrumentId) {
        return slotRepository.findTimelineByInstrument(instrumentId);
    }

    @Override
    public List<RequestView> requests(String instrumentId, String projectId) {
        List<ObservationRequest> all = requestRepository.findAll();
        Map<Long, String> sourceNoById = sourceNoMap(all);
        return all.stream()
                .filter(r -> instrumentId == null || instrumentId.isBlank()
                        || instrumentId.equals(r.instrumentId()))
                .filter(r -> projectId == null || projectId.isBlank()
                        || projectId.equals(r.projectId()))
                .map(r -> toView(r, sourceNoById))
                .toList();
    }

    @Override
    public WaitlistView waitlist(String instrumentId) {
        Map<Long, String> sourceNoById =
                sourceNoMap(requestRepository.findByInstrument(instrumentId));
        List<RequestView> waiting = requestRepository.findPendingByInstrument(instrumentId).stream()
                .map(r -> toView(r, sourceNoById))
                .toList();
        return new WaitlistView(instrumentId, waiting);
    }

    @Override
    public List<PromotionView> promotions() {
        return requestRepository.findPromotions().stream()
                .map(promoted -> {
                    ObservationRequest source = promoted.promotedFromRequestId() == null
                            ? null
                            : requestRepository.findById(promoted.promotedFromRequestId())
                                    .orElse(null);
                    return new PromotionView(
                            source == null ? null : source.requestNo(),
                            promoted.requestNo(),
                            promoted.instrumentId(),
                            source == null ? null : source.startAt(),
                            source == null ? null : source.endAt(),
                            promoted.promotedAt());
                })
                .toList();
    }

    /**
     * 按候补顺序（申请时间、申请编号升序）寻找首个<b>完整</b>落入空档且尚未开始的申请接替。
     * 前面的候补不合格就继续向后找，不跳过更早且同样可用的申请；只接替一个。
     * 找不到合格者时空档保持空闲，不产生任何占用。
     */
    private ObservationRequest promoteFromWaitlist(ObservationRequest cancelled, Instant now) {
        List<ObservationRequest> pending =
                requestRepository.findPendingByInstrument(cancelled.instrumentId());
        for (ObservationRequest candidate : pending) {
            boolean notStarted = candidate.startAt().isAfter(now);
            boolean fitsCompletely = !candidate.startAt().isBefore(cancelled.startAt())
                    && !candidate.endAt().isAfter(cancelled.endAt());
            if (notStarted && fitsCompletely) {
                requestRepository.markPromoted(candidate.id(), cancelled.id(), now);
                slotRepository.insert(candidate.instrumentId(), candidate.id(),
                        candidate.startAt(), candidate.endAt());
                return requestRepository.findById(candidate.id()).orElseThrow();
            }
        }
        return null;
    }

    /**
     * 申请窗口语义校验：结束必须晚于开始；所需时长不得超过窗口时长。
     */
    private void validateWindow(ApplyRequest request, Instant now) {
        if (!request.endAt().isAfter(request.startAt())) {
            throw new ValidationException("END_NOT_AFTER_START",
                    "结束时刻必须晚于开始时刻: startAt=" + request.startAt()
                            + ", endAt=" + request.endAt());
        }
        long windowMinutes = Duration.between(request.startAt(), request.endAt()).toMinutes();
        if (request.durationMinutes() < 1) {
            throw new ValidationException("DURATION_TOO_SHORT", "所需分钟数必须大于0");
        }
        if (request.durationMinutes() > windowMinutes) {
            throw new ValidationException("DURATION_EXCEEDS_WINDOW",
                    "所需分钟数 " + request.durationMinutes()
                            + " 超过窗口时长 " + windowMinutes + " 分钟");
        }
    }

    private ObservationRequest requireRequest(String requestNo) {
        return requestRepository.findByNo(requestNo)
                .orElseThrow(() -> new NotFoundException("REQUEST_NOT_FOUND",
                        "申请不存在: " + requestNo));
    }

    private RequestView toView(ObservationRequest request, Map<Long, String> sourceNoById) {
        String sourceNo = request.promotedFromRequestId() == null
                ? null
                : sourceNoById.get(request.promotedFromRequestId());
        return RequestView.from(request, sourceNo);
    }

    /** 去掉可能为 null 的结果（如撤销时无候补接替），便于统一构造视图。 */
    private List<ObservationRequest> nonNullList(ObservationRequest... requests) {
        List<ObservationRequest> list = new java.util.ArrayList<>(requests.length);
        for (ObservationRequest request : requests) {
            if (request != null) {
                list.add(request);
            }
        }
        return list;
    }

    private Map<Long, String> sourceNoMap(List<ObservationRequest> requests) {        Map<Long, String> byId = requests.stream()
                .filter(r -> r.id() != null)
                .collect(Collectors.toMap(ObservationRequest::id, ObservationRequest::requestNo));
        requests.stream()
                .map(ObservationRequest::promotedFromRequestId)
                .filter(id -> id != null && !byId.containsKey(id))
                .distinct()
                .forEach(id -> requestRepository.findById(id)
                        .ifPresent(source -> byId.put(source.id(), source.requestNo())));
        return byId;
    }

    private String writeJson(ActionType action, Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("无法序列化 " + action + " 参数", e);
        }
    }

    /**
     * 幂等执行模板。
     *
     * <p>无幂等键时直接执行。有键时：先查首次记录，命中则校验指纹并重放；
     * 未命中则在业务事务内先插入 RUNNING 记录占位，与业务同提交同回滚。
     * 并发下第二个相同键插入冲突后对该行加锁等待首个事务结束，再重放其结果。
     */
    private <T> T runIdempotent(String idempotencyKey, ActionType action, String fingerprint,
                                String targetRequestNo, Class<T> responseType,
                                Supplier<Execution<T>> body) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return transactionTemplate.execute(status -> body.get()).response();
        }
        IdempotentCall existing = idempotentCallRepository.findByRequestId(idempotencyKey).orElse(null);
        if (existing != null) {
            verifyFingerprint(existing, action, fingerprint);
            return readSnapshot(existing, responseType);
        }
        try {
            return transactionTemplate.execute(status -> {
                boolean acquired = tryInsertMarker(idempotencyKey, action, fingerprint, targetRequestNo);
                if (!acquired) {
                    // 同键的另一个事务正在执行：行锁等待其结束
                    IdempotentCall first = waitForCall(idempotencyKey);
                    if (first != null) {
                        verifyFingerprint(first, action, fingerprint);
                        throw new ReplaySentinel(first.responseSnapshot(), responseType);
                    }
                    // 首个事务失败回滚（键未被占用），本事务接手执行
                    tryInsertMarkerOrThrow(idempotencyKey, action, fingerprint, targetRequestNo);
                }
                Execution<T> execution = body.get();
                String snapshot = writeJson(action, execution.response());
                idempotentCallRepository.complete(idempotencyKey, execution.outcomeStatus(),
                        execution.message(), execution.promotedRequestNo(), snapshot, clock.instant());
                return execution.response();
            });
        } catch (ReplaySentinel replay) {
            @SuppressWarnings("unchecked")
            T response = (T) parseSnapshot(replay.snapshot(), replay.responseType());
            return response;
        }
    }

    private boolean tryInsertMarker(String key, ActionType action, String fingerprint, String target) {
        try {
            tryInsertMarkerOrThrow(key, action, fingerprint, target);
            return true;
        } catch (DuplicateKeyException concurrent) {
            return false;
        }
    }

    private void tryInsertMarkerOrThrow(String key, ActionType action, String fingerprint, String target) {
        idempotentCallRepository.insert(new IdempotentCall(
                key, action, fingerprint, target, OUTCOME_RUNNING, null,
                OUTCOME_RUNNING, null, null, clock.instant()));
    }

    /**
     * 对幂等记录行加锁等待并发首个事务提交/回滚；返回其最终记录，回滚消失则返回 null。
     */
    private IdempotentCall waitForCall(String key) {
        idempotentCallRepository.lockForUpdate(key);
        return idempotentCallRepository.findByRequestId(key).orElse(null);
    }

    private void verifyFingerprint(IdempotentCall call, ActionType action, String fingerprint) {
        if (call.action() != action || !call.fingerprint().equals(fingerprint)) {
            throw new ConflictException("IDEMPOTENCY_KEY_CONFLICT",
                    "幂等键已用于不同的请求参数: requestId=" + call.requestId());
        }
    }

    private <T> T readSnapshot(IdempotentCall call, Class<T> responseType) {
        return parseSnapshot(call.responseSnapshot(), responseType);
    }

    private <T> T parseSnapshot(String snapshot, Class<T> responseType) {
        if (snapshot == null) {
            throw new IllegalStateException("幂等记录缺少响应快照: " + responseType.getSimpleName());
        }
        try {
            return objectMapper.readValue(snapshot, responseType);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("无法解析幂等响应快照", e);
        }
    }

    /**
     * 一次业务执行的结果及落库所需的摘要信息。
     */
    private record Execution<T>(
            T response, String outcomeStatus, String message, String promotedRequestNo) {
    }

    /**
     * 并发重放信号：携带已提交事务的响应快照，事务外解析返回。
     */
    private static final class ReplaySentinel extends RuntimeException {

        private final String snapshot;
        private final Class<?> responseType;

        private ReplaySentinel(String snapshot, Class<?> responseType) {
            super(null, null, false, false);
            this.snapshot = snapshot;
            this.responseType = responseType;
        }

        private String snapshot() {
            return snapshot;
        }

        private Class<?> responseType() {
            return responseType;
        }
    }
}
