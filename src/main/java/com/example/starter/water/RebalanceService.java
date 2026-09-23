package com.example.starter.water;

import com.example.starter.water.WaterRepository.ApplicabilityRow;
import com.example.starter.water.WaterRepository.BlockQuotaRow;
import com.example.starter.water.WaterRepository.CellInit;
import com.example.starter.water.WaterRepository.DetailInit;
import com.example.starter.water.WaterRepository.MatrixSnapshotRow;
import com.example.starter.water.WaterRepository.RebalanceDetailRow;
import com.example.starter.water.WaterRepository.RebalanceOrderRow;
import com.example.starter.water.WaterRepository.SnapshotInit;
import com.example.starter.water.WaterRepository.SourceConfig;
import com.example.starter.water.WaterRepository.SourceRow;
import com.example.starter.water.WaterRepository.WindowRow;
import com.example.starter.water.WaterRepository.WriteoffRow;
import com.example.starter.water.dto.Dtos.BlockCellResponse;
import com.example.starter.water.dto.Dtos.BlockQuotaInput;
import com.example.starter.water.dto.Dtos.BlockResponse;
import com.example.starter.water.dto.Dtos.ExpectedVersionInput;
import com.example.starter.water.dto.Dtos.MatrixCellView;
import com.example.starter.water.dto.Dtos.RebalanceActivateRequest;
import com.example.starter.water.dto.Dtos.RebalanceDetailInput;
import com.example.starter.water.dto.Dtos.RebalanceDetailView;
import com.example.starter.water.dto.Dtos.RebalancePreviewRequest;
import com.example.starter.water.dto.Dtos.RebalancePreviewResponse;
import com.example.starter.water.dto.Dtos.RebalanceResponse;
import com.example.starter.water.dto.Dtos.SourceCapInput;
import com.example.starter.water.dto.Dtos.SourceResponse;
import com.example.starter.water.dto.Dtos.SourceTotalView;
import com.example.starter.water.dto.Dtos.WriteoffResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 多水源配额矩阵重平衡业务服务。
 *
 * <p>预览：把同一区块同一源目标的重复明细规范化求和，按完整矩阵一次性计算后态（允许水源间闭环），
 * 不逐条扣减、不落库。</p>
 *
 * <p>激活：单事务内先锁窗口行，再重读供给上限、适用性与全部矩阵单元格（FOR UPDATE），
 * 校验窗口开放、expectedVersion、已核销量不可搬走、目标水源适用与水源供给上限；
 * 任一不满足整单 409/422 回滚，矩阵不变。成功后一次性更新全部变动额度、逐记录增版，
 * 并冻结规范化明细、前后矩阵、上限与核销量快照。</p>
 *
 * <p>与既有批准/转让/限供/核销互斥均依赖 supply_window 行锁按事务提交顺序串行裁决，
 * 外部只能观察到完整旧态或完整新态。</p>
 */
@Service
public class RebalanceService {

    static final String WINDOW_OPEN = "OPEN";
    static final String WINDOW_CLOSED = "CLOSED";
    static final String PHASE_BEFORE = "BEFORE";
    static final String PHASE_AFTER = "AFTER";

    static final int MIN_DETAILS = 2;
    static final int MAX_DETAILS = 50;
    static final int MAX_SOURCES = 10;

    private static final Pattern NON_NEGATIVE_PATTERN = Pattern.compile("\\d{1,16}(\\.\\d{1,3})?");

    private final WaterRepository repository;
    private final TransactionTemplate tx;
    private final ObjectMapper objectMapper;

    public RebalanceService(WaterRepository repository, PlatformTransactionManager transactionManager,
                            ObjectMapper objectMapper) {
        this.repository = repository;
        this.tx = new TransactionTemplate(transactionManager);
        this.objectMapper = objectMapper;
    }

    /** 规范化明细键：区块 + 源水源 + 目标水源。 */
    record DetailKey(String blockId, String sourceSourceId, String targetSourceId)
            implements Comparable<DetailKey> {
        @Override
        public int compareTo(DetailKey other) {
            int c = blockId.compareTo(other.blockId);
            if (c != 0) {
                return c;
            }
            c = sourceSourceId.compareTo(other.sourceSourceId);
            if (c != 0) {
                return c;
            }
            return targetSourceId.compareTo(other.targetSourceId);
        }
    }

    /** 规范化后的明细：同键求和，按 DetailKey 稳定排序。 */
    public record NormalizedDetail(String blockId, String sourceSourceId, String targetSourceId,
                                   BigDecimal volume) {
    }

    /** 矩阵单元格键。 */
    record CellKey(String blockId, String sourceId) implements Comparable<CellKey> {
        @Override
        public int compareTo(CellKey other) {
            int c = blockId.compareTo(other.blockId);
            return c != 0 ? c : sourceId.compareTo(other.sourceId);
        }
    }

    /** 违例类别，决定激活时映射 409 还是 422。 */
    enum ViolationKind {
        UNKNOWN_BLOCK(false),
        SOURCE_NOT_APPLICABLE(false),
        TARGET_NOT_APPLICABLE(false),
        UNKNOWN_SOURCE(false),
        INSUFFICIENT_MOVABLE(true),
        SUPPLY_CAP_EXCEEDED(true);

        final boolean unprocessable;

        ViolationKind(boolean unprocessable) {
            this.unprocessable = unprocessable;
        }
    }

    record Violation(ViolationKind kind, String message) {
    }

    /** 完整矩阵评估结果。 */
    record Evaluation(Map<CellKey, BlockQuotaRow> beforeCells, Map<CellKey, BigDecimal> afterQuota,
                      Map<String, BigDecimal> caps, List<Violation> violations, boolean conserved,
                      Map<String, BigDecimal> totalsBefore, Map<String, BigDecimal> totalsAfter,
                      Set<CellKey> changedCells) {

        boolean valid() {
            return violations.isEmpty() && conserved;
        }
    }

    // ------------------------------------------------------------------
    // 水源 / 区块 / 核销 / 关闭
    // ------------------------------------------------------------------

    /** 配置窗口水源（1~10 个，窗口内唯一，仅可配置一次）。 */
    public List<SourceResponse> configureSources(String commandKey, long windowId,
                                                 List<SourceCapInput> sourceInputs) {
        WaterService.requireKey("commandKey", commandKey);
        if (sourceInputs == null) {
            throw ApiException.badRequest("INVALID_ARGUMENT", "sources 不能为空");
        }
        List<SourceConfig> sources = sourceInputs.stream()
                .map(input -> {
                    WaterService.requireKey("sourceId", input == null ? null : input.sourceId());
                    return new SourceConfig(input.sourceId(),
                            parseNonNegative("supplyCap", parseDecimal(input.supplyCap())));
                })
                .toList();
        validateSourceConfigs(sources);
        return runIdempotent("SOURCE_CONFIGURE", commandKey,
                "SOURCE_CONFIGURE|" + windowId + "|" + canonicalSources(sources),
                json -> readList(json, SourceResponse.class), () -> {
                    WindowRow window = repository.lockWindowById(windowId);
                    if (window == null) {
                        throw ApiException.notFound("WINDOW_NOT_FOUND", "供水窗口不存在: " + windowId);
                    }
                    if (!WINDOW_OPEN.equals(window.status())) {
                        throw ApiException.conflict("WINDOW_CLOSED", "窗口已关闭，不能配置水源");
                    }
                    if (repository.countSources(windowId) > 0) {
                        throw ApiException.conflict("SOURCES_ALREADY_CONFIGURED", "窗口水源已配置，不可修改");
                    }
                    long now = WaterService.nowNanos();
                    repository.insertSources(windowId, sources, now);
                    return repository.listSources(windowId).stream().map(this::toSourceResponse).toList();
                });
    }

    /** 登记区块：适用水源白名单 + 各适用水源初始额度（未列出按 0 建格）。 */
    public BlockResponse configureBlock(String commandKey, long windowId, String blockId,
                                        List<String> applicableSources, List<BlockQuotaInput> quotaInputs) {
        WaterService.requireKey("commandKey", commandKey);
        WaterService.requireKey("blockId", blockId);
        validateApplicableSources(applicableSources);
        if (quotaInputs == null) {
            throw ApiException.badRequest("INVALID_ARGUMENT", "quotas 不能为空");
        }
        List<CellInit> quotas = quotaInputs.stream()
                .map(input -> {
                    WaterService.requireKey("sourceId", input == null ? null : input.sourceId());
                    return new CellInit(input.sourceId(),
                            parseNonNegative("quota", parseDecimal(input.quota())));
                })
                .toList();
        return runIdempotent("BLOCK_CONFIGURE", commandKey,
                "BLOCK_CONFIGURE|" + windowId + "|" + blockId + "|"
                        + canonicalStringList(applicableSources) + "|" + canonicalQuotas(quotas),
                json -> fromJson(json, BlockResponse.class), () -> {
                    WindowRow window = repository.lockWindowById(windowId);
                    if (window == null) {
                        throw ApiException.notFound("WINDOW_NOT_FOUND", "供水窗口不存在: " + windowId);
                    }
                    if (!WINDOW_OPEN.equals(window.status())) {
                        throw ApiException.conflict("WINDOW_CLOSED", "窗口已关闭，不能登记区块");
                    }
                    List<SourceRow> sources = repository.listSources(windowId);
                    if (sources.isEmpty()) {
                        throw ApiException.conflict("SOURCES_NOT_CONFIGURED", "窗口尚未配置水源");
                    }
                    Set<String> sourceIds = sources.stream().map(SourceRow::sourceId).collect(Collectors.toSet());
                    for (String sourceId : applicableSources) {
                        if (!sourceIds.contains(sourceId)) {
                            throw ApiException.badRequest("INVALID_ARGUMENT", "水源未在窗口配置中: " + sourceId);
                        }
                    }
                    Set<String> applicable = Set.copyOf(applicableSources);
                    for (CellInit quota : quotas) {
                        if (!applicable.contains(quota.sourceId())) {
                            throw ApiException.badRequest("INVALID_ARGUMENT",
                                    "额度水源必须在区块适用水源内: " + quota.sourceId());
                        }
                    }
                    if (repository.existsBlock(windowId, blockId)) {
                        throw ApiException.conflict("BLOCK_EXISTS", "区块已登记: " + blockId);
                    }
                    long now = WaterService.nowNanos();
                    repository.insertApplicability(windowId, blockId,
                            applicableSources.stream().sorted().toList(), now);
                    Map<String, BigDecimal> quotaBySource = new TreeMap<>();
                    for (String sourceId : applicableSources) {
                        quotaBySource.put(sourceId, BigDecimal.ZERO);
                    }
                    for (CellInit quota : quotas) {
                        quotaBySource.put(quota.sourceId(),
                                quotaBySource.get(quota.sourceId()).add(quota.quota()));
                    }
                    List<CellInit> cells = quotaBySource.entrySet().stream()
                            .map(e -> new CellInit(e.getKey(), e.getValue())).toList();
                    repository.insertBlockQuotas(windowId, blockId, cells, now);
                    return toBlockResponse(windowId, blockId,
                            applicableSources.stream().sorted().toList(),
                            repository.listBlockQuotasOfBlock(windowId, blockId));
                });
    }

    /** 用水核销：累加指定单元格已核销量，核销后不得超过该格额度。 */
    public WriteoffResponse writeoff(String commandKey, String writeoffKey, long windowId, String blockId,
                                     String sourceId, String volume, String actor) {
        WaterService.requireKey("commandKey", commandKey);
        WaterService.requireKey("writeoffKey", writeoffKey);
        WaterService.requireKey("blockId", blockId);
        WaterService.requireKey("sourceId", sourceId);
        WaterService.requireKey("X-Actor-Id", actor);
        BigDecimal qty = WaterService.parseAmount("volume", volume);
        return runIdempotent("WRITEOFF", commandKey,
                "WRITEOFF|" + writeoffKey + "|" + windowId + "|" + blockId + "|" + sourceId + "|"
                        + WaterService.fmt(qty) + "|" + actor,
                json -> fromJson(json, WriteoffResponse.class), () -> {
                    if (repository.findWriteoffByKey(writeoffKey) != null) {
                        throw ApiException.conflict("WRITEOFF_KEY_REUSED", "writeoffKey 已被使用: " + writeoffKey);
                    }
                    WindowRow window = repository.lockWindowById(windowId);
                    if (window == null) {
                        throw ApiException.notFound("WINDOW_NOT_FOUND", "供水窗口不存在: " + windowId);
                    }
                    if (!WINDOW_OPEN.equals(window.status())) {
                        throw ApiException.conflict("WINDOW_CLOSED", "窗口已关闭，不能核销");
                    }
                    BlockQuotaRow cell = lockCell(windowId, blockId, sourceId);
                    if (cell == null) {
                        throw ApiException.conflict("CELL_NOT_FOUND",
                                "区块在该水源下没有额度单元: " + blockId + "/" + sourceId);
                    }
                    BigDecimal consumedAfter = cell.consumed().add(qty);
                    if (consumedAfter.compareTo(cell.quota()) > 0) {
                        throw ApiException.quotaExceeded("核销后已核销量 " + WaterService.fmt(consumedAfter)
                                + " 超过该格额度 " + WaterService.fmt(cell.quota()));
                    }
                    long now = WaterService.nowNanos();
                    int updated = repository.addConsumed(cell.id(), qty, cell.version(), now);
                    if (updated != 1) {
                        throw ApiException.conflict("VERSION_CONFLICT", "单元格版本已变化，请重试");
                    }
                    try {
                        repository.insertWriteoff(writeoffKey, windowId, blockId, sourceId, qty, actor, now);
                    } catch (DuplicateKeyException e) {
                        throw ApiException.conflict("WRITEOFF_KEY_REUSED", "writeoffKey 已被使用: " + writeoffKey);
                    }
                    return new WriteoffResponse(writeoffKey, windowId, blockId, sourceId,
                            WaterService.fmt(qty), WaterService.fmt(consumedAfter), cell.version() + 1,
                            WaterService.toIso(now));
                });
    }

    /** 关闭窗口；关闭后重平衡整单拒绝。 */
    public void closeWindow(String commandKey, long windowId) {
        WaterService.requireKey("commandKey", commandKey);
        runIdempotent("WINDOW_CLOSE", commandKey, "WINDOW_CLOSE|" + windowId, json -> null, () -> {
            WindowRow window = repository.lockWindowById(windowId);
            if (window == null) {
                throw ApiException.notFound("WINDOW_NOT_FOUND", "供水窗口不存在: " + windowId);
            }
            if (WINDOW_CLOSED.equals(window.status())) {
                throw ApiException.conflict("WINDOW_ALREADY_CLOSED", "窗口已关闭");
            }
            repository.closeWindow(windowId, WaterService.nowNanos());
            return null;
        });
    }

    /** 查询窗口水源。 */
    public List<SourceResponse> getSources(long windowId) {
        requireWindow(windowId);
        return repository.listSources(windowId).stream().map(this::toSourceResponse).toList();
    }

    /** 查询窗口全部区块矩阵，按区块、水源排序。 */
    public List<BlockResponse> getBlocks(long windowId) {
        requireWindow(windowId);
        List<BlockQuotaRow> cells = repository.listBlockQuotas(windowId);
        Map<String, List<BlockQuotaRow>> byBlock = cells.stream()
                .collect(Collectors.groupingBy(BlockQuotaRow::blockId, TreeMap::new, Collectors.toList()));
        List<BlockResponse> blocks = new ArrayList<>();
        for (Map.Entry<String, List<BlockQuotaRow>> entry : byBlock.entrySet()) {
            List<String> applicable = repository.listApplicableSources(windowId, entry.getKey());
            blocks.add(toBlockResponse(windowId, entry.getKey(), applicable, entry.getValue()));
        }
        return blocks;
    }

    // ------------------------------------------------------------------
    // 预览
    // ------------------------------------------------------------------

    /** 预览：规范化明细并按完整矩阵计算后态，不落库、不改矩阵。 */
    public RebalancePreviewResponse preview(RebalancePreviewRequest request) {
        if (request == null || request.windowId() == null) {
            throw ApiException.badRequest("INVALID_ARGUMENT", "windowId 不能为空");
        }
        long windowId = request.windowId();
        WindowRow window = repository.findWindowById(windowId);
        if (window == null) {
            throw ApiException.notFound("WINDOW_NOT_FOUND", "供水窗口不存在: " + windowId);
        }
        List<NormalizedDetail> normalized = normalizeDetails(request.details());
        List<SourceRow> sources = repository.listSources(windowId);
        Map<String, List<String>> applicability = loadApplicability(windowId);
        List<BlockQuotaRow> cells = repository.listBlockQuotas(windowId);
        Evaluation evaluation = evaluate(sources, applicability, cells, normalized);

        List<MatrixCellView> beforeMatrix = matrixViews(cells, evaluation.caps(), false, evaluation.afterQuota());
        List<MatrixCellView> afterMatrix = matrixViews(cells, evaluation.caps(), true, evaluation.afterQuota());
        List<SourceTotalView> totals = sourceTotalViews(sources, evaluation);
        List<String> violationMessages = evaluation.violations().stream().map(Violation::message).toList();
        return new RebalancePreviewResponse(windowId, toDetailViews(normalized), beforeMatrix, afterMatrix,
                totals, evaluation.conserved(), evaluation.valid(), violationMessages);
    }

    // ------------------------------------------------------------------
    // 激活
    // ------------------------------------------------------------------

    /**
     * 激活重平衡单。同 requestId 同参（规范化后等价）重放首次快照；异参 409；失败不占键；
     * rebalanceKey 全局唯一。成功返回冻结证据。
     */
    public RebalanceResponse activate(RebalanceActivateRequest request) {
        if (request == null) {
            throw ApiException.badRequest("INVALID_ARGUMENT", "请求体不能为空");
        }
        WaterService.requireKey("requestId", request.requestId());
        WaterService.requireKey("rebalanceKey", request.rebalanceKey());
        if (request.windowId() == null) {
            throw ApiException.badRequest("INVALID_ARGUMENT", "windowId 不能为空");
        }
        long windowId = request.windowId();
        List<NormalizedDetail> normalized = normalizeDetails(request.details());
        Map<CellKey, Long> expectedVersions = validateExpectedVersions(request.expectedVersions());
        String normalizedParams = canonicalActivateParams(request.rebalanceKey(), windowId, normalized);

        try {
            return tx.execute(status -> doActivate(request.requestId(), request.rebalanceKey(), windowId,
                    normalized, expectedVersions, normalizedParams));
        } catch (DuplicateKeyException e) {
            // 并发复用 requestId 或 rebalanceKey：读取已提交首单，按重放/409 裁决
            RebalanceOrderRow existing = repository.findRebalanceByRequestId(request.requestId());
            if (existing != null) {
                return replayOrConflict(existing, request.rebalanceKey(), windowId, normalizedParams);
            }
            RebalanceOrderRow byKey = repository.findRebalanceByKey(request.rebalanceKey());
            if (byKey != null) {
                throw ApiException.conflict("REBALANCE_KEY_REUSED",
                        "rebalanceKey 已被使用: " + request.rebalanceKey());
            }
            throw ApiException.conflict("REBALANCE_CONFLICT", "重平衡并发冲突，请重试");
        }
    }

    private RebalanceResponse doActivate(String requestId, String rebalanceKey, long windowId,
                                         List<NormalizedDetail> normalized,
                                         Map<CellKey, Long> expectedVersions, String normalizedParams) {
        // 1. 幂等重放优先：同 requestId 已成功提交，直接回放首次快照（不再校验当前窗口状态）
        RebalanceOrderRow existing = repository.findRebalanceByRequestId(requestId);
        if (existing != null) {
            return replayOrConflict(existing, rebalanceKey, windowId, normalizedParams);
        }
        // 2. 锁窗口行，与批准/转让/限供/核销/另一重平衡按提交顺序串行
        WindowRow window = repository.lockWindowById(windowId);
        if (window == null) {
            throw ApiException.notFound("WINDOW_NOT_FOUND", "供水窗口不存在: " + windowId);
        }
        if (WINDOW_CLOSED.equals(window.status())) {
            throw ApiException.conflict("WINDOW_CLOSED", "窗口已关闭，不能重平衡");
        }
        // 3. 事务内重读上限、适用性、全部矩阵单元格并加行锁（稳定排序避免死锁）
        List<SourceRow> sources = repository.listSources(windowId);
        Map<String, List<String>> applicability = loadApplicability(windowId);
        List<BlockQuotaRow> cells = repository.lockBlockQuotas(windowId);
        Map<CellKey, BlockQuotaRow> cellIndex = indexByKey(cells);

        // 4. expectedVersion 校验（版本变化整单 409）
        for (NormalizedDetail detail : normalized) {
            requireVersion(cellIndex, new CellKey(detail.blockId(), detail.sourceSourceId()),
                    expectedVersions, windowId);
            requireVersion(cellIndex, new CellKey(detail.blockId(), detail.targetSourceId()),
                    expectedVersions, windowId);
        }

        // 5. 完整后态评估：适用性/余额/上限任一违例整单失败，矩阵不变
        Evaluation evaluation = evaluate(sources, applicability, cells, normalized);
        if (!evaluation.valid()) {
            throw mapViolation(evaluation.violations().get(0));
        }
        if (repository.findRebalanceByKey(rebalanceKey) != null) {
            throw ApiException.conflict("REBALANCE_KEY_REUSED", "rebalanceKey 已被使用: " + rebalanceKey);
        }

        // 6. 一次性更新全部变动额度并逐记录增版
        long now = WaterService.nowNanos();
        Map<CellKey, Long> afterVersions = new TreeMap<>(Comparator
                .comparing(CellKey::blockId).thenComparing(CellKey::sourceId));
        for (BlockQuotaRow cell : cells) {
            afterVersions.put(new CellKey(cell.blockId(), cell.sourceId()), cell.version());
        }
        for (CellKey changed : evaluation.changedCells()) {
            BlockQuotaRow cell = cellIndex.get(changed);
            BigDecimal afterQuota = evaluation.afterQuota().get(changed);
            int updated = repository.updateCellQuota(cell.id(), afterQuota, cell.version(), now);
            if (updated != 1) {
                throw ApiException.conflict("VERSION_CONFLICT", "单元格版本已变化，请重试");
            }
            afterVersions.put(changed, cell.version() + 1);
        }

        // 7. 冻结重平衡单、规范化明细、前后矩阵 + 上限 + 核销量快照
        long orderId = repository.insertRebalanceOrder(rebalanceKey, requestId, windowId,
                normalizedParams, normalized.size(), now);
        List<DetailInit> detailInits = new ArrayList<>();
        for (int i = 0; i < normalized.size(); i++) {
            NormalizedDetail d = normalized.get(i);
            detailInits.add(new DetailInit(d.blockId(), d.sourceSourceId(), d.targetSourceId(),
                    d.volume(), i + 1));
        }
        repository.insertRebalanceDetails(orderId, detailInits);
        repository.insertMatrixSnapshots(orderId,
                snapshotInits(PHASE_BEFORE, cells, indexByKey(cells), evaluation.caps(),
                        evaluation.afterQuota(), afterVersions, false));
        repository.insertMatrixSnapshots(orderId,
                snapshotInits(PHASE_AFTER, cells, indexByKey(cells), evaluation.caps(),
                        evaluation.afterQuota(), afterVersions, true));
        return toRebalanceResponse(repository.findRebalanceById(orderId));
    }

    /** 查询重平衡只读证据，按 sourceId、区块稳定排序。 */
    public RebalanceResponse getEvidence(String rebalanceKey) {
        WaterService.requireKey("rebalanceKey", rebalanceKey);
        RebalanceOrderRow order = repository.findRebalanceByKey(rebalanceKey);
        if (order == null) {
            throw ApiException.notFound("REBALANCE_NOT_FOUND", "重平衡单不存在: " + rebalanceKey);
        }
        return toRebalanceResponse(order);
    }

    // ------------------------------------------------------------------
    // 规范化与矩阵计算
    // ------------------------------------------------------------------

    /**
     * 规范化输入明细：校验数量（2~50）、字段与正数三位小数、源/目标不同；
     * 同区块同源目标的重复明细求和，并按区块、源、目标稳定排序。
     */
    List<NormalizedDetail> normalizeDetails(List<RebalanceDetailInput> inputs) {
        if (inputs == null || inputs.size() < MIN_DETAILS || inputs.size() > MAX_DETAILS) {
            throw ApiException.badRequest("INVALID_ARGUMENT",
                    "重平衡明细数量必须在 " + MIN_DETAILS + "~" + MAX_DETAILS + " 条之间");
        }
        TreeMap<DetailKey, BigDecimal> summed = new TreeMap<>();
        for (RebalanceDetailInput input : inputs) {
            if (input == null) {
                throw ApiException.badRequest("INVALID_ARGUMENT", "明细不能为空");
            }
            WaterService.requireKey("blockId", input.blockId());
            WaterService.requireKey("sourceSourceId", input.sourceSourceId());
            WaterService.requireKey("targetSourceId", input.targetSourceId());
            if (input.sourceSourceId().equals(input.targetSourceId())) {
                throw ApiException.badRequest("INVALID_ARGUMENT", "源水源与目标水源不能相同");
            }
            BigDecimal volume = WaterService.parseAmount("volume", input.volume());
            DetailKey key = new DetailKey(input.blockId(), input.sourceSourceId(), input.targetSourceId());
            summed.merge(key, volume, BigDecimal::add);
        }
        return summed.entrySet().stream()
                .map(e -> new NormalizedDetail(e.getKey().blockId(), e.getKey().sourceSourceId(),
                        e.getKey().targetSourceId(), e.getValue()))
                .toList();
    }

    /**
     * 按完整矩阵一次性评估后态：先汇总每个单元格的净增量（允许跨水源闭环），再统一计算后态，
     * 不产生逐条扣减的临时中间态。
     */
    Evaluation evaluate(List<SourceRow> sources, Map<String, List<String>> applicability,
                        List<BlockQuotaRow> cells, List<NormalizedDetail> normalized) {
        Map<String, BigDecimal> caps = new TreeMap<>();
        for (SourceRow source : sources) {
            caps.put(source.sourceId(), source.supplyCap());
        }
        Map<CellKey, BlockQuotaRow> beforeCells = indexByKey(cells);
        Map<String, List<BlockQuotaRow>> cellsByBlock = cells.stream()
                .collect(Collectors.groupingBy(BlockQuotaRow::blockId));

        Map<CellKey, BigDecimal> deltas = new LinkedHashMap<>();
        List<Violation> violations = new ArrayList<>();
        for (NormalizedDetail d : normalized) {
            CellKey sourceKey = new CellKey(d.blockId(), d.sourceSourceId());
            CellKey targetKey = new CellKey(d.blockId(), d.targetSourceId());
            if (!caps.containsKey(d.sourceSourceId()) || !caps.containsKey(d.targetSourceId())) {
                String unknown = caps.containsKey(d.sourceSourceId()) ? d.targetSourceId() : d.sourceSourceId();
                violations.add(new Violation(ViolationKind.UNKNOWN_SOURCE, "窗口未配置水源: " + unknown));
                continue;
            }
            if (!cellsByBlock.containsKey(d.blockId())) {
                violations.add(new Violation(ViolationKind.UNKNOWN_BLOCK, "区块未登记: " + d.blockId()));
                continue;
            }
            List<String> whitelist = applicability.getOrDefault(d.blockId(), List.of());
            if (!whitelist.contains(d.sourceSourceId())) {
                violations.add(new Violation(ViolationKind.SOURCE_NOT_APPLICABLE,
                        "源水源不适用于该区块: " + d.blockId() + "/" + d.sourceSourceId()));
            }
            if (!whitelist.contains(d.targetSourceId())) {
                violations.add(new Violation(ViolationKind.TARGET_NOT_APPLICABLE,
                        "目标水源不适用于该区块: " + d.blockId() + "/" + d.targetSourceId()));
            }
            if (whitelist.contains(d.sourceSourceId()) && whitelist.contains(d.targetSourceId())) {
                deltas.merge(sourceKey, d.volume().negate(), BigDecimal::add);
                deltas.merge(targetKey, d.volume(), BigDecimal::add);
            }
        }

        Map<CellKey, BigDecimal> afterQuota = new TreeMap<>(Comparator
                .comparing(CellKey::blockId).thenComparing(CellKey::sourceId));
        for (BlockQuotaRow cell : cells) {
            afterQuota.put(new CellKey(cell.blockId(), cell.sourceId()), cell.quota());
        }
        for (Map.Entry<CellKey, BigDecimal> delta : deltas.entrySet()) {
            afterQuota.merge(delta.getKey(), delta.getValue(), BigDecimal::add);
        }

        // 已核销用水量不能被搬走：后态额度不得低于已核销量
        for (CellKey key : deltas.keySet()) {
            BlockQuotaRow cell = beforeCells.get(key);
            BigDecimal after = afterQuota.get(key);
            if (cell != null && after.compareTo(cell.consumed()) < 0) {
                violations.add(new Violation(ViolationKind.INSUFFICIENT_MOVABLE,
                        "区块 " + key.blockId() + " 水源 " + key.sourceId()
                                + " 可搬水量不足：后态额度 " + WaterService.fmt(after)
                                + " 低于已核销量 " + WaterService.fmt(cell.consumed())));
            }
        }

        // 区块总额度守恒校验（构造上必然成立，仍独立核对后态合计）
        boolean conserved = true;
        for (Map.Entry<String, List<BlockQuotaRow>> entry : cellsByBlock.entrySet()) {
            BigDecimal before = entry.getValue().stream().map(BlockQuotaRow::quota)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal after = entry.getValue().stream()
                    .map(c -> afterQuota.get(new CellKey(c.blockId(), c.sourceId())))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (after.compareTo(before) != 0) {
                conserved = false;
            }
        }

        // 水源总分配前后合计与供给上限
        Map<String, BigDecimal> totalsBefore = totalsBySource(caps, beforeCells, null);
        Map<String, BigDecimal> totalsAfter = totalsBySource(caps, beforeCells, afterQuota);
        for (Map.Entry<String, BigDecimal> total : totalsAfter.entrySet()) {
            BigDecimal cap = caps.get(total.getKey());
            if (total.getValue().compareTo(cap) > 0) {
                violations.add(new Violation(ViolationKind.SUPPLY_CAP_EXCEEDED,
                        "水源 " + total.getKey() + " 重平衡后总分配 " + WaterService.fmt(total.getValue())
                                + " 超过供给上限 " + WaterService.fmt(cap)));
            }
        }

        Set<CellKey> changedCells = deltas.keySet().stream()
                .filter(k -> {
                    BlockQuotaRow c = beforeCells.get(k);
                    return c != null && afterQuota.get(k).compareTo(c.quota()) != 0;
                })
                .collect(Collectors.toCollection(java.util.TreeSet::new));
        return new Evaluation(beforeCells, afterQuota, caps, violations, conserved,
                totalsBefore, totalsAfter, changedCells);
    }

    private ApiException mapViolation(Violation violation) {
        return switch (violation.kind()) {
            case INSUFFICIENT_MOVABLE ->
                    new ApiException(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,
                            "INSUFFICIENT_MOVABLE_QUOTA", violation.message());
            case SUPPLY_CAP_EXCEEDED ->
                    new ApiException(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,
                            "SUPPLY_CAP_EXCEEDED", violation.message());
            case UNKNOWN_BLOCK -> ApiException.conflict("BLOCK_NOT_FOUND", violation.message());
            case UNKNOWN_SOURCE -> ApiException.conflict("SOURCE_NOT_CONFIGURED", violation.message());
            case SOURCE_NOT_APPLICABLE ->
                    ApiException.conflict("SOURCE_NOT_APPLICABLE", violation.message());
            case TARGET_NOT_APPLICABLE ->
                    ApiException.conflict("APPLICABILITY_CHANGED", violation.message());
        };
    }

    // ------------------------------------------------------------------
    // 视图与快照
    // ------------------------------------------------------------------

    private RebalanceResponse replayOrConflict(RebalanceOrderRow existing, String rebalanceKey, long windowId,
                                               String normalizedParams) {
        if (!existing.rebalanceKey().equals(rebalanceKey) || existing.windowId() != windowId
                || !existing.normalizedParams().equals(normalizedParams)) {
            throw ApiException.conflict("REQUEST_ID_REUSED",
                    "相同 requestId 但参数不同，拒绝重放: " + existing.requestId());
        }
        return toRebalanceResponse(existing);
    }

    private RebalanceResponse toRebalanceResponse(RebalanceOrderRow order) {
        List<RebalanceDetailRow> details = repository.listRebalanceDetails(order.id());
        List<MatrixSnapshotRow> before = repository.listMatrixSnapshots(order.id(), PHASE_BEFORE);
        List<MatrixSnapshotRow> after = repository.listMatrixSnapshots(order.id(), PHASE_AFTER);
        return new RebalanceResponse(order.rebalanceKey(), order.requestId(), order.windowId(),
                order.status(), details.stream().map(d -> new RebalanceDetailView(d.blockId(),
                        d.sourceSourceId(), d.targetSourceId(), WaterService.fmt(d.volume()))).toList(),
                before.stream().map(this::toMatrixView).toList(),
                after.stream().map(this::toMatrixView).toList(),
                WaterService.toIso(order.createdNanos()));
    }

    private MatrixCellView toMatrixView(MatrixSnapshotRow row) {
        return new MatrixCellView(row.blockId(), row.sourceId(), WaterService.fmt(row.quota()),
                WaterService.fmt(row.consumed()), row.version(), WaterService.fmt(row.supplyCap()));
    }

    private List<RebalanceDetailView> toDetailViews(List<NormalizedDetail> normalized) {
        return normalized.stream().map(d -> new RebalanceDetailView(d.blockId(), d.sourceSourceId(),
                d.targetSourceId(), WaterService.fmt(d.volume()))).toList();
    }

    private List<MatrixCellView> matrixViews(List<BlockQuotaRow> orderedCells,
                                             Map<String, BigDecimal> caps, boolean after,
                                             Map<CellKey, BigDecimal> afterQuota) {
        return orderedCells.stream()
                .sorted(Comparator.comparing(BlockQuotaRow::sourceId).thenComparing(BlockQuotaRow::blockId))
                .map(cell -> {
                    CellKey key = new CellKey(cell.blockId(), cell.sourceId());
                    BigDecimal quota = after ? afterQuota.getOrDefault(key, cell.quota()) : cell.quota();
                    return new MatrixCellView(cell.blockId(), cell.sourceId(), WaterService.fmt(quota),
                            WaterService.fmt(cell.consumed()), cell.version(),
                            WaterService.fmt(caps.getOrDefault(cell.sourceId(), BigDecimal.ZERO)));
                })
                .toList();
    }

    private List<SnapshotInit> snapshotInits(String phase, List<BlockQuotaRow> cells,
                                             Map<CellKey, BlockQuotaRow> index,
                                             Map<String, BigDecimal> caps,
                                             Map<CellKey, BigDecimal> afterQuota,
                                             Map<CellKey, Long> afterVersions, boolean after) {
        List<BlockQuotaRow> ordered = cells.stream()
                .sorted(Comparator.comparing(BlockQuotaRow::sourceId).thenComparing(BlockQuotaRow::blockId))
                .toList();
        List<SnapshotInit> inits = new ArrayList<>();
        int seq = 1;
        for (BlockQuotaRow cell : ordered) {
            CellKey key = new CellKey(cell.blockId(), cell.sourceId());
            BigDecimal quota = after ? afterQuota.get(key) : cell.quota();
            long version = after ? afterVersions.getOrDefault(key, cell.version()) : cell.version();
            inits.add(new SnapshotInit(phase, cell.blockId(), cell.sourceId(), quota, cell.consumed(),
                    version, caps.get(cell.sourceId()), seq++));
        }
        return inits;
    }

    private List<SourceTotalView> sourceTotalViews(List<SourceRow> sources, Evaluation evaluation) {
        return sources.stream()
                .sorted(Comparator.comparing(SourceRow::sourceId))
                .map(s -> new SourceTotalView(s.sourceId(), WaterService.fmt(s.supplyCap()),
                        WaterService.fmt(evaluation.totalsBefore().getOrDefault(s.sourceId(), BigDecimal.ZERO)),
                        WaterService.fmt(evaluation.totalsAfter().getOrDefault(s.sourceId(), BigDecimal.ZERO))))
                .toList();
    }

    private Map<String, BigDecimal> totalsBySource(Map<String, BigDecimal> caps,
                                                   Map<CellKey, BlockQuotaRow> beforeCells,
                                                   Map<CellKey, BigDecimal> afterQuota) {
        Map<String, BigDecimal> totals = new TreeMap<>();
        caps.keySet().forEach(source -> totals.put(source, BigDecimal.ZERO));
        for (CellKey key : beforeCells.keySet()) {
            BigDecimal quota = afterQuota == null ? beforeCells.get(key).quota()
                    : afterQuota.getOrDefault(key, beforeCells.get(key).quota());
            totals.merge(key.sourceId(), quota, BigDecimal::add);
        }
        return totals;
    }

    private Map<CellKey, BlockQuotaRow> indexByKey(List<BlockQuotaRow> cells) {
        Map<CellKey, BlockQuotaRow> index = new LinkedHashMap<>();
        for (BlockQuotaRow cell : cells) {
            index.put(new CellKey(cell.blockId(), cell.sourceId()), cell);
        }
        return index;
    }

    private Map<String, List<String>> loadApplicability(long windowId) {
        // block_quota 每个适用水源一行，适用集合以白名单表为准
        List<BlockQuotaRow> cells = repository.listBlockQuotas(windowId);
        Map<String, List<String>> byBlock = new TreeMap<>();
        for (BlockQuotaRow cell : cells) {
            byBlock.computeIfAbsent(cell.blockId(), k -> new ArrayList<>());
        }
        for (ApplicabilityRow row : repository.listApplicabilityRows(windowId)) {
            byBlock.computeIfAbsent(row.blockId(), k -> new ArrayList<>()).add(row.sourceId());
        }
        return byBlock;
    }

    private void requireVersion(Map<CellKey, BlockQuotaRow> index, CellKey key,
                                Map<CellKey, Long> expectedVersions, long windowId) {
        BlockQuotaRow cell = index.get(key);
        if (cell == null) {
            throw ApiException.conflict("CELL_NOT_FOUND",
                    "区块在该水源下没有额度单元: " + key.blockId() + "/" + key.sourceId());
        }
        Long expected = expectedVersions.get(key);
        if (expected == null) {
            throw ApiException.badRequest("INVALID_ARGUMENT",
                    "缺少涉及额度的 expectedVersion: " + key.blockId() + "/" + key.sourceId());
        }
        if (expected != cell.version()) {
            throw ApiException.conflict("VERSION_CONFLICT",
                    "额度版本已变化: " + key.blockId() + "/" + key.sourceId()
                            + " expected=" + expected + " actual=" + cell.version());
        }
    }

    private BlockQuotaRow lockCell(long windowId, String blockId, String sourceId) {
        return repository.lockBlockQuotas(windowId).stream()
                .filter(c -> c.blockId().equals(blockId) && c.sourceId().equals(sourceId))
                .findFirst().orElse(null);
    }

    private void requireWindow(long windowId) {
        if (repository.findWindowById(windowId) == null) {
            throw ApiException.notFound("WINDOW_NOT_FOUND", "供水窗口不存在: " + windowId);
        }
    }

    private SourceResponse toSourceResponse(SourceRow row) {
        return new SourceResponse(row.windowId(), row.sourceId(), WaterService.fmt(row.supplyCap()));
    }

    private BlockResponse toBlockResponse(long windowId, String blockId, List<String> applicableSources,
                                          List<BlockQuotaRow> cells) {
        List<BlockCellResponse> cellViews = cells.stream()
                .sorted(Comparator.comparing(BlockQuotaRow::sourceId))
                .map(c -> new BlockCellResponse(c.sourceId(), WaterService.fmt(c.quota()),
                        WaterService.fmt(c.consumed()), c.version()))
                .toList();
        return new BlockResponse(windowId, blockId,
                applicableSources.stream().sorted().toList(), cellViews);
    }

    // ------------------------------------------------------------------
    // 参数校验与规范化串
    // ------------------------------------------------------------------

    private void validateSourceConfigs(List<SourceConfig> sources) {
        if (sources == null || sources.isEmpty() || sources.size() > MAX_SOURCES) {
            throw ApiException.badRequest("INVALID_ARGUMENT",
                    "每个窗口必须配置 1~" + MAX_SOURCES + " 个水源");
        }
        for (SourceConfig source : sources) {
            if (source == null) {
                throw ApiException.badRequest("INVALID_ARGUMENT", "水源不能为空");
            }
            WaterService.requireKey("sourceId", source.sourceId());
            parseNonNegative("supplyCap", source.supplyCap());
        }
        long distinct = sources.stream().map(SourceConfig::sourceId).distinct().count();
        if (distinct != sources.size()) {
            throw ApiException.badRequest("INVALID_ARGUMENT", "sourceId 在窗口内必须唯一");
        }
    }

    private void validateApplicableSources(List<String> applicableSources) {
        if (applicableSources == null || applicableSources.isEmpty()) {
            throw ApiException.badRequest("INVALID_ARGUMENT", "区块至少需要一个适用水源");
        }
        for (String sourceId : applicableSources) {
            WaterService.requireKey("sourceId", sourceId);
        }
        if (applicableSources.stream().distinct().count() != applicableSources.size()) {
            throw ApiException.badRequest("INVALID_ARGUMENT", "适用水源不能重复");
        }
    }

    /** 解析非负水量（供给上限、初始额度允许为 0）。 */
    static BigDecimal parseNonNegative(String field, BigDecimal value) {
        if (value == null || value.signum() < 0 || value.stripTrailingZeros().scale() > 3) {
            throw ApiException.badRequest("INVALID_ARGUMENT",
                    field + " 必须为非负、最多 3 位小数的十进制数");
        }
        return value;
    }

    /** 解析十进制字符串（允许 0 与正数，最多 3 位小数），用于非负字段。 */
    private BigDecimal parseDecimal(String value) {
        if (value == null || !NON_NEGATIVE_PATTERN.matcher(value.trim()).matches()) {
            throw ApiException.badRequest("INVALID_ARGUMENT",
                    "必须为非负、最多 3 位小数的十进制字符串: " + value);
        }
        return new BigDecimal(value.trim());
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("响应序列化失败", e);
        }
    }

    private <T> T fromJson(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("响应反序列化失败", e);
        }
    }

    private <T> List<T> readList(String json, Class<T> elementType) {
        try {
            return objectMapper.readValue(json,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, elementType));
        } catch (Exception e) {
            throw new IllegalStateException("响应反序列化失败", e);
        }
    }

    private String canonicalSources(List<SourceConfig> sources) {
        return sources.stream()
                .sorted(Comparator.comparing(SourceConfig::sourceId))
                .map(s -> s.sourceId() + ":" + WaterService.fmt(s.supplyCap()))
                .collect(Collectors.joining(";"));
    }

    private String canonicalStringList(List<String> values) {
        return values.stream().sorted().collect(Collectors.joining(","));
    }

    private String canonicalQuotas(List<CellInit> quotas) {
        return quotas.stream()
                .sorted(Comparator.comparing(CellInit::sourceId))
                .map(q -> q.sourceId() + ":" + WaterService.fmt(q.quota()))
                .collect(Collectors.joining(";"));
    }

    /** 重平衡幂等指纹：rebalanceKey + windowId + 规范化明细（求和、去顺序、去尾零）。 */
    private String canonicalActivateParams(String rebalanceKey, long windowId,
                                           List<NormalizedDetail> normalized) {
        String details = normalized.stream()
                .map(d -> String.join(",", d.blockId(), d.sourceSourceId(), d.targetSourceId(),
                        WaterService.fmt(d.volume())))
                .collect(Collectors.joining(";"));
        return "REBALANCE|" + rebalanceKey + "|" + windowId + "|" + details;
    }

    private Map<CellKey, Long> validateExpectedVersions(List<ExpectedVersionInput> inputs) {
        if (inputs == null || inputs.isEmpty()) {
            throw ApiException.badRequest("INVALID_ARGUMENT", "expectedVersions 不能为空");
        }
        Map<CellKey, Long> versions = new LinkedHashMap<>();
        for (ExpectedVersionInput input : inputs) {
            if (input == null) {
                throw ApiException.badRequest("INVALID_ARGUMENT", "expectedVersion 不能为空");
            }
            WaterService.requireKey("blockId", input.blockId());
            WaterService.requireKey("sourceId", input.sourceId());
            if (input.expectedVersion() == null || input.expectedVersion() < 0) {
                throw ApiException.badRequest("INVALID_ARGUMENT", "expectedVersion 必须为非负整数");
            }
            CellKey key = new CellKey(input.blockId(), input.sourceId());
            if (versions.containsKey(key) && !versions.get(key).equals(input.expectedVersion())) {
                throw ApiException.badRequest("INVALID_ARGUMENT",
                        "同一额度的 expectedVersion 不一致: " + input.blockId() + "/" + input.sourceId());
            }
            versions.put(key, input.expectedVersion());
        }
        return versions;
    }

    /**
     * 与 {@link WaterService} 同构的命令幂等模板：事务内先占位命令行，再执行业务并写回响应；
     * 同键同参重放首次结果，同键异参 409。
     */
    private <T> T runIdempotent(String operation, String commandKey, String params,
                                java.util.function.Function<String, T> replayReader,
                                java.util.function.Supplier<T> business) {
        try {
            return tx.execute(status -> {
                WaterRepository.CommandRow existing = repository.findCommand(commandKey);
                if (existing != null) {
                    return replay(existing, operation, params, replayReader);
                }
                repository.insertCommand(commandKey, operation, params, WaterService.nowNanos());
                T result = business.get();
                repository.updateCommandResponse(commandKey, toJson(result));
                return result;
            });
        } catch (DuplicateKeyException e) {
            WaterRepository.CommandRow committed = repository.findCommand(commandKey);
            if (committed == null || committed.response() == null) {
                throw ApiException.conflict("COMMAND_CONFLICT", "相同 commandKey 的命令正在处理，请重试");
            }
            return replay(committed, operation, params, replayReader);
        }
    }

    private <T> T replay(WaterRepository.CommandRow existing, String operation, String params,
                         java.util.function.Function<String, T> replayReader) {
        if (!existing.operation().equals(operation) || !existing.params().equals(params)) {
            throw ApiException.conflict("COMMAND_KEY_REUSED", "相同 commandKey 但参数不同，拒绝重放");
        }
        return replayReader.apply(existing.response());
    }
}
