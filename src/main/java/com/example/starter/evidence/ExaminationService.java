package com.example.starter.evidence;

import com.example.starter.error.ApiException;
import com.example.starter.evidence.dto.BenchView;
import com.example.starter.evidence.dto.ExaminationEventView;
import com.example.starter.evidence.dto.ReservationBatchRequest;
import com.example.starter.evidence.dto.ReservationCreateRequest;
import com.example.starter.evidence.dto.ReservationView;
import com.example.starter.evidence.dto.SealVersionView;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 证物检验预约核心服务。所有写操作在单事务内完成：
 * 按固定顺序先锁证物行再锁检验台行（批量按键排序加锁），校验完整最终状态后一次性提交，
 * 任一失败整体回滚，不留下台位或证物占用。预约、开始、完成、取消均追加不可变保管链事件。
 */
@Service
public class ExaminationService {

    static final String OP_BENCH_REGISTER = "BENCH_REGISTER";
    static final String OP_EXAM_RESERVE = "EXAM_RESERVE";
    static final String OP_EXAM_RESERVE_BATCH = "EXAM_RESERVE_BATCH";
    static final String OP_EXAM_START = "EXAM_START";
    static final String OP_EXAM_COMPLETE = "EXAM_COMPLETE";
    static final String OP_EXAM_CANCEL = "EXAM_CANCEL";

    private final EvidenceRepository evidenceRepository;
    private final LabBenchRepository benchRepository;
    private final ExaminationReservationRepository reservationRepository;
    private final ExaminationChainEventRepository eventRepository;
    private final SealVersionRepository sealVersionRepository;
    private final CommandLogRepository commandLogRepository;
    private final ObjectMapper objectMapper;
    private final EvidenceClock clock;

    public ExaminationService(EvidenceRepository evidenceRepository,
                              LabBenchRepository benchRepository,
                              ExaminationReservationRepository reservationRepository,
                              ExaminationChainEventRepository eventRepository,
                              SealVersionRepository sealVersionRepository,
                              CommandLogRepository commandLogRepository,
                              ObjectMapper objectMapper,
                              EvidenceClock clock) {
        this.evidenceRepository = evidenceRepository;
        this.benchRepository = benchRepository;
        this.reservationRepository = reservationRepository;
        this.eventRepository = eventRepository;
        this.sealVersionRepository = sealVersionRepository;
        this.commandLogRepository = commandLogRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 注册检验台：bench_key 全局唯一，容量为正整数。
     */
    @Transactional
    public StoredResponse registerBench(String actorId, BenchRegisterRequestInput input,
                                        String requestHash) {
        Optional<StoredResponse> replay = checkReplay(input.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        if (input.capacity() <= 0) {
            throw ApiException.badRequest("检验台容量必须为正整数: 实际=" + input.capacity()
                    + " 要求>=1 差额=" + (1 - input.capacity()));
        }
        if (benchRepository.findByKey(input.benchKey()).isPresent()) {
            throw ApiException.conflict("检验台已存在: " + input.benchKey());
        }
        LocalDateTime now = LocalDateTime.now();
        benchRepository.insert(input.benchKey(), input.name(), input.capacity(), now);
        LabBench bench = benchRepository.findByKey(input.benchKey()).orElseThrow();
        return record(input.commandKey(), OP_BENCH_REGISTER, actorId, requestHash,
                201, toView(bench));
    }

    /**
     * 创建单个检验预约。仅证物当前保管人操作；证物须在库（SEALED）且封签正常；
     * 时段 UTC 左闭右开；检验台重叠时段容量满或证物时段已占用返回 409。
     */
    @Transactional
    public StoredResponse reserve(String actorId, String evidenceKey,
                                  ReservationCreateRequest request, String requestHash) {
        Optional<StoredResponse> replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        LocalDateTime nowUtc = clock.nowUtc();
        validateWindow(request.startAt(), request.endAt(), nowUtc);

        // 固定加锁顺序：先证物后检验台，避免与并发事务死锁。
        Evidence evidence = lockEvidence(evidenceKey);
        replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        LabBench bench = benchRepository.findByKeyForUpdate(request.benchKey())
                .orElseThrow(() -> ApiException.notFound("检验台不存在: " + request.benchKey()));
        requireReservable(evidence, actorId);
        requireReservationKeyAbsent(request.reservationKey());
        requireEvidenceNotReserved(evidenceKey);
        requireBenchCapacity(bench, List.of(new Slot(
                request.reservationKey(), request.startAt(), request.endAt())));

        LocalDateTime createdAt = LocalDateTime.now();
        reservationRepository.insert(request.reservationKey(), evidenceKey, request.benchKey(),
                request.examinerId(), actorId, request.startAt(), request.endAt(), createdAt);
        eventRepository.insert(request.reservationKey(), evidenceKey, ExaminationEventType.RESERVED,
                actorId, nowUtc, request.startAt(), request.endAt(), null, null, null);
        ExaminationReservation created =
                reservationRepository.findByKey(request.reservationKey()).orElseThrow();
        return record(request.commandKey(), OP_EXAM_RESERVE, actorId, requestHash,
                201, toView(created));
    }

    /**
     * 批量创建检验预约：先锁定并校验全部台位与证物（含批次内相互冲突），
     * 模拟包含本批全部预约的最终占用；任一失败整批回滚，不留下任何占用。
     */
    @Transactional
    public StoredResponse reserveBatch(String actorId, ReservationBatchRequest request,
                                       String requestHash) {
        Optional<StoredResponse> replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        List<ReservationBatchRequest.Item> items = request.items();
        LocalDateTime nowUtc = clock.nowUtc();
        for (ReservationBatchRequest.Item item : items) {
            validateWindow(item.startAt(), item.endAt(), nowUtc);
            requireReservationKeyAbsent(item.reservationKey());
        }
        detectDuplicateKeysInBatch(items);

        // 固定加锁顺序：证物按键排序、检验台按键排序，全部锁齐后再校验最终状态。
        List<String> evidenceKeys = items.stream().map(ReservationBatchRequest.Item::evidenceKey)
                .distinct().sorted().toList();
        Map<String, Evidence> evidences = new LinkedHashMap<>();
        for (String evidenceKey : evidenceKeys) {
            evidences.put(evidenceKey, lockEvidence(evidenceKey));
        }
        replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        List<String> benchKeys = items.stream().map(ReservationBatchRequest.Item::benchKey)
                .distinct().sorted().toList();
        Map<String, LabBench> benches = new LinkedHashMap<>();
        for (String benchKey : benchKeys) {
            benches.put(benchKey, benchRepository.findByKeyForUpdate(benchKey)
                    .orElseThrow(() -> ApiException.notFound("检验台不存在: " + benchKey)));
        }

        for (ReservationBatchRequest.Item item : items) {
            requireReservable(evidences.get(item.evidenceKey()), actorId);
        }
        // 证物占用：每件证物至多一个未结束预约（预约即占用证物）；库内已有未结束预约、
        // 或同一证物在本批出现多次，均整批拒绝。
        for (String evidenceKey : evidenceKeys) {
            requireEvidenceNotReserved(evidenceKey);
            long countInBatch = items.stream()
                    .filter(i -> i.evidenceKey().equals(evidenceKey)).count();
            if (countInBatch > 1) {
                throw ApiException.conflict("批量内同一证物出现多个预约: " + evidenceKey
                        + " 实际数量=" + countInBatch + " 要求数量<=1 差额=" + (countInBatch - 1));
            }
        }
        // 台位容量：库内既有重叠预约与本批预约构成最终占用，逐台位校验容量并给出实际值/差额。
        for (String benchKey : benchKeys) {
            List<Slot> batchSlots = items.stream()
                    .filter(i -> i.benchKey().equals(benchKey))
                    .map(i -> new Slot(i.reservationKey(), i.startAt(), i.endAt()))
                    .toList();
            requireBenchCapacity(benches.get(benchKey), batchSlots);
        }

        LocalDateTime createdAt = LocalDateTime.now();
        List<ExaminationReservation> created = new ArrayList<>();
        for (ReservationBatchRequest.Item item : items) {
            reservationRepository.insert(item.reservationKey(), item.evidenceKey(),
                    item.benchKey(), item.examinerId(), actorId,
                    item.startAt(), item.endAt(), createdAt);
            eventRepository.insert(item.reservationKey(), item.evidenceKey(),
                    ExaminationEventType.RESERVED, actorId, nowUtc,
                    item.startAt(), item.endAt(), null, null, null);
            created.add(reservationRepository.findByKey(item.reservationKey()).orElseThrow());
        }
        List<ReservationView> views = created.stream().map(this::toView).toList();
        Object body = new BatchReservationView(views.size(), views);
        return record(request.commandKey(), OP_EXAM_RESERVE_BATCH, actorId, requestHash,
                201, body);
    }

    /**
     * 开始检验：同一事务内将证物转为 EXAMINATION、写入 STARTED 临时链记录并锁定原封签版本。
     * 仅预约指定检验执行人可操作；证物须仍在库且封签正常；未到开始时刻或时段已结束返回 409。
     */
    @Transactional
    public StoredResponse start(String actorId, String evidenceKey, String reservationKey,
                                String commandKey, String requestHash) {
        Optional<StoredResponse> replay = checkReplay(commandKey, requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        Evidence evidence = lockEvidence(evidenceKey);
        replay = checkReplay(commandKey, requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        ExaminationReservation reservation = lockReservation(reservationKey, evidenceKey);
        if (!reservation.examinerId().equals(actorId)) {
            throw ApiException.conflict("仅预约指定检验执行人可开始检验: " + actorId);
        }
        if (reservation.status() != ReservationStatus.SCHEDULED) {
            throw ApiException.conflict("预约不是待开始状态，无法开始: " + reservationKey
                    + " 实际状态=" + reservation.status() + " 要求状态=SCHEDULED");
        }
        LocalDateTime nowUtc = clock.nowUtc();
        if (nowUtc.isBefore(reservation.startAt())) {
            throw ApiException.conflict("未到预约开始时刻: " + reservationKey
                    + " 当前=" + nowUtc + " 要求>=" + reservation.startAt()
                    + " 差额(分钟)=" + java.time.Duration.between(nowUtc, reservation.startAt()).toMinutes());
        }
        if (!nowUtc.isBefore(reservation.endAt())) {
            throw ApiException.conflict("预约时段已结束，无法开始: " + reservationKey
                    + " 当前=" + nowUtc + " 要求<" + reservation.endAt());
        }
        requireEvidenceInStockSealed(evidence);

        SealVersion current = sealVersionRepository.findCurrentForUpdate(evidenceKey)
                .orElseThrow(() -> ApiException.conflict("证物缺少当前封签版本: " + evidenceKey));
        if (!sealVersionRepository.supersede(current.id(), nowUtc)) {
            throw ApiException.conflict("原封签版本已被并发检验锁定: 版本=" + current.version());
        }
        if (!reservationRepository.markStarted(reservation.id(), current.version(), nowUtc)) {
            throw ApiException.conflict("预约已被并发处理: " + reservationKey);
        }
        evidenceRepository.updateStatus(evidenceKey, EvidenceStatus.EXAMINATION, LocalDateTime.now());
        eventRepository.insert(reservationKey, evidenceKey, ExaminationEventType.STARTED,
                actorId, nowUtc, reservation.startAt(), reservation.endAt(),
                current.version(), null, null);
        ExaminationReservation updated =
                reservationRepository.findByKey(reservationKey).orElseThrow();
        return record(commandKey, OP_EXAM_START, actorId, requestHash, 200, toView(updated));
    }

    /**
     * 完成检验：必须记录非空结果摘要与新封签编号（新封签校验）；
     * 同一事务追加 RESEALED 新封签版本、写入 COMPLETED 链事件，证物回到在库 SEALED。
     */
    @Transactional
    public StoredResponse complete(String actorId, String evidenceKey, String reservationKey,
                                   String resultSummary, String newSealNo,
                                   String commandKey, String requestHash) {
        Optional<StoredResponse> replay = checkReplay(commandKey, requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        Evidence evidence = lockEvidence(evidenceKey);
        replay = checkReplay(commandKey, requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        ExaminationReservation reservation = lockReservation(reservationKey, evidenceKey);
        if (!reservation.examinerId().equals(actorId)) {
            throw ApiException.conflict("仅预约指定检验执行人可完成检验: " + actorId);
        }
        if (reservation.status() != ReservationStatus.IN_PROGRESS) {
            throw ApiException.conflict("预约不是检验中状态，无法完成: " + reservationKey
                    + " 实际状态=" + reservation.status() + " 要求状态=IN_PROGRESS");
        }
        if (evidence.status() != EvidenceStatus.EXAMINATION) {
            throw ApiException.conflict("证物不在检验中状态: " + evidenceKey
                    + " 实际状态=" + evidence.status() + " 要求状态=EXAMINATION");
        }
        int lockedVersion = reservation.sealVersionLocked() == null
                ? 0 : reservation.sealVersionLocked();
        List<SealVersion> versions = sealVersionRepository.findByEvidenceKey(evidenceKey);
        int newVersion = versions.stream().mapToInt(SealVersion::version).max().orElse(0) + 1;
        LocalDateTime nowUtc = clock.nowUtc();
        if (!reservationRepository.markCompleted(reservation.id(), resultSummary, newSealNo,
                newVersion, nowUtc)) {
            throw ApiException.conflict("预约已被并发完成: " + reservationKey);
        }
        sealVersionRepository.insert(evidenceKey, newVersion, newSealNo, SealVersionKind.RESEALED,
                actorId, SealVersionEvent.EXAMINATION_COMPLETED, nowUtc);
        evidenceRepository.updateStatus(evidenceKey, EvidenceStatus.SEALED, LocalDateTime.now());
        eventRepository.insert(reservationKey, evidenceKey, ExaminationEventType.COMPLETED,
                actorId, nowUtc, reservation.startAt(), reservation.endAt(),
                newVersion, resultSummary, null);
        ExaminationReservation updated =
                reservationRepository.findByKey(reservationKey).orElseThrow();
        return record(commandKey, OP_EXAM_COMPLETE, actorId, requestHash, 200, toView(updated));
    }

    /**
     * 取消预约：仅未开始（SCHEDULED）预约可取消，且仅创建人可取消；
     * 取消后状态条件更新并追加 CANCELLED 事件，台位容量与证物占用随即释放。
     */
    @Transactional
    public StoredResponse cancel(String actorId, String evidenceKey, String reservationKey,
                                 String reason, String commandKey, String requestHash) {
        Optional<StoredResponse> replay = checkReplay(commandKey, requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        Evidence evidence = lockEvidence(evidenceKey);
        replay = checkReplay(commandKey, requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        ExaminationReservation reservation = lockReservation(reservationKey, evidenceKey);
        if (!reservation.requestedBy().equals(actorId)) {
            throw ApiException.conflict("仅预约创建人可取消预约: " + actorId);
        }
        if (reservation.status() != ReservationStatus.SCHEDULED) {
            throw ApiException.conflict("仅未开始预约可取消: " + reservationKey
                    + " 实际状态=" + reservation.status() + " 要求状态=SCHEDULED");
        }
        LocalDateTime nowUtc = clock.nowUtc();
        if (!reservationRepository.cancel(reservation.id(), nowUtc)) {
            throw ApiException.conflict("预约已被并发处理: " + reservationKey);
        }
        eventRepository.insert(reservationKey, evidenceKey, ExaminationEventType.CANCELLED,
                actorId, nowUtc, reservation.startAt(), reservation.endAt(),
                null, null, reason);
        ExaminationReservation updated =
                reservationRepository.findByKey(reservationKey).orElseThrow();
        return record(commandKey, OP_EXAM_CANCEL, actorId, requestHash, 200, toView(updated));
    }

    /**
     * 查询预约明细（读取，不改变任何状态）。
     */
    @Transactional(readOnly = true)
    public ReservationView getReservation(String reservationKey) {
        return toView(reservationRepository.findByKey(reservationKey)
                .orElseThrow(() -> ApiException.notFound("检验预约不存在: " + reservationKey)));
    }

    /**
     * 按证物查询全部预约（按创建顺序，历史稳定排序，只读）。
     */
    @Transactional(readOnly = true)
    public List<ReservationView> listByEvidence(String evidenceKey) {
        evidenceRepository.findByKey(evidenceKey)
                .orElseThrow(() -> ApiException.notFound("证物不存在: " + evidenceKey));
        return reservationRepository.findByEvidenceKey(evidenceKey).stream()
                .map(this::toView).toList();
    }

    /**
     * 查询全部检验台（只读）。
     */
    @Transactional(readOnly = true)
    public List<BenchView> listBenches() {
        return benchRepository.findAll().stream().map(this::toView).toList();
    }

    /**
     * 台位诊断：窗口 UTC 左闭右开；给出每段实际占用、容量（要求值）、剩余容量（差额）与利用率比例。
     * 读取不改变状态，分段按开始时刻稳定排序。
     */
    @Transactional(readOnly = true)
    public BenchDiagnosticsView benchDiagnostics(String benchKey,
                                                 LocalDateTime from, LocalDateTime to) {
        if (!from.isBefore(to)) {
            throw ApiException.badRequest("诊断窗口结束时刻必须晚于开始时刻: 实际时长(分钟)="
                    + java.time.Duration.between(from, to).toMinutes() + " 要求>0");
        }
        LabBench bench = benchRepository.findByKey(benchKey)
                .orElseThrow(() -> ApiException.notFound("检验台不存在: " + benchKey));
        // 先把每个预约裁剪到诊断窗口内，再计算窗口内的恒定占用分段。
        List<ReservationPlanner.Interval> intervals =
                reservationRepository.findActiveByBenchInWindow(benchKey, from, to).stream()
                        .map(r -> ReservationPlanner.clip(
                                new ReservationPlanner.Interval(r.startAt(), r.endAt()), from, to))
                        .filter(java.util.Objects::nonNull)
                        .toList();
        List<LoadSegmentView> segments = ReservationPlanner.segments(intervals).stream()
                .map(segment -> {
                    int load = segment.load();
                    int remaining = bench.capacity() - load;
                    // 利用率比例以千分位整数返回（load/capacity），避免浮点序列化不稳定。
                    int utilizationPermille = Math.round(load * 1000f / bench.capacity());
                    return new LoadSegmentView(segment.start(), segment.end(), load,
                            bench.capacity(), remaining, utilizationPermille);
                })
                .toList();
        return new BenchDiagnosticsView(toView(bench), from, to, segments);
    }

    /**
     * 按证物查询封签版本历史（按版本号升序，只读）。
     */
    @Transactional(readOnly = true)
    public List<SealVersionView> listSealVersions(String evidenceKey) {
        evidenceRepository.findByKey(evidenceKey)
                .orElseThrow(() -> ApiException.notFound("证物不存在: " + evidenceKey));
        return sealVersionRepository.findByEvidenceKey(evidenceKey).stream()
                .map(this::toSealVersionView).toList();
    }

    /**
     * 按证物查询检验保管链事件（按发生顺序，只读）。
     */
    @Transactional(readOnly = true)
    public List<ExaminationEventView> listEvents(String evidenceKey) {
        evidenceRepository.findByKey(evidenceKey)
                .orElseThrow(() -> ApiException.notFound("证物不存在: " + evidenceKey));
        return eventRepository.findByEvidenceKey(evidenceKey).stream()
                .map(this::toEventView).toList();
    }

    private Evidence lockEvidence(String evidenceKey) {
        return evidenceRepository.findByKeyForUpdate(evidenceKey)
                .orElseThrow(() -> ApiException.notFound("证物不存在: " + evidenceKey));
    }

    private ExaminationReservation lockReservation(String reservationKey, String evidenceKey) {
        ExaminationReservation reservation = reservationRepository.findByKeyForUpdate(reservationKey)
                .orElseThrow(() -> ApiException.notFound("检验预约不存在: " + reservationKey));
        if (!reservation.evidenceKey().equals(evidenceKey)) {
            throw ApiException.notFound("检验预约不属于该证物: " + reservationKey);
        }
        return reservation;
    }

    private void requireReservable(Evidence evidence, String actorId) {
        if (!evidence.custodianId().equals(actorId)) {
            throw ApiException.conflict("操作人不是当前保管人: " + actorId
                    + " 实际保管人=" + evidence.custodianId());
        }
        switch (evidence.status()) {
            case SEAL_BROKEN -> throw ApiException.unprocessable("封签异常证物禁止检验预约: "
                    + evidence.evidenceKey() + " 实际状态=SEAL_BROKEN 要求状态=SEALED");
            case BORROWED -> throw ApiException.conflict("证物借出中，禁止检验预约: "
                    + evidence.evidenceKey() + " 实际状态=BORROWED 要求状态=SEALED");
            case TRANSFER_PENDING -> throw ApiException.conflict("待接收交接期间禁止检验预约: "
                    + evidence.evidenceKey() + " 实际状态=TRANSFER_PENDING 要求状态=SEALED");
            case EXAMINATION -> throw ApiException.conflict("证物检验中，禁止再次预约: "
                    + evidence.evidenceKey() + " 实际状态=EXAMINATION 要求状态=SEALED");
            case SEALED -> {
                // 在库且封签正常，可以预约。
            }
        }
    }

    private void requireEvidenceInStockSealed(Evidence evidence) {
        switch (evidence.status()) {
            case SEAL_BROKEN -> throw ApiException.unprocessable("封签异常，禁止开始检验: "
                    + evidence.evidenceKey() + " 实际状态=SEAL_BROKEN 要求状态=SEALED");
            case BORROWED -> throw ApiException.conflict("证物借出中，禁止开始检验: "
                    + evidence.evidenceKey() + " 实际状态=BORROWED 要求状态=SEALED");
            case TRANSFER_PENDING -> throw ApiException.conflict("待接收交接期间禁止开始检验: "
                    + evidence.evidenceKey() + " 实际状态=TRANSFER_PENDING 要求状态=SEALED");
            case EXAMINATION -> throw ApiException.conflict("证物已在检验中: "
                    + evidence.evidenceKey());
            case SEALED -> {
                // 符合开始条件。
            }
        }
    }

    private void validateWindow(LocalDateTime startAt, LocalDateTime endAt, LocalDateTime nowUtc) {
        if (!endAt.isAfter(startAt)) {
            long durationMinutes = java.time.Duration.between(startAt, endAt).toMinutes();
            throw ApiException.badRequest("预约结束时刻必须晚于开始时刻: 实际时长(分钟)="
                    + durationMinutes + " 要求>0 差额(分钟)=" + (1 - durationMinutes));
        }
        if (startAt.isBefore(nowUtc)) {
            throw ApiException.badRequest("预约开始时刻不得早于服务端当前时刻: 实际="
                    + startAt + " 要求>=" + nowUtc);
        }
    }

    private void requireReservationKeyAbsent(String reservationKey) {
        if (reservationRepository.findByKey(reservationKey).isPresent()) {
            throw ApiException.conflict("检验预约键已存在: " + reservationKey);
        }
    }

    private void detectDuplicateKeysInBatch(List<ReservationBatchRequest.Item> items) {
        Map<String, Integer> firstIndex = new LinkedHashMap<>();
        for (int i = 0; i < items.size(); i++) {
            String key = items.get(i).reservationKey();
            Integer first = firstIndex.putIfAbsent(key, i);
            if (first != null) {
                throw ApiException.conflict("批量内预约键重复: " + key
                        + " 首次出现下标=" + first + " 重复下标=" + i);
            }
        }
    }

    /**
     * 每件证物至多一个未结束预约（SCHEDULED 或 IN_PROGRESS）：预约即占用证物，
     * 并发预约由证物行锁串行裁决，后到者返回 409；取消或完成后才允许再次预约。
     * 调用前必须已锁定证物行。
     */
    private void requireEvidenceNotReserved(String evidenceKey) {
        List<ExaminationReservation> active =
                reservationRepository.findActiveByEvidenceKey(evidenceKey);
        if (!active.isEmpty()) {
            ExaminationReservation occupied = active.get(0);
            throw ApiException.conflict("证物已存在未结束检验预约: " + evidenceKey
                    + " 已占预约=" + occupied.reservationKey()
                    + " 状态=" + occupied.status()
                    + " 已占时段=[" + occupied.startAt() + "," + occupied.endAt() + ")");
        }
    }

    /**
     * 校验检验台在并入本批预约后的最终占用是否始终不超过容量；
     * 超容返回 409，并给出实际峰值占用、要求容量与差额。
     */
    private void requireBenchCapacity(LabBench bench, List<Slot> batchSlots) {
        LocalDateTime windowStart = batchSlots.stream().map(Slot::start)
                .min(LocalDateTime::compareTo).orElseThrow();
        LocalDateTime windowEnd = batchSlots.stream().map(Slot::end)
                .max(LocalDateTime::compareTo).orElseThrow();
        List<ReservationPlanner.Interval> intervals =
                reservationRepository.findActiveOverlapping(bench.benchKey(), windowStart, windowEnd)
                        .stream()
                        .map(r -> new ReservationPlanner.Interval(r.startAt(), r.endAt()))
                        .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        for (Slot slot : batchSlots) {
            intervals.add(new ReservationPlanner.Interval(slot.start(), slot.end()));
        }
        List<ReservationPlanner.Segment> segments = ReservationPlanner.segments(intervals);
        ReservationPlanner.Segment peak = segments.stream()
                .max(Comparator.comparingInt(ReservationPlanner.Segment::load)
                        .thenComparing(ReservationPlanner.Segment::start))
                .orElse(null);
        if (peak == null || peak.load() <= bench.capacity()) {
            return;
        }
        // 定位首个超容的本批预约，使失败原因可区分到具体预约。
        String offender = batchSlots.stream()
                .filter(slot -> segments.stream().anyMatch(segment ->
                        segment.load() > bench.capacity()
                                && ReservationPlanner.overlaps(slot.start(), slot.end(),
                                        segment.start(), segment.end())))
                .map(Slot::reservationKey)
                .findFirst()
                .orElse(batchSlots.get(0).reservationKey());
        throw ApiException.conflict("检验台容量不足: 检验台=" + bench.benchKey()
                + " 预约=" + offender
                + " 实际峰值占用=" + peak.load() + " 要求容量=" + bench.capacity()
                + " 差额=" + (peak.load() - bench.capacity())
                + " 超容时段=[" + peak.start() + "," + peak.end() + ")");
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

    private BenchView toView(LabBench bench) {
        return new BenchView(bench.benchKey(), bench.name(), bench.capacity(), bench.createdAt());
    }

    private ReservationView toView(ExaminationReservation reservation) {
        return new ReservationView(
                reservation.reservationKey(),
                reservation.evidenceKey(),
                reservation.benchKey(),
                reservation.examinerId(),
                reservation.requestedBy(),
                reservation.startAt(),
                reservation.endAt(),
                reservation.status(),
                reservation.sealVersionLocked(),
                reservation.startedAt(),
                reservation.completedAt(),
                reservation.resultSummary(),
                reservation.newSealNo(),
                reservation.newSealVersion(),
                reservation.cancelledAt(),
                reservation.createdAt());
    }

    private SealVersionView toSealVersionView(SealVersion version) {
        return new SealVersionView(version.version(), version.sealNo(), version.kind(),
                version.createdBy(), version.eventType(), version.createdAt(),
                version.supersededAt());
    }

    private ExaminationEventView toEventView(ExaminationChainEvent event) {
        return new ExaminationEventView(
                event.reservationKey(), event.evidenceKey(), event.eventType(), event.actorId(),
                event.eventTime(), event.startAt(), event.endAt(), event.sealVersion(),
                event.resultSummary(), event.detail());
    }

    /**
     * 注册检验台的服务层入参（由控制器从请求 DTO 构造）。
     */
    public record BenchRegisterRequestInput(String commandKey, String benchKey, String name,
                                            int capacity) {
    }

    /**
     * 待校验的预约时段。
     */
    private record Slot(String reservationKey, LocalDateTime start, LocalDateTime end) {
    }

    /**
     * 批量预约响应：实际创建数量与全部明细（稳定排序）。
     */
    public record BatchReservationView(int createdCount, List<ReservationView> reservations) {
    }

    /**
     * 台位占用分段诊断。
     *
     * @param start                 分段开始时刻（UTC，含）
     * @param end                   分段结束时刻（UTC，不含）
     * @param actualLoad            实际并行占用数量
     * @param requiredCapacity      检验台容量（要求值）
     * @param remainingCapacity     剩余容量（差额=要求-实际，非负；数据异常超容时为负）
     * @param utilizationPermille   利用率比例（实际/要求）的千分位整数，1000 表示 100%
     */
    public record LoadSegmentView(LocalDateTime start, LocalDateTime end, int actualLoad,
                                  int requiredCapacity, int remainingCapacity,
                                  int utilizationPermille) {
    }

    /**
     * 检验台诊断视图。
     */
    public record BenchDiagnosticsView(BenchView bench, LocalDateTime windowStart,
                                       LocalDateTime windowEnd, List<LoadSegmentView> segments) {
    }
}
