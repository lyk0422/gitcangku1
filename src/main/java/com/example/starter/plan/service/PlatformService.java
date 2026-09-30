package com.example.starter.plan.service;

import com.example.starter.plan.model.DayPlan;
import com.example.starter.plan.model.Occupancy;
import com.example.starter.plan.model.PlatformBooking;
import com.example.starter.plan.model.PlatformCapability;
import com.example.starter.plan.model.PowerSupplyType;
import com.example.starter.plan.repo.IdempotencyRepository;
import com.example.starter.plan.repo.PlanRepository;
import com.example.starter.plan.web.ApiException;
import com.example.starter.plan.web.dto.CandidatePlatformView;
import com.example.starter.plan.web.dto.PlatformBookingView;
import com.example.starter.plan.web.dto.PlatformCapabilityRequest;
import com.example.starter.plan.web.dto.PlatformCapabilityView;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 站台侧业务：站台管理方登记版本化有效能力、查询本站台生效占用；
 * 调度员按日计划编组与到发时段评估候选站台及具体不适配原因。
 *
 * <p>能力仅追加新版本不改写历史；能力登记经全局发布锁与发布/改签串行化，
 * 保证发布登记快照到的能力版本与并发裁决顺序一致。
 */
@Service
public class PlatformService {

    /** 能力满足但申请窗口与生效占用重叠时的原因代码。 */
    public static final String REASON_WINDOW_CONFLICT = "WINDOW_CONFLICT";

    private static final String OP_PLATFORM_CAPABILITY = "PLATFORM_CAPABILITY";

    private final PlanRepository planRepo;
    private final IdempotencyRepository idemRepo;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate tx;

    public PlatformService(PlanRepository planRepo, IdempotencyRepository idemRepo,
                           ObjectMapper objectMapper, PlatformTransactionManager txManager) {
        this.planRepo = planRepo;
        this.idemRepo = idemRepo;
        this.objectMapper = objectMapper;
        this.tx = new TransactionTemplate(txManager);
    }

    /**
     * 登记站台新一版有效能力（仅追加），返回新版本视图。
     * 同 requestKey 同参重放返回首次结果；同键不同参返回 409。
     */
    public PlatformCapabilityView registerCapability(PlatformCapabilityRequest req) {
        Set<PowerSupplyType> powerTypes = parsePowerTypes(req.supportedPowerTypes());
        String hash = hashCapability(req);
        Optional<PlatformCapabilityView> replay =
                replayIfPresent(req.requestKey(), hash, PlatformCapabilityView.class);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> {
                planRepo.acquirePublishLock();
                long now = System.currentTimeMillis();
                int version = planRepo.insertCapabilityVersion(req.platformId(), req.maxLengthM(),
                        powerTypes, req.accessible(), now);
                PlatformCapabilityView view = toView(
                        new PlatformCapability(req.platformId(), version, req.maxLengthM(),
                                powerTypes, req.accessible()));
                idemRepo.insert(OP_PLATFORM_CAPABILITY, req.requestKey(), hash, toJson(view), now);
                return view;
            });
        } catch (DuplicateKeyException e) {
            return replayIfPresent(req.requestKey(), hash, PlatformCapabilityView.class)
                    .orElseThrow(() -> conflict("IDEMPOTENT_KEY_REUSED",
                            "requestKey 已用于其他参数: " + req.requestKey()));
        }
    }

    /**
     * 查询站台当前有效能力，不存在返回 404。
     */
    public PlatformCapabilityView getCapability(String platformId) {
        return toView(planRepo.findLatestCapability(platformId)
                .orElseThrow(() -> platformNotFound(platformId)));
    }

    /**
     * 调度员候选站台评估：按计划登记的编组与全部到发时段，对每个站台的当前有效能力
     * 评估长度/制式/无障碍适配与窗口占用，返回具体原因与冲突占用明细。
     * 计划不存在返回 404；计划未登记编组返回 400。
     */
    public List<CandidatePlatformView> evaluateCandidates(String scheduleKey) {
        DayPlan plan = planRepo.findByKey(scheduleKey)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PLAN_NOT_FOUND",
                        "计划不存在: " + scheduleKey));
        if (!plan.hasConsist()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT",
                    "计划未登记编组，无法评估候选站台: " + scheduleKey);
        }
        List<Occupancy> occupancies = planRepo.findOccupancies(plan.id());
        List<PlatformCapability> capabilities = planRepo.findAllLatestCapabilities();
        List<String> platformIds = capabilities.stream().map(PlatformCapability::platformId).toList();
        List<PlatformBooking> activeBookings =
                planRepo.findActiveBookings(platformIds, List.of(plan.id()));

        List<CandidatePlatformView> views = new ArrayList<>(capabilities.size());
        for (PlatformCapability capability : capabilities) {
            List<String> reasons = new ArrayList<>(PlatformFit.mismatchReasons(capability,
                    plan.consistLengthM(), plan.powerSupplyType(), plan.accessibleRequired()));
            List<CandidatePlatformView.ConflictingBookingView> conflicting = new ArrayList<>();
            for (PlatformBooking booking : activeBookings) {
                if (!booking.platformId().equals(capability.platformId())) {
                    continue;
                }
                if (overlapsAny(occupancies, booking)) {
                    conflicting.add(new CandidatePlatformView.ConflictingBookingView(
                            booking.scheduleKey(), booking.trainNo(),
                            booking.startUtc(), booking.endUtc()));
                }
            }
            boolean compatible = reasons.isEmpty();
            if (compatible && !conflicting.isEmpty()) {
                reasons.add(REASON_WINDOW_CONFLICT);
            }
            views.add(new CandidatePlatformView(
                    capability.platformId(),
                    compatible,
                    compatible && conflicting.isEmpty(),
                    List.copyOf(reasons),
                    capability.version(),
                    capability.maxLengthM(),
                    capability.supportedPowerTypes().stream().map(Enum::name).toList(),
                    capability.accessible(),
                    List.copyOf(conflicting)));
        }
        // 可用候选优先，其后按站台 ID 稳定排序
        return views.stream()
                .sorted(java.util.Comparator
                        .comparing(CandidatePlatformView::available).reversed()
                        .thenComparing(CandidatePlatformView::platformId))
                .toList();
    }

    /**
     * 站台管理方查询某站台当前生效（来源计划 PUBLISHED）的到发占用；
     * 取消计划的登记不返回（窗口已释放），站台不存在返回 404。
     */
    public List<PlatformBookingView> getActiveBookings(String platformId) {
        if (planRepo.findLatestCapability(platformId).isEmpty()) {
            throw platformNotFound(platformId);
        }
        return planRepo.findActiveBookingsByPlatform(platformId).stream()
                .map(b -> new PlatformBookingView(b.scheduleKey(), b.trainNo(), b.platformId(),
                        b.startUtc(), b.endUtc(), b.capabilityVersion()))
                .toList();
    }

    // ---------- 内部实现 ----------

    private boolean overlapsAny(List<Occupancy> occupancies, PlatformBooking booking) {
        for (Occupancy o : occupancies) {
            if (!o.platformId().equals(booking.platformId())) {
                continue;
            }
            boolean overlap = o.startUtc().isBefore(booking.endUtc())
                    && booking.startUtc().isBefore(o.endUtc());
            if (overlap) {
                return true;
            }
        }
        return false;
    }

    private Set<PowerSupplyType> parsePowerTypes(List<String> codes) {
        Set<PowerSupplyType> types = new LinkedHashSet<>();
        for (String code : codes) {
            try {
                types.add(PowerSupplyType.valueOf(code));
            } catch (IllegalArgumentException e) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT",
                        "未知供电制式代码: " + code);
            }
        }
        return types;
    }

    private PlatformCapabilityView toView(PlatformCapability capability) {
        return new PlatformCapabilityView(capability.platformId(), capability.version(),
                capability.maxLengthM(),
                capability.supportedPowerTypes().stream().map(Enum::name)
                        .collect(Collectors.toList()),
                capability.accessible());
    }

    private <T> Optional<T> replayIfPresent(String requestKey, String hash, Class<T> type) {
        return idemRepo.find(OP_PLATFORM_CAPABILITY, requestKey).map(record -> {
            if (!record.requestHash().equals(hash)) {
                throw conflict("IDEMPOTENT_KEY_REUSED",
                        "requestKey 已用于其他参数: " + requestKey);
            }
            return fromJson(record.responseJson(), type);
        });
    }

    private String hashCapability(PlatformCapabilityRequest req) {
        String text = OP_PLATFORM_CAPABILITY + '\n' + req.platformId() + '\n' + req.maxLengthM()
                + '\n' + req.supportedPowerTypes().stream().sorted().collect(Collectors.joining(","))
                + '\n' + req.accessible();
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

    private <T> T fromJson(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("幂等响应反序列化失败", e);
        }
    }

    private ApiException platformNotFound(String platformId) {
        return new ApiException(HttpStatus.NOT_FOUND, "PLATFORM_NOT_FOUND",
                "站台能力不存在: " + platformId);
    }

    private ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }
}
