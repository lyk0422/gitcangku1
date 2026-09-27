package com.example.starter.pallet;

import com.example.starter.batch.ApiException;
import com.example.starter.batch.BatchRepository;
import com.example.starter.batch.BatchStatus;
import com.example.starter.batch.StoredResponse;
import com.example.starter.pallet.dto.ConfigureDividerRequest;
import com.example.starter.pallet.dto.CreatePalletRequest;
import com.example.starter.pallet.dto.DiagnosticsResponse;
import com.example.starter.pallet.dto.DisposalResponse;
import com.example.starter.pallet.dto.DividerResponse;
import com.example.starter.pallet.dto.LoadBatchesRequest;
import com.example.starter.pallet.dto.LoadResponse;
import com.example.starter.pallet.dto.PalletDetailResponse;
import com.example.starter.pallet.dto.PalletHistoryResponse;
import com.example.starter.pallet.dto.PalletResponse;
import com.example.starter.pallet.dto.QuarantineDisposalRequest;
import com.example.starter.pallet.dto.ShipmentReleaseRequest;
import com.example.starter.pallet.dto.ShipmentResponse;
import com.example.starter.pallet.dto.UnloadRequest;
import com.example.starter.pallet.dto.UnloadResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 批次托盘隔离核心服务。
 *
 * <p>隔离规则：同一托盘内批次须声明隔离类别；类别不同的批次只有在托盘配置了有效隔板
 * （隔板数 ≥ 1）且各批次分配到不同分隔位时共存；有效分隔位数 = 隔板数 + 1。
 *
 * <p>并发策略：同一托盘的装载/解除/装运/隔板变更在事务内对 pallet 行 SELECT ... FOR UPDATE
 * 串行化；装载再按 batchKey 规范顺序锁定批次行（先托盘后批次，与召回联动一致，避免死锁）；
 * 同一批次并发装载由 pallet_active_load 主键保证只在一个托盘成功。
 * commandKey 幂等复用 command_log：同键同参重放首次响应，同键改参 409，失败不占键。
 */
@Service
public class PalletService {

    private static final String CMD_CREATE = "CREATE_PALLET";
    private static final String CMD_DIVIDER = "CONFIGURE_DIVIDER";
    private static final String CMD_LOAD = "LOAD";
    private static final String CMD_UNLOAD = "UNLOAD";
    private static final String CMD_SHIP = "SHIPMENT_RELEASE";
    private static final String CMD_DISPOSAL = "QUARANTINE_DISPOSAL";

    /**
     * 指纹拼接分隔符（NUL 字符）：业务参数不可能包含该字符，避免拼接碰撞。
     */
    private static final String SEP = String.valueOf((char) 0);

    private static final int IDEMPOTENCY_MAX_ATTEMPTS = 3;

    private final PalletRepository palletRepo;
    private final BatchRepository batchRepo;
    private final TransactionTemplate tx;
    private final ObjectMapper objectMapper;

    public PalletService(PalletRepository palletRepo,
                         BatchRepository batchRepo,
                         PlatformTransactionManager transactionManager,
                         ObjectMapper objectMapper) {
        this.palletRepo = palletRepo;
        this.batchRepo = batchRepo;
        this.tx = new TransactionTemplate(transactionManager);
        this.objectMapper = objectMapper;
    }

    /**
     * 创建托盘：初始 OPEN，隔板版本 1；palletKey 全局唯一，重复返回 409。
     */
    public StoredResponse createPallet(CreatePalletRequest req) {
        String fingerprint = fingerprint("create", req.palletKey(), String.valueOf(req.dividerCount()));
        return executeIdempotent(CMD_CREATE, req.commandKey(), fingerprint, () -> {
            palletRepo.findPallet(req.palletKey()).ifPresent(p -> {
                throw ApiException.conflict("palletKey 已存在: " + req.palletKey());
            });
            String now = now();
            palletRepo.insertPallet(new PalletRepository.PalletRow(0L, req.palletKey(),
                    PalletStatus.OPEN.name(), req.dividerCount(), 1, null, null, null, null,
                    null, now));
            PalletResponse body = new PalletResponse(req.palletKey(), PalletStatus.OPEN.name(),
                    req.dividerCount(), 1, Instant.parse(now));
            return new StoredResponse(201, toJson(body));
        });
    }

    /**
     * 变更隔板配置：版本 +1 并记录变更历史；存在在托（ACTIVE）批次时拒绝（409），
     * 避免在托隔离关系被静默破坏；已装运托盘允许变更，装运快照不追溯。
     */
    public StoredResponse configureDivider(String palletKey, ConfigureDividerRequest req) {
        String fingerprint = fingerprint("divider", palletKey, String.valueOf(req.dividerCount()));
        return executeIdempotent(CMD_DIVIDER, req.commandKey(), fingerprint, () -> {
            PalletRepository.PalletRow pallet = lockPallet(palletKey);
            // 托盘行锁后重查命令快照：并发同键请求在锁等待期间可能已由对方提交
            var loggedDivider = loggedResponse(CMD_DIVIDER, req.commandKey(), fingerprint);
            if (loggedDivider.isPresent()) {
                return loggedDivider.get();
            }
            List<PalletRepository.LoadRow> active = palletRepo.findActiveLoads(palletKey);
            if (!active.isEmpty()) {
                throw new ApiException(HttpStatus.CONFLICT, "PALLET_HAS_ACTIVE_LOADS",
                        "托盘存在 " + active.size() + " 个在托批次，不允许变更隔板配置: " + palletKey);
            }
            int newVersion = pallet.dividerVersion() + 1;
            String now = now();
            palletRepo.updateDivider(palletKey, req.dividerCount(), newVersion);
            palletRepo.insertDividerChange(new PalletRepository.DividerChangeRow(0L, palletKey,
                    pallet.dividerCount(), req.dividerCount(), newVersion, now));
            DividerResponse body = new DividerResponse(palletKey, req.dividerCount(), newVersion,
                    Instant.parse(now));
            return new StoredResponse(200, toJson(body));
        });
    }

    /**
     * 装载：请求批次按 batchKey 规范排序后，一次校验批次未召回、未装运放行、未被其他托盘占用
     * 以及完整最终隔离关系（隔板数量、分隔位唯一与范围、类别共存），全部通过后在一个事务提交；
     * 任一校验失败返回 422/404/409，不写入任何托盘关系，失败不占 commandKey。
     */
    public StoredResponse load(String palletKey, LoadBatchesRequest req) {
        List<LoadBatchesRequest.Item> items = canonicalItems(req.items());
        List<String> parts = new ArrayList<>();
        parts.add("load");
        parts.add(palletKey);
        for (LoadBatchesRequest.Item item : items) {
            parts.add(item.batchKey());
            parts.add(item.category().trim());
            parts.add(String.valueOf(item.compartment()));
        }
        String fingerprint = fingerprint(parts.toArray(new String[0]));
        return executeIdempotent(CMD_LOAD, req.commandKey(), fingerprint, () -> {
            PalletRepository.PalletRow pallet = lockPallet(palletKey);
            // 托盘行锁后重查命令快照：并发同键请求在锁等待期间可能已由对方提交，
            // 此时须重放首次结果而不是因批次已被占用误判 422
            var loggedLoad = loggedResponse(CMD_LOAD, req.commandKey(), fingerprint);
            if (loggedLoad.isPresent()) {
                return loggedLoad.get();
            }
            assertPalletOpen(pallet, "装载");

            // 逐一批次：行锁（规范顺序）+ 状态校验（未召回、未装运放行、未被占用）
            for (LoadBatchesRequest.Item item : items) {
                BatchRepository.BatchRow batch = batchRepo.findBatchForUpdate(item.batchKey())
                        .orElseThrow(() -> ApiException.notFound("批次不存在: " + item.batchKey()));
                if (BatchStatus.RECALLED.name().equals(batch.status())) {
                    throw unprocessable("BATCH_RECALLED",
                            "批次已召回，不允许装载: " + item.batchKey());
                }
                if (palletRepo.isBatchShipped(item.batchKey())) {
                    throw unprocessable("BATCH_ALREADY_SHIPPED",
                            "批次已装运放行，不允许再次装载: " + item.batchKey());
                }
                Optional<PalletRepository.LoadRow> occupied =
                        palletRepo.findActiveLoadByBatch(item.batchKey());
                if (occupied.isPresent()) {
                    throw unprocessable("BATCH_OCCUPIED",
                            "批次已被托盘 " + occupied.get().palletKey() + " 占用: " + item.batchKey());
                }
            }

            // 完整最终隔离关系：现有在托 + 本次装载
            List<PalletRepository.LoadRow> existing = palletRepo.findActiveLoads(palletKey);
            List<Slot> finalSlots = new ArrayList<>();
            for (PalletRepository.LoadRow row : existing) {
                finalSlots.add(new Slot(row.batchKey(), row.category(), row.compartment()));
            }
            for (LoadBatchesRequest.Item item : items) {
                finalSlots.add(new Slot(item.batchKey(), item.category().trim(), item.compartment()));
            }
            validateSegregation(pallet.dividerCount(), finalSlots);

            String now = now();
            List<LoadResponse.Item> responseItems = new ArrayList<>(items.size());
            for (int i = 0; i < items.size(); i++) {
                LoadBatchesRequest.Item item = items.get(i);
                int seq = i + 1;
                palletRepo.insertLoad(new PalletRepository.LoadRow(0L, palletKey, item.batchKey(),
                        item.category().trim(), item.compartment(), seq, LoadStatus.ACTIVE.name(),
                        false, null, now, null, null));
                long loadId = palletRepo.findActiveLoadId(palletKey, item.batchKey());
                palletRepo.insertActiveLoad(item.batchKey(), palletKey, loadId);
                responseItems.add(new LoadResponse.Item(item.batchKey(), item.category().trim(),
                        item.compartment(), seq));
            }
            // 不变式：任一在托批次被血缘召回命中时，该托盘全部未装运批次标记待复核
            markReviewIfRecallHit(palletKey, now);
            LoadResponse body = new LoadResponse(palletKey, pallet.dividerVersion(), responseItems,
                    Instant.parse(now));
            return new StoredResponse(201, toJson(body));
        });
    }

    /**
     * 解除装载：仅允许托盘尚未装运；释放分隔位但保留历史（记录置 UNLOADED，不删除）。
     * 批次不在本托盘在托时返回 422；整批解除在一个事务提交。
     */
    public StoredResponse unload(String palletKey, UnloadRequest req) {
        List<String> batchKeys = req.batchKeys().stream().map(String::trim).sorted().toList();
        if (new HashSet<>(batchKeys).size() != batchKeys.size()) {
            throw ApiException.badRequest("batchKeys 存在重复批次");
        }
        String fingerprint = fingerprint("unload", palletKey, String.join(SEP, batchKeys));
        return executeIdempotent(CMD_UNLOAD, req.commandKey(), fingerprint, () -> {
            PalletRepository.PalletRow pallet = lockPallet(palletKey);
            // 托盘行锁后重查命令快照：并发同键请求在锁等待期间可能已由对方提交
            var loggedUnload = loggedResponse(CMD_UNLOAD, req.commandKey(), fingerprint);
            if (loggedUnload.isPresent()) {
                return loggedUnload.get();
            }
            assertPalletOpen(pallet, "解除装载");

            List<PalletRepository.LoadRow> toRelease = new ArrayList<>(batchKeys.size());
            for (String batchKey : batchKeys) {
                Optional<PalletRepository.LoadRow> active = palletRepo.findActiveLoadByBatch(batchKey);
                if (active.isEmpty() || !active.get().palletKey().equals(palletKey)) {
                    throw unprocessable("NOT_LOADED",
                            "批次未在本托盘在托，不能解除装载: " + batchKey);
                }
                toRelease.add(active.get());
            }
            String now = now();
            List<UnloadResponse.ReleasedItem> released = new ArrayList<>(toRelease.size());
            for (PalletRepository.LoadRow row : toRelease) {
                palletRepo.markLoadUnloaded(row.id(), now);
                palletRepo.deleteActiveLoad(row.batchKey());
                released.add(new UnloadResponse.ReleasedItem(row.batchKey(), row.compartment()));
            }
            UnloadResponse body = new UnloadResponse(palletKey, released, Instant.parse(now));
            return new StoredResponse(200, toJson(body));
        });
    }

    /**
     * 装运放行：固化托盘、批次、类别、分隔位与隔板版本快照；快照创建后不可改写，
     * 后续隔板变更不追溯。空托盘返回 422；非 OPEN 状态返回 409。
     */
    public StoredResponse releaseShipment(String palletKey, String actorId,
                                          ShipmentReleaseRequest req) {
        if (actorId == null || actorId.isBlank()) {
            throw ApiException.badRequest("X-Actor-Id 不能为空");
        }
        String actor = actorId.trim();
        String fingerprint = fingerprint("ship", palletKey, actor);
        return executeIdempotent(CMD_SHIP, req.commandKey(), fingerprint, () -> {
            PalletRepository.PalletRow pallet = lockPallet(palletKey);
            // 托盘行锁后重查命令快照：并发同键请求在锁等待期间可能已由对方提交
            var loggedShip = loggedResponse(CMD_SHIP, req.commandKey(), fingerprint);
            if (loggedShip.isPresent()) {
                return loggedShip.get();
            }
            assertPalletOpen(pallet, "装运放行");

            List<PalletRepository.LoadRow> active = palletRepo.findActiveLoads(palletKey);
            if (active.isEmpty()) {
                throw unprocessable("PALLET_EMPTY", "托盘无在托批次，不能装运放行: " + palletKey);
            }
            // 防御性复核完整最终隔离关系：在托期间隔板配置未被破坏时才可放行
            List<Slot> slots = new ArrayList<>(active.size());
            for (PalletRepository.LoadRow row : active) {
                slots.add(new Slot(row.batchKey(), row.category(), row.compartment()));
            }
            validateSegregation(pallet.dividerCount(), slots);

            String now = now();
            List<ShipmentResponse.Item> responseItems = new ArrayList<>(active.size());
            int seq = 0;
            for (PalletRepository.LoadRow row : active) {
                seq++;
                palletRepo.insertShipment(new PalletRepository.ShipmentRow(0L, palletKey,
                        row.batchKey(), row.category(), row.compartment(), seq,
                        pallet.dividerCount(), pallet.dividerVersion(), now));
                palletRepo.deleteActiveLoad(row.batchKey());
                responseItems.add(new ShipmentResponse.Item(row.batchKey(), row.category(),
                        row.compartment(), seq));
            }
            palletRepo.markActiveLoadsShipped(palletKey, now);
            palletRepo.markShipped(palletKey, actor, now);
            ShipmentResponse body = new ShipmentResponse(palletKey, actor, pallet.dividerCount(),
                    pallet.dividerVersion(), responseItems, Instant.parse(now));
            return new StoredResponse(201, toJson(body));
        });
    }

    /**
     * 隔离处置：已装运托盘发现类别错误时进入 QUARANTINE_DISPOSAL；
     * 装载与装运记录全部保留，不得删除。仅 SHIPPED 状态可处置，否则 409。
     */
    public StoredResponse quarantineDisposal(String palletKey, String actorId,
                                             QuarantineDisposalRequest req) {
        if (actorId == null || actorId.isBlank()) {
            throw ApiException.badRequest("X-Actor-Id 不能为空");
        }
        String actor = actorId.trim();
        String fingerprint = fingerprint("disposal", palletKey, actor, req.reason());
        return executeIdempotent(CMD_DISPOSAL, req.commandKey(), fingerprint, () -> {
            PalletRepository.PalletRow pallet = lockPallet(palletKey);
            // 托盘行锁后重查命令快照：并发同键请求在锁等待期间可能已由对方提交
            var loggedDisposal = loggedResponse(CMD_DISPOSAL, req.commandKey(), fingerprint);
            if (loggedDisposal.isPresent()) {
                return loggedDisposal.get();
            }
            if (!PalletStatus.SHIPPED.name().equals(pallet.status())) {
                throw new ApiException(HttpStatus.CONFLICT, "PALLET_NOT_SHIPPED",
                        "托盘状态 " + pallet.status() + " 不允许隔离处置，仅已装运托盘可处置: "
                                + palletKey);
            }
            String now = now();
            palletRepo.markDisposal(palletKey, actor, now, req.reason());
            DisposalResponse body = new DisposalResponse(palletKey,
                    PalletStatus.QUARANTINE_DISPOSAL.name(), req.reason(), actor,
                    Instant.parse(now));
            return new StoredResponse(201, toJson(body));
        });
    }

    /**
     * 托盘明细：当前状态与全部在托装载；只读，不改变状态。
     */
    public PalletDetailResponse detail(String palletKey) {
        PalletRepository.PalletRow pallet = palletRepo.findPallet(palletKey)
                .orElseThrow(() -> ApiException.notFound("托盘不存在: " + palletKey));
        List<PalletDetailResponse.ActiveLoad> loads = palletRepo.findActiveLoads(palletKey).stream()
                .map(row -> new PalletDetailResponse.ActiveLoad(row.batchKey(), row.category(),
                        row.compartment(), row.seq(), row.reviewFlag(),
                        parseInstant(row.reviewMarkedAt()), Instant.parse(row.loadedAt())))
                .toList();
        return new PalletDetailResponse(pallet.palletKey(), pallet.status(), pallet.dividerCount(),
                pallet.dividerVersion(), pallet.shippedBy(), parseInstant(pallet.shippedAt()),
                pallet.disposedBy(), parseInstant(pallet.disposedAt()), pallet.disposalReason(),
                Instant.parse(pallet.createdAt()), loads);
    }

    /**
     * 托盘历史：全部装载记录、装运快照与隔板变更历史；只读，历史不随后续操作改写。
     */
    public PalletHistoryResponse history(String palletKey) {
        PalletRepository.PalletRow pallet = palletRepo.findPallet(palletKey)
                .orElseThrow(() -> ApiException.notFound("托盘不存在: " + palletKey));
        List<PalletHistoryResponse.LoadEntry> loads = palletRepo.findAllLoads(palletKey).stream()
                .map(row -> new PalletHistoryResponse.LoadEntry(row.batchKey(), row.category(),
                        row.compartment(), row.seq(), row.status(), row.reviewFlag(),
                        parseInstant(row.reviewMarkedAt()), Instant.parse(row.loadedAt()),
                        parseInstant(row.unloadedAt()), parseInstant(row.shippedAt())))
                .toList();
        List<PalletHistoryResponse.ShipmentEntry> shipment =
                palletRepo.findShipment(palletKey).stream()
                        .map(row -> new PalletHistoryResponse.ShipmentEntry(row.batchKey(),
                                row.category(), row.compartment(), row.seq(), row.dividerCount(),
                                row.dividerVersion(), Instant.parse(row.shippedAt())))
                        .toList();
        List<PalletHistoryResponse.DividerChangeEntry> dividerChanges =
                palletRepo.findDividerChanges(palletKey).stream()
                        .map(row -> new PalletHistoryResponse.DividerChangeEntry(
                                row.oldDividerCount(), row.newDividerCount(), row.dividerVersion(),
                                Instant.parse(row.createdAt())))
                        .toList();
        return new PalletHistoryResponse(pallet.palletKey(), pallet.status(), pallet.dividerCount(),
                pallet.dividerVersion(), pallet.shippedBy(), parseInstant(pallet.shippedAt()),
                pallet.disposedBy(), parseInstant(pallet.disposedAt()), pallet.disposalReason(),
                Instant.parse(pallet.createdAt()), loads, shipment, dividerChanges);
    }

    /**
     * 托盘隔离诊断：按当前在托批次与隔板配置评估隔离关系，给出要求值/实际值/差额；
     * 只读，不改变状态。
     */
    public DiagnosticsResponse diagnostics(String palletKey) {
        PalletRepository.PalletRow pallet = palletRepo.findPallet(palletKey)
                .orElseThrow(() -> ApiException.notFound("托盘不存在: " + palletKey));
        List<PalletRepository.LoadRow> active = palletRepo.findActiveLoads(palletKey);
        List<Slot> slots = new ArrayList<>(active.size());
        List<DiagnosticsResponse.LoadEntry> loadEntries = new ArrayList<>(active.size());
        for (PalletRepository.LoadRow row : active) {
            slots.add(new Slot(row.batchKey(), row.category(), row.compartment()));
            loadEntries.add(new DiagnosticsResponse.LoadEntry(row.batchKey(), row.category(),
                    row.compartment(), row.reviewFlag()));
        }
        SegregationAssessment assessment = assessSegregation(pallet.dividerCount(), slots);
        return new DiagnosticsResponse(pallet.palletKey(), pallet.status(), pallet.dividerCount(),
                pallet.dividerVersion(), active.size(), assessment.distinctCategories(),
                assessment.requiredDividers(), pallet.dividerCount(), assessment.dividerDeficit(),
                pallet.dividerCount() + 1, assessment.conflicts().isEmpty(),
                assessment.conflicts(), loadEntries);
    }

    // ---------- 内部实现 ----------

    /**
     * 隔离评估槽位：批次、类别、分隔位。
     */
    private record Slot(String batchKey, String category, int compartment) {
    }

    /**
     * 隔离评估结果：类别数、要求隔板数、差额与冲突描述。
     */
    private record SegregationAssessment(int distinctCategories, int requiredDividers,
                                         int dividerDeficit, List<String> conflicts) {
    }

    /**
     * 校验完整最终隔离关系；任一违例抛出可区分原因的 422。
     */
    private void validateSegregation(int dividerCount, List<Slot> slots) {
        SegregationAssessment assessment = assessSegregation(dividerCount, slots);
        if (assessment.conflicts().isEmpty()) {
            return;
        }
        String first = assessment.conflicts().get(0);
        String code = first.substring(0, first.indexOf('|'));
        String message = first.substring(first.indexOf('|') + 1);
        throw unprocessable(code, message);
    }

    /**
     * 评估隔离关系，冲突按主因优先排序：分隔位重复 → 类别共存/隔板数量 → 分隔位范围。
     * 冲突描述以 "CODE|消息" 形式返回，便于校验与诊断共用。
     */
    private SegregationAssessment assessSegregation(int dividerCount, List<Slot> slots) {
        List<String> conflicts = new ArrayList<>();
        Map<Integer, String> compartmentOwner = new HashMap<>();
        for (Slot slot : slots) {
            String owner = compartmentOwner.putIfAbsent(slot.compartment(), slot.batchKey());
            if (owner != null) {
                conflicts.add("COMPARTMENT_DUPLICATE|分隔位 " + slot.compartment()
                        + " 被批次 " + owner + " 与 " + slot.batchKey() + " 重复占用");
            }
        }
        Set<String> categories = new LinkedHashSet<>();
        for (Slot slot : slots) {
            categories.add(slot.category());
        }
        int distinctCategories = categories.size();
        int requiredDividers = Math.max(0, distinctCategories - 1);
        int dividerDeficit = Math.max(0, requiredDividers - dividerCount);
        if (distinctCategories > 1 && dividerCount == 0) {
            conflicts.add("CATEGORY_CONFLICT|托盘未配置有效隔板，" + distinctCategories
                    + " 个不同类别 " + categories + " 的批次不能共存");
        } else if (dividerDeficit > 0) {
            conflicts.add("INSUFFICIENT_DIVIDERS|隔板数量不足：" + distinctCategories
                    + " 个类别需要 " + requiredDividers + " 个隔板，实际 " + dividerCount
                    + " 个，差额 " + dividerDeficit);
        }
        int availableCompartments = dividerCount + 1;
        for (Slot slot : slots) {
            if (slot.compartment() > availableCompartments) {
                conflicts.add("COMPARTMENT_OUT_OF_RANGE|分隔位 " + slot.compartment()
                        + " 超出有效范围 1～" + availableCompartments + "（当前隔板数 " + dividerCount
                        + "），批次: " + slot.batchKey());
            }
        }
        return new SegregationAssessment(distinctCategories, requiredDividers, dividerDeficit,
                conflicts);
    }

    /**
     * 装载后维持不变式：若任一在托批次被血缘召回命中（自身召回或祖先召回），
     * 该托盘全部未装运批次标记待复核。
     */
    private void markReviewIfRecallHit(String palletKey, String markedAt) {
        List<PalletRepository.LoadRow> active = palletRepo.findActiveLoads(palletKey);
        if (active.isEmpty()) {
            return;
        }
        Map<String, String> parentOf = new HashMap<>();
        for (BatchRepository.LineageRow row : batchRepo.findAllLineage()) {
            parentOf.put(row.childKey(), row.parentKey());
        }
        Set<String> recalled = new HashSet<>(batchRepo.findRecalledKeys());
        boolean hit = active.stream().anyMatch(row -> recallHit(row.batchKey(), parentOf, recalled));
        if (hit) {
            palletRepo.markReviewForPallets(List.of(palletKey), markedAt);
        }
    }

    /**
     * 批次自身已召回或任一级祖先已召回。
     */
    private boolean recallHit(String batchKey, Map<String, String> parentOf, Set<String> recalled) {
        String current = batchKey;
        if (recalled.contains(current)) {
            return true;
        }
        while (parentOf.containsKey(current)) {
            current = parentOf.get(current);
            if (recalled.contains(current)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 请求批次按 batchKey 规范排序；请求内重复 batchKey 返回 400。
     */
    private List<LoadBatchesRequest.Item> canonicalItems(List<LoadBatchesRequest.Item> items) {
        List<LoadBatchesRequest.Item> sorted = items.stream()
                .sorted(Comparator.comparing(LoadBatchesRequest.Item::batchKey))
                .toList();
        for (int i = 1; i < sorted.size(); i++) {
            if (sorted.get(i).batchKey().equals(sorted.get(i - 1).batchKey())) {
                throw ApiException.badRequest("items 存在重复批次: " + sorted.get(i).batchKey());
            }
        }
        return sorted;
    }

    private PalletRepository.PalletRow lockPallet(String palletKey) {
        return palletRepo.findPalletForUpdate(palletKey)
                .orElseThrow(() -> ApiException.notFound("托盘不存在: " + palletKey));
    }

    private void assertPalletOpen(PalletRepository.PalletRow pallet, String operation) {
        if (!PalletStatus.OPEN.name().equals(pallet.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "PALLET_NOT_OPEN",
                    "托盘状态 " + pallet.status() + " 不允许" + operation + ": " + pallet.palletKey());
        }
    }

    private static ApiException unprocessable(String code, String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, code, message);
    }

    /**
     * 幂等执行：同事务内先查 command_log，命中则按指纹返回快照或 409；
     * 未命中执行业务动作并写入快照。并发同键或占用索引冲突时回滚重试，读取已提交结果。
     */
    private StoredResponse executeIdempotent(String type, String commandKey, String fingerprint,
                                             Supplier<StoredResponse> action) {
        for (int attempt = 0; attempt < IDEMPOTENCY_MAX_ATTEMPTS; attempt++) {
            try {
                return tx.execute(status -> {
                    Optional<StoredResponse> logged = loggedResponse(type, commandKey, fingerprint);
                    if (logged.isPresent()) {
                        return logged.get();
                    }
                    StoredResponse response = action.get();
                    batchRepo.insertCommand(new BatchRepository.CommandRow(type, commandKey,
                            fingerprint, response.status(), response.body()), now());
                    return response;
                });
            } catch (DuplicateKeyException e) {
                // 并发同键或同一批次并发占用冲突：回滚后重试，读取对方已提交结果
            }
        }
        throw ApiException.conflict("命令并发冲突，请重试: " + commandKey);
    }

    /**
     * 查询命令快照：命中且指纹一致返回首次响应；指纹不一致抛 409；未命中返回空。
     */
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

    private Instant parseInstant(String value) {
        return value == null ? null : Instant.parse(value);
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
