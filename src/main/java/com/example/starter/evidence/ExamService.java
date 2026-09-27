package com.example.starter.evidence;

import com.example.starter.error.ApiException;
import com.example.starter.evidence.dto.BatchExamReservationRequest;
import com.example.starter.evidence.dto.BenchCreateRequest;
import com.example.starter.evidence.dto.BenchScheduleView;
import com.example.starter.evidence.dto.BenchView;
import com.example.starter.evidence.dto.CommandRequest;
import com.example.starter.evidence.dto.ExamCancelRequest;
import com.example.starter.evidence.dto.ExamCompleteRequest;
import com.example.starter.evidence.dto.ExamEventView;
import com.example.starter.evidence.dto.ExamReservationCreateRequest;
import com.example.starter.evidence.dto.ExamReservationDetailView;
import com.example.starter.evidence.dto.ExamReservationItem;
import com.example.starter.evidence.dto.ExamReservationView;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 证物检验预约核心服务。所有写操作在单事务内完成：
 * 先按固定顺序锁定证物行与检验台行（均按业务键排序，避免死锁），
 * 校验完整最终状态后一次性落库，最后写入幂等日志。
 * 预约/开始/完成/取消均追加不可变检验事件，与封签版本共同构成保管链；
 * 历史字段（原封签、结果摘要、新封签、各时刻）写入后不可改写。
 * 时段统一为 UTC 左闭右开区间 [startAt, endAt)。
 */
@Service
public class ExamService {

    static final String OP_EXAM_BENCH_CREATE = "EXAM_BENCH_CREATE";
    static final String OP_EXAM_RESERVE = "EXAM_RESERVE";
    static final String OP_EXAM_RESERVE_BATCH = "EXAM_RESERVE_BATCH";
    static final String OP_EXAM_START = "EXAM_START";
    static final String OP_EXAM_COMPLETE = "EXAM_COMPLETE";
    static final String OP_EXAM_CANCEL = "EXAM_CANCEL";

    static final String EVENT_RESERVED = "RESERVED";
    static final String EVENT_STARTED = "STARTED";
    static final String EVENT_COMPLETED = "COMPLETED";
    static final String EVENT_CANCELLED = "CANCELLED";

    private final EvidenceRepository evidenceRepository;
    private final ExamBenchRepository benchRepository;
    private final ExamReservationRepository reservationRepository;
    private final ExamEventRepository eventRepository;
    private final CommandLogRepository commandLogRepository;
    private final ObjectMapper objectMapper;
    private final EvidenceClock clock;

    public ExamService(EvidenceRepository evidenceRepository,
                       ExamBenchRepository benchRepository,
                       ExamReservationRepository reservationRepository,
                       ExamEventRepository eventRepository,
                       CommandLogRepository commandLogRepository,
                       ObjectMapper objectMapper,
                       EvidenceClock clock) {
        this.evidenceRepository = evidenceRepository;
        this.benchRepository = benchRepository;
        this.reservationRepository = reservationRepository;
        this.eventRepository = eventRepository;
        this.commandLogRepository = commandLogRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 注册检验台：容量为同一时段可承接的预约数上限。benchKey 全局唯一。
     */
    @Transactional
    public StoredResponse createBench(String actorId, BenchCreateRequest request, String requestHash) {
        Optional<StoredResponse> replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        if (benchRepository.findByKey(request.benchKey()).isPresent()) {
            throw ApiException.conflict("检验台已存在: " + request.benchKey());
        }
        benchRepository.insert(request.benchKey(), request.capacity(), LocalDateTime.now());
        ExamBench bench = benchRepository.findByKey(request.benchKey()).orElseThrow();
        return record(request.commandKey(), OP_EXAM_BENCH_CREATE, actorId, requestHash,
                201, toView(bench));
    }

    /**
     * 创建单条检验预约：证物须在库（SEALED）且封签正常，时段合法且不与台位容量冲突。
     */
    @Transactional
    public StoredResponse reserve(String actorId, ExamReservationCreateRequest request,
                                  String requestHash) {
        Optional<StoredResponse> replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        ExamReservationItem item = new ExamReservationItem(request.reservationKey(),
                request.evidenceKey(), request.benchKey(), request.startAt(), request.endAt());
        return doReserve(actorId, List.of(item), request.commandKey(), requestHash,
                OP_EXAM_RESERVE, false);
    }

    /**
     * 批量检验预约：先校验全部台位与证物，任一失败整批不落库（不留下任何占用）；
     * 全部合法时在同一事务中提交。
     */
    @Transactional
    public StoredResponse reserveBatch(String actorId, BatchExamReservationRequest request,
                                       String requestHash) {
        Optional<StoredResponse> replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        return doReserve(actorId, request.items(), request.commandKey(), requestHash,
                OP_EXAM_RESERVE_BATCH, true);
    }

    /**
     * 开始检验：仅预约人本人、预约仍为 RESERVED 且当前时刻处于预约时段内。
     * 同一事务内将证物转为 EXAMINATION、锁定原封签编号与版本、追加 STARTED 事件。
     */
    @Transactional
    public StoredResponse start(String actorId, String reservationKey,
                                CommandRequest request, String requestHash) {
        Optional<StoredResponse> replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        ExamReservation found = reservationRepository.findByKey(reservationKey)
                .orElseThrow(() -> ApiException.notFound("检验预约不存在: " + reservationKey));
        Evidence evidence = evidenceRepository.findByKeyForUpdate(found.evidenceKey())
                .orElseThrow(() -> ApiException.notFound("证物不存在: " + found.evidenceKey()));
        ExamReservation reservation = reservationRepository.findByKeyForUpdate(reservationKey)
                .orElseThrow(() -> ApiException.notFound("检验预约不存在: " + reservationKey));
        // 并发下本事务可能在行锁上等待；持锁后复查幂等日志，重放先提交事务的首次结果。
        replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        requireExaminer(reservation, actorId);
        if (reservation.status() != ExamReservationStatus.RESERVED) {
            throw ApiException.conflict("预约不是未开始状态，不能开始检验: " + reservationKey);
        }
        if (evidence.status() != EvidenceStatus.SEALED) {
            throw ApiException.conflict("证物不在库，不能开始检验: " + found.evidenceKey());
        }
        LocalDateTime nowUtc = clock.nowUtc();
        if (nowUtc.isBefore(reservation.startAt())) {
            throw ApiException.badRequest("未到预约开始时刻: 当前=" + nowUtc
                    + ", 要求不早于=" + reservation.startAt());
        }
        if (!nowUtc.isBefore(reservation.endAt())) {
            throw ApiException.badRequest("预约时段已结束: 当前=" + nowUtc
                    + ", 要求早于=" + reservation.endAt());
        }
        if (!reservationRepository.markStarted(reservation.id(), evidence.sealNo(),
                evidence.sealVersion(), nowUtc)) {
            throw ApiException.conflict("预约已被并发处理: " + reservationKey);
        }
        evidenceRepository.updateStatus(found.evidenceKey(), EvidenceStatus.EXAMINATION,
                LocalDateTime.now());
        eventRepository.insert(reservationKey, found.evidenceKey(), EVENT_STARTED, actorId,
                evidence.sealVersion(), "锁定原封签 " + evidence.sealNo()
                        + "（版本 " + evidence.sealVersion() + "）", nowUtc);
        ExamReservation started = reservationRepository.findByKey(reservationKey).orElseThrow();
        return record(request.commandKey(), OP_EXAM_START, actorId, requestHash,
                200, toView(started));
    }

    /**
     * 完成检验：仅预约人本人、预约须为 IN_PROGRESS；必须记录结果摘要与新封签校验
     * （新封签编号且校验通过），同一事务内证物换上新封签、版本递增并回到 SEALED。
     */
    @Transactional
    public StoredResponse complete(String actorId, String reservationKey,
                                   ExamCompleteRequest request, String requestHash) {
        Optional<StoredResponse> replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        ExamReservation found = reservationRepository.findByKey(reservationKey)
                .orElseThrow(() -> ApiException.notFound("检验预约不存在: " + reservationKey));
        Evidence evidence = evidenceRepository.findByKeyForUpdate(found.evidenceKey())
                .orElseThrow(() -> ApiException.notFound("证物不存在: " + found.evidenceKey()));
        ExamReservation reservation = reservationRepository.findByKeyForUpdate(reservationKey)
                .orElseThrow(() -> ApiException.notFound("检验预约不存在: " + reservationKey));
        // 并发下本事务可能在行锁上等待；持锁后复查幂等日志，重放先提交事务的首次结果。
        replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        requireExaminer(reservation, actorId);
        if (reservation.status() != ExamReservationStatus.IN_PROGRESS) {
            throw ApiException.conflict("预约不在检验中，不能完成: " + reservationKey);
        }
        if (evidence.status() != EvidenceStatus.EXAMINATION) {
            throw ApiException.conflict("证物不在检验中，不能完成: " + found.evidenceKey());
        }
        if (!request.newSealIntact()) {
            throw ApiException.unprocessable("新封签校验未通过，不能完成检验: " + reservationKey);
        }
        int newSealVersion = evidence.sealVersion() + 1;
        LocalDateTime nowUtc = clock.nowUtc();
        if (!reservationRepository.markCompleted(reservation.id(), request.resultSummary(),
                request.newSealNo(), newSealVersion, request.newSealNote(), nowUtc)) {
            throw ApiException.conflict("预约已被并发处理: " + reservationKey);
        }
        evidenceRepository.updateSealAndStatus(found.evidenceKey(), request.newSealNo(),
                newSealVersion, EvidenceStatus.SEALED, LocalDateTime.now());
        eventRepository.insert(reservationKey, found.evidenceKey(), EVENT_COMPLETED, actorId,
                newSealVersion, request.resultSummary(), nowUtc);
        ExamReservation completed = reservationRepository.findByKey(reservationKey).orElseThrow();
        return record(request.commandKey(), OP_EXAM_COMPLETE, actorId, requestHash,
                200, toView(completed));
    }

    /**
     * 取消预约：仅预约人本人、仅未开始（RESERVED）的预约可取消；取消后释放检验台与证物占用。
     */
    @Transactional
    public StoredResponse cancel(String actorId, String reservationKey,
                                 ExamCancelRequest request, String requestHash) {
        Optional<StoredResponse> replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        ExamReservation reservation = reservationRepository.findByKeyForUpdate(reservationKey)
                .orElseThrow(() -> ApiException.notFound("检验预约不存在: " + reservationKey));
        // 并发下本事务可能在行锁上等待；持锁后复查幂等日志，重放先提交事务的首次结果。
        replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        requireExaminer(reservation, actorId);
        if (reservation.status() != ExamReservationStatus.RESERVED) {
            throw ApiException.conflict("仅未开始的预约可取消: " + reservationKey);
        }
        LocalDateTime nowUtc = clock.nowUtc();
        if (!reservationRepository.markCancelled(reservation.id(), request.reason(), nowUtc)) {
            throw ApiException.conflict("预约已被并发处理: " + reservationKey);
        }
        eventRepository.insert(reservationKey, reservation.evidenceKey(), EVENT_CANCELLED, actorId,
                null, request.reason(), nowUtc);
        ExamReservation cancelled = reservationRepository.findByKey(reservationKey).orElseThrow();
        return record(request.commandKey(), OP_EXAM_CANCEL, actorId, requestHash,
                200, toView(cancelled));
    }

    /**
     * 预约明细：预约当前状态 + 该预约的完整事件链（按发生顺序）。
     */
    @Transactional(readOnly = true)
    public ExamReservationDetailView getReservation(String reservationKey) {
        ExamReservation reservation = reservationRepository.findByKey(reservationKey)
                .orElseThrow(() -> ApiException.notFound("检验预约不存在: " + reservationKey));
        List<ExamEventView> events = eventRepository.findByReservationKey(reservationKey)
                .stream().map(this::toView).toList();
        return new ExamReservationDetailView(toView(reservation), events);
    }

    /**
     * 按证物查询全部检验预约历史（按发生顺序）。
     */
    @Transactional(readOnly = true)
    public List<ExamReservationView> listByEvidence(String evidenceKey) {
        return reservationRepository.findByEvidenceKey(evidenceKey).stream()
                .map(this::toView).toList();
    }

    /**
     * 按证物查询全部检验事件（按发生顺序），用于保管链展示。
     */
    @Transactional(readOnly = true)
    public List<ExamEventView> listEventsByEvidence(String evidenceKey) {
        return eventRepository.findByEvidenceKey(evidenceKey).stream()
                .map(this::toView).toList();
    }

    /**
     * 检验台时段诊断：容量上限（要求值）、窗口内进行中预约实际数与剩余差额，
     * 以及窗口内进行中预约明细（按开始时刻与创建顺序稳定排序）。
     * 窗口为 UTC 左闭右开区间 [from, to)；缺省为当前时刻起 24 小时。
     */
    @Transactional(readOnly = true)
    public BenchScheduleView benchSchedule(String benchKey, LocalDateTime from, LocalDateTime to) {
        ExamBench bench = benchRepository.findByKey(benchKey)
                .orElseThrow(() -> ApiException.notFound("检验台不存在: " + benchKey));
        LocalDateTime windowFrom = from != null ? from : clock.nowUtc();
        LocalDateTime windowTo = to != null ? to : windowFrom.plusHours(24);
        if (!windowTo.isAfter(windowFrom)) {
            throw ApiException.badRequest("查询窗口结束时刻须晚于开始时刻: 实际开始=" + windowFrom
                    + ", 实际结束=" + windowTo);
        }
        List<ExamReservationView> reservations = reservationRepository
                .findActiveOverlapping(benchKey, windowFrom, windowTo)
                .stream().map(this::toView).toList();
        int active = reservations.size();
        return new BenchScheduleView(bench.benchKey(), bench.capacity(), active,
                bench.capacity() - active, reservations);
    }

    /**
     * 查询全部检验台（按注册顺序）。
     */
    @Transactional(readOnly = true)
    public List<BenchView> listBenches() {
        return benchRepository.findAll().stream().map(this::toView).toList();
    }

    /**
     * 批量预约核心：先锁定并校验全部证物与台位（含批内互相占用），全部合法才统一落库。
     * 任一校验失败抛出异常，整批回滚，不留下任何占用。
     *
     * @param asList true 时响应为预约视图列表（批量），false 为单个视图
     */
    private StoredResponse doReserve(String actorId, List<ExamReservationItem> items,
                                     String commandKey, String requestHash,
                                     String operation, boolean asList) {
        // 批内结构校验：预约键与证物不得重复（同一证物同一时刻只允许一条进行中预约）。
        Set<String> seenKeys = new HashSet<>();
        Set<String> seenEvidence = new HashSet<>();
        for (ExamReservationItem item : items) {
            if (!seenKeys.add(item.reservationKey())) {
                throw ApiException.badRequest("批内预约键重复: " + item.reservationKey());
            }
            if (!seenEvidence.add(item.evidenceKey())) {
                throw ApiException.badRequest("批内证物重复: " + item.evidenceKey());
            }
        }
        LocalDateTime nowUtc = clock.nowUtc();
        for (ExamReservationItem item : items) {
            requireValidSlot(item, nowUtc);
        }
        // 按业务键排序依次锁定全部证物行，保证并发下锁顺序一致。
        List<String> evidenceKeys = items.stream().map(ExamReservationItem::evidenceKey)
                .distinct().sorted().toList();
        Map<String, Evidence> evidenceByKey = new LinkedHashMap<>();
        for (String evidenceKey : evidenceKeys) {
            Evidence evidence = evidenceRepository.findByKeyForUpdate(evidenceKey)
                    .orElseThrow(() -> ApiException.notFound("证物不存在: " + evidenceKey));
            evidenceByKey.put(evidenceKey, evidence);
        }
        // 按业务键排序依次锁定全部检验台行。
        List<String> benchKeys = items.stream().map(ExamReservationItem::benchKey)
                .distinct().sorted().toList();
        Map<String, ExamBench> benchByKey = new HashMap<>();
        for (String benchKey : benchKeys) {
            ExamBench bench = benchRepository.findByKeyForUpdate(benchKey)
                    .orElseThrow(() -> ApiException.notFound("检验台不存在: " + benchKey));
            benchByKey.put(benchKey, bench);
        }
        // 持锁后复查幂等日志：并发重复命令在此重放先提交事务的首次结果。
        Optional<StoredResponse> replay = checkReplay(commandKey, requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        // 全量校验：证物状态、证物占用、预约键唯一、台位容量（含批内互相重叠）。
        Map<String, List<ExamReservationItem>> itemsByBench = new LinkedHashMap<>();
        for (ExamReservationItem item : items) {
            Evidence evidence = evidenceByKey.get(item.evidenceKey());
            requireReservable(evidence, item.evidenceKey());
            if (reservationRepository.findActiveByEvidenceKey(item.evidenceKey()).isPresent()) {
                throw ApiException.conflict("证物已存在进行中的检验预约: " + item.evidenceKey());
            }
            if (reservationRepository.findByKey(item.reservationKey()).isPresent()) {
                throw ApiException.conflict("预约键已存在: " + item.reservationKey());
            }
            itemsByBench.computeIfAbsent(item.benchKey(), k -> new ArrayList<>()).add(item);
        }
        for (Map.Entry<String, List<ExamReservationItem>> entry : itemsByBench.entrySet()) {
            ExamBench bench = benchByKey.get(entry.getKey());
            List<ExamReservationItem> benchItems = entry.getValue();
            for (int i = 0; i < benchItems.size(); i++) {
                ExamReservationItem item = benchItems.get(i);
                int overlapping = reservationRepository.countActiveOverlapping(
                        item.benchKey(), item.startAt(), item.endAt());
                // 批内同一台位上先于本项且时段重叠的预约也占用容量。
                for (int j = 0; j < i; j++) {
                    ExamReservationItem other = benchItems.get(j);
                    if (item.startAt().isBefore(other.endAt())
                            && item.endAt().isAfter(other.startAt())) {
                        overlapping++;
                    }
                }
                if (overlapping >= bench.capacity()) {
                    throw ApiException.conflict("检验台容量已满: " + item.benchKey()
                            + ", 时段内已占用=" + overlapping + ", 容量上限=" + bench.capacity()
                            + ", 本次申请=1");
                }
            }
        }
        // 全部校验通过，统一落库：预约记录 + RESERVED 事件。
        LocalDateTime createdAt = LocalDateTime.now();
        List<ExamReservationView> views = new ArrayList<>();
        for (ExamReservationItem item : items) {
            reservationRepository.insert(item.reservationKey(), item.evidenceKey(), item.benchKey(),
                    actorId, item.startAt(), item.endAt(), createdAt);
            eventRepository.insert(item.reservationKey(), item.evidenceKey(), EVENT_RESERVED,
                    actorId, null, "占用检验台 " + item.benchKey(), nowUtc);
            ExamReservation created = reservationRepository.findByKey(item.reservationKey())
                    .orElseThrow();
            views.add(toView(created));
        }
        Object body = asList ? views : views.get(0);
        return record(commandKey, operation, actorId, requestHash, 201, body);
    }

    private void requireValidSlot(ExamReservationItem item, LocalDateTime nowUtc) {
        if (!item.endAt().isAfter(item.startAt())) {
            throw ApiException.badRequest("预约结束时刻须晚于开始时刻: 实际开始=" + item.startAt()
                    + ", 实际结束=" + item.endAt());
        }
        if (item.startAt().isBefore(nowUtc)) {
            throw ApiException.badRequest("预约开始时刻不得早于服务端当前时刻: 实际开始=" + item.startAt()
                    + ", 当前=" + nowUtc);
        }
    }

    private void requireReservable(Evidence evidence, String evidenceKey) {
        switch (evidence.status()) {
            case SEALED -> {
                // 在库且封签正常，可预约。
            }
            case SEAL_BROKEN ->
                    throw ApiException.unprocessable("封条已异常，禁止检验预约: " + evidenceKey);
            case BORROWED ->
                    throw ApiException.conflict("证物借出未归还，禁止检验预约: " + evidenceKey);
            case TRANSFER_PENDING ->
                    throw ApiException.conflict("待接收交接期间禁止检验预约: " + evidenceKey);
            case EXAMINATION ->
                    throw ApiException.conflict("证物检验中，禁止重复检验预约: " + evidenceKey);
        }
    }

    private void requireExaminer(ExamReservation reservation, String actorId) {
        if (!reservation.examinerId().equals(actorId)) {
            throw ApiException.conflict("操作人不是该预约的检验室操作人: " + actorId);
        }
    }

    private Optional<StoredResponse> checkReplay(String commandKey, String requestHash) {
        return commandLogRepository.findByKey(commandKey).map(log -> {
            if (!log.requestHash().equals(requestHash)) {
                throw ApiException.conflict("幂等键已被不同参数使用: " + commandKey);
            }
            return new StoredResponse(log.responseStatus(), log.responseBody());
        });
    }

    private StoredResponse record(String commandKey, String operation, String actorId,
                                  String requestHash, int status, Object body) {
        String json = toJson(body);
        commandLogRepository.insert(commandKey, actorId, operation, requestHash, status, json,
                LocalDateTime.now());
        return new StoredResponse(status, json);
    }

    private String toJson(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new IllegalStateException("响应序列化失败", e);
        }
    }

    private BenchView toView(ExamBench bench) {
        return new BenchView(bench.benchKey(), bench.capacity(), bench.createdAt());
    }

    private ExamReservationView toView(ExamReservation reservation) {
        return new ExamReservationView(reservation.reservationKey(), reservation.evidenceKey(),
                reservation.benchKey(), reservation.examinerId(), reservation.startAt(),
                reservation.endAt(), reservation.status(), reservation.originalSealNo(),
                reservation.originalSealVersion(), reservation.startedAt(),
                reservation.resultSummary(), reservation.newSealNo(), reservation.newSealVersion(),
                reservation.newSealNote(), reservation.completedAt(), reservation.cancelReason(),
                reservation.cancelledAt(), reservation.createdAt());
    }

    private ExamEventView toView(ExamEvent event) {
        return new ExamEventView(event.reservationKey(), event.evidenceKey(), event.eventType(),
                event.actorId(), event.sealVersion(), event.detail(), event.createdAt());
    }
}
