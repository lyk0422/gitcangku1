package com.example.starter.plan.service;

import com.example.starter.plan.model.ChainBreak;
import com.example.starter.plan.model.DayPlan;
import com.example.starter.plan.model.RollingStock;
import com.example.starter.plan.repo.IdempotencyRepository;
import com.example.starter.plan.repo.PlanRepository;
import com.example.starter.plan.repo.RollingStockRepository;
import com.example.starter.plan.service.ChainValidator.ChainSegment;
import com.example.starter.plan.web.ApiException;
import com.example.starter.plan.web.dto.ChainBreakView;
import com.example.starter.plan.web.dto.ChainLinkView;
import com.example.starter.plan.web.dto.ChainSegmentView;
import com.example.starter.plan.web.dto.CreateRollingStockRequest;
import com.example.starter.plan.web.dto.RollingStockResponse;
import com.example.starter.plan.web.dto.StockChainResponse;
import com.example.starter.plan.web.dto.UpdateTurnaroundRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 车底登记、最小周转参数修改与交路链查询。
 *
 * <p>周转参数修改经全局发布锁串行化并携带 expectedVersion 乐观校验（冲突 409）；
 * 修改前以新参数重校验该车底全部已发布相邻段，任一违规则整次 422 列出违规段、不做部分生效。
 * 写操作按 (操作类型, requestKey) 幂等，仅缓存成功结果。
 */
@Service
public class RollingStockService {

    private static final String OP_STOCK_CREATE = "STOCK_CREATE";
    private static final String OP_TURNAROUND = "TURNAROUND";

    private final RollingStockRepository stockRepo;
    private final PlanRepository planRepo;
    private final IdempotencyRepository idemRepo;
    private final ChainValidator chainValidator;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate tx;

    public RollingStockService(RollingStockRepository stockRepo, PlanRepository planRepo,
                               IdempotencyRepository idemRepo, ChainValidator chainValidator,
                               ObjectMapper objectMapper, PlatformTransactionManager txManager) {
        this.stockRepo = stockRepo;
        this.planRepo = planRepo;
        this.idemRepo = idemRepo;
        this.chainValidator = chainValidator;
        this.objectMapper = objectMapper;
        this.tx = new TransactionTemplate(txManager);
    }

    /**
     * 登记车底（初始版本 1）。stockKey 已存在返回 409。
     */
    public RollingStockResponse createStock(CreateRollingStockRequest req) {
        String hash = sha256(OP_STOCK_CREATE + '\n' + req.stockKey() + '\n'
                + req.minTurnaroundMinutes());
        Optional<RollingStockResponse> replay = replayIfPresent(OP_STOCK_CREATE, req.requestKey(), hash);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> {
                if (stockRepo.findByKey(req.stockKey()).isPresent()) {
                    throw conflict("STOCK_KEY_EXISTS", "stockKey 已存在: " + req.stockKey());
                }
                long now = System.currentTimeMillis();
                stockRepo.insertStock(req.stockKey(), req.minTurnaroundMinutes(), now);
                RollingStockResponse response = new RollingStockResponse(req.stockKey(),
                        req.minTurnaroundMinutes(), 1);
                idemRepo.insert(OP_STOCK_CREATE, req.requestKey(), hash, toJson(response), now);
                return response;
            });
        } catch (DuplicateKeyException e) {
            return replayIfPresent(OP_STOCK_CREATE, req.requestKey(), hash)
                    .orElseThrow(() -> conflict("STOCK_KEY_EXISTS", "stockKey 已存在"));
        }
    }

    /**
     * 修改最小周转分钟数：全局发布锁内校验 expectedVersion（冲突 409），
     * 并以新参数重校验该车底全部运营日的已发布相邻段，任一违规整次 422 不生效。
     */
    public RollingStockResponse updateTurnaround(String stockKey, UpdateTurnaroundRequest req) {
        String hash = sha256(OP_TURNAROUND + '\n' + stockKey + '\n' + req.expectedVersion()
                + '\n' + req.minTurnaroundMinutes());
        Optional<RollingStockResponse> replay = replayIfPresent(OP_TURNAROUND, req.requestKey(), hash);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> {
                planRepo.acquirePublishLock();
                RollingStock stock = stockRepo.findByKeyForUpdate(stockKey)
                        .orElseThrow(() -> notFound(stockKey));
                if (req.expectedVersion() != stock.version()) {
                    throw conflict("VERSION_CONFLICT",
                            "expectedVersion=" + req.expectedVersion()
                                    + " 与车底当前版本 " + stock.version() + " 不一致");
                }
                List<Map<String, Object>> violations =
                        revalidateAllChains(stockKey, req.minTurnaroundMinutes());
                if (!violations.isEmpty()) {
                    throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                            "TURNAROUND_REVALIDATION_FAILED",
                            "新周转参数下存在不满足的已发布相邻段，本次修改未生效", violations);
                }
                long now = System.currentTimeMillis();
                stockRepo.updateTurnaround(stock.id(), req.minTurnaroundMinutes(),
                        stock.version() + 1, now);
                // 全部链在新参数下连续，清除该车底所有待重排标记
                planRepo.clearPendingReplan(stockKey, null, now);
                RollingStockResponse response = new RollingStockResponse(stockKey,
                        req.minTurnaroundMinutes(), stock.version() + 1);
                idemRepo.insert(OP_TURNAROUND, req.requestKey(), hash, toJson(response), now);
                return response;
            });
        } catch (DuplicateKeyException e) {
            return replayIfPresent(OP_TURNAROUND, req.requestKey(), hash)
                    .orElseThrow(() -> conflict("STOCK_KEY_EXISTS", "车底并发登记冲突"));
        }
    }

    /**
     * 查询指定车底在指定运营日的交路链明细（已发布段按始发时刻升序，含逐段衔接评估）。
     */
    public StockChainResponse getChain(String stockKey, LocalDate opDate) {
        RollingStock stock = stockRepo.findByKey(stockKey)
                .orElseThrow(() -> notFound(stockKey));
        List<ChainSegment> segments = chainValidator.loadPublishedSegments(stockKey, opDate);
        Map<Long, DayPlan> plansById = new LinkedHashMap<>();
        planRepo.findPublishedByStock(stockKey, opDate).forEach(p -> plansById.put(p.id(), p));
        List<ChainSegmentView> views = new ArrayList<>(segments.size());
        for (int i = 0; i < segments.size(); i++) {
            ChainSegment seg = segments.get(i);
            DayPlan plan = plansById.get(seg.planId());
            ChainLinkView link = null;
            if (i + 1 < segments.size()) {
                ChainSegment next = segments.get(i + 1);
                long actualGap = Duration.between(seg.arrivalUtc(), next.departureUtc()).toMinutes();
                boolean stationConnected = seg.destStation().equals(next.originStation());
                link = new ChainLinkView(next.scheduleKey(), stationConnected, actualGap,
                        stock.minTurnaroundMinutes(),
                        stationConnected && actualGap >= stock.minTurnaroundMinutes());
            }
            views.add(new ChainSegmentView(seg.scheduleKey(), plan.status().name(),
                    plan.chainState().name(), seg.originStation(), seg.destStation(),
                    seg.departureUtc(), seg.arrivalUtc(), link));
        }
        return new StockChainResponse(stockKey, opDate, stock.minTurnaroundMinutes(), views);
    }

    /**
     * 查询指定车底的全部不可变断链记录（按创建顺序）。
     */
    public List<ChainBreakView> getBreaks(String stockKey) {
        if (stockRepo.findByKey(stockKey).isEmpty()) {
            throw notFound(stockKey);
        }
        return stockRepo.findBreaksByStock(stockKey).stream()
                .map(this::toView)
                .toList();
    }

    // ---------- 内部实现 ----------

    /**
     * 以给定周转参数重校验该车底全部运营日的已发布交路链，返回全部违规明细。
     */
    private List<Map<String, Object>> revalidateAllChains(String stockKey,
                                                          int minTurnaroundMinutes) {
        Map<LocalDate, List<DayPlan>> byDate = new LinkedHashMap<>();
        planRepo.findPublishedByStockAllDates(stockKey)
                .forEach(p -> byDate.computeIfAbsent(p.opDate(), d -> new ArrayList<>()).add(p));
        List<Map<String, Object>> violations = new ArrayList<>();
        byDate.forEach((opDate, plans) -> {
            List<ChainSegment> segments = chainValidator.loadPublishedSegments(stockKey, opDate);
            List<Map<String, Object>> dayViolations =
                    chainValidator.validate(segments, minTurnaroundMinutes);
            dayViolations.forEach(v -> v.put("opDate", opDate.toString()));
            violations.addAll(dayViolations);
        });
        return violations;
    }

    private ChainBreakView toView(ChainBreak b) {
        return new ChainBreakView(b.id(), b.stockKey(), b.opDate(), b.cancelledScheduleKey(),
                b.prevScheduleKey(), b.nextScheduleKey(), b.createdAt());
    }

    /**
     * 幂等重放：存在记录且参数一致返回首次结果；参数不一致抛 409。
     */
    private Optional<RollingStockResponse> replayIfPresent(String opType, String requestKey,
                                                           String hash) {
        return idemRepo.find(opType, requestKey).map(record -> {
            if (!record.requestHash().equals(hash)) {
                throw conflict("IDEMPOTENT_KEY_REUSED",
                        "requestKey 已用于其他参数: " + requestKey);
            }
            return fromJson(record.responseJson());
        });
    }

    private String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hashed.length * 2);
            for (byte b : hashed) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private String toJson(Object response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (Exception e) {
            throw new IllegalStateException("响应序列化失败", e);
        }
    }

    private RollingStockResponse fromJson(String json) {
        try {
            return objectMapper.readValue(json, RollingStockResponse.class);
        } catch (Exception e) {
            throw new IllegalStateException("幂等响应反序列化失败", e);
        }
    }

    private ApiException notFound(String stockKey) {
        return new ApiException(HttpStatus.NOT_FOUND, "STOCK_NOT_FOUND",
                "车底不存在: " + stockKey);
    }

    private ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }
}
