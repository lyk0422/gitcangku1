package com.example.starter.batch;

import com.example.starter.batch.dto.LabelDetailResponse;
import com.example.starter.batch.dto.LabelDiagnosisResponse;
import com.example.starter.batch.dto.LabelHistoryResponse;
import com.example.starter.batch.dto.LabelPlanResponse;
import com.example.starter.batch.dto.RegisterLabelPlanRequest;
import com.example.starter.batch.dto.ReleaseSnapshotResponse;
import com.example.starter.batch.dto.SealBoxesRequest;
import com.example.starter.batch.dto.SealBoxesResponse;
import com.example.starter.batch.dto.SealRecordResponse;
import com.example.starter.batch.dto.SnapshotSealResponse;
import com.example.starter.batch.dto.VoidSealsRequest;
import com.example.starter.batch.dto.VoidSealsResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * 包装标签核销核心服务。
 *
 * <p>守恒规则：标签号全局只允许关联一个批次；同一标签同时只允许一个生效封箱（一个封箱版本）；
 * 封箱数量为正整数；某批次全部生效封箱数量之和不得超过计划数量；放行前须数量封足且
 * 已用标签数等于生效封箱数。
 *
 * <p>并发策略：同一批次的封箱/作废/批准/召回在事务内对 batch 行 SELECT ... FOR UPDATE 串行化，
 * 按事务提交顺序裁决；跨批次的同标签并发由 label_occupancy 主键与 package_seal(label_no,version)
 * 唯一约束兜底，保证同标签最多一次成功。批量封箱/作废先在事务内校验完整最终集合再统一写入，
 * 任一标签失败整次回滚。commandKey 同参重放首次结果，异参 409，失败不占键。
 */
@Service
public class LabelService {

    private static final String CMD_PLAN = "REGISTER_LABEL_PLAN";
    private static final String CMD_SEAL = "SEAL_BOXES";
    private static final String CMD_VOID = "VOID_SEALS";

    /**
     * 指纹拼接分隔符（NUL）：业务参数不可能包含该字符，避免拼接碰撞。
     */
    private static final String SEP = "\u0000";

    private static final int IDEMPOTENCY_MAX_ATTEMPTS = 3;

    private final BatchRepository batchRepo;
    private final LabelRepository repo;
    private final TransactionTemplate tx;
    private final ObjectMapper objectMapper;

    public LabelService(BatchRepository batchRepo,
                        LabelRepository repo,
                        PlatformTransactionManager transactionManager,
                        ObjectMapper objectMapper) {
        this.batchRepo = batchRepo;
        this.repo = repo;
        this.tx = new TransactionTemplate(transactionManager);
        this.objectMapper = objectMapper;
    }

    /**
     * 登记标签计划：每个批次至多一条，登记后不可修改；号段端点均含，起点不得大于终点。
     */
    public StoredResponse registerPlan(String batchKey, RegisterLabelPlanRequest req) {
        if (req.labelRangeStart() > req.labelRangeEnd()) {
            throw ApiException.badRequest("labelRangeStart 不能大于 labelRangeEnd");
        }
        String fingerprint = fingerprint("plan", batchKey, String.valueOf(req.plannedQuantity()),
                String.valueOf(req.labelRangeStart()), String.valueOf(req.labelRangeEnd()));
        return executeIdempotent(CMD_PLAN, req.commandKey(), fingerprint, () -> {
            BatchRepository.BatchRow batch = batchRepo.findBatchForUpdate(batchKey)
                    .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
            assertBatchAllowsLabelOps(BatchStatus.valueOf(batch.status()));
            assertNoRecalledAncestor(batchKey);
            if (repo.findPlan(batchKey).isPresent()) {
                throw ApiException.conflict("批次已登记标签计划，不可重复登记或修改: " + batchKey);
            }
            String now = now();
            repo.insertPlan(new LabelRepository.LabelPlanRow(0L, batchKey, req.plannedQuantity(),
                    req.labelRangeStart(), req.labelRangeEnd(), now));
            LabelPlanResponse body = new LabelPlanResponse(batchKey, req.plannedQuantity(),
                    req.labelRangeStart(), req.labelRangeEnd(), Instant.parse(now));
            return new StoredResponse(201, toJson(body));
        });
    }

    /**
     * 批量封箱：先校验完整最终集合（越界、重复、已被占用/归属其他批次、超计划），
     * 再在一个事务内插入全部封箱版本与标签占用；任一标签失败整次回滚。
     */
    public StoredResponse sealBoxes(String batchKey, SealBoxesRequest req) {
        List<SealBoxesRequest.SealItem> seals = req.seals();
        List<String> parts = new ArrayList<>();
        parts.add("seal");
        parts.add(batchKey);
        for (SealBoxesRequest.SealItem item : seals) {
            parts.add(String.valueOf(item.labelNo()));
            parts.add(String.valueOf(item.quantity()));
        }
        String fingerprint = fingerprint(parts.toArray(new String[0]));
        return executeIdempotent(CMD_SEAL, req.commandKey(), fingerprint, () -> {
            BatchRepository.BatchRow batch = batchRepo.findBatchForUpdate(batchKey)
                    .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
            assertBatchAllowsLabelOps(BatchStatus.valueOf(batch.status()));
            assertNoRecalledAncestor(batchKey);
            LabelRepository.LabelPlanRow plan = repo.findPlan(batchKey)
                    .orElseThrow(() -> ApiException.unprocessable("批次尚未登记标签计划: " + batchKey));

            // 请求内标签号重复：最终集合不合法
            Set<Long> requestLabels = new LinkedHashSet<>();
            for (SealBoxesRequest.SealItem item : seals) {
                if (!requestLabels.add(item.labelNo())) {
                    throw ApiException.unprocessable(
                            "本次封箱请求内标签号重复: " + item.labelNo());
                }
            }

            int currentQuantity = sumQuantity(repo.findActiveSeals(batchKey));
            int addedQuantity = 0;
            String now = now();
            List<LabelRepository.PackageSealRow> accepted = new ArrayList<>(seals.size());
            // 先完成全部校验（完整最终集合），再统一写入
            for (SealBoxesRequest.SealItem item : seals) {
                long labelNo = item.labelNo();
                if (labelNo < plan.labelRangeStart() || labelNo > plan.labelRangeEnd()) {
                    throw ApiException.unprocessable("标签号 " + labelNo + " 越界，要求落在 ["
                            + plan.labelRangeStart() + ", " + plan.labelRangeEnd() + "] 内");
                }
                List<LabelRepository.PackageSealRow> versions = repo.findSealVersions(labelNo);
                if (!versions.isEmpty() && !versions.get(0).batchKey().equals(batchKey)) {
                    throw ApiException.conflict("标签号 " + labelNo + " 已关联其他批次，不可再用于批次 "
                            + batchKey);
                }
                if (repo.findOccupancy(labelNo).isPresent()) {
                    throw ApiException.conflict(
                            "标签号 " + labelNo + " 已存在生效封箱，不可重复封箱");
                }
                long projected = (long) currentQuantity + addedQuantity + item.quantity();
                if (projected > plan.plannedQuantity()) {
                    throw ApiException.unprocessable("封箱数量超过计划：当前已封 " + currentQuantity
                            + "，本次新增后将达 " + projected + "，计划要求 " + plan.plannedQuantity()
                            + "，超出 " + (projected - plan.plannedQuantity()));
                }
                addedQuantity += item.quantity();
            }
            // 校验通过：统一写入封箱版本与占用
            for (SealBoxesRequest.SealItem item : seals) {
                long labelNo = item.labelNo();
                int version = repo.findSealVersions(labelNo).size() + 1;
                LabelRepository.PackageSealRow row = new LabelRepository.PackageSealRow(
                        0L, batchKey, labelNo, item.quantity(), version, "ACTIVE",
                        req.commandKey(), now, null, null, null);
                repo.insertSeal(row);
                long sealId = repo.findActiveSeal(batchKey, labelNo).orElseThrow().id();
                repo.insertOccupancy(new LabelRepository.OccupancyRow(labelNo, batchKey, sealId));
                accepted.add(repo.findActiveSeal(batchKey, labelNo).orElseThrow());
            }

            return new StoredResponse(201,
                    toJson(buildSealResponse(batchKey, plan, accepted, Instant.parse(now))));
        });
    }

    /**
     * 批量作废：仅未放行批次可作废；每个标签须存在该批次的生效封箱。作废写入原因，
     * 释放数量与标签占用；任一条目失败整次回滚。
     */
    public StoredResponse voidSeals(String batchKey, VoidSealsRequest req) {
        List<VoidSealsRequest.VoidItem> voids = req.voids();
        List<String> parts = new ArrayList<>();
        parts.add("void");
        parts.add(batchKey);
        for (VoidSealsRequest.VoidItem item : voids) {
            parts.add(String.valueOf(item.labelNo()));
            parts.add(item.reason());
        }
        String fingerprint = fingerprint(parts.toArray(new String[0]));
        return executeIdempotent(CMD_VOID, req.commandKey(), fingerprint, () -> {
            BatchRepository.BatchRow batch = batchRepo.findBatchForUpdate(batchKey)
                    .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
            assertBatchAllowsLabelOps(BatchStatus.valueOf(batch.status()));
            assertNoRecalledAncestor(batchKey);
            LabelRepository.LabelPlanRow plan = repo.findPlan(batchKey)
                    .orElseThrow(() -> ApiException.unprocessable("批次尚未登记标签计划: " + batchKey));

            String now = now();
            Set<Long> requestLabels = new LinkedHashSet<>();
            for (VoidSealsRequest.VoidItem item : voids) {
                if (!requestLabels.add(item.labelNo())) {
                    throw ApiException.unprocessable(
                            "本次作废请求内标签号重复: " + item.labelNo());
                }
            }

            // 先校验完整最终集合：标签 → 生效封箱 与作废原因
            java.util.LinkedHashMap<Long, LabelRepository.PackageSealRow> activeByLabel =
                    new java.util.LinkedHashMap<>();
            int releasedQuantity = 0;
            for (VoidSealsRequest.VoidItem item : voids) {
                long labelNo = item.labelNo();
                LabelRepository.PackageSealRow active = repo.findActiveSeal(batchKey, labelNo)
                        .orElse(null);
                if (active == null) {
                    if (repo.findOccupancy(labelNo).isPresent()) {
                        throw ApiException.conflict(
                                "标签号 " + labelNo + " 的生效封箱属于其他批次，不能由本批次作废");
                    }
                    throw ApiException.conflict(
                            "标签号 " + labelNo + " 在批次 " + batchKey + " 不存在生效封箱，不能作废");
                }
                activeByLabel.put(labelNo, active);
                releasedQuantity += active.quantity();
            }
            // 统一作废并释放占用
            for (VoidSealsRequest.VoidItem item : voids) {
                repo.voidSeal(activeByLabel.get(item.labelNo()).id(), now, item.reason(),
                        req.commandKey());
                repo.deleteOccupancy(item.labelNo());
            }

            // 作废后重读最新版本行，返回含作废原因/时间的稳定证据
            List<SealRecordResponse> voidedRecords = voids.stream()
                    .map(item -> repo.findSealVersions(item.labelNo()).stream()
                            .reduce((first, second) -> second).orElseThrow())
                    .map(this::toSealResponse)
                    .toList();
            List<LabelRepository.PackageSealRow> activeAfter = repo.findActiveSeals(batchKey);
            int sealedQuantity = sumQuantity(activeAfter);
            VoidSealsResponse body = new VoidSealsResponse(batchKey, voidedRecords, releasedQuantity,
                    sealedQuantity, plan.plannedQuantity(),
                    plan.plannedQuantity() - sealedQuantity,
                    repo.countOccupancyForBatch(batchKey), activeAfter.size(), Instant.parse(now));
            return new StoredResponse(201, toJson(body));
        });
    }

    /**
     * 标签核销当前明细（只读）：计划、守恒汇总与当前生效封箱。
     */
    public LabelDetailResponse detail(String batchKey) {
        BatchRepository.BatchRow batch = batchRepo.findBatch(batchKey)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
        Optional<LabelRepository.LabelPlanRow> planOpt = repo.findPlan(batchKey);
        List<LabelRepository.PackageSealRow> active = repo.findActiveSeals(batchKey);
        int sealedQuantity = sumQuantity(active);
        int labelCount = repo.countOccupancyForBatch(batchKey);
        if (planOpt.isEmpty()) {
            return new LabelDetailResponse(batchKey, batch.status(), false, 0, sealedQuantity, 0,
                    labelCount, active.size(), 0L, 0L, 0L, 0L, false,
                    active.stream().map(this::toSealResponse).toList());
        }
        LabelRepository.LabelPlanRow plan = planOpt.get();
        long rangeCount = plan.labelRangeEnd() - plan.labelRangeStart() + 1;
        boolean reconciled = sealedQuantity == plan.plannedQuantity()
                && labelCount == active.size();
        return new LabelDetailResponse(batchKey, batch.status(), true, plan.plannedQuantity(),
                sealedQuantity, plan.plannedQuantity() - sealedQuantity, labelCount, active.size(),
                plan.labelRangeStart(), plan.labelRangeEnd(), rangeCount,
                rangeCount - labelCount, reconciled,
                active.stream().map(this::toSealResponse).toList());
    }

    /**
     * 标签封箱完整历史（只读）：计划概要 + 全部封箱版本（含已作废），按提交顺序。
     */
    public LabelHistoryResponse history(String batchKey) {
        BatchRepository.BatchRow batch = batchRepo.findBatch(batchKey)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
        LabelPlanResponse plan = repo.findPlan(batchKey)
                .map(p -> new LabelPlanResponse(batchKey, p.plannedQuantity(), p.labelRangeStart(),
                        p.labelRangeEnd(), Instant.parse(p.createdAt())))
                .orElse(null);
        List<SealRecordResponse> seals = repo.findSealsByBatch(batchKey).stream()
                .map(this::toSealResponse).toList();
        return new LabelHistoryResponse(batchKey, batch.status(), plan, seals);
    }

    /**
     * 放行快照查询（只读）：未固化快照返回 404。
     */
    public ReleaseSnapshotResponse snapshot(String batchKey) {
        batchRepo.findBatch(batchKey)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
        return toSnapshotResponse(batchKey)
                .orElseThrow(() -> ApiException.notFound("批次尚无放行标签快照: " + batchKey));
    }

    /**
     * 标签差异诊断（只读）：返回当前核销状态、放行快照及二者差异，不改变任何状态。
     */
    public LabelDiagnosisResponse diagnose(String batchKey) {
        BatchRepository.BatchRow batch = batchRepo.findBatch(batchKey)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
        LabelDetailResponse current = detail(batchKey);
        ReleaseSnapshotResponse snapshot = toSnapshotResponse(batchKey).orElse(null);
        LabelDiagnosisResponse.SnapshotDiff diff = null;
        if (snapshot != null) {
            List<LabelRepository.PackageSealRow> active = repo.findActiveSeals(batchKey);
            List<Long> currentLabels = active.stream().map(LabelRepository.PackageSealRow::labelNo).toList();
            List<Long> snapshotLabels = snapshot.seals().stream().map(SnapshotSealResponse::labelNo).toList();
            List<Long> onlySnapshot = snapshotLabels.stream()
                    .filter(l -> !currentLabels.contains(l)).toList();
            List<Long> onlyCurrent = currentLabels.stream()
                    .filter(l -> !snapshotLabels.contains(l)).toList();
            diff = new LabelDiagnosisResponse.SnapshotDiff(onlySnapshot, onlyCurrent,
                    current.sealedQuantity(), snapshot.sealedQuantity(),
                    current.sealedQuantity() - snapshot.sealedQuantity(), true);
        }
        return new LabelDiagnosisResponse(batchKey, batch.status(), current.planRegistered(),
                current.plannedQuantity(), current.labelRangeStart(), current.labelRangeEnd(),
                current.sealedQuantity(), current.remainingQuantity(), current.activeLabelCount(),
                current.activeSealCount(), current.activeLabelCount() - current.activeSealCount(),
                current.reconciled(), snapshot, diff);
    }

    /**
     * 放行闸门：在批准事务（已持有 batch 行锁）内调用。
     * 已登记标签计划的批次必须数量封足且已用标签数等于生效封箱数，否则 422；
     * 通过后在同一事务固化放行快照。未登记计划的批次沿用既有放行流程，不固化快照。
     */
    public void assertReleasableAndCaptureSnapshot(String batchKey, String capturedAt) {
        Optional<LabelRepository.LabelPlanRow> planOpt = repo.findPlan(batchKey);
        if (planOpt.isEmpty()) {
            return;
        }
        LabelRepository.LabelPlanRow plan = planOpt.get();
        List<LabelRepository.PackageSealRow> active = repo.findActiveSeals(batchKey);
        int sealedQuantity = sumQuantity(active);
        int labelCount = repo.countOccupancyForBatch(batchKey);
        if (sealedQuantity != plan.plannedQuantity()) {
            throw ApiException.unprocessable("放行核销未完成：实际封箱 " + sealedQuantity
                    + "，计划要求 " + plan.plannedQuantity() + "，尚缺 "
                    + (plan.plannedQuantity() - sealedQuantity));
        }
        if (labelCount != active.size()) {
            throw ApiException.unprocessable("放行核销未完成：已用标签数 " + labelCount
                    + " 与生效封箱数 " + active.size() + " 不一致");
        }
        List<Long> labels = active.stream().map(LabelRepository.PackageSealRow::labelNo).toList();
        String usedLabels = labels.stream().map(String::valueOf).collect(Collectors.joining(","));
        repo.insertSnapshot(new LabelRepository.SnapshotRow(0L, batchKey, plan.plannedQuantity(),
                sealedQuantity, usedLabels, compressRanges(labels), capturedAt));
        int seq = 1;
        for (LabelRepository.PackageSealRow seal : active) {
            repo.insertSnapshotSeal(new LabelRepository.SnapshotSealRow(0L, batchKey, seal.labelNo(),
                    seal.quantity(), seal.version(), seq++));
        }
    }

    /**
     * 标签写操作（登记/封箱/作废）仅允许未放行批次。RELEASED/SPLIT 已放行（持有冻结快照，
     * SPLIT 由放行后拆分而来），RECALLED 已召回：三者均拒绝（409）。
     * REJECTED 从未放行、不存在快照，仍属于“未放行批次”，允许封箱与作废。
     * 任一祖先被召回的后代批次同样禁止新增标签操作（与既有检验/批准/拆分规则一致）。
     */
    private void assertBatchAllowsLabelOps(BatchStatus status) {
        if (status == BatchStatus.RELEASED || status == BatchStatus.SPLIT
                || status == BatchStatus.RECALLED) {
            throw ApiException.conflict("批次状态 " + status
                    + " 属于已放行或已召回，不允许标签登记、封箱或作废");
        }
    }

    /**
     * 若任一级祖先已被召回则抛 422：后代批次禁止新增标签登记、封箱与作废。
     */
    private void assertNoRecalledAncestor(String batchKey) {
        java.util.HashMap<String, String> parentOf = new java.util.HashMap<>();
        for (BatchRepository.LineageRow row : batchRepo.findAllLineage()) {
            parentOf.put(row.childKey(), row.parentKey());
        }
        java.util.Set<String> recalled = new java.util.HashSet<>(batchRepo.findRecalledKeys());
        String current = batchKey;
        while (parentOf.containsKey(current)) {
            current = parentOf.get(current);
            if (recalled.contains(current)) {
                throw ApiException.unprocessable(
                        "祖先批次 " + current + " 已召回，禁止标签登记、封箱与作废");
            }
        }
    }

    private int sumQuantity(List<LabelRepository.PackageSealRow> seals) {
        return seals.stream().mapToInt(LabelRepository.PackageSealRow::quantity).sum();
    }

    private SealBoxesResponse buildSealResponse(String batchKey,
                                                LabelRepository.LabelPlanRow plan,
                                                List<LabelRepository.PackageSealRow> accepted,
                                                Instant sealedAt) {
        List<LabelRepository.PackageSealRow> active = repo.findActiveSeals(batchKey);
        int sealedQuantity = sumQuantity(active);
        int labelCount = repo.countOccupancyForBatch(batchKey);
        long rangeCount = plan.labelRangeEnd() - plan.labelRangeStart() + 1;
        boolean reconciled = sealedQuantity == plan.plannedQuantity() && labelCount == active.size();
        return new SealBoxesResponse(batchKey, accepted.stream().map(this::toSealResponse).toList(),
                sealedQuantity, plan.plannedQuantity(), plan.plannedQuantity() - sealedQuantity,
                labelCount, active.size(), plan.labelRangeStart(), plan.labelRangeEnd(),
                rangeCount, rangeCount - labelCount, reconciled, sealedAt);
    }

    private SealRecordResponse toSealResponse(LabelRepository.PackageSealRow row) {
        return new SealRecordResponse(row.id(), row.labelNo(), row.quantity(), row.version(),
                row.status(), row.commandKey(), Instant.parse(row.sealedAt()),
                row.voidedAt() == null ? null : Instant.parse(row.voidedAt()),
                row.voidReason(), row.voidCommandKey());
    }

    private Optional<ReleaseSnapshotResponse> toSnapshotResponse(String batchKey) {
        return repo.findSnapshot(batchKey).map(s -> {
            List<SnapshotSealResponse> seals = repo.findSnapshotSeals(batchKey).stream()
                    .map(r -> new SnapshotSealResponse(r.labelNo(), r.quantity(), r.version(), r.seq()))
                    .toList();
            int usedCount = s.usedLabels().isBlank() ? 0 : s.usedLabels().split(",").length;
            return new ReleaseSnapshotResponse(batchKey, s.plannedQuantity(), s.sealedQuantity(),
                    s.usedLabels(), s.labelRangeSummary(), usedCount, seals.size(), seals,
                    Instant.parse(s.capturedAt()));
        });
    }

    /**
     * 将升序标签号列表压缩为号段摘要：连续区间 "a-b"，单点 "a"，逗号分隔，如 1-3,7-8。
     */
    static String compressRanges(List<Long> sortedAscending) {
        if (sortedAscending.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        long start = sortedAscending.get(0);
        long prev = start;
        for (int i = 1; i <= sortedAscending.size(); i++) {
            if (i < sortedAscending.size() && sortedAscending.get(i) == prev + 1) {
                prev = sortedAscending.get(i);
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append(',');
            }
            if (start == prev) {
                sb.append(start);
            } else {
                sb.append(start).append('-').append(prev);
            }
            if (i < sortedAscending.size()) {
                start = sortedAscending.get(i);
                prev = start;
            }
        }
        return sb.toString();
    }

    /**
     * 幂等执行：同事务内先查 command_log，命中则按指纹返回快照或 409；
     * 未命中执行业务动作并写入快照。并发同键插入冲突时重试，读取已提交结果。
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
                // 并发同命令键（或跨批次同标签唯一约束）冲突：回滚后重试
            }
        }
        throw ApiException.conflict("命令并发冲突，请重试: " + commandKey);
    }

    private Optional<StoredResponse> loggedResponse(String type, String commandKey,
                                                    String fingerprint) {
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

    private String toJson(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("响应序列化失败", e);
        }
    }

    private String now() {
        return Instant.now().toString();
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
