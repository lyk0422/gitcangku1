package com.example.starter.service;

import com.example.starter.api.ApiException;
import com.example.starter.api.dto.BucketCapacityDto;
import com.example.starter.api.dto.CapacityConfigRequest;
import com.example.starter.api.dto.CapacityConfigResult;
import com.example.starter.api.dto.MutationResponse;
import com.example.starter.api.dto.OccupancyPlanItemDto;
import com.example.starter.api.dto.OccupancyPlanRequest;
import com.example.starter.api.dto.OccupancyPlanResult;
import com.example.starter.api.dto.PlanSlotDto;
import com.example.starter.domain.BucketSlot;
import com.example.starter.domain.Buckets;
import com.example.starter.repo.AirspaceRepository;
import com.example.starter.repo.CapacityConfigPo;
import com.example.starter.repo.CapacityConfigRepository;
import com.example.starter.repo.DedupPo;
import com.example.starter.repo.OccupancyPlanRepository;
import com.example.starter.repo.PlanSlotPo;
import com.example.starter.repo.RoutePo;
import com.example.starter.repo.RouteRepository;
import com.example.starter.repo.ReviewRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Supplier;

/**
 * 容量账本服务：管理员配置时空桶容量，运营方登记当前航线版本的穿越序列。
 * 写操作复用 request_dedup 幂等去重，失败回滚不占键。
 */
@Service
public class CapacityService {

    static final String KIND_CAPACITY_CONFIG = "CAPACITY_CONFIG";
    static final String KIND_OCCUPANCY_PLAN = "OCCUPANCY_PLAN";

    private final CapacityConfigRepository configRepo;
    private final OccupancyPlanRepository planRepo;
    private final RouteRepository routeRepo;
    private final ReviewRepository reviewRepo;
    private final AirspaceRepository airspaceRepo;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final TransactionTemplate txTemplate;

    public CapacityService(CapacityConfigRepository configRepo,
                           OccupancyPlanRepository planRepo,
                           RouteRepository routeRepo,
                           ReviewRepository reviewRepo,
                           AirspaceRepository airspaceRepo,
                           ObjectMapper objectMapper,
                           Clock clock,
                           PlatformTransactionManager transactionManager) {
        this.configRepo = configRepo;
        this.planRepo = planRepo;
        this.routeRepo = routeRepo;
        this.reviewRepo = reviewRepo;
        this.airspaceRepo = airspaceRepo;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.txTemplate = new TransactionTemplate(transactionManager);
    }

    /** 配置（新增或覆盖）时空桶容量；同键同参重放，异参 409。 */
    public MutationResponse configure(CapacityConfigRequest request) {
        return withIdempotency(request.requestId(), KIND_CAPACITY_CONFIG, canonicalHash(request), () -> {
            if (request.bucketStart() % Buckets.BUCKET_SIZE_MILLIS != 0L) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "BUCKET_NOT_ALIGNED",
                        "bucketStart 必须是 15 分钟（UTC）对齐的 epoch 毫秒");
            }
            // 持全局协调锁：与转配激活按提交顺序串行，激活不会读到半更新的容量配置
            airspaceRepo.getGlobalVersionForUpdate();
            configRepo.upsert(new CapacityConfigPo(request.cellX(), request.cellY(),
                    request.bucketStart(), request.maxFlights(), nowMillis()));
            return new MutationResponse(request.requestId(), false, new CapacityConfigResult(
                    request.cellX(), request.cellY(), request.bucketStart(), request.maxFlights()));
        });
    }

    /** 查询单个时空桶容量与当前全量占用（只读）。 */
    public BucketCapacityDto getBucket(int cellX, int cellY, long bucketStart) {
        if (bucketStart % Buckets.BUCKET_SIZE_MILLIS != 0L) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "BUCKET_NOT_ALIGNED",
                    "bucketStart 必须是 15 分钟（UTC）对齐的 epoch 毫秒");
        }
        BucketSlot bucket = new BucketSlot(cellX, cellY, bucketStart);
        CapacityConfigPo config = configRepo.find(cellX, cellY, bucketStart);
        int max = config == null ? 0 : config.maxFlights();
        int used = configRepo.countCurrentOccupancy(bucket);
        return new BucketCapacityDto(cellX, cellY, bucketStart, max, used, used);
    }

    /**
     * 登记当前航线版本穿越序列：仅当 routeVersion 等于当前版本时成功；
     * 同版本重复登记整体替换序列。序列项必须 seq 从 0 连续、桶 15 分钟对齐。
     */
    public MutationResponse registerPlan(OccupancyPlanRequest request) {
        return withIdempotency(request.requestId(), KIND_OCCUPANCY_PLAN, canonicalHash(request), () -> {
            List<PlanSlotPo> items = validateAndNormalize(request.items());
            // 锁航线行：与转配激活、航线替换按提交顺序串行，
            // 激活不会读到为旧版本半写入的序列
            RoutePo route = routeRepo.findRouteForUpdate(request.routeId());
            if (route == null) {
                throw new ApiException(HttpStatus.NOT_FOUND, "ROUTE_NOT_FOUND",
                        "航线不存在: " + request.routeId());
            }
            if (route.version() != request.routeVersion()) {
                throw new ApiException(HttpStatus.CONFLICT, "VERSION_CONFLICT",
                        "只能为当前版本登记穿越序列：submitted=" + request.routeVersion()
                                + ", current=" + route.version());
            }
            planRepo.deletePlan(request.routeId(), request.routeVersion());
            planRepo.insertPlan(request.routeId(), request.routeVersion(), items);
            return new MutationResponse(request.requestId(), false, new OccupancyPlanResult(
                    request.routeId(), request.routeVersion(), toSlotDtos(items)));
        });
    }

    /** 校验序列项：seq 从 0 连续、桶 15 分钟对齐。 */
    static List<PlanSlotPo> validateAndNormalize(List<OccupancyPlanItemDto> items) {
        List<PlanSlotPo> normalized = new ArrayList<>(items.size());
        for (int i = 0; i < items.size(); i++) {
            OccupancyPlanItemDto item = items.get(i);
            if (item.seq() != i) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "PLAN_SEQ_NOT_CONTINUOUS",
                        "穿越序列 seq 必须从 0 开始连续递增：位置 " + i + " 实际 seq=" + item.seq());
            }
            if (item.bucketStart() % Buckets.BUCKET_SIZE_MILLIS != 0L) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "BUCKET_NOT_ALIGNED",
                        "bucketStart 必须是 15 分钟（UTC）对齐的 epoch 毫秒");
            }
            normalized.add(new PlanSlotPo(i, item.cellX(), item.cellY(), item.bucketStart()));
        }
        return normalized;
    }

    static List<PlanSlotDto> toSlotDtos(List<PlanSlotPo> items) {
        List<PlanSlotDto> dtos = new ArrayList<>(items.size());
        for (PlanSlotPo item : items) {
            dtos.add(new PlanSlotDto(item.seq(), item.cellX(), item.cellY(), item.bucketStart()));
        }
        return dtos;
    }

    // ============================ 幂等与事务 ============================

    private MutationResponse withIdempotency(String requestId, String kind, String paramHash,
                                             Supplier<MutationResponse> action) {
        try {
            return txTemplate.execute(status -> doIdempotent(requestId, kind, paramHash, action));
        } catch (DuplicateKeyException dup) {
            return resolveAfterRace(requestId, kind, paramHash,
                    new ApiException(HttpStatus.CONFLICT, "RESOURCE_CONFLICT",
                            "并发资源冲突，请稍后使用相同 requestId 与参数重试"));
        } catch (ApiException api) {
            if (api.status() == HttpStatus.CONFLICT) {
                return resolveAfterRace(requestId, kind, paramHash, api);
            }
            throw api;
        }
    }

    private MutationResponse resolveAfterRace(String requestId, String kind, String paramHash,
                                              ApiException original) {
        DedupPo winner = txTemplate.execute(status -> reviewRepo.findDedup(requestId));
        if (winner == null) {
            throw original;
        }
        ensureSameRequest(winner, kind, paramHash);
        return deserializeReplay(winner);
    }

    private MutationResponse doIdempotent(String requestId, String kind, String paramHash,
                                          Supplier<MutationResponse> action) {
        DedupPo existing = reviewRepo.findDedup(requestId);
        if (existing != null) {
            ensureSameRequest(existing, kind, paramHash);
            return deserializeReplay(existing);
        }
        MutationResponse result = action.get();
        reviewRepo.insertDedup(new DedupPo(requestId, kind, paramHash,
                writeJson(result), nowMillis()));
        return result;
    }

    private void ensureSameRequest(DedupPo existing, String kind, String paramHash) {
        if (!existing.requestKind().equals(kind) || !existing.requestHash().equals(paramHash)) {
            throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENT_PARAM_MISMATCH",
                    "requestId 已用于参数不同的请求: " + existing.requestId());
        }
    }

    private MutationResponse deserializeReplay(DedupPo po) {
        try {
            MutationResponse original = objectMapper.readValue(po.responseJson(), MutationResponse.class);
            return new MutationResponse(original.requestId(), true, original.data());
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("无法解析幂等重放结果: " + po.requestId(), ex);
        }
    }

    private long nowMillis() {
        return clock.instant().toEpochMilli();
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("响应序列化失败", ex);
        }
    }

    /**
     * 计算请求参数的规范化哈希（SHA-256）。对象字段按键名字典序递归排序，
     * 列表保持顺序，使字段书写顺序不同不影响比对。
     * 转配项换序由 {@code CapacityTransferService} 在调用前完成列表排序。
     */
    private String canonicalHash(Object request) {
        try {
            JsonNode sorted = canonicalize(objectMapper.valueToTree(request));
            String canonical = objectMapper.writeValueAsString(sorted);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (JsonProcessingException | NoSuchAlgorithmException ex) {
            throw new IllegalStateException("无法计算请求哈希", ex);
        }
    }

    private static JsonNode canonicalize(JsonNode node) {
        if (node.isObject()) {
            ObjectNode sorted = JsonNodeFactory.instance.objectNode();
            List<String> names = new ArrayList<>();
            node.fieldNames().forEachRemaining(names::add);
            names.sort(String::compareTo);
            for (String name : names) {
                sorted.set(name, canonicalize(node.get(name)));
            }
            return sorted;
        }
        if (node.isArray()) {
            ArrayNode array = JsonNodeFactory.instance.arrayNode();
            node.forEach(child -> array.add(canonicalize(child)));
            return array;
        }
        return node;
    }
}
