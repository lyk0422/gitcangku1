package com.example.starter.batch;

import com.example.starter.batch.dto.CreateSerialBatchRequest;
import com.example.starter.batch.dto.SerialBatchResponse;
import com.example.starter.batch.dto.SerialIntervalSpec;
import com.example.starter.batch.dto.SerialIntervalView;
import com.example.starter.batch.dto.SerialQueryResponse;
import com.example.starter.batch.dto.SerialSplitRequest;
import com.example.starter.batch.dto.SerialSplitResponse;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * 序号区间批次服务：新类型根批登记、受控拆批、序号归属查询。
 *
 * <p>幂等：requestId 全局唯一，指纹覆盖 操作+资源+全参数+期望版本（带字段名拼接，
 * NUL 分隔避免碰撞；区间先规范化因此换序同参；数量按精确整数比较因此十进制同值同参，
 * 文本原样）。仅成功命令写入 request_log，失败不占键；业务与去重同事务原子提交；
 * 并发同键插入冲突回滚重试，不向外暴露唯一键错误。
 *
 * <p>并发：根批登记按产品号在 JVM 内加锁串行化根域相交判定（单 JVM 部署前提）；
 * 受控拆批沿用父批行锁 SELECT ... FOR UPDATE。
 */
@Service
public class SerialBatchService {

    private static final String OP_CREATE = "CREATE_SERIAL_BATCH";
    private static final String OP_SPLIT = "SPLIT_SERIAL";

    /**
     * 指纹内列表元素分隔符（SOH 控制字符）：业务参数不可能包含该字符，避免列表拼接碰撞。
     */
    private static final String LIST_SEP = ""; //"";

    private static final int IDEMPOTENCY_MAX_ATTEMPTS = 3;

    private final BatchRepository repo;
    private final SerialBatchRepository serialRepo;
    private final BatchService batchService;
    private final TransactionTemplate tx;

    /**
     * 产品号级互斥锁：串行化同产品根批登记的根域相交检查与写入。
     */
    private final ConcurrentMap<String, ReentrantLock> productLocks = new ConcurrentHashMap<>();

    public SerialBatchService(BatchRepository repo,
                              SerialBatchRepository serialRepo,
                              BatchService batchService,
                              PlatformTransactionManager transactionManager) {
        this.repo = repo;
        this.serialRepo = serialRepo;
        this.batchService = batchService;
        this.tx = new TransactionTemplate(transactionManager);
    }

    /**
     * 登记新类型根批：绑定 1～10 个闭区间（相邻先合并），区间总长度必须等于批次整数数量；
     * 同产品号下与任一现有根域（含已拆分、已召回根批）相交返回 409。
     */
    public StoredResponse createSerialBatch(CreateSerialBatchRequest req) {
        List<String> items = req.requiredTests().stream().map(String::trim).toList();
        if (new HashSet<>(items).size() != items.size()) {
            throw ApiException.badRequest("requiredTests 存在重复检验项");
        }
        long quantity = toExactLong(req.quantity(), "quantity");
        List<SerialIntervals.Interval> intervals = canonicalize(req.serialIntervals());
        long total = SerialIntervals.totalLength(intervals);
        if (total != quantity) {
            throw ApiException.unprocessable("区间总长度 " + total + " 与批次数量 " + quantity
                    + " 不一致");
        }
        String fingerprint = batchService.fingerprint(
                "op", OP_CREATE,
                "resource", req.batchKey(),
                "operator", req.operator(),
                "batchKey", req.batchKey(),
                "productCode", req.productCode(),
                "batchNo", req.batchNo(),
                "producedAt", req.producedAt().toString(),
                "requiredTests", String.join(LIST_SEP, items),
                "quantity", Long.toString(quantity),
                "serialIntervals", SerialIntervals.canonicalString(intervals));
        ReentrantLock productLock = productLocks.computeIfAbsent(req.productCode(),
                k -> new ReentrantLock());
        productLock.lock();
        try {
            return executeRequest(req.requestId(), fingerprint, () -> {
                repo.findBatch(req.batchKey()).ifPresent(b -> {
                    throw ApiException.conflict("batchKey 已存在: " + req.batchKey());
                });
                for (SerialIntervals.Interval interval : intervals) {
                    if (!serialRepo.findRootDomainIntersections(req.productCode(),
                            interval.start(), interval.end()).isEmpty()) {
                        throw ApiException.conflict("序号区间 [" + interval.start() + ", "
                                + interval.end() + "] 与产品 " + req.productCode()
                                + " 现有根域相交（含已拆分、已召回根批）");
                    }
                }
                String now = batchService.now();
                repo.insertBatch(new BatchRepository.BatchRow(0L, req.batchKey(),
                        req.productCode(), req.batchNo(), req.producedAt().toString(),
                        BatchStatus.QUARANTINED.name(), now));
                for (int i = 0; i < items.size(); i++) {
                    repo.insertRequiredTest(req.batchKey(), items.get(i), i + 1);
                }
                serialRepo.insertSerialBatch(req.batchKey(), req.productCode(), quantity,
                        true, 1, now);
                for (SerialIntervals.Interval interval : intervals) {
                    serialRepo.insertInterval(req.batchKey(), req.productCode(),
                            interval.start(), interval.end(), now);
                }
                SerialBatchResponse body = new SerialBatchResponse(req.batchKey(),
                        req.productCode(), req.batchNo(), req.producedAt(),
                        BatchStatus.QUARANTINED, quantity, toViews(intervals), items, 1,
                        Instant.parse(now));
                return new StoredResponse(201, batchService.toJson(body));
            });
        } finally {
            productLock.unlock();
        }
    }

    /**
     * 受控拆批：仅当前可用的 RELEASED 序号区间批次可拆成 2～5 个全新子批。
     * 全部子批区间的并集必须精确等于父批当前区间且两两不交，否则整次 422，
     * 不建任何子批或血缘；子批数量按区间总长度生成；父批置 SPLIT、版本 +1，
     * 父批区间冻结为历史。创建全部子批、血缘与父批状态在同一事务提交。
     */
    public StoredResponse splitSerial(String parentKey, SerialSplitRequest req) {
        List<SerialSplitRequest.ChildSpec> children = req.children();
        List<String> childKeys = children.stream().map(SerialSplitRequest.ChildSpec::batchKey)
                .toList();
        if (new HashSet<>(childKeys).size() != childKeys.size()) {
            throw ApiException.conflict("子批 batchKey 在请求内重复");
        }
        List<List<SerialIntervals.Interval>> childIntervals = new ArrayList<>(children.size());
        for (SerialSplitRequest.ChildSpec child : children) {
            childIntervals.add(canonicalize(child.serialIntervals()));
        }
        List<String> parts = new ArrayList<>();
        parts.add("op");
        parts.add(OP_SPLIT);
        parts.add("resource");
        parts.add(parentKey);
        parts.add("operator");
        parts.add(req.operator());
        parts.add("expectedVersion");
        parts.add(String.valueOf(req.expectedVersion()));
        for (int i = 0; i < children.size(); i++) {
            parts.add("child[" + i + "].batchKey");
            parts.add(children.get(i).batchKey());
            parts.add("child[" + i + "].batchNo");
            parts.add(children.get(i).batchNo());
            parts.add("child[" + i + "].serialIntervals");
            parts.add(SerialIntervals.canonicalString(childIntervals.get(i)));
        }
        String fingerprint = batchService.fingerprint(parts.toArray(new String[0]));
        return executeRequest(req.requestId(), fingerprint, () -> {
            BatchRepository.BatchRow parent = repo.findBatchForUpdate(parentKey)
                    .orElseThrow(() -> ApiException.notFound("批次不存在: " + parentKey));
            // 父批行锁后重查请求快照：并发同键请求在锁等待期间可能已由对方提交
            Optional<StoredResponse> logged = loggedRequest(req.requestId(), fingerprint);
            if (logged.isPresent()) {
                return logged.get();
            }
            SerialBatchRepository.SerialBatchRow serialParent = serialRepo
                    .findSerialBatch(parentKey)
                    .orElseThrow(() -> ApiException.unprocessable(
                            "普通批次不能使用受控拆批入口，请使用按数量拆批入口: " + parentKey));
            batchService.assertNoRecalledAncestor(parentKey);
            if (!BatchStatus.RELEASED.name().equals(parent.status())) {
                throw ApiException.conflict("批次状态 " + parent.status()
                        + " 不允许拆分，仅当前可用的 RELEASED 批次可拆分");
            }
            if (serialParent.version() != req.expectedVersion()) {
                throw ApiException.conflict("期望版本 " + req.expectedVersion() + " 与父批当前版本 "
                        + serialParent.version() + " 不一致");
            }
            for (String childKey : childKeys) {
                if (repo.findBatch(childKey).isPresent()) {
                    throw ApiException.conflict("子批 batchKey 已存在: " + childKey);
                }
            }
            assertUnionEqualsParent(parentKey, childIntervals);

            String now = batchService.now();
            List<String> required = repo.findRequiredTests(parentKey);
            List<SerialSplitResponse.SplitChild> childBodies = new ArrayList<>(children.size());
            for (int i = 0; i < children.size(); i++) {
                SerialSplitRequest.ChildSpec spec = children.get(i);
                List<SerialIntervals.Interval> intervals = childIntervals.get(i);
                long childQuantity = SerialIntervals.totalLength(intervals);
                repo.insertBatch(new BatchRepository.BatchRow(0L, spec.batchKey(),
                        parent.productCode(), spec.batchNo(), parent.producedAt(),
                        BatchStatus.QUARANTINED.name(), now));
                for (int j = 0; j < required.size(); j++) {
                    repo.insertRequiredTest(spec.batchKey(), required.get(j), j + 1);
                }
                serialRepo.insertSerialBatch(spec.batchKey(), parent.productCode(),
                        childQuantity, false, 1, now);
                for (SerialIntervals.Interval interval : intervals) {
                    serialRepo.insertInterval(spec.batchKey(), parent.productCode(),
                            interval.start(), interval.end(), now);
                }
                repo.insertLineage(new BatchRepository.LineageRow(0L, parentKey,
                        spec.batchKey(), i + 1, now));
                childBodies.add(new SerialSplitResponse.SplitChild(spec.batchKey(),
                        spec.batchNo(), parent.productCode(), BatchStatus.QUARANTINED,
                        childQuantity, toViews(intervals), 1, Instant.parse(now)));
            }
            repo.updateStatus(parentKey, BatchStatus.SPLIT.name());
            serialRepo.incrementVersion(parentKey);
            SerialSplitResponse body = new SerialSplitResponse(parentKey, BatchStatus.SPLIT,
                    serialParent.version() + 1, childBodies, Instant.parse(now));
            return new StoredResponse(201, batchService.toJson(body));
        });
    }

    /**
     * 序号归属查询：返回该序号在当前血缘树中的叶批及召回状态（自身或任一祖先召回即视为召回）。
     */
    public SerialQueryResponse querySerial(String productCode, long serial) {
        if (serial < SerialIntervals.MIN_SERIAL || serial > SerialIntervals.MAX_SERIAL) {
            throw ApiException.badRequest("序号 " + serial + " 越界，须在 "
                    + SerialIntervals.MIN_SERIAL + "～" + SerialIntervals.MAX_SERIAL + " 之间");
        }
        List<String> candidates = serialRepo.findOwnerCandidates(productCode, serial);
        if (candidates.isEmpty()) {
            throw ApiException.notFound("序号未登记: 产品 " + productCode + " 序号 " + serial);
        }
        // 每次拆分的子批区间精确划分父批区间，因此候选批次沿血缘构成 根→叶 链，
        // 其中唯一不作为父批出现的即当前叶批
        Map<String, String> parentOf = batchService.childToParent();
        Set<String> parents = new HashSet<>(parentOf.values());
        String leaf = candidates.stream()
                .filter(key -> !parents.contains(key))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("序号归属链异常: " + serial));
        BatchRepository.BatchRow row = repo.findBatch(leaf)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + leaf));
        String recalledBy = null;
        if (BatchStatus.RECALLED.name().equals(row.status())) {
            recalledBy = leaf;
        } else {
            recalledBy = batchService.recalledAncestor(leaf, parentOf,
                    new HashSet<>(repo.findRecalledKeys())).orElse(null);
        }
        return new SerialQueryResponse(productCode, serial, leaf, row.batchNo(),
                BatchStatus.valueOf(row.status()), recalledBy != null, recalledBy);
    }

    /**
     * 校验全部子批区间的并集精确等于父批当前区间：两两重叠抛 422；
     * 相邻合并后与父批区间集合不一致（遗漏或区间外）抛 422。
     */
    private void assertUnionEqualsParent(String parentKey,
                                         List<List<SerialIntervals.Interval>> childIntervals) {
        List<SerialIntervals.Interval> parentIntervals = serialRepo.findIntervals(parentKey)
                .stream()
                .map(r -> new SerialIntervals.Interval(r.intervalStart(), r.intervalEnd()))
                .toList();
        List<SerialIntervals.Interval> union = new ArrayList<>();
        for (List<SerialIntervals.Interval> intervals : childIntervals) {
            union.addAll(intervals);
        }
        union.sort(Comparator.comparingLong(SerialIntervals.Interval::start)
                .thenComparingLong(SerialIntervals.Interval::end));
        List<SerialIntervals.Interval> merged = new ArrayList<>(union.size());
        for (SerialIntervals.Interval next : union) {
            if (merged.isEmpty()) {
                merged.add(next);
                continue;
            }
            SerialIntervals.Interval last = merged.get(merged.size() - 1);
            if (next.start() <= last.end()) {
                throw ApiException.unprocessable("子批序号区间重叠 [" + last.start() + ", "
                        + last.end() + "] 与 [" + next.start() + ", " + next.end() + "]");
            }
            if (next.start() == last.end() + 1) {
                merged.set(merged.size() - 1,
                        new SerialIntervals.Interval(last.start(), next.end()));
            } else {
                merged.add(next);
            }
        }
        if (!merged.equals(parentIntervals)) {
            throw ApiException.unprocessable("子批区间并集 " + SerialIntervals.canonicalString(merged)
                    + " 必须精确等于父批序号域 " + SerialIntervals.canonicalString(parentIntervals)
                    + "（存在遗漏或区间外序号）");
        }
    }

    /**
     * 幂等执行：同事务内先查 request_log，命中则按指纹返回快照或 409；
     * 未命中执行业务动作并写入快照。并发同键插入冲突时回滚重试，读取已提交结果。
     */
    private StoredResponse executeRequest(String requestId, String fingerprint,
                                          Supplier<StoredResponse> action) {
        for (int attempt = 0; attempt < IDEMPOTENCY_MAX_ATTEMPTS; attempt++) {
            try {
                return tx.execute(status -> {
                    Optional<StoredResponse> logged = loggedRequest(requestId, fingerprint);
                    if (logged.isPresent()) {
                        return logged.get();
                    }
                    StoredResponse response = action.get();
                    serialRepo.insertRequest(new SerialBatchRepository.RequestRow(requestId,
                            fingerprint, response.status(), response.body()), batchService.now());
                    return response;
                });
            } catch (DuplicateKeyException e) {
                // 并发同 requestId 冲突：回滚后重试，读取对方已提交的请求快照
            }
        }
        throw ApiException.conflict("请求并发冲突，请重试: " + requestId);
    }

    /**
     * 查询请求快照：命中且指纹一致返回首次响应；指纹不一致抛 409；未命中返回空。
     */
    private Optional<StoredResponse> loggedRequest(String requestId, String fingerprint) {
        Optional<SerialBatchRepository.RequestRow> existing = serialRepo.findRequest(requestId);
        if (existing.isEmpty()) {
            return Optional.empty();
        }
        SerialBatchRepository.RequestRow row = existing.get();
        if (!row.fingerprint().equals(fingerprint)) {
            throw ApiException.conflict("requestId 已以不同参数使用: " + requestId);
        }
        return Optional.of(new StoredResponse(row.responseStatus(), row.responseBody()));
    }

    private List<SerialIntervals.Interval> canonicalize(List<SerialIntervalSpec> specs) {
        List<SerialIntervals.Interval> raw = new ArrayList<>(specs.size());
        for (SerialIntervalSpec spec : specs) {
            raw.add(new SerialIntervals.Interval(spec.start(), spec.end()));
        }
        return SerialIntervals.canonicalize(raw);
    }

    private List<SerialIntervalView> toViews(List<SerialIntervals.Interval> intervals) {
        return intervals.stream()
                .map(i -> new SerialIntervalView(i.start(), i.end()))
                .toList();
    }

    private long toExactLong(BigDecimal value, String field) {
        try {
            return value.longValueExact();
        } catch (ArithmeticException e) {
            throw ApiException.unprocessable(field + " 必须为整数: " + value);
        }
    }
}
