package com.example.starter.batch;

import com.example.starter.batch.dto.CreatePalletRequest;
import com.example.starter.batch.dto.DisposalInfo;
import com.example.starter.batch.dto.DisposalRequest;
import com.example.starter.batch.dto.DisposalResponse;
import com.example.starter.batch.dto.DividerUpdateRequest;
import com.example.starter.batch.dto.LoadRequest;
import com.example.starter.batch.dto.LoadResponse;
import com.example.starter.batch.dto.PalletDetailResponse;
import com.example.starter.batch.dto.PalletDiagnosticsResponse;
import com.example.starter.batch.dto.PalletHistoryResponse;
import com.example.starter.batch.dto.PalletLoadEntry;
import com.example.starter.batch.dto.PalletResponse;
import com.example.starter.batch.dto.ShipRequest;
import com.example.starter.batch.dto.ShipmentResponse;
import com.example.starter.batch.dto.ShipmentSnapshot;
import com.example.starter.batch.dto.UnloadRequest;
import com.example.starter.batch.dto.UnloadResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * 批次托盘隔离核心服务。
 *
 * <p>隔离规则：托盘可用分隔位数 = 隔板数量 + 1；同一分隔位内批次类别必须一致，
 * 不同类别必须分配到不同分隔位；所需隔板数 = max(最大分隔位编号, 在托类别数) - 1。
 *
 * <p>并发策略：装载/解除/装运/处置/隔板修改在事务内对 pallet 行 SELECT ... FOR UPDATE 串行化，
 * 装载与装运再按业务键升序锁定批次行，与批次检验/批准/召回按提交顺序裁决；
 * batch_occupancy 主键保证同一批次并发只能装入一个托盘。
 *
 * <p>幂等：commandKey 经 {@link IdempotentExecutor} 同参重放首次响应、异参 409、失败不占键。
 */
@Service
public class PalletService {

    private static final String CMD_CREATE_PALLET = "CREATE_PALLET";
    private static final String CMD_UPDATE_DIVIDERS = "UPDATE_DIVIDERS";
    private static final String CMD_LOAD = "LOAD";
    private static final String CMD_UNLOAD = "UNLOAD";
    private static final String CMD_SHIP = "SHIP";
    private static final String CMD_DISPOSAL = "DISPOSAL";

    /**
     * 允许装载/装运的批次状态：未召回、未放行、未拒绝、未拆分。
     */
    private static final Set<String> LOADABLE_STATUSES = Set.of(
            BatchStatus.QUARANTINED.name(),
            BatchStatus.PENDING_RELEASE.name(),
            BatchStatus.RELEASE_REVIEW.name());

    private final PalletRepository palletRepo;
    private final BatchRepository batchRepo;
    private final IdempotentExecutor idempotent;
    private final ObjectMapper objectMapper;

    public PalletService(PalletRepository palletRepo, BatchRepository batchRepo,
                         IdempotentExecutor idempotent, ObjectMapper objectMapper) {
        this.palletRepo = palletRepo;
        this.batchRepo = batchRepo;
        this.idempotent = idempotent;
        this.objectMapper = objectMapper;
    }

    /**
     * 创建托盘：初始状态 OPEN，隔板版本从 1 开始；palletKey 全局唯一，重复返回 409。
     */
    public StoredResponse createPallet(CreatePalletRequest req) {
        String fingerprint = IdempotentExecutor.fingerprint("pallet-create", req.palletKey(),
                String.valueOf(req.dividerCount()));
        return idempotent.execute(CMD_CREATE_PALLET, req.commandKey(), fingerprint, () -> {
            palletRepo.findPallet(req.palletKey()).ifPresent(p -> {
                throw ApiException.conflict("palletKey 已存在: " + req.palletKey());
            });
            String now = now();
            palletRepo.insertPallet(new PalletRepository.PalletRow(0L, req.palletKey(),
                    req.dividerCount(), 1, PalletStatus.OPEN.name(), now));
            PalletResponse body = new PalletResponse(req.palletKey(), req.dividerCount(), 1,
                    PalletStatus.OPEN, Instant.parse(now));
            return new StoredResponse(201, toJson(body));
        });
    }

    /**
     * 修改隔板数量：版本 +1。OPEN 托盘须保证在托装载仍满足隔离规则，否则 422；
     * 已装运托盘允许修改但不追溯影响装运快照。
     */
    public StoredResponse updateDividers(String palletKey, DividerUpdateRequest req) {
        String fingerprint = IdempotentExecutor.fingerprint("dividers", palletKey,
                String.valueOf(req.dividerCount()));
        return idempotent.execute(CMD_UPDATE_DIVIDERS, req.commandKey(), fingerprint, () -> {
            PalletRepository.PalletRow pallet = palletRepo.findPalletForUpdate(palletKey)
                    .orElseThrow(() -> ApiException.notFound("托盘不存在: " + palletKey));
            // 托盘行锁后重查命令快照：并发同键请求在锁等待期间可能已由对方提交
            var logged = idempotent.replayIfLogged(CMD_UPDATE_DIVIDERS, req.commandKey(),
                    fingerprint);
            if (logged.isPresent()) {
                return logged.get();
            }
            if (PalletStatus.OPEN.name().equals(pallet.status())) {
                int required = requiredDividers(palletRepo.findCurrentLoads(palletKey));
                if (req.dividerCount() < required) {
                    throw ApiException.unprocessable("隔板数量不足: 实际 " + req.dividerCount()
                            + "，要求 " + required + "，差额 " + (required - req.dividerCount())
                            + "（在托装载所需）");
                }
            }
            int newVersion = pallet.dividerVersion() + 1;
            palletRepo.updateDividers(palletKey, req.dividerCount(), newVersion);
            PalletResponse body = new PalletResponse(palletKey, req.dividerCount(), newVersion,
                    PalletStatus.valueOf(pallet.status()), Instant.parse(pallet.createdAt()));
            return new StoredResponse(201, toJson(body));
        });
    }

    /**
     * 装载：一次校验全部批次未召回、未放行、未被其他托盘占用及完整最终隔离关系，
     * 任一失败整批 422/404 且不写入任何托盘关系；成功则同一事务写入装载与占用。
     */
    public StoredResponse load(String palletKey, LoadRequest req) {
        List<LoadRequest.LoadItem> items = req.items();
        List<String> keys = items.stream().map(LoadRequest.LoadItem::batchKey).toList();
        if (new HashSet<>(keys).size() != keys.size()) {
            throw ApiException.unprocessable("装载请求内 batchKey 重复");
        }
        List<String> parts = new ArrayList<>();
        parts.add("load");
        parts.add(palletKey);
        for (LoadRequest.LoadItem item : items) {
            parts.add(item.batchKey());
            parts.add(item.category().trim());
            parts.add(String.valueOf(item.compartmentNo()));
        }
        String fingerprint = IdempotentExecutor.fingerprint(parts.toArray(new String[0]));
        return idempotent.execute(CMD_LOAD, req.commandKey(), fingerprint, () -> {
            PalletRepository.PalletRow pallet = palletRepo.findPalletForUpdate(palletKey)
                    .orElseThrow(() -> ApiException.notFound("托盘不存在: " + palletKey));
            // 托盘行锁后重查命令快照：并发同键请求在锁等待期间可能已由对方提交
            var logged = idempotent.replayIfLogged(CMD_LOAD, req.commandKey(), fingerprint);
            if (logged.isPresent()) {
                return logged.get();
            }
            if (!PalletStatus.OPEN.name().equals(pallet.status())) {
                throw ApiException.conflict("托盘状态 " + pallet.status() + " 不允许装载，仅 OPEN 可装载");
            }
            // 请求内分隔位-类别一致性：同一分隔位不得出现两个类别
            Map<Integer, String> requestCompartments = new HashMap<>();
            for (LoadRequest.LoadItem item : items) {
                String category = item.category().trim();
                String existing = requestCompartments.putIfAbsent(item.compartmentNo(), category);
                if (existing != null && !existing.equals(category)) {
                    throw ApiException.unprocessable("分隔位 " + item.compartmentNo()
                            + " 在请求内被多个类别占用: " + existing + " / " + category);
                }
            }
            // 与在托装载的类别冲突：新批次分隔位已被不同类别占用则冲突
            List<PalletRepository.LoadRow> current = palletRepo.findCurrentLoads(palletKey);
            Map<Integer, String> compartmentCategory = new HashMap<>();
            for (PalletRepository.LoadRow row : current) {
                compartmentCategory.put(row.compartmentNo(), row.category());
            }
            for (LoadRequest.LoadItem item : items) {
                String category = item.category().trim();
                String existing = compartmentCategory.get(item.compartmentNo());
                if (existing != null && !existing.equals(category)) {
                    throw ApiException.unprocessable("分隔位 " + item.compartmentNo()
                            + " 已装载类别 " + existing + "，与批次 " + item.batchKey()
                            + " 类别 " + category + " 冲突");
                }
                compartmentCategory.putIfAbsent(item.compartmentNo(), category);
            }
            // 隔板充足性：可用分隔位 = 隔板数 + 1，须覆盖最大分隔位编号与最终类别数
            assertDividersSufficient(pallet.dividerCount(), current, items);
            // 批次状态校验：按业务键升序加行锁，与检验/批准/召回按提交顺序串行
            List<String> sortedKeys = new ArrayList<>(new HashSet<>(keys));
            Collections.sort(sortedKeys);
            Map<String, BatchRepository.BatchRow> batches = new HashMap<>();
            for (String key : sortedKeys) {
                BatchRepository.BatchRow batch = batchRepo.findBatchForUpdate(key)
                        .orElseThrow(() -> ApiException.notFound("批次不存在: " + key));
                batches.put(key, batch);
            }
            for (LoadRequest.LoadItem item : items) {
                String status = batches.get(item.batchKey()).status();
                if (!LOADABLE_STATUSES.contains(status)) {
                    throw ApiException.unprocessable("批次 " + item.batchKey() + " 状态 " + status
                            + " 不允许装载，须未召回且未放行");
                }
                assertNoRecalledAncestor(item.batchKey());
                palletRepo.findOccupancy(item.batchKey()).ifPresent(o -> {
                    throw ApiException.unprocessable("批次 " + item.batchKey()
                            + " 已被托盘 " + o.palletKey() + " 占用");
                });
            }
            String now = now();
            List<PalletLoadEntry> entries = new ArrayList<>(items.size());
            for (int i = 0; i < items.size(); i++) {
                LoadRequest.LoadItem item = items.get(i);
                String category = item.category().trim();
                long loadId = palletRepo.insertLoad(new PalletRepository.LoadRow(0L, palletKey,
                        item.batchKey(), category, item.compartmentNo(), i + 1,
                        PalletLoadStatus.LOADED.name(), false, null, req.commandKey(), now, null));
                palletRepo.insertOccupancy(new PalletRepository.OccupancyRow(item.batchKey(),
                        palletKey, loadId, now));
                entries.add(new PalletLoadEntry(item.batchKey(), category, item.compartmentNo(),
                        i + 1, PalletLoadStatus.LOADED, false, null, Instant.parse(now), null));
            }
            LoadResponse body = new LoadResponse(palletKey, pallet.dividerVersion(), entries,
                    Instant.parse(now));
            return new StoredResponse(201, toJson(body));
        });
    }

    /**
     * 解除装载：仅托盘未装运（OPEN）允许；释放分隔位与批次占用，装载历史保留不删除。
     */
    public StoredResponse unload(String palletKey, UnloadRequest req) {
        List<String> keys = req.batchKeys();
        if (new HashSet<>(keys).size() != keys.size()) {
            throw ApiException.unprocessable("解除请求内 batchKey 重复");
        }
        List<String> parts = new ArrayList<>();
        parts.add("unload");
        parts.add(palletKey);
        parts.addAll(keys);
        String fingerprint = IdempotentExecutor.fingerprint(parts.toArray(new String[0]));
        return idempotent.execute(CMD_UNLOAD, req.commandKey(), fingerprint, () -> {
            PalletRepository.PalletRow pallet = palletRepo.findPalletForUpdate(palletKey)
                    .orElseThrow(() -> ApiException.notFound("托盘不存在: " + palletKey));
            // 托盘行锁后重查命令快照：并发同键请求在锁等待期间可能已由对方提交
            var logged = idempotent.replayIfLogged(CMD_UNLOAD, req.commandKey(), fingerprint);
            if (logged.isPresent()) {
                return logged.get();
            }
            if (!PalletStatus.OPEN.name().equals(pallet.status())) {
                throw ApiException.conflict("托盘状态 " + pallet.status()
                        + " 不允许解除装载，装载记录保留");
            }
            String now = now();
            List<PalletLoadEntry> released = new ArrayList<>(keys.size());
            for (String key : keys) {
                PalletRepository.LoadRow load = palletRepo.findCurrentLoad(palletKey, key)
                        .orElse(null);
                if (load == null) {
                    if (batchRepo.findBatch(key).isEmpty()) {
                        throw ApiException.notFound("批次不存在: " + key);
                    }
                    throw ApiException.conflict("批次 " + key + " 未装载于托盘 " + palletKey);
                }
                palletRepo.markUnloaded(load.id(), now);
                palletRepo.deleteOccupancy(key);
                released.add(new PalletLoadEntry(key, load.category(), load.compartmentNo(),
                        load.seq(), PalletLoadStatus.UNLOADED, load.pendingReview(),
                        parseInstant(load.reviewMarkedAt()), Instant.parse(load.loadedAt()),
                        Instant.parse(now)));
            }
            UnloadResponse body = new UnloadResponse(palletKey, released, Instant.parse(now));
            return new StoredResponse(201, toJson(body));
        });
    }

    /**
     * 装运放行：先核对整批最终状态（批次未召回未放行、无召回祖先、隔离关系满足当前隔板配置），
     * 再一次事务提交：固化托盘、批次、类别、分隔位与隔板版本快照；后续修改隔板不追溯。
     */
    public StoredResponse ship(String palletKey, ShipRequest req) {
        String fingerprint = IdempotentExecutor.fingerprint("ship", palletKey);
        return idempotent.execute(CMD_SHIP, req.commandKey(), fingerprint, () -> {
            PalletRepository.PalletRow pallet = palletRepo.findPalletForUpdate(palletKey)
                    .orElseThrow(() -> ApiException.notFound("托盘不存在: " + palletKey));
            // 托盘行锁后重查命令快照：并发同键请求在锁等待期间可能已由对方提交
            var logged = idempotent.replayIfLogged(CMD_SHIP, req.commandKey(), fingerprint);
            if (logged.isPresent()) {
                return logged.get();
            }
            if (!PalletStatus.OPEN.name().equals(pallet.status())) {
                throw ApiException.conflict("托盘状态 " + pallet.status()
                        + " 不允许装运放行，仅 OPEN 可装运");
            }
            List<PalletRepository.LoadRow> loads = palletRepo.findCurrentLoads(palletKey);
            if (loads.isEmpty()) {
                throw ApiException.unprocessable("托盘无在托批次，不能装运放行");
            }
            // 整批最终状态核对：按业务键升序加锁，与召回等命令按提交顺序裁决
            List<String> sortedKeys = loads.stream().map(PalletRepository.LoadRow::batchKey)
                    .distinct().sorted().toList();
            Map<String, BatchRepository.BatchRow> batches = new HashMap<>();
            for (String key : sortedKeys) {
                BatchRepository.BatchRow batch = batchRepo.findBatchForUpdate(key)
                        .orElseThrow(() -> ApiException.notFound("批次不存在: " + key));
                batches.put(key, batch);
            }
            for (PalletRepository.LoadRow load : loads) {
                String status = batches.get(load.batchKey()).status();
                if (!LOADABLE_STATUSES.contains(status)) {
                    throw ApiException.unprocessable("批次 " + load.batchKey() + " 状态 " + status
                            + " 不允许装运，须未召回且未放行");
                }
                assertNoRecalledAncestor(load.batchKey());
            }
            int required = requiredDividers(loads);
            if (pallet.dividerCount() < required) {
                throw ApiException.unprocessable("隔板数量不足: 实际 " + pallet.dividerCount()
                        + "，要求 " + required + "，差额 " + (required - pallet.dividerCount()));
            }
            String now = now();
            Set<String> categories = new HashSet<>();
            for (PalletRepository.LoadRow load : loads) {
                categories.add(load.category());
            }
            palletRepo.insertShipment(new PalletRepository.ShipmentRow(0L, palletKey,
                    req.commandKey(), pallet.dividerCount(), pallet.dividerVersion(),
                    loads.size(), categories.size(), now));
            palletRepo.markShipped(palletKey);
            palletRepo.updatePalletStatus(palletKey, PalletStatus.SHIPPED.name());
            List<PalletLoadEntry> entries = loads.stream()
                    .map(load -> toEntry(load, PalletLoadStatus.SHIPPED))
                    .toList();
            long openDurationMillis = Duration.between(Instant.parse(pallet.createdAt()),
                    Instant.parse(now)).toMillis();
            ShipmentResponse body = new ShipmentResponse(palletKey, pallet.dividerCount(),
                    pallet.dividerVersion(), loads.size(), categories.size(), entries,
                    Instant.parse(now), openDurationMillis);
            return new StoredResponse(201, toJson(body));
        });
    }

    /**
     * 隔离处置：已装运托盘发现类别错误时进入 DISPOSAL；装载记录保留不删除。
     */
    public StoredResponse dispose(String palletKey, DisposalRequest req) {
        String fingerprint = IdempotentExecutor.fingerprint("disposal", palletKey, req.reason());
        return idempotent.execute(CMD_DISPOSAL, req.commandKey(), fingerprint, () -> {
            PalletRepository.PalletRow pallet = palletRepo.findPalletForUpdate(palletKey)
                    .orElseThrow(() -> ApiException.notFound("托盘不存在: " + palletKey));
            // 托盘行锁后重查命令快照：并发同键请求在锁等待期间可能已由对方提交
            var logged = idempotent.replayIfLogged(CMD_DISPOSAL, req.commandKey(), fingerprint);
            if (logged.isPresent()) {
                return logged.get();
            }
            if (!PalletStatus.SHIPPED.name().equals(pallet.status())) {
                throw ApiException.conflict("托盘状态 " + pallet.status()
                        + " 不允许隔离处置，仅已装运托盘可处置");
            }
            String now = now();
            palletRepo.insertDisposal(new PalletRepository.DisposalRow(0L, palletKey,
                    req.commandKey(), req.reason(), now));
            palletRepo.updatePalletStatus(palletKey, PalletStatus.DISPOSAL.name());
            DisposalResponse body = new DisposalResponse(palletKey, PalletStatus.DISPOSAL,
                    req.reason(), Instant.parse(now));
            return new StoredResponse(201, toJson(body));
        });
    }

    /**
     * 托盘明细（只读）：概要 + 当前在托装载 + 装运快照 + 处置信息。
     */
    public PalletDetailResponse detail(String palletKey) {
        PalletRepository.PalletRow pallet = palletRepo.findPallet(palletKey)
                .orElseThrow(() -> ApiException.notFound("托盘不存在: " + palletKey));
        List<PalletLoadEntry> current = palletRepo.findCurrentLoads(palletKey).stream()
                .map(load -> toEntry(load, PalletLoadStatus.LOADED))
                .toList();
        return new PalletDetailResponse(toPalletResponse(pallet), current,
                shipmentSnapshot(palletKey), disposalInfo(palletKey));
    }

    /**
     * 托盘历史（只读）：全部装载事件按发生顺序返回；from/to 按装载时间左闭右开 [from, to) 过滤。
     */
    public PalletHistoryResponse history(String palletKey, String from, String to) {
        Instant fromInstant = parseBoundary(from, "from");
        Instant toInstant = parseBoundary(to, "to");
        PalletRepository.PalletRow pallet = palletRepo.findPallet(palletKey)
                .orElseThrow(() -> ApiException.notFound("托盘不存在: " + palletKey));
        List<PalletLoadEntry> events = palletRepo.findLoadHistory(palletKey).stream()
                .filter(load -> fromInstant == null
                        || !Instant.parse(load.loadedAt()).isBefore(fromInstant))
                .filter(load -> toInstant == null
                        || Instant.parse(load.loadedAt()).isBefore(toInstant))
                .map(load -> toEntry(load, PalletLoadStatus.valueOf(load.status())))
                .toList();
        return new PalletHistoryResponse(toPalletResponse(pallet), events,
                shipmentSnapshot(palletKey), disposalInfo(palletKey), fromInstant, toInstant);
    }

    /**
     * 托盘诊断（只读）：隔板实际值/要求值/差额、类别到分隔位映射、各状态装载数与待复核数。
     */
    public PalletDiagnosticsResponse diagnostics(String palletKey) {
        PalletRepository.PalletRow pallet = palletRepo.findPallet(palletKey)
                .orElseThrow(() -> ApiException.notFound("托盘不存在: " + palletKey));
        List<PalletRepository.LoadRow> history = palletRepo.findLoadHistory(palletKey);
        List<PalletRepository.LoadRow> current = history.stream()
                .filter(load -> PalletLoadStatus.LOADED.name().equals(load.status()))
                .toList();
        int required = requiredDividers(current);
        int actual = pallet.dividerCount();
        Map<String, List<Integer>> categoryCompartments = new TreeMap<>();
        for (PalletRepository.LoadRow load : current) {
            List<Integer> compartments = categoryCompartments.computeIfAbsent(load.category(),
                    k -> new ArrayList<>());
            if (!compartments.contains(load.compartmentNo())) {
                compartments.add(load.compartmentNo());
            }
        }
        categoryCompartments.values().forEach(Collections::sort);
        int shipped = 0;
        int unloaded = 0;
        int pendingReview = 0;
        for (PalletRepository.LoadRow load : history) {
            if (PalletLoadStatus.SHIPPED.name().equals(load.status())) {
                shipped++;
            } else if (PalletLoadStatus.UNLOADED.name().equals(load.status())) {
                unloaded++;
            } else if (load.pendingReview()) {
                pendingReview++;
            }
        }
        return new PalletDiagnosticsResponse(palletKey, PalletStatus.valueOf(pallet.status()),
                actual, required, Math.max(0, required - actual), actual >= required,
                pallet.dividerVersion(), categoryCompartments, current.size(), shipped, unloaded,
                pendingReview);
    }

    /**
     * 所需隔板数：max(最大分隔位编号, 类别数) - 1；空集合为 0。
     */
    private int requiredDividers(List<PalletRepository.LoadRow> loads) {
        if (loads.isEmpty()) {
            return 0;
        }
        int maxCompartment = 0;
        Set<String> categories = new HashSet<>();
        for (PalletRepository.LoadRow load : loads) {
            maxCompartment = Math.max(maxCompartment, load.compartmentNo());
            categories.add(load.category());
        }
        return Math.max(maxCompartment, categories.size()) - 1;
    }

    /**
     * 校验最终隔离关系所需隔板数不超过托盘配置；不足时 422 并给出实际值、要求值与差额。
     */
    private void assertDividersSufficient(int actualDividers, List<PalletRepository.LoadRow> current,
                                          List<LoadRequest.LoadItem> items) {
        int maxCompartment = 0;
        Set<String> categories = new HashSet<>();
        for (PalletRepository.LoadRow row : current) {
            maxCompartment = Math.max(maxCompartment, row.compartmentNo());
            categories.add(row.category());
        }
        for (LoadRequest.LoadItem item : items) {
            maxCompartment = Math.max(maxCompartment, item.compartmentNo());
            categories.add(item.category().trim());
        }
        int required = Math.max(maxCompartment, categories.size()) - 1;
        if (actualDividers < required) {
            throw ApiException.unprocessable("隔板数量不足: 实际 " + actualDividers
                    + "，要求 " + required + "，差额 " + (required - actualDividers));
        }
    }

    /**
     * 若任一级祖先已被召回则抛 422：召回后代批次禁止装载与装运。
     */
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
                throw ApiException.unprocessable("批次 " + batchKey + " 的祖先批次 " + current
                        + " 已召回，禁止装载与装运");
            }
        }
    }

    private PalletResponse toPalletResponse(PalletRepository.PalletRow pallet) {
        return new PalletResponse(pallet.palletKey(), pallet.dividerCount(),
                pallet.dividerVersion(), PalletStatus.valueOf(pallet.status()),
                Instant.parse(pallet.createdAt()));
    }

    private PalletLoadEntry toEntry(PalletRepository.LoadRow load, PalletLoadStatus status) {
        return new PalletLoadEntry(load.batchKey(), load.category(), load.compartmentNo(),
                load.seq(), status, load.pendingReview(), parseInstant(load.reviewMarkedAt()),
                Instant.parse(load.loadedAt()), parseInstant(load.unloadedAt()));
    }

    private ShipmentSnapshot shipmentSnapshot(String palletKey) {
        return palletRepo.findShipment(palletKey)
                .map(s -> new ShipmentSnapshot(s.dividerCount(), s.dividerVersion(), s.itemCount(),
                        s.categoryCount(), Instant.parse(s.shippedAt())))
                .orElse(null);
    }

    private DisposalInfo disposalInfo(String palletKey) {
        return palletRepo.findDisposal(palletKey)
                .map(d -> new DisposalInfo(d.reason(), Instant.parse(d.createdAt())))
                .orElse(null);
    }

    private Instant parseBoundary(String value, String name) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value.trim());
        } catch (Exception e) {
            throw ApiException.badRequest(name + " 必须为 ISO-8601 UTC instant: " + value);
        }
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
}
