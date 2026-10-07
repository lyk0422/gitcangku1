package com.example.starter.batch;

import com.example.starter.batch.dto.BatchIntervalsResponse;
import com.example.starter.batch.dto.IntervalSpec;
import com.example.starter.batch.dto.IntervalView;
import com.example.starter.batch.dto.RegisterSerialBatchRequest;
import com.example.starter.batch.dto.RegisterSerialBatchResponse;
import com.example.starter.batch.dto.SerialLookupResponse;
import com.example.starter.batch.dto.SerialSplitRequest;
import com.example.starter.batch.dto.SerialSplitResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

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

/**
 * 序号区间批次服务：根批登记、受控区间拆批、序号归属查询。
 *
 * <p>幂等策略：新增写请求携带非空 operator 与全局 requestId；request_log 以 requestId
 * 为全局唯一键，结构化比较操作、资源与全参数（含期望版本）指纹，同键同参重放首次完整
 * 响应，同键改参 409；仅成功请求占键，业务与去重同事务原子提交；并发唯一键冲突回滚重试，
 * 不向外暴露唯一键错误。
 *
 * <p>并发策略：根批登记按产品编码持 serial_domain_lock 行锁串行化域相交判定；
 * 拆批对父批行 SELECT ... FOR UPDATE，与检验/批准/召回按事务提交顺序裁决。
 */
@Service
public class SerialBatchService {

    private static final String OP_REGISTER = "REGISTER_SERIAL_BATCH";
    private static final String OP_SERIAL_SPLIT = "SERIAL_SPLIT";
    private static final String KIND_ROOT = "ROOT";
    private static final String KIND_SPLIT = "SPLIT";

    private static final int IDEMPOTENCY_MAX_ATTEMPTS = 3;

    private final BatchRepository batchRepo;
    private final SerialRepository serialRepo;
    private final TransactionTemplate tx;
    private final ObjectMapper objectMapper;

    public SerialBatchService(BatchRepository batchRepo,
                              SerialRepository serialRepo,
                              PlatformTransactionManager transactionManager,
                              ObjectMapper objectMapper) {
        this.batchRepo = batchRepo;
        this.serialRepo = serialRepo;
        this.tx = new TransactionTemplate(transactionManager);
        this.objectMapper = objectMapper;
    }

    /**
     * 登记序号根批：绑定 1～10 个闭区间（范围 1..1000000，无重叠、相邻合并），
     * 区间总长度必须等于整数数量；根批序号域按产品编码唯一，与现有根域（含已拆分、
     * 已召回的根）相交返回 409。初始状态 QUARANTINED。
     */
    public StoredResponse register(RegisterSerialBatchRequest req) {
        List<String> items = req.requiredTests().stream().map(String::trim).toList();
        if (new HashSet<>(items).size() != items.size()) {
            throw ApiException.badRequest("requiredTests 存在重复检验项");
        }
        List<SerialIntervals.Interval> domain = normalizeSpecs(req.intervals());
        long total = SerialIntervals.totalLength(domain);
        if (total != req.quantity().longValue()) {
            throw ApiException.unprocessable(
                    "区间总长度 " + total + " 与批次数量 " + req.quantity() + " 不一致");
        }
        String fingerprint = registerFingerprint(req, domain, items);
        return executeRequest(OP_REGISTER, req.requestId(), req.batchKey(), fingerprint, () -> {
            serialRepo.lockProductDomain(req.productCode());
            // 持域锁后重查请求快照：并发同 requestId 在锁等待期间可能已由对方提交
            var logged = loggedRequest(OP_REGISTER, req.requestId(), req.batchKey(), fingerprint);
            if (logged.isPresent()) {
                return logged.get();
            }
            batchRepo.findBatch(req.batchKey()).ifPresent(b -> {
                throw ApiException.conflict("batchKey 已存在: " + req.batchKey());
            });
            List<SerialRepository.IntervalRow> existing =
                    serialRepo.findRootIntervalsByProduct(req.productCode());
            for (SerialIntervals.Interval interval : domain) {
                for (SerialRepository.IntervalRow taken : existing) {
                    if (interval.overlaps(new SerialIntervals.Interval(
                            taken.startSerial(), taken.endSerial()))) {
                        throw ApiException.conflict("序号域与产品 " + req.productCode()
                                + " 现有根批域相交: [" + interval.start() + "," + interval.end()
                                + "] 与批次 " + taken.batchKey() + " 的 [" + taken.startSerial()
                                + "," + taken.endSerial() + "]");
                    }
                }
            }
            String now = now();
            batchRepo.insertBatch(new BatchRepository.BatchRow(0L, req.batchKey(),
                    req.productCode(), req.batchNo(), req.producedAt().toString(),
                    BatchStatus.QUARANTINED.name(), now, BatchKind.SERIAL.name(), req.quantity()));
            for (int i = 0; i < items.size(); i++) {
                batchRepo.insertRequiredTest(req.batchKey(), items.get(i), i + 1);
            }
            for (int i = 0; i < domain.size(); i++) {
                SerialIntervals.Interval interval = domain.get(i);
                serialRepo.insertInterval(req.batchKey(), req.productCode(), KIND_ROOT,
                        interval.start(), interval.end(), i + 1, now);
            }
            RegisterSerialBatchResponse body = new RegisterSerialBatchResponse(req.batchKey(),
                    req.productCode(), req.batchNo(), req.producedAt(), BatchStatus.QUARANTINED,
                    req.quantity(), toViews(domain), items, Instant.parse(now));
            return new StoredResponse(201, toJson(body));
        });
    }

    /**
     * 幂等执行：同事务内先查 request_log，命中则按结构化比较重放或 409；
     * 未命中执行业务动作并写入快照。并发唯一键冲突回滚重试，读取已提交结果。
     */
    private StoredResponse executeRequest(String operation, String requestId, String resource,
                                          String fingerprint, Supplier<StoredResponse> action) {
        for (int attempt = 0; attempt < IDEMPOTENCY_MAX_ATTEMPTS; attempt++) {
            try {
                return tx.execute(status -> {
                    var logged = loggedRequest(operation, requestId, resource, fingerprint);
                    if (logged.isPresent()) {
                        return logged.get();
                    }
                    StoredResponse response = action.get();
                    serialRepo.insertRequest(new SerialRepository.RequestLogRow(requestId,
                            operation, resource, fingerprint, response.status(), response.body()),
                            now());
                    return response;
                });
            } catch (DuplicateKeyException e) {
                // 并发同 requestId 或业务唯一键冲突：回滚后重试，读取对方已提交结果
            }
        }
        throw ApiException.conflict("请求并发冲突，请重试: " + requestId);
    }

    /**
     * 查询请求快照：命中且操作、资源、全参数指纹一致返回首次响应；任一不一致抛 409。
     */
    private Optional<StoredResponse> loggedRequest(String operation, String requestId,
                                                   String resource, String fingerprint) {
        var existing = serialRepo.findRequest(requestId);
        if (existing.isEmpty()) {
            return Optional.empty();
        }
        SerialRepository.RequestLogRow row = existing.get();
        if (!row.operation().equals(operation) || !row.resource().equals(resource)
                || !row.fingerprint().equals(fingerprint)) {
            throw ApiException.conflict("requestId 已以不同参数使用: " + requestId);
        }
        return Optional.of(new StoredResponse(row.responseStatus(), row.responseBody()));
    }

    private String registerFingerprint(RegisterSerialBatchRequest req,
                                       List<SerialIntervals.Interval> domain,
                                       List<String> items) {
        CanonicalFingerprint fp = new CanonicalFingerprint();
        fp.put("operation", OP_REGISTER)
                .put("resource", req.batchKey())
                .put("operator", req.operator())
                .put("expectedVersion", req.expectedVersion())
                .put("productCode", req.productCode())
                .put("batchNo", req.batchNo())
                .put("producedAt", req.producedAt())
                .put("quantity", req.quantity().longValue());
        for (int i = 0; i < items.size(); i++) {
            fp.put("requiredTests[" + i + "]", items.get(i));
        }
        for (int i = 0; i < domain.size(); i++) {
            fp.put("intervals[" + i + "]", intervalText(domain.get(i)));
        }
        return fp.sha256();
    }

    private List<SerialIntervals.Interval> normalizeSpecs(List<IntervalSpec> specs) {
        List<int[]> raw = new ArrayList<>(specs.size());
        for (IntervalSpec spec : specs) {
            raw.add(new int[]{spec.start(), spec.end()});
        }
        return SerialIntervals.normalize(raw);
    }

    private List<IntervalView> toViews(List<SerialIntervals.Interval> intervals) {
        List<IntervalView> views = new ArrayList<>(intervals.size());
        for (SerialIntervals.Interval interval : intervals) {
            views.add(new IntervalView(interval.start(), interval.end(), interval.length()));
        }
        return views;
    }

    private String intervalText(SerialIntervals.Interval interval) {
        return interval.start() + "-" + interval.end();
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

    /**
     * 受控序号区间拆批：仅当前可用的 RELEASED 序号批可一次拆成 2～5 个全新子批。
     * 全部子批区间的并集必须精确等于父批当前序号域且两两不交；遗漏、重叠、区间外
     * 整次 422，不建任何子批或血缘。子批数量按各自区间总长度生成，初始 QUARANTINED，
     * 继承产品编码、生产 UTC 时间与必做检验项；父批置为 SPLIT，其序号域仅作冻结历史。
     */
    public StoredResponse split(String parentKey, SerialSplitRequest req) {
        List<SerialSplitRequest.ChildSpec> children = req.children();
        List<String> childKeys = children.stream().map(SerialSplitRequest.ChildSpec::batchKey).toList();
        if (new HashSet<>(childKeys).size() != childKeys.size()) {
            throw ApiException.conflict("子批 batchKey 在请求内重复");
        }
        List<List<SerialIntervals.Interval>> childDomains = new ArrayList<>(children.size());
        for (SerialSplitRequest.ChildSpec child : children) {
            childDomains.add(normalizeSpecs(child.intervals()));
        }
        String fingerprint = splitFingerprint(parentKey, req, childDomains);
        return executeRequest(OP_SERIAL_SPLIT, req.requestId(), parentKey, fingerprint, () -> {
            BatchRepository.BatchRow parent = batchRepo.findBatchForUpdate(parentKey)
                    .orElseThrow(() -> ApiException.notFound("批次不存在: " + parentKey));
            // 父批行锁后重查请求快照：并发同 requestId 在锁等待期间可能已由对方提交
            var logged = loggedRequest(OP_SERIAL_SPLIT, req.requestId(), parentKey, fingerprint);
            if (logged.isPresent()) {
                return logged.get();
            }
            assertNoRecalledAncestor(parentKey);
            if (!BatchKind.SERIAL.name().equals(parent.batchKind())) {
                throw ApiException.conflict("普通批次无序号域，不能按序号区间拆分: " + parentKey);
            }
            if (!BatchStatus.RELEASED.name().equals(parent.status())) {
                throw ApiException.conflict("批次状态 " + parent.status()
                        + " 不允许拆分，仅当前可用的 RELEASED 批次可拆分");
            }
            List<SerialIntervals.Interval> parentDomain = serialRepo.findIntervals(parentKey)
                    .stream()
                    .map(r -> new SerialIntervals.Interval(r.startSerial(), r.endSerial()))
                    .toList();
            validateCoverage(childDomains, parentDomain);
            for (String childKey : childKeys) {
                if (batchRepo.findBatch(childKey).isPresent()) {
                    throw ApiException.conflict("子批 batchKey 已存在: " + childKey);
                }
            }
            String now = now();
            List<String> required = batchRepo.findRequiredTests(parentKey);
            List<SerialSplitResponse.SplitChild> childBodies = new ArrayList<>(children.size());
            for (int i = 0; i < children.size(); i++) {
                SerialSplitRequest.ChildSpec spec = children.get(i);
                List<SerialIntervals.Interval> childDomain = childDomains.get(i);
                int quantity = (int) SerialIntervals.totalLength(childDomain);
                batchRepo.insertBatch(new BatchRepository.BatchRow(0L, spec.batchKey(),
                        parent.productCode(), spec.batchNo(), parent.producedAt(),
                        BatchStatus.QUARANTINED.name(), now, BatchKind.SERIAL.name(), quantity));
                for (int j = 0; j < required.size(); j++) {
                    batchRepo.insertRequiredTest(spec.batchKey(), required.get(j), j + 1);
                }
                for (int j = 0; j < childDomain.size(); j++) {
                    SerialIntervals.Interval interval = childDomain.get(j);
                    serialRepo.insertInterval(spec.batchKey(), parent.productCode(), KIND_SPLIT,
                            interval.start(), interval.end(), j + 1, now);
                }
                batchRepo.insertLineage(new BatchRepository.LineageRow(0L, parentKey,
                        spec.batchKey(), i + 1, now));
                childBodies.add(new SerialSplitResponse.SplitChild(spec.batchKey(), spec.batchNo(),
                        quantity, toViews(childDomain), BatchStatus.QUARANTINED));
            }
            batchRepo.updateStatus(parentKey, BatchStatus.SPLIT.name());
            SerialSplitResponse body = new SerialSplitResponse(parentKey, BatchStatus.SPLIT,
                    childBodies, Instant.parse(now));
            return new StoredResponse(201, toJson(body));
        });
    }

    /**
     * 覆盖判定：跨子批区间两两不交（重叠 422）、全部落在父域内（区间外 422）、
     * 总长度等于父域（否则遗漏 422）。父域已规范化，互不相交且互不相邻。
     */
    private void validateCoverage(List<List<SerialIntervals.Interval>> childDomains,
                                  List<SerialIntervals.Interval> parentDomain) {
        List<SerialIntervals.Interval> all = new ArrayList<>();
        for (List<SerialIntervals.Interval> childDomain : childDomains) {
            all.addAll(childDomain);
        }
        all.sort(Comparator.comparingInt(SerialIntervals.Interval::start));
        for (int i = 1; i < all.size(); i++) {
            SerialIntervals.Interval prev = all.get(i - 1);
            SerialIntervals.Interval next = all.get(i);
            if (next.start() <= prev.end()) {
                throw ApiException.unprocessable("子批区间重叠: [" + prev.start() + ","
                        + prev.end() + "] 与 [" + next.start() + "," + next.end() + "]");
            }
        }
        for (SerialIntervals.Interval interval : all) {
            if (!SerialIntervals.containedInAny(interval, parentDomain)) {
                throw ApiException.unprocessable("子批区间超出父批序号域: [" + interval.start()
                        + "," + interval.end() + "]");
            }
        }
        long covered = SerialIntervals.totalLength(all);
        long parentTotal = SerialIntervals.totalLength(parentDomain);
        if (covered != parentTotal) {
            throw ApiException.unprocessable("子批区间遗漏父批序号: 覆盖 " + covered
                    + " 件，父域共 " + parentTotal + " 件");
        }
    }

    /**
     * 序号归属查询：返回当前持有该序号的叶批及其召回状态（自身或任一祖先召回即不可用）。
     */
    public SerialLookupResponse lookup(String productCode, int serial) {
        if (serial < SerialIntervals.MIN_SERIAL || serial > SerialIntervals.MAX_SERIAL) {
            throw ApiException.unprocessable("序号超出范围 " + SerialIntervals.MIN_SERIAL
                    + ".." + SerialIntervals.MAX_SERIAL + ": " + serial);
        }
        SerialRepository.LeafOwnerRow owner = serialRepo.findLeafOwner(productCode, serial)
                .orElseThrow(() -> ApiException.notFound(
                        "序号不存在于任何当前序号域: " + productCode + "#" + serial));
        Map<String, String> parentOf = childToParent();
        Set<String> recalled = new HashSet<>(batchRepo.findRecalledKeys());
        String recalledBy;
        if (recalled.contains(owner.batchKey())) {
            recalledBy = owner.batchKey();
        } else {
            recalledBy = recalledAncestor(owner.batchKey(), parentOf, recalled).orElse(null);
        }
        return new SerialLookupResponse(productCode, serial, owner.batchKey(),
                BatchStatus.valueOf(owner.status()), recalledBy != null, recalledBy);
    }

    /**
     * 批次序号域查询：区间在登记/拆分时落库后不改写；已拆分（存在血缘子批）父批的域
     * 仅作历史（current=false），即使父批拆分后再被召回状态变为 RECALLED 也不恢复。
     */
    public BatchIntervalsResponse intervals(String batchKey) {
        BatchRepository.BatchRow batch = batchRepo.findBatch(batchKey)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchKey));
        List<IntervalView> views = serialRepo.findIntervals(batchKey).stream()
                .map(r -> new IntervalView(r.startSerial(), r.endSerial(),
                        r.endSerial() - r.startSerial() + 1))
                .toList();
        boolean current = batchRepo.countChildren(batchKey) == 0;
        return new BatchIntervalsResponse(batchKey, batch.batchKind(), batch.quantity(),
                current, views);
    }

    private String splitFingerprint(String parentKey, SerialSplitRequest req,
                                    List<List<SerialIntervals.Interval>> childDomains) {
        CanonicalFingerprint fp = new CanonicalFingerprint();
        fp.put("operation", OP_SERIAL_SPLIT)
                .put("resource", parentKey)
                .put("operator", req.operator())
                .put("expectedVersion", req.expectedVersion());
        for (int i = 0; i < req.children().size(); i++) {
            SerialSplitRequest.ChildSpec child = req.children().get(i);
            fp.put("children[" + i + "].batchKey", child.batchKey())
                    .put("children[" + i + "].batchNo", child.batchNo());
            List<SerialIntervals.Interval> domain = childDomains.get(i);
            for (int j = 0; j < domain.size(); j++) {
                fp.put("children[" + i + "].intervals[" + j + "]", intervalText(domain.get(j)));
            }
        }
        return fp.sha256();
    }

    /**
     * 若任一级祖先已被召回则抛 422：后代批次禁止拆分。
     */
    private void assertNoRecalledAncestor(String batchKey) {
        Optional<String> recalled = recalledAncestor(batchKey, childToParent(),
                new HashSet<>(batchRepo.findRecalledKeys()));
        if (recalled.isPresent()) {
            throw ApiException.unprocessable(
                    "祖先批次 " + recalled.get() + " 已召回，禁止新增检验、批准和拆分");
        }
    }

    /**
     * 沿父链向上查找最近的被直接召回（RECALLED）祖先；批次自身召回不算祖先召回。
     */
    private Optional<String> recalledAncestor(String batchKey, Map<String, String> parentOf,
                                              Set<String> recalled) {
        String current = batchKey;
        while (parentOf.containsKey(current)) {
            current = parentOf.get(current);
            if (recalled.contains(current)) {
                return Optional.of(current);
            }
        }
        return Optional.empty();
    }

    /**
     * 全部血缘边的 子批→父批 映射；关系不可改写，只增不改。
     */
    private Map<String, String> childToParent() {
        Map<String, String> parentOf = new HashMap<>();
        for (BatchRepository.LineageRow row : batchRepo.findAllLineage()) {
            parentOf.put(row.childKey(), row.parentKey());
        }
        return parentOf;
    }
}
