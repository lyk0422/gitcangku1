package com.example.starter.batch;

import com.example.starter.batch.dto.LabelDetailResponse;
import com.example.starter.batch.dto.LabelDiagnosticsResponse;
import com.example.starter.batch.dto.LabelSummary;
import com.example.starter.batch.dto.PackagingPlanResponse;
import com.example.starter.batch.dto.RegisterPlanRequest;
import com.example.starter.batch.dto.SealBoxesRequest;
import com.example.starter.batch.dto.SealBoxesResponse;
import com.example.starter.batch.dto.SealResponse;
import com.example.starter.batch.dto.VoidSealRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 包装标签核销服务：包装计划登记、按标签号批量封箱、封箱作废与明细/历史/诊断查询。
 *
 * <p>守恒规则：每个标签号全局最多一条活跃占用（box_seal.active_label_no 唯一约束保证并发
 * 同标签最多一次成功）；活跃封箱数量之和不得超过计划数量；放行前活跃封箱数量之和必须等于
 * 计划数量且号段内已用标签数等于活跃封箱数（每条封箱恰好一个标签，结构性成立）。
 *
 * <p>并发策略：与检验/批准/召回一致，事务内对 batch 行 SELECT ... FOR UPDATE 串行化，
 * 按事务提交顺序裁决；跨批次同标签由数据库唯一约束兜底。批量封箱先校验完整最终集合，
 * 任一标签失败整次回滚，不留下部分占用。
 */
@Service
public class LabelService {

    private static final String CMD_REGISTER_PLAN = "REGISTER_PLAN";
    private static final String CMD_SEAL_BOXES = "SEAL_BOXES";
    private static final String CMD_VOID_SEAL = "VOID_SEAL";

    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_VOIDED = "VOIDED";

    /**
     * 指纹拼接分隔符（NUL 字符）：业务参数不可能包含该字符，避免拼接碰撞。
     */
    private static final String SEP = String.valueOf((char) 0);

    private final BatchRepository batchRepo;
    private final LabelRepository labelRepo;
    private final IdempotentExecutor idempotent;
    private final ObjectMapper objectMapper;

    public LabelService(BatchRepository batchRepo, LabelRepository labelRepo,
                        IdempotentExecutor idempotent, ObjectMapper objectMapper) {
        this.batchRepo = batchRepo;
        this.labelRepo = labelRepo;
        this.idempotent = idempotent;
        this.objectMapper = objectMapper;
    }

    /**
     * 登记包装计划：每批次一份，登记后不可改写；已放行/已召回/已拒绝/已拆分批次不可登记。
     */
    public StoredResponse registerPlan(String batchKey, RegisterPlanRequest req) {
        if (req.labelStart() > req.labelEnd()) {
            throw ApiException.badRequest("labelStart 不能大于 labelEnd: "
                    + req.labelStart() + " > " + req.labelEnd());
        }
        String fingerprint = fingerprint("plan", batchKey, req.plannedQuantity().toString(),
                req.labelStart().toString(), req.labelEnd().toString());
        return idempotent.execute(CMD_REGISTER_PLAN, req.commandKey(), fingerprint, () -> {
            BatchRepository.BatchRow batch = batchRepo.findBatchForUpdate(batchKey)
                    .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
            assertNotTerminalForLabelOps(batch, "登记包装计划");
            if (labelRepo.findPlan(batchKey).isPresent()) {
                throw ApiException.conflict("批次已登记包装计划，计划不可改写: " + batchKey);
            }
            String now = now();
            labelRepo.insertPlan(new LabelRepository.PlanRow(0L, batchKey, req.plannedQuantity(),
                    req.labelStart(), req.labelEnd(), now));
            PackagingPlanResponse body = new PackagingPlanResponse(batchKey, req.plannedQuantity(),
                    req.labelStart(), req.labelEnd(), Instant.parse(now));
            return new StoredResponse(201, toJson(body));
        });
    }

    /**
     * 批量封箱：先校验完整最终集合（号段、请求内重复、全局标签占用、sealKey 冲突、
     * 数量不超计划），任一失败整次回滚；全部校验通过后同事务插入全部封箱。
     * sealKey 同内容重放跳过该条，不同内容 409；全部为重放时整体返回 200。
     */
    public StoredResponse sealBoxes(String batchKey, SealBoxesRequest req) {
        List<SealBoxesRequest.SealSpec> specs = req.seals();
        List<String> parts = new ArrayList<>();
        parts.add("seal");
        parts.add(batchKey);
        for (SealBoxesRequest.SealSpec spec : specs) {
            parts.add(spec.sealKey());
            parts.add(spec.labelNo().toString());
            parts.add(spec.quantity().toString());
        }
        String fingerprint = fingerprint(parts.toArray(new String[0]));
        return idempotent.execute(CMD_SEAL_BOXES, req.commandKey(), fingerprint, () -> {
            BatchRepository.BatchRow batch = batchRepo.findBatchForUpdate(batchKey)
                    .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
            // 行锁后重查命令快照：并发同键请求在锁等待期间可能已由对方提交，须重放首次结果
            var logged = idempotent.loggedResponse(CMD_SEAL_BOXES, req.commandKey(), fingerprint);
            if (logged.isPresent()) {
                return logged.get();
            }
            assertNotTerminalForLabelOps(batch, "封箱");
            LabelRepository.PlanRow plan = labelRepo.findPlan(batchKey)
                    .orElseThrow(() -> ApiException.unprocessable(
                            "批次未登记包装计划，不能封箱: " + batchKey));

            // ---- 先校验完整最终集合，不插入任何记录 ----
            Set<Long> seenLabels = new HashSet<>();
            Set<String> seenSealKeys = new HashSet<>();
            for (SealBoxesRequest.SealSpec spec : specs) {
                if (!seenLabels.add(spec.labelNo())) {
                    throw ApiException.conflict("请求内标签号重复: " + spec.labelNo());
                }
                if (!seenSealKeys.add(spec.sealKey())) {
                    throw ApiException.conflict("请求内 sealKey 重复: " + spec.sealKey());
                }
                if (spec.labelNo() < plan.labelStart() || spec.labelNo() > plan.labelEnd()) {
                    throw ApiException.unprocessable("标签号 " + spec.labelNo() + " 超出该批次号段 ["
                            + plan.labelStart() + ", " + plan.labelEnd() + "]");
                }
            }
            // 区分新封箱与同内容重放；sealKey 异内容 409
            List<SealBoxesRequest.SealSpec> toInsert = new ArrayList<>();
            Map<String, LabelRepository.SealRow> replayed = new HashMap<>();
            for (SealBoxesRequest.SealSpec spec : specs) {
                Optional<LabelRepository.SealRow> existing =
                        labelRepo.findSeal(batchKey, spec.sealKey());
                if (existing.isPresent()) {
                    LabelRepository.SealRow row = existing.get();
                    if (row.labelNo() != spec.labelNo() || row.quantity() != spec.quantity()) {
                        throw ApiException.conflict("sealKey 已以不同内容提交: " + spec.sealKey());
                    }
                    replayed.put(spec.sealKey(), row);
                } else {
                    toInsert.add(spec);
                }
            }
            // 全局标签占用：新封箱的标签不得被任意批次活跃占用
            for (SealBoxesRequest.SealSpec spec : toInsert) {
                Optional<LabelRepository.SealRow> owner =
                        labelRepo.findActiveLabelOwner(spec.labelNo());
                if (owner.isPresent()) {
                    LabelRepository.SealRow o = owner.get();
                    throw ApiException.conflict("标签号 " + spec.labelNo() + " 已被批次 "
                            + o.batchKey() + " 的封箱 " + o.sealKey() + " 占用");
                }
            }
            // 数量守恒：活跃封箱数量之和 + 本次新增不得超过计划数量
            int currentSum = labelRepo.sumActiveQuantities(batchKey);
            int newSum = toInsert.stream().mapToInt(SealBoxesRequest.SealSpec::quantity).sum();
            if (currentSum + newSum > plan.plannedQuantity()) {
                throw ApiException.unprocessable("封箱数量超计划：实际 " + (currentSum + newSum)
                        + " / 计划 " + plan.plannedQuantity() + "，超出 "
                        + (currentSum + newSum - plan.plannedQuantity()));
            }

            // ---- 校验通过，同事务插入全部新封箱 ----
            String now = now();
            Map<String, LabelRepository.SealRow> inserted = new HashMap<>();
            for (SealBoxesRequest.SealSpec spec : toInsert) {
                LabelRepository.SealRow row = new LabelRepository.SealRow(0L, batchKey,
                        spec.sealKey(), spec.labelNo(), spec.quantity(), STATUS_ACTIVE, 1,
                        spec.labelNo(), null, now, null);
                labelRepo.insertSeal(row);
                inserted.put(spec.sealKey(), row);
            }
            List<SealResponse> seals = new ArrayList<>(specs.size());
            for (SealBoxesRequest.SealSpec spec : specs) {
                LabelRepository.SealRow row = inserted.containsKey(spec.sealKey())
                        ? inserted.get(spec.sealKey())
                        : replayed.get(spec.sealKey());
                seals.add(toSealResponse(row));
            }
            SealBoxesResponse body = new SealBoxesResponse(batchKey, seals,
                    summary(batchKey, plan));
            return new StoredResponse(toInsert.isEmpty() ? 200 : 201, toJson(body));
        });
    }

    /**
     * 作废封箱：仅未放行批次可作废；写入原因、版本 +1，并释放数量与标签占用。
     * 已放行/已召回/已拆分批次 409（放行快照已固化，作废申请不得改写）。
     */
    public StoredResponse voidSeal(String batchKey, String sealKey, VoidSealRequest req) {
        String fingerprint = fingerprint("void", batchKey, sealKey, req.reason());
        return idempotent.execute(CMD_VOID_SEAL, req.commandKey(), fingerprint, () -> {
            BatchRepository.BatchRow batch = batchRepo.findBatchForUpdate(batchKey)
                    .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
            BatchStatus status = BatchStatus.valueOf(batch.status());
            if (status == BatchStatus.RELEASED || status == BatchStatus.RECALLED
                    || status == BatchStatus.SPLIT) {
                throw ApiException.conflict("批次状态 " + status + " 不允许作废封箱，仅未放行批次可作废");
            }
            LabelRepository.SealRow seal = labelRepo.findSeal(batchKey, sealKey)
                    .orElseThrow(() -> ApiException.notFound(
                            "封箱不存在: " + batchKey + "/" + sealKey));
            if (STATUS_VOIDED.equals(seal.status())) {
                throw ApiException.conflict("封箱已作废，不可重复作废: " + sealKey);
            }
            String now = now();
            labelRepo.voidSeal(batchKey, sealKey, seal.version() + 1, req.reason(), now);
            LabelRepository.SealRow updated = labelRepo.findSeal(batchKey, sealKey)
                    .orElseThrow(() -> new IllegalStateException("作废后封箱读取失败: " + sealKey));
            return new StoredResponse(200, toJson(toSealResponse(updated)));
        });
    }

    /**
     * 标签核销明细：计划 + 活跃封箱 + 汇总（实际值/要求值/差额）；只读，不改变状态。
     */
    public LabelDetailResponse detail(String batchKey) {
        batchRepo.findBatch(batchKey)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
        Optional<LabelRepository.PlanRow> plan = labelRepo.findPlan(batchKey);
        List<SealResponse> seals = labelRepo.findActiveSeals(batchKey).stream()
                .map(this::toSealResponse)
                .toList();
        return new LabelDetailResponse(batchKey, plan.map(this::toPlanResponse).orElse(null),
                seals, summary(batchKey, plan.orElse(null)));
    }

    /**
     * 标签核销历史：计划 + 全部封箱（含已作废，按提交顺序）+ 汇总；历史不因作废改写。
     */
    public LabelDetailResponse history(String batchKey) {
        batchRepo.findBatch(batchKey)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
        Optional<LabelRepository.PlanRow> plan = labelRepo.findPlan(batchKey);
        List<SealResponse> seals = labelRepo.findSeals(batchKey).stream()
                .map(this::toSealResponse)
                .toList();
        return new LabelDetailResponse(batchKey, plan.map(this::toPlanResponse).orElse(null),
                seals, summary(batchKey, plan.orElse(null)));
    }

    /**
     * 标签核销诊断：计划/实际/差额、当前标签摘要，并与放行快照比对（未放行则 snapshot 为 null）。
     * 已放行批次若发现标签差异，只能按既有召回流程处置；本查询保留差异诊断，只读不改状态。
     */
    public LabelDiagnosticsResponse diagnostics(String batchKey) {
        BatchRepository.BatchRow batch = batchRepo.findBatch(batchKey)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
        Optional<LabelRepository.PlanRow> plan = labelRepo.findPlan(batchKey);
        List<LabelRepository.SealRow> active = labelRepo.findActiveSeals(batchKey);
        LabelSummary summary = summary(batchKey, plan.orElse(null));
        String currentDigest = labelDigest(active);
        boolean released = BatchStatus.RELEASED.name().equals(batch.status())
                || BatchStatus.RECALLED.name().equals(batch.status())
                || BatchStatus.SPLIT.name().equals(batch.status());

        Optional<LabelRepository.SnapshotRow> snapshot = labelRepo.findSnapshot(batchKey);
        LabelDiagnosticsResponse.SnapshotView snapshotView = null;
        Boolean digestMatches = null;
        List<String> discrepancies = List.of();
        if (snapshot.isPresent()) {
            LabelRepository.SnapshotRow snap = snapshot.get();
            List<LabelDiagnosticsResponse.SnapshotSeal> snapshotSeals = parseSnapshotSeals(snap);
            snapshotView = new LabelDiagnosticsResponse.SnapshotView(snap.plannedQuantity(),
                    snap.sealedQuantity(), snap.labelCount(), snap.labelDigest(), snapshotSeals,
                    Instant.parse(snap.createdAt()));
            digestMatches = snap.labelDigest().equals(currentDigest);
            discrepancies = discrepancies(snapshotSeals, active);
        }
        return new LabelDiagnosticsResponse(batchKey, batch.status(),
                summary.plannedQuantity(), summary.sealedQuantity(), summary.remainingQuantity(),
                summary.usedLabelCount(), summary.activeSealCount(),
                summary.usedLabelCount() == summary.activeSealCount(),
                released, currentDigest, snapshotView, digestMatches, discrepancies);
    }

    /**
     * 放行门禁：批次登记了包装计划时，放行前活跃封箱数量之和必须等于计划数量。
     * 在批准事务内、持有批次行锁时调用；不满足抛 422 并携带实际值/要求值/差额。
     */
    public void assertReleaseReady(String batchKey) {
        Optional<LabelRepository.PlanRow> plan = labelRepo.findPlan(batchKey);
        if (plan.isEmpty()) {
            return;
        }
        int sealed = labelRepo.sumActiveQuantities(batchKey);
        int planned = plan.get().plannedQuantity();
        if (sealed != planned) {
            throw ApiException.unprocessable("封箱数量未达计划，不能放行：实际 " + sealed
                    + " / 计划 " + planned + "，差额 " + (planned - sealed));
        }
    }

    /**
     * 固化放行快照：计划数量、实际封箱数量、已用标签规范排序摘要与每个封箱版本。
     * 在批准事务内、持有批次行锁时调用；快照一旦写入不得改写（每批次仅一份，由唯一约束保证）。
     */
    public void freezeReleaseSnapshot(String batchKey) {
        Optional<LabelRepository.PlanRow> plan = labelRepo.findPlan(batchKey);
        if (plan.isEmpty()) {
            return;
        }
        List<LabelRepository.SealRow> active = labelRepo.findActiveSeals(batchKey);
        List<LabelDiagnosticsResponse.SnapshotSeal> seals = active.stream()
                .sorted(Comparator.comparingLong(LabelRepository.SealRow::labelNo))
                .map(s -> new LabelDiagnosticsResponse.SnapshotSeal(s.sealKey(), s.labelNo(),
                        s.quantity(), s.version()))
                .toList();
        int sealed = active.stream().mapToInt(LabelRepository.SealRow::quantity).sum();
        labelRepo.insertSnapshot(new LabelRepository.SnapshotRow(0L, batchKey,
                plan.get().plannedQuantity(), sealed, active.size(), labelDigest(active),
                toJson(seals), now()));
    }

    /**
     * 快照封箱与当前活跃封箱的差异诊断：双向对比标签号集合与封箱版本。
     */
    private List<String> discrepancies(List<LabelDiagnosticsResponse.SnapshotSeal> snapshotSeals,
                                       List<LabelRepository.SealRow> active) {
        Map<Long, LabelDiagnosticsResponse.SnapshotSeal> snapshotByLabel = new HashMap<>();
        for (LabelDiagnosticsResponse.SnapshotSeal s : snapshotSeals) {
            snapshotByLabel.put(s.labelNo(), s);
        }
        Map<Long, LabelRepository.SealRow> activeByLabel = new HashMap<>();
        for (LabelRepository.SealRow s : active) {
            activeByLabel.put(s.labelNo(), s);
        }
        List<String> result = new ArrayList<>();
        for (LabelDiagnosticsResponse.SnapshotSeal s : snapshotSeals) {
            LabelRepository.SealRow current = activeByLabel.get(s.labelNo());
            if (current == null) {
                result.add("标签 " + s.labelNo() + " 在放行快照中（封箱 " + s.sealKey()
                        + " v" + s.version() + "）但当前无活跃占用");
            } else if (!current.sealKey().equals(s.sealKey()) || current.version() != s.version()
                    || current.quantity() != s.quantity()) {
                result.add("标签 " + s.labelNo() + " 与快照不一致：快照 封箱 " + s.sealKey()
                        + " v" + s.version() + " 数量 " + s.quantity() + "，当前 封箱 "
                        + current.sealKey() + " v" + current.version() + " 数量 "
                        + current.quantity());
            }
        }
        for (LabelRepository.SealRow s : active) {
            if (!snapshotByLabel.containsKey(s.labelNo())) {
                result.add("标签 " + s.labelNo() + " 当前活跃（封箱 " + s.sealKey()
                        + "）但不在放行快照中");
            }
        }
        return result;
    }

    /**
     * 已用标签号升序规范摘要：排序后以逗号连接取 SHA-256 十六进制。
     */
    private String labelDigest(List<LabelRepository.SealRow> active) {
        List<Long> labels = active.stream()
                .map(LabelRepository.SealRow::labelNo)
                .sorted()
                .toList();
        StringBuilder canonical = new StringBuilder();
        for (int i = 0; i < labels.size(); i++) {
            if (i > 0) {
                canonical.append(',');
            }
            canonical.append(labels.get(i));
        }
        return sha256Hex(canonical.toString());
    }

    private List<LabelDiagnosticsResponse.SnapshotSeal> parseSnapshotSeals(
            LabelRepository.SnapshotRow snap) {
        try {
            return objectMapper.readValue(snap.sealsJson(),
                    new TypeReference<List<LabelDiagnosticsResponse.SnapshotSeal>>() {
                    });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("放行快照解析失败: " + snap.batchKey(), e);
        }
    }

    /**
     * 标签类写操作（登记计划/封箱）的状态门禁：已放行、已召回、已拒绝、已拆分批次拒绝。
     */
    private void assertNotTerminalForLabelOps(BatchRepository.BatchRow batch, String operation) {
        BatchStatus status = BatchStatus.valueOf(batch.status());
        if (status == BatchStatus.RELEASED || status == BatchStatus.RECALLED
                || status == BatchStatus.REJECTED || status == BatchStatus.SPLIT) {
            throw ApiException.conflict("批次状态 " + status + " 不允许" + operation);
        }
    }

    private LabelSummary summary(String batchKey, LabelRepository.PlanRow plan) {
        List<LabelRepository.SealRow> active = labelRepo.findActiveSeals(batchKey);
        int sealed = active.stream().mapToInt(LabelRepository.SealRow::quantity).sum();
        Integer planned = plan == null ? null : plan.plannedQuantity();
        Integer remaining = plan == null ? null : plan.plannedQuantity() - sealed;
        return new LabelSummary(planned, sealed, remaining, active.size(), active.size());
    }

    private PackagingPlanResponse toPlanResponse(LabelRepository.PlanRow row) {
        return new PackagingPlanResponse(row.batchKey(), row.plannedQuantity(), row.labelStart(),
                row.labelEnd(), Instant.parse(row.createdAt()));
    }

    private SealResponse toSealResponse(LabelRepository.SealRow row) {
        return new SealResponse(row.batchKey(), row.sealKey(), row.labelNo(), row.quantity(),
                row.status(), row.version(), Instant.parse(row.createdAt()),
                row.voidedAt() == null ? null : Instant.parse(row.voidedAt()), row.voidReason());
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
        return sha256Hex(String.join(SEP, parts));
    }

    private String sha256Hex(String canonical) {
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
