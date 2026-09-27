package com.example.starter.batch;

import com.example.starter.batch.dto.LabelDiagnosisResponse;
import com.example.starter.batch.dto.PackRequest;
import com.example.starter.batch.dto.PackResponse;
import com.example.starter.batch.dto.PackingDetailResponse;
import com.example.starter.batch.dto.PackingEventResponse;
import com.example.starter.batch.dto.PackingHistoryResponse;
import com.example.starter.batch.dto.PackingPlanResponse;
import com.example.starter.batch.dto.PackingRecordResponse;
import com.example.starter.batch.dto.RegisterPlanRequest;
import com.example.starter.batch.dto.ReleaseSnapshotResponse;
import com.example.starter.batch.dto.VoidPackingRequest;
import com.example.starter.batch.dto.VoidPackingResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * 批次标签核销核心服务：包装计划登记、按标签批量封箱、封箱作废与放行快照固化，
 * 以及明细/历史/差异诊断只读查询。
 *
 * <p>并发策略：同一批次的登记/封箱/作废/放行均在事务内先对 batch 行
 * SELECT ... FOR UPDATE 串行化，按事务提交顺序裁决；标签号全局唯一由
 * label_occupancy 主键约束兜底，并发同标签最多一次成功，失败整事务回滚不留占用。
 * commandKey 幂等复用 command_log：同类型同键同参重放首次结果，异参 409，失败不占键。
 * 所有时间取 UTC 时钟（可在测试替换）。
 */
@Service
public class PackingService {

    private static final String CMD_REGISTER_PLAN = "REGISTER_PLAN";
    private static final String CMD_PACK = "PACK";
    private static final String CMD_VOID_PACK = "VOID_PACK";

    /**
     * 指纹拼接分隔符（NUL）：业务参数不可能包含该字符，避免拼接碰撞。
     */
    private static final String SEP = "\\u0000";

    private static final int IDEMPOTENCY_MAX_ATTEMPTS = 3;

    private final BatchRepository batchRepo;
    private final PackingRepository repo;
    private final TransactionTemplate tx;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public PackingService(BatchRepository batchRepo,
                          PackingRepository repo,
                          PlatformTransactionManager transactionManager,
                          ObjectMapper objectMapper,
                          Clock clock) {
        this.batchRepo = batchRepo;
        this.repo = repo;
        this.tx = new TransactionTemplate(transactionManager);
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 放行快照草稿：第二批准放行门禁通过后在放行事务内落库；字段含义同 release_snapshot 表。
     */
    public record SnapshotDraft(String batchKey, int plannedQuantity, int packedQuantity,
                                int usedLabelCount, String labelSummary, String recordsJson,
                                String createdAt) {
    }

    /**
     * 登记包装计划：每个批次至多一份，登记后不可修改。仅 QUARANTINED/PENDING_RELEASE/
     * RELEASE_REVIEW 状态可登记；号段长度必须不小于计划数量。
     */
    public StoredResponse registerPlan(String batchKey, RegisterPlanRequest req) {
        long rangeSize = req.labelEnd() - req.labelStart() + 1;
        if (req.labelStart() > req.labelEnd()) {
            throw ApiException.unprocessable("标签号段起点不得大于终点");
        }
        if (rangeSize < req.plannedQuantity()) {
            throw ApiException.unprocessable("标签号段长度不足：实际 " + rangeSize
                    + "，要求不小于计划数量 " + req.plannedQuantity()
                    + "，差额 " + (req.plannedQuantity() - rangeSize));
        }
        String fingerprint = fingerprint("registerPlan", batchKey, String.valueOf(req.plannedQuantity()),
                String.valueOf(req.labelStart()), String.valueOf(req.labelEnd()));
        return executeIdempotent(CMD_REGISTER_PLAN, req.commandKey(), fingerprint, () -> {
            BatchRepository.BatchRow batch = batchRepo.findBatchForUpdate(batchKey)
                    .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
            var logged = loggedResponse(CMD_REGISTER_PLAN, req.commandKey(), fingerprint);
            if (logged.isPresent()) {
                return logged.get();
            }
            assertNoRecalledAncestor(batchKey);
            BatchStatus status = BatchStatus.valueOf(batch.status());
            if (status == BatchStatus.RELEASED || status == BatchStatus.RECALLED
                    || status == BatchStatus.REJECTED || status == BatchStatus.SPLIT) {
                throw ApiException.conflict("批次状态 " + status + " 不允许登记包装计划");
            }
            if (repo.findPlan(batchKey).isPresent()) {
                throw ApiException.conflict("该批次已登记包装计划，不可重复登记或修改");
            }
            String now = now();
            repo.insertPlan(new PackingRepository.PlanRow(0L, batchKey, req.plannedQuantity(),
                    req.labelStart(), req.labelEnd(), now));
            PackingPlanResponse body = new PackingPlanResponse(batchKey, req.plannedQuantity(),
                    req.labelStart(), req.labelEnd(), Instant.parse(now));
            return new StoredResponse(201, toJson(body));
        });
    }

    /**
     * 批量封箱：按“既有 ACTIVE 封箱 + 本次提交”的完整最终集合一次性校验，
     * 任一标签越界/重复或累计数量超计划即整次回滚；全部通过才统一插入版本与标签占用并提交。
     */
    public StoredResponse pack(String batchKey, String actorId, PackRequest req) {
        if (actorId == null || actorId.isBlank()) {
            throw ApiException.badRequest("X-Actor-Id 不能为空");
        }
        String actor = actorId.trim();
        List<PackRequest.Item> items = req.items();
        List<String> parts = new ArrayList<>();
        parts.add("pack");
        parts.add(batchKey);
        parts.add(actor);
        for (PackRequest.Item item : items) {
            parts.add(String.valueOf(item.labelNo()));
            parts.add(String.valueOf(item.quantity()));
        }
        String fingerprint = fingerprint(parts.toArray(new String[0]));
        return executeIdempotent(CMD_PACK, req.commandKey(), fingerprint, () -> {
            BatchRepository.BatchRow batch = batchRepo.findBatchForUpdate(batchKey)
                    .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
            var logged = loggedResponse(CMD_PACK, req.commandKey(), fingerprint);
            if (logged.isPresent()) {
                return logged.get();
            }
            assertNoRecalledAncestor(batchKey);
            BatchStatus status = BatchStatus.valueOf(batch.status());
            if (status == BatchStatus.RECALLED) {
                throw ApiException.conflict("批次已召回，不允许封箱");
            }
            if (status == BatchStatus.RELEASED || status == BatchStatus.SPLIT) {
                throw ApiException.conflict("批次已放行，不允许封箱");
            }
            if (status == BatchStatus.REJECTED) {
                throw ApiException.conflict("批次已拒绝，不允许封箱");
            }
            PackingRepository.PlanRow plan = repo.findPlan(batchKey)
                    .orElseThrow(() -> ApiException.unprocessable("该批次尚未登记包装计划，不能封箱"));

            // 先校验完整最终集合，再做任何写入：请求内标签不可重复，标签号须落在号段内。
            List<PackRequest.Item> ordered = items.stream()
                    .sorted(Comparator.comparingLong(PackRequest.Item::labelNo))
                    .toList();
            Set<Long> requestLabels = new HashSet<>();
            for (PackRequest.Item item : ordered) {
                if (!requestLabels.add(item.labelNo())) {
                    throw ApiException.unprocessable("本次封箱标签号重复: " + item.labelNo());
                }
                if (item.labelNo() < plan.labelStart() || item.labelNo() > plan.labelEnd()) {
                    throw ApiException.unprocessable("标签号越界: " + item.labelNo()
                            + "，要求落在号段 [" + plan.labelStart() + ", " + plan.labelEnd() + "] 内");
                }
            }
            List<PackingRepository.PackingRow> existing = repo.findActiveRecords(batchKey);
            Set<Long> existingLabels = existing.stream()
                    .map(PackingRepository.PackingRow::labelNo)
                    .collect(Collectors.toSet());
            for (long label : requestLabels) {
                if (existingLabels.contains(label)) {
                    throw ApiException.conflict("标签号已在本批次封箱中占用，不可重复: " + label);
                }
                Optional<PackingRepository.OccupancyRow> occupied = repo.findOccupancy(label);
                if (occupied.isPresent() && !occupied.get().batchKey().equals(batchKey)) {
                    throw ApiException.conflict("标签号已关联其他批次 "
                            + occupied.get().batchKey() + "，一个标签只能关联一个批次: " + label);
                }
            }
            int currentQuantity = existing.stream()
                    .mapToInt(PackingRepository.PackingRow::quantity)
                    .sum();
            int addQuantity = ordered.stream().mapToInt(PackRequest.Item::quantity).sum();
            int finalQuantity = currentQuantity + addQuantity;
            if (finalQuantity > plan.plannedQuantity()) {
                throw ApiException.unprocessable("封箱数量超过计划：本次后实际 " + finalQuantity
                        + "，要求不超过 " + plan.plannedQuantity()
                        + "，超出 " + (finalQuantity - plan.plannedQuantity()));
            }

            // 校验全部通过后统一写入；并发下 label_occupancy 主键冲突会整事务回滚，不留部分占用。
            String now = now();
            List<PackResponse.Entry> entries = new ArrayList<>(ordered.size());
            for (PackRequest.Item item : ordered) {
                long packingId;
                try {
                    packingId = repo.insertPacking(new PackingRepository.PackingRow(0L, batchKey,
                            req.commandKey(), item.labelNo(), item.quantity(), actor,
                            "ACTIVE", now, null, null, null));
                    repo.insertOccupancy(new PackingRepository.OccupancyRow(item.labelNo(),
                            batchKey, packingId, now));
                } catch (DuplicateKeyException e) {
                    throw ApiException.conflict("标签号并发占用冲突，整次封箱已回滚: " + item.labelNo());
                }
                entries.add(new PackResponse.Entry(packingId, item.labelNo(), item.quantity(),
                        actor, Instant.parse(now)));
            }
            List<PackingRepository.PackingRow> allActive = repo.findActiveRecords(batchKey);
            int packedQuantity = allActive.stream().mapToInt(PackingRepository.PackingRow::quantity).sum();
            int usedLabelCount = allActive.size();
            PackResponse body = new PackResponse(batchKey, entries, packedQuantity, usedLabelCount,
                    plan.plannedQuantity(), plan.plannedQuantity() - packedQuantity, Instant.parse(now));
            return new StoredResponse(201, toJson(body));
        });
    }

    /**
     * 作废封箱版本：只允许未放行批次（不存在放行快照且状态非 RELEASED/SPLIT/RECALLED）。
     * 作废保留版本历史，写操作人与原因，释放该版本数量与标签占用。
     */
    public StoredResponse voidPacking(String batchKey, String actorId, long packingId,
                                      VoidPackingRequest req) {
        if (actorId == null || actorId.isBlank()) {
            throw ApiException.badRequest("X-Actor-Id 不能为空");
        }
        String actor = actorId.trim();
        String fingerprint = fingerprint("voidPack", batchKey, String.valueOf(packingId), actor, req.reason());
        return executeIdempotent(CMD_VOID_PACK, req.commandKey(), fingerprint, () -> {
            BatchRepository.BatchRow batch = batchRepo.findBatchForUpdate(batchKey)
                    .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
            var logged = loggedResponse(CMD_VOID_PACK, req.commandKey(), fingerprint);
            if (logged.isPresent()) {
                return logged.get();
            }
            PackingRepository.PackingRow record = repo.findRecordForUpdate(packingId)
                    .orElseThrow(() -> ApiException.notFound("封箱记录不存在: " + packingId));
            if (!record.batchKey().equals(batchKey)) {
                throw ApiException.notFound("封箱记录不属于该批次: " + packingId);
            }
            BatchStatus status = BatchStatus.valueOf(batch.status());
            if (repo.findSnapshot(batchKey).isPresent()
                    || status == BatchStatus.RELEASED || status == BatchStatus.SPLIT
                    || status == BatchStatus.RECALLED) {
                throw ApiException.conflict("批次已放行（或已召回/拆分），封箱不可作废，放行快照不得改写");
            }
            if (status == BatchStatus.REJECTED) {
                throw ApiException.conflict("批次已拒绝，不允许作废封箱");
            }
            if ("VOID".equals(record.status())) {
                throw ApiException.conflict("封箱版本已作废，不能重复作废: " + packingId);
            }
            String now = now();
            repo.voidRecord(packingId, actor, req.reason(), now);
            repo.deleteOccupancy(record.labelNo());
            List<PackingRepository.PackingRow> active = repo.findActiveRecords(batchKey);
            int packedQuantity = active.stream().mapToInt(PackingRepository.PackingRow::quantity).sum();
            VoidPackingResponse body = new VoidPackingResponse(batchKey, packingId, record.labelNo(),
                    record.quantity(), actor, req.reason(), Instant.parse(now),
                    packedQuantity, active.size());
            return new StoredResponse(200, toJson(body));
        });
    }

    /**
     * 包装/标签核销明细（只读）：计划、实际数量、标签计数、全部封箱版本与放行快照。
     */
    public PackingDetailResponse detail(String batchKey) {
        batchRepo.findBatch(batchKey)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
        Optional<PackingRepository.PlanRow> plan = repo.findPlan(batchKey);
        List<PackingRepository.PackingRow> all = repo.findAllRecords(batchKey);
        List<PackingRepository.PackingRow> active = all.stream()
                .filter(r -> "ACTIVE".equals(r.status()))
                .toList();
        int packedQuantity = active.stream().mapToInt(PackingRepository.PackingRow::quantity).sum();
        int voidedCount = all.size() - active.size();
        ReleaseSnapshotResponse snapshot = repo.findSnapshot(batchKey)
                .map(this::toSnapshotResponse)
                .orElse(null);
        int planned = plan.map(PackingRepository.PlanRow::plannedQuantity).orElse(0);
        long rangeSize = plan.map(p -> p.labelEnd() - p.labelStart() + 1).orElse(0L);
        List<PackingRecordResponse> records = all.stream().map(this::toRecordResponse).toList();
        return new PackingDetailResponse(batchKey, plan.map(this::toPlanResponse).orElse(null),
                planned, packedQuantity, active.size(), active.size(), voidedCount,
                rangeSize, Math.max(rangeSize - active.size(), 0),
                planned - packedQuantity, records, snapshot);
    }

    /**
     * 封箱/作废历史（只读）：UTC 时间窗 [from, to) 左闭右开，null 侧不限；
     * 返回窗内事件及时间窗实际时长（秒）。事件按发生时间升序、同刻按版本号升序。
     */
    public PackingHistoryResponse history(String batchKey, Instant from, Instant to) {
        batchRepo.findBatch(batchKey)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
        if (from != null && to != null && !from.isBefore(to)) {
            throw ApiException.badRequest("时间窗起点必须早于终点（左闭右开）");
        }
        List<PackingEventResponse> events = repo.findAllRecords(batchKey).stream()
                .flatMap(r -> {
                    List<PackingEventResponse> out = new ArrayList<>(2);
                    Instant packedAt = Instant.parse(r.packedAt());
                    if (inWindow(packedAt, from, to)) {
                        out.add(new PackingEventResponse(r.id(), "PACKED", r.labelNo(),
                                r.quantity(), r.packedBy(), packedAt));
                    }
                    if ("VOID".equals(r.status())) {
                        Instant voidedAt = Instant.parse(r.voidedAt());
                        if (inWindow(voidedAt, from, to)) {
                            out.add(new PackingEventResponse(r.id(), "VOIDED", r.labelNo(),
                                    r.quantity(), r.voidedBy(), voidedAt));
                        }
                    }
                    return out.stream();
                })
                .sorted(Comparator.comparing(PackingEventResponse::occurredAt)
                        .thenComparingLong(PackingEventResponse::packingId))
                .toList();
        Long elapsedSeconds = (from != null && to != null)
                ? Duration.between(from, to).getSeconds()
                : null;
        return new PackingHistoryResponse(batchKey, from, to, elapsedSeconds, events.size(), events);
    }

    /**
     * 标签差异诊断（只读，不修复）：核对数量差额、标签计数差额、越界/缺失标签；
     * 已放行批次额外比对当前状态与放行快照差异，差异只能按既有召回流程处置。
     */
    public LabelDiagnosisResponse diagnose(String batchKey) {
        batchRepo.findBatch(batchKey)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
        Optional<PackingRepository.PlanRow> planOpt = repo.findPlan(batchKey);
        Optional<PackingRepository.SnapshotRow> snapshotOpt = repo.findSnapshot(batchKey);
        if (planOpt.isEmpty()) {
            return new LabelDiagnosisResponse(batchKey, false, false, 0, 0, 0, 0, 0, 0,
                    List.of(), List.of(), false, List.of(),
                    snapshotOpt.map(this::toSnapshotResponse).orElse(null));
        }
        PackingRepository.PlanRow plan = planOpt.get();
        List<PackingRepository.PackingRow> active = repo.findActiveRecords(batchKey);
        int packedQuantity = active.stream().mapToInt(PackingRepository.PackingRow::quantity).sum();
        List<Long> occupied = repo.findOccupiedLabels(batchKey);
        List<Long> outOfRange = occupied.stream()
                .filter(l -> l < plan.labelStart() || l > plan.labelEnd())
                .sorted()
                .toList();
        Set<Long> occupiedSet = new HashSet<>(occupied);
        List<Long> missing = new ArrayList<>();
        boolean truncated = false;
        for (long label = plan.labelStart(); label <= plan.labelEnd(); label++) {
            if (!occupiedSet.contains(label)) {
                if (missing.size() >= 1000) {
                    truncated = true;
                    break;
                }
                missing.add(label);
            }
        }
        int quantityDifference = plan.plannedQuantity() - packedQuantity;
        int labelCountDifference = occupied.size() - active.size();
        boolean reconcilable = quantityDifference == 0 && labelCountDifference == 0 && outOfRange.isEmpty();

        List<String> differences = new ArrayList<>();
        ReleaseSnapshotResponse snapshot = snapshotOpt.map(this::toSnapshotResponse).orElse(null);
        if (snapshotOpt.isPresent()) {
            PackingRepository.SnapshotRow snap = snapshotOpt.get();
            String currentSummary = occupied.stream().map(String::valueOf)
                    .collect(Collectors.joining(","));
            if (snap.plannedQuantity() != plan.plannedQuantity()) {
                differences.add("计划数量与放行快照不一致：当前 " + plan.plannedQuantity()
                        + "，快照 " + snap.plannedQuantity());
            }
            if (snap.packedQuantity() != packedQuantity) {
                differences.add("实际封箱数量与放行快照不一致：当前 " + packedQuantity
                        + "，快照 " + snap.packedQuantity()
                        + "，差额 " + (packedQuantity - snap.packedQuantity()));
            }
            if (snap.usedLabelCount() != occupied.size()) {
                differences.add("已用标签数与放行快照不一致：当前 " + occupied.size()
                        + "，快照 " + snap.usedLabelCount()
                        + "，差额 " + (occupied.size() - snap.usedLabelCount()));
            }
            if (!snap.labelSummary().equals(currentSummary)) {
                differences.add("已用标签排序摘要与放行快照不一致：当前 [" + currentSummary
                        + "]，快照 [" + snap.labelSummary() + "]");
            }
        }
        return new LabelDiagnosisResponse(batchKey, true, reconcilable, plan.plannedQuantity(),
                packedQuantity, occupied.size(), active.size(), quantityDifference,
                labelCountDifference, outOfRange, missing, truncated, differences, snapshot);
    }

    /**
     * 放行门禁：未登记计划返回 null（放行不涉及标签核销）；已登记则校验
     * 有效封箱数量之和恰好等于计划数量、号段内已用标签数等于封箱版本数、无越界标签，
     * 任一不满足抛 422（消息含实际值/要求值/差额）；通过则返回事务内待固化的快照草稿。
     */
    public SnapshotDraft prepareReleaseSnapshot(String batchKey) {
        Optional<PackingRepository.PlanRow> planOpt = repo.findPlan(batchKey);
        if (planOpt.isEmpty()) {
            return null;
        }
        PackingRepository.PlanRow plan = planOpt.get();
        List<PackingRepository.PackingRow> active = repo.findActiveRecords(batchKey);
        int packedQuantity = active.stream().mapToInt(PackingRepository.PackingRow::quantity).sum();
        List<Long> occupied = repo.findOccupiedLabels(batchKey);
        List<String> reasons = new ArrayList<>();
        if (packedQuantity != plan.plannedQuantity()) {
            reasons.add("放行前须完成全部计划数量封箱：实际 " + packedQuantity
                    + "，要求 " + plan.plannedQuantity()
                    + "，差额 " + (plan.plannedQuantity() - packedQuantity));
        }
        if (occupied.size() != active.size()) {
            reasons.add("号段内已用标签数必须等于封箱数：已用标签 " + occupied.size()
                    + "，封箱 " + active.size() + "，差额 " + (occupied.size() - active.size()));
        }
        List<Long> outOfRange = occupied.stream()
                .filter(l -> l < plan.labelStart() || l > plan.labelEnd())
                .sorted()
                .toList();
        if (!outOfRange.isEmpty()) {
            reasons.add("存在越界标签: " + outOfRange);
        }
        if (!reasons.isEmpty()) {
            throw ApiException.unprocessable("标签核销未完成，不能放行：" + String.join("；", reasons));
        }
        List<Long> sortedLabels = occupied.stream().sorted().toList();
        String summary = sortedLabels.stream().map(String::valueOf).collect(Collectors.joining(","));
        // 固化每个封箱版本（含已作废版本），按版本号（封箱提交顺序）排序；
        // 作废版本仅作历史证据，实际数量以 packedQuantity、已用标签以 labelSummary 为准。
        List<PackResponse.Entry> entries = repo.findAllRecords(batchKey).stream()
                .sorted(Comparator.comparingLong(PackingRepository.PackingRow::id))
                .map(r -> new PackResponse.Entry(r.id(), r.labelNo(), r.quantity(),
                        r.packedBy(), Instant.parse(r.packedAt())))
                .toList();
        return new SnapshotDraft(batchKey, plan.plannedQuantity(), packedQuantity, occupied.size(),
                summary, toJson(entries), now());
    }

    /**
     * 在放行事务内固化快照（只插不改）。
     */
    public void saveReleaseSnapshot(SnapshotDraft draft) {
        repo.insertSnapshot(new PackingRepository.SnapshotRow(0L, draft.batchKey(),
                draft.plannedQuantity(), draft.packedQuantity(), draft.usedLabelCount(),
                draft.labelSummary(), draft.recordsJson(), draft.createdAt()));
    }

    // ---------- 内部工具 ----------

    /**
     * 幂等执行：同事务内先查 command_log，命中则按指纹返回快照或 409；
     * 未命中执行业务动作并写入快照。并发同键插入冲突时重试，读取已提交结果；失败动作不占键。
     */
    private StoredResponse executeIdempotent(String type, String commandKey, String fingerprint,
                                             Supplier<StoredResponse> action) {
        for (int attempt = 0; attempt < IDEMPOTENCY_MAX_ATTEMPTS; attempt++) {
            try {
                return tx.execute(status -> {
                    var logged = loggedResponse(type, commandKey, fingerprint);
                    if (logged.isPresent()) {
                        return logged.get();
                    }
                    StoredResponse response = action.get();
                    batchRepo.insertCommand(new BatchRepository.CommandRow(type, commandKey, fingerprint,
                            response.status(), response.body()), now());
                    return response;
                });
            } catch (DuplicateKeyException e) {
                // 并发同命令键或业务唯一键冲突：回滚后重试命令快照；若为业务键冲突，重试时由业务校验抛出
            }
        }
        throw ApiException.conflict("命令并发冲突，请重试: " + commandKey);
    }

    private Optional<StoredResponse> loggedResponse(String type, String commandKey, String fingerprint) {
        var existing = batchRepo.findCommand(type, commandKey);
        if (existing.isEmpty()) {
            return Optional.empty();
        }
        BatchRepository.CommandRow row = existing.get();
        if (!row.fingerprint().equals(fingerprint)) {
            throw ApiException.conflict("commandKey 已以不同参数使用: " + commandKey);
        }
        return Optional.of(new StoredResponse(row.responseStatus(), row.responseBody()));
    }

    private boolean inWindow(Instant t, Instant from, Instant to) {
        return (from == null || !t.isBefore(from)) && (to == null || t.isBefore(to));
    }

    private void assertNoRecalledAncestor(String batchKey) {
        Map<String, String> parentOf = new HashMap<>();
        for (BatchRepository.LineageRow row : batchRepo.findAllLineage()) {
            parentOf.put(row.childKey(), row.parentKey());
        }
        Set<String> recalled = new HashSet<>(batchRepo.findRecalledKeys());
        String current = batchKey;
        while (parentOf.containsKey(current)) {
            current = parentOf.get(current);
            if (recalled.contains(current)) {
                throw ApiException.unprocessable(
                        "祖先批次 " + current + " 已召回，禁止登记计划、封箱和作废");
            }
        }
    }

    private PackingPlanResponse toPlanResponse(PackingRepository.PlanRow row) {
        return new PackingPlanResponse(row.batchKey(), row.plannedQuantity(),
                row.labelStart(), row.labelEnd(), Instant.parse(row.createdAt()));
    }

    private PackingRecordResponse toRecordResponse(PackingRepository.PackingRow r) {
        return new PackingRecordResponse(r.id(), r.labelNo(), r.quantity(), r.packedBy(),
                r.status(), Instant.parse(r.packedAt()), r.voidedBy(), r.voidReason(),
                r.voidedAt() == null ? null : Instant.parse(r.voidedAt()));
    }

    private ReleaseSnapshotResponse toSnapshotResponse(PackingRepository.SnapshotRow row) {
        List<PackResponse.Entry> entries;
        try {
            entries = objectMapper.readValue(row.recordsJson(),
                    new TypeReference<List<PackResponse.Entry>>() {
                    });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("放行快照反序列化失败", e);
        }
        return new ReleaseSnapshotResponse(row.batchKey(), row.plannedQuantity(),
                row.packedQuantity(), row.usedLabelCount(), row.labelSummary(), entries,
                Instant.parse(row.createdAt()));
    }

    private String toJson(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("响应序列化失败", e);
        }
    }

    private String now() {
        return Instant.now(clock).toString();
    }

    private String fingerprint(String... parts) {
        String canonical = String.join(SEP, parts);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
