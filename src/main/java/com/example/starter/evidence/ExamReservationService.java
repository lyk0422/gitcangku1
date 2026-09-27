package com.example.starter.evidence;

import com.example.starter.error.ApiException;
import com.example.starter.evidence.dto.BenchOccupancyView;
import com.example.starter.evidence.dto.ExamCancelRequest;
import com.example.starter.evidence.dto.ExamChainEventView;
import com.example.starter.evidence.dto.ExamCompleteRequest;
import com.example.starter.evidence.dto.ExamFailureDetail;
import com.example.starter.evidence.dto.ExamReserveRequest;
import com.example.starter.evidence.dto.ExamReserveRequest.ExamReservationItemRequest;
import com.example.starter.evidence.dto.ExamReserveResultView;
import com.example.starter.evidence.dto.ExamReservationView;
import com.example.starter.evidence.dto.ExamStartRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 证物检验预约核心服务。全部写操作在单事务内完成：
 * 先按固定顺序锁定检验台行与证物行（ORDER BY id，防交叉死锁），再校验完整最终状态
 * （台位容量含本批全部新增占用），全部通过后才一次性插入预约与保管链事件并提交；
 * 任一明细失败整批回滚，不留下任何占用。预约、开始、完成、取消均只追加不可变保管链事件。
 */
@Service
public class ExamReservationService {

    static final String OP_EXAM_RESERVE = "EXAM_RESERVE";
    static final String OP_EXAM_START = "EXAM_START";
    static final String OP_EXAM_COMPLETE = "EXAM_COMPLETE";
    static final String OP_EXAM_CANCEL = "EXAM_CANCEL";

    static final String WINDOW_INVALID = "WINDOW_INVALID";
    static final String EVIDENCE_NOT_FOUND = "EVIDENCE_NOT_FOUND";
    static final String BENCH_NOT_FOUND = "BENCH_NOT_FOUND";
    static final String EVIDENCE_BORROWED = "EVIDENCE_BORROWED";
    static final String EVIDENCE_IN_EXAMINATION = "EVIDENCE_IN_EXAMINATION";
    static final String EVIDENCE_PENDING_TRANSFER = "EVIDENCE_PENDING_TRANSFER";
    static final String SEAL_ABNORMAL = "SEAL_ABNORMAL";
    static final String EVIDENCE_OCCUPIED = "EVIDENCE_OCCUPIED";
    static final String DUPLICATE_EVIDENCE = "DUPLICATE_EVIDENCE";
    static final String DUPLICATE_RESERVATION_KEY = "DUPLICATE_RESERVATION_KEY";
    static final String BENCH_CAPACITY_EXCEEDED = "BENCH_CAPACITY_EXCEEDED";

    private final EvidenceRepository evidenceRepository;
    private final ExamBenchRepository benchRepository;
    private final ExamReservationRepository reservationRepository;
    private final ExamChainEventRepository eventRepository;
    private final CommandLogRepository commandLogRepository;
    private final ObjectMapper objectMapper;
    private final EvidenceClock clock;

    public ExamReservationService(EvidenceRepository evidenceRepository,
                                  ExamBenchRepository benchRepository,
                                  ExamReservationRepository reservationRepository,
                                  ExamChainEventRepository eventRepository,
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
     * 批量创建检验预约（UTC 左闭右开时段）。先校验全部明细的最终状态再统一提交，
     * 任一明细失败整批回滚，不留下台位或证物占用；失败响应携带逐条可区分原因。
     */
    @Transactional
    public StoredResponse reserve(String actorId, ExamReserveRequest request, String requestHash) {
        Optional<StoredResponse> replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        List<ExamReservationItemRequest> items = request.items();
        List<ExamFailureDetail> failures = new ArrayList<>();

        // 第一阶段：不依赖行锁的静态校验（时段、批内重复、预约键已存在）。
        Map<String, Integer> evidenceIndexes = new LinkedHashMap<>();
        Map<String, Integer> reservationKeyIndexes = new LinkedHashMap<>();
        for (int i = 0; i < items.size(); i++) {
            ExamReservationItemRequest item = items.get(i);
            long durationMinutes = Duration.between(item.startAt(), item.endAt()).toMinutes();
            if (!item.endAt().isAfter(item.startAt())) {
                failures.add(new ExamFailureDetail(i, WINDOW_INVALID, item.reservationKey(),
                        item.evidenceKey(), item.benchKey(),
                        Math.toIntExact(durationMinutes), 0, Math.toIntExact(durationMinutes),
                        "预约时段结束时刻必须晚于开始时刻（UTC 左闭右开），实际时长(分钟)=" + durationMinutes));
            }
            if (evidenceIndexes.putIfAbsent(item.evidenceKey(), i) != null) {
                failures.add(ExamFailureDetail.of(i, DUPLICATE_EVIDENCE, item.reservationKey(),
                        item.evidenceKey(), item.benchKey(),
                        "同批预约中同一证物只能出现一次: " + item.evidenceKey()));
            }
            if (reservationKeyIndexes.putIfAbsent(item.reservationKey(), i) != null
                    || reservationRepository.findByKey(item.reservationKey()).isPresent()) {
                failures.add(ExamFailureDetail.of(i, DUPLICATE_RESERVATION_KEY, item.reservationKey(),
                        item.evidenceKey(), item.benchKey(),
                        "预约键已存在或在同批中重复: " + item.reservationKey()));
            }
        }
        if (!failures.isEmpty()) {
            throw batchFailure(failures);
        }

        // 第二阶段：按固定顺序加锁——先检验台（按主键升序），再证物（按主键升序），避免交叉死锁。
        List<String> benchKeys = items.stream().map(ExamReservationItemRequest::benchKey)
                .distinct().sorted().toList();
        Map<String, ExamBench> benches = lockBenches(benchKeys);
        List<Evidence> evidences =
                evidenceRepository.findByKeysForUpdateOrdered(
                        items.stream().map(ExamReservationItemRequest::evidenceKey).toList());
        Map<String, Evidence> evidenceByKey = evidences.stream()
                .collect(Collectors.toMap(Evidence::evidenceKey, e -> e));

        // 并发持锁后复查幂等日志：重放先提交事务的首次结果。
        replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }

        // 按检验台归集本批明细，容量按“已提交占位 ∪ 本批全部新增”的完整最终状态计算。
        Map<String, List<ExamReservationItemRequest>> itemsByBench = items.stream()
                .collect(Collectors.groupingBy(ExamReservationItemRequest::benchKey, LinkedHashMap::new,
                        Collectors.toList()));

        for (int i = 0; i < items.size(); i++) {
            ExamReservationItemRequest item = items.get(i);
            ExamBench bench = benches.get(item.benchKey());
            if (bench == null) {
                failures.add(ExamFailureDetail.of(i, BENCH_NOT_FOUND, item.reservationKey(),
                        item.evidenceKey(), item.benchKey(),
                        "检验台不存在: " + item.benchKey()));
                continue;
            }
            Evidence evidence = evidenceByKey.get(item.evidenceKey());
            if (evidence == null) {
                failures.add(ExamFailureDetail.of(i, EVIDENCE_NOT_FOUND, item.reservationKey(),
                        item.evidenceKey(), item.benchKey(),
                        "证物不存在: " + item.evidenceKey()));
                continue;
            }
            switch (evidence.status()) {
                case SEAL_BROKEN -> failures.add(ExamFailureDetail.of(i, SEAL_ABNORMAL,
                        item.reservationKey(), item.evidenceKey(), item.benchKey(),
                        "封签异常（SEAL_BROKEN）证物禁止检验预约: " + item.evidenceKey()));
                case BORROWED -> failures.add(ExamFailureDetail.of(i, EVIDENCE_BORROWED,
                        item.reservationKey(), item.evidenceKey(), item.benchKey(),
                        "证物借出未归还，禁止检验预约: " + item.evidenceKey()));
                case EXAMINATION -> failures.add(ExamFailureDetail.of(i, EVIDENCE_IN_EXAMINATION,
                        item.reservationKey(), item.evidenceKey(), item.benchKey(),
                        "证物正在检验中，禁止再次预约: " + item.evidenceKey()));
                case TRANSFER_PENDING -> failures.add(ExamFailureDetail.of(i, EVIDENCE_PENDING_TRANSFER,
                        item.reservationKey(), item.evidenceKey(), item.benchKey(),
                        "证物存在待接收交接，禁止检验预约: " + item.evidenceKey()));
                case SEALED -> {
                    List<ExamReservation> occupied = reservationRepository.findOverlappingByEvidenceKey(
                            item.evidenceKey(), item.startAt(), item.endAt());
                    if (!occupied.isEmpty()) {
                        ExamReservation blocker = occupied.get(0);
                        failures.add(ExamFailureDetail.of(i, EVIDENCE_OCCUPIED,
                                item.reservationKey(), item.evidenceKey(), item.benchKey(),
                                "证物在该时段已有预约或正在检验，冲突预约=" + blocker.reservationKey()
                                        + " 占用区间=[" + blocker.startAt() + "," + blocker.endAt() + ")"));
                    }
                }
            }

            // 台位容量：已提交占位 + 本批同台位且与当前明细时段重叠的全部明细（含自身）。
            int committed = reservationRepository.countOverlappingOccupancy(
                    item.benchKey(), item.startAt(), item.endAt());
            int incoming = 0;
            for (ExamReservationItemRequest other : itemsByBench.get(item.benchKey())) {
                if (other.startAt().isBefore(item.endAt()) && other.endAt().isAfter(item.startAt())) {
                    incoming++;
                }
            }
            int finalOccupancy = committed + incoming;
            if (finalOccupancy > bench.capacity()) {
                failures.add(new ExamFailureDetail(i, BENCH_CAPACITY_EXCEEDED,
                        item.reservationKey(), item.evidenceKey(), item.benchKey(),
                        finalOccupancy, bench.capacity(), finalOccupancy - bench.capacity(),
                        "检验台容量不足: 实际最终占用=" + finalOccupancy + " 要求上限=" + bench.capacity()
                                + " 超出=" + (finalOccupancy - bench.capacity())
                                + " 台位=" + item.benchKey()
                                + " 窗口=[" + item.startAt() + "," + item.endAt() + ")"));
            }
        }

        if (!failures.isEmpty()) {
            throw batchFailure(failures);
        }

        // 全部明细可行：一次性写入完整最终状态。
        LocalDateTime nowUtc = clock.nowUtc();
        List<ExamReservationView> views = new ArrayList<>();
        for (ExamReservationItemRequest item : items) {
            reservationRepository.insert(item.reservationKey(), item.evidenceKey(), item.benchKey(),
                    item.startAt(), item.endAt(), actorId, LocalDateTime.now());
            ExamReservation created =
                    reservationRepository.findByKey(item.reservationKey()).orElseThrow();
            Evidence evidence = evidenceByKey.get(item.evidenceKey());
            eventRepository.insert(item.reservationKey(), item.evidenceKey(), item.benchKey(),
                    ExamChainEventType.RESERVED, actorId, evidence.sealNo(), evidence.sealVersion(),
                    null, null, null, nowUtc, LocalDateTime.now());
            views.add(toView(created));
        }
        views.sort(Comparator.comparing(ExamReservationView::startAt)
                .thenComparing(ExamReservationView::reservationKey));
        return record(request.commandKey(), OP_EXAM_RESERVE, actorId, requestHash,
                201, new ExamReserveResultView(views.size(), views));
    }

    /**
     * 开始检验：同一事务内把证物转为 EXAMINATION、写入 STARTED 临时保管链记录并锁定原封签版本快照。
     * 仅预约操作人可执行；开始时证物必须仍在库且封签正常。
     */
    @Transactional
    public StoredResponse start(String actorId, String reservationKey,
                                ExamStartRequest request, String requestHash) {
        Optional<StoredResponse> replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        ExamReservation reservation = lockReservation(reservationKey);
        Evidence evidence = lockEvidence(reservation.evidenceKey());
        replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        requireOperator(reservation, actorId);
        if (reservation.status() != ExamReservationStatus.RESERVED) {
            throw ApiException.conflict("仅未开始（RESERVED）预约可开始检验，当前状态="
                    + reservation.status() + " 预约=" + reservationKey);
        }
        if (evidence.status() == EvidenceStatus.SEAL_BROKEN) {
            throw ApiException.unprocessable("封签已异常，禁止开始检验: " + evidence.evidenceKey());
        }
        if (evidence.status() != EvidenceStatus.SEALED) {
            throw ApiException.conflict("证物当前不在库（状态=" + evidence.status()
                    + "），禁止开始检验: " + evidence.evidenceKey());
        }
        LocalDateTime nowUtc = clock.nowUtc();
        if (!reservationRepository.markStarted(reservation.id(), nowUtc, evidence.sealNo(),
                evidence.sealVersion(), LocalDateTime.now())) {
            throw ApiException.conflict("预约已被并发处理: " + reservationKey);
        }
        evidenceRepository.updateStatus(evidence.evidenceKey(), EvidenceStatus.EXAMINATION,
                LocalDateTime.now());
        eventRepository.insert(reservationKey, evidence.evidenceKey(), reservation.benchKey(),
                ExamChainEventType.STARTED, actorId, evidence.sealNo(), evidence.sealVersion(),
                null, null, null, nowUtc, LocalDateTime.now());
        ExamReservation updated = reservationRepository.findByKey(reservationKey).orElseThrow();
        return record(request.commandKey(), OP_EXAM_START, actorId, requestHash, 200, toView(updated));
    }

    /**
     * 完成检验：必须记录结果摘要与新封签编号、校验值（且新封签不同于锁定的原封签），
     * 封签版本递增，证物才回到在库 SEALED；COMPLETED 事件保存结果与新版本快照。
     */
    @Transactional
    public StoredResponse complete(String actorId, String reservationKey,
                                   ExamCompleteRequest request, String requestHash) {
        Optional<StoredResponse> replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        ExamReservation reservation = lockReservation(reservationKey);
        Evidence evidence = lockEvidence(reservation.evidenceKey());
        replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        requireOperator(reservation, actorId);
        if (reservation.status() != ExamReservationStatus.IN_PROGRESS) {
            throw ApiException.conflict("仅检验中（IN_PROGRESS）预约可完成，当前状态="
                    + reservation.status() + " 预约=" + reservationKey);
        }
        if (request.newSealNo().equals(reservation.lockedSealNo())) {
            throw ApiException.badRequest("新封签编号必须不同于原封签编号: " + request.newSealNo());
        }
        int newSealVersion = reservation.lockedSealVersion() + 1;
        LocalDateTime nowUtc = clock.nowUtc();
        if (!reservationRepository.markCompleted(reservation.id(), nowUtc, request.resultSummary(),
                request.newSealNo(), request.newSealCheck(), LocalDateTime.now())) {
            throw ApiException.conflict("预约已被并发处理: " + reservationKey);
        }
        evidenceRepository.updateSeal(evidence.evidenceKey(), request.newSealNo(), newSealVersion,
                EvidenceStatus.SEALED, LocalDateTime.now());
        eventRepository.insert(reservationKey, evidence.evidenceKey(), reservation.benchKey(),
                ExamChainEventType.COMPLETED, actorId, request.newSealNo(), newSealVersion,
                request.resultSummary(), request.newSealNo(), request.newSealCheck(),
                nowUtc, LocalDateTime.now());
        ExamReservation updated = reservationRepository.findByKey(reservationKey).orElseThrow();
        return record(request.commandKey(), OP_EXAM_COMPLETE, actorId, requestHash,
                200, toView(updated));
    }

    /**
     * 取消预约：仅未开始（RESERVED）可取消，取消后释放台位与证物；已开始预约不可取消。
     */
    @Transactional
    public StoredResponse cancel(String actorId, String reservationKey,
                                 ExamCancelRequest request, String requestHash) {
        Optional<StoredResponse> replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        ExamReservation reservation = lockReservation(reservationKey);
        Evidence evidence = evidenceRepository.findByKey(reservation.evidenceKey())
                .orElseThrow(() -> ApiException.notFound("证物不存在: " + reservation.evidenceKey()));
        replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        requireOperator(reservation, actorId);
        if (reservation.status() == ExamReservationStatus.IN_PROGRESS) {
            throw ApiException.conflict("检验已开始，禁止取消: " + reservationKey);
        }
        if (reservation.status() != ExamReservationStatus.RESERVED) {
            throw ApiException.conflict("预约已终结（" + reservation.status()
                    + "），不可取消: " + reservationKey);
        }
        LocalDateTime nowUtc = clock.nowUtc();
        if (!reservationRepository.markCancelled(reservation.id(), nowUtc, LocalDateTime.now())) {
            throw ApiException.conflict("预约已被并发处理: " + reservationKey);
        }
        eventRepository.insert(reservationKey, evidence.evidenceKey(), reservation.benchKey(),
                ExamChainEventType.CANCELLED, actorId, evidence.sealNo(), evidence.sealVersion(),
                null, null, null, nowUtc, LocalDateTime.now());
        ExamReservation updated = reservationRepository.findByKey(reservationKey).orElseThrow();
        return record(request.commandKey(), OP_EXAM_CANCEL, actorId, requestHash,
                200, toView(updated));
    }

    /**
     * 预约明细查询（只读，不改变状态）。
     */
    @Transactional(readOnly = true)
    public ExamReservationView getReservation(String reservationKey) {
        return toView(reservationRepository.findByKey(reservationKey)
                .orElseThrow(() -> ApiException.notFound("检验预约不存在: " + reservationKey)));
    }

    /**
     * 证物检验历史：全部预约明细与全部保管链事件（均按发生顺序，只读）。
     */
    @Transactional(readOnly = true)
    public ExamHistoryView history(String evidenceKey) {
        if (evidenceRepository.findByKey(evidenceKey).isEmpty()) {
            throw ApiException.notFound("证物不存在: " + evidenceKey);
        }
        List<ExamReservationView> reservations = reservationRepository.findByEvidenceKey(evidenceKey)
                .stream().map(this::toView).toList();
        List<ExamChainEventView> events = eventRepository.findByEvidenceKey(evidenceKey)
                .stream().map(this::toView).toList();
        return new ExamHistoryView(evidenceKey, reservations, events);
    }

    /**
     * 台位占用诊断：给定时段（UTC 左闭右开）内实际占用、容量要求、剩余差额与重叠明细；只读。
     */
    @Transactional(readOnly = true)
    public BenchOccupancyView occupancy(String benchKey, LocalDateTime startAt, LocalDateTime endAt) {
        if (!endAt.isAfter(startAt)) {
            throw ApiException.badRequest("诊断窗口结束时刻必须晚于开始时刻（UTC 左闭右开）");
        }
        ExamBench bench = benchRepository.findByKey(benchKey)
                .orElseThrow(() -> ApiException.notFound("检验台不存在: " + benchKey));
        List<ExamReservationView> overlapping =
                reservationRepository.findOverlappingOccupancy(benchKey, startAt, endAt).stream()
                        .map(this::toView).toList();
        return new BenchOccupancyView(benchKey, startAt, endAt, overlapping.size(), bench.capacity(),
                bench.capacity() - overlapping.size(), overlapping);
    }

    /**
     * 证物检验历史视图：预约明细 + 不可变保管链事件。
     */
    public record ExamHistoryView(
            String evidenceKey,
            List<ExamReservationView> reservations,
            List<ExamChainEventView> events) {
    }

    private Map<String, ExamBench> lockBenches(List<String> benchKeys) {
        // 先以无锁查询确定主键顺序，再按主键升序逐一加锁，保证多事务加锁顺序一致。
        Map<String, Long> benchIds = benchRepository.findAll().stream()
                .filter(b -> benchKeys.contains(b.benchKey()))
                .collect(Collectors.toMap(ExamBench::benchKey, ExamBench::id));
        Map<String, ExamBench> locked = new LinkedHashMap<>();
        for (String benchKey : benchIds.entrySet().stream()
                .sorted(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey).toList()) {
            locked.put(benchKey, benchRepository.findByKeyForUpdate(benchKey).orElseThrow());
        }
        return locked;
    }

    private ExamReservation lockReservation(String reservationKey) {
        return reservationRepository.findByKeyForUpdate(reservationKey)
                .orElseThrow(() -> ApiException.notFound("检验预约不存在: " + reservationKey));
    }

    private Evidence lockEvidence(String evidenceKey) {
        return evidenceRepository.findByKeyForUpdate(evidenceKey)
                .orElseThrow(() -> ApiException.notFound("证物不存在: " + evidenceKey));
    }

    private void requireOperator(ExamReservation reservation, String actorId) {
        if (!reservation.requestedBy().equals(actorId)) {
            throw ApiException.conflict("仅预约操作人可执行该操作: " + actorId
                    + "，预约人=" + reservation.requestedBy());
        }
    }

    /**
     * 汇总全部明细失败并映射单一 HTTP 状态：参数类 400 &gt; 不存在 404 &gt; 前置条件 422 &gt; 冲突 409。
     * 整个批量事务随异常回滚，不留下任何占用。
     */
    private ApiException batchFailure(List<ExamFailureDetail> failures) {
        Set<String> reasons = failures.stream().map(ExamFailureDetail::reason).collect(Collectors.toSet());
        HttpStatus status;
        if (reasons.contains(WINDOW_INVALID) || reasons.contains(DUPLICATE_EVIDENCE)
                || reasons.contains(DUPLICATE_RESERVATION_KEY)) {
            status = HttpStatus.BAD_REQUEST;
        } else if (reasons.contains(EVIDENCE_NOT_FOUND) || reasons.contains(BENCH_NOT_FOUND)) {
            status = HttpStatus.NOT_FOUND;
        } else if (reasons.contains(EVIDENCE_BORROWED) || reasons.contains(SEAL_ABNORMAL)) {
            status = HttpStatus.UNPROCESSABLE_ENTITY;
        } else {
            status = HttpStatus.CONFLICT;
        }
        return ApiException.withDetails(status, "批量检验预约校验失败，失败明细数=" + failures.size()
                + "，整批未提交、不产生任何占用", failures);
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

    private ExamReservationView toView(ExamReservation reservation) {
        return new ExamReservationView(
                reservation.reservationKey(), reservation.evidenceKey(), reservation.benchKey(),
                reservation.startAt(), reservation.endAt(), reservation.status(),
                reservation.requestedBy(), reservation.startedAt(), reservation.completedAt(),
                reservation.cancelledAt(), reservation.resultSummary(), reservation.newSealNo(),
                reservation.newSealCheck(), reservation.lockedSealNo(),
                reservation.lockedSealVersion(), reservation.createdAt(), reservation.updatedAt());
    }

    private ExamChainEventView toView(ExamChainEvent event) {
        return new ExamChainEventView(
                event.reservationKey(), event.evidenceKey(), event.benchKey(), event.eventType(),
                event.actorId(), event.sealNo(), event.sealVersion(), event.resultSummary(),
                event.newSealNo(), event.newSealCheck(), event.eventAt(), event.createdAt());
    }
}
