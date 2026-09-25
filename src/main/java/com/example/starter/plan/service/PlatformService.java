package com.example.starter.plan.service;

import com.example.starter.plan.model.DayPlan;
import com.example.starter.plan.model.Platform;
import com.example.starter.plan.model.PlatformRisk;
import com.example.starter.plan.repo.IdempotencyRepository;
import com.example.starter.plan.repo.PlanRepository;
import com.example.starter.plan.repo.PlatformRepository;
import com.example.starter.plan.web.ApiException;
import com.example.starter.plan.web.dto.PlatformCreateRequest;
import com.example.starter.plan.web.dto.PlatformLengthRequest;
import com.example.starter.plan.web.dto.PlatformLengthResponse;
import com.example.starter.plan.web.dto.PlatformOccupancyView;
import com.example.starter.plan.web.dto.PlatformResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 站台主数据业务：站台创建、有效长度调整与站台占用查询。
 *
 * <p>长度下调与发布/改签经同一全局发布锁按事务提交顺序裁决；下调在同一事务内
 * 回查运营日不早于今天（业务时钟，Asia/Shanghai）的已发布计划，编组超长者标记
 * PLATFORM_RISK 并固化下调前原长度快照，计划不自动取消。幂等约定与计划写操作一致：
 * 仅成功结果占键，同键同参重放首次响应，异参 409。
 */
@Service
public class PlatformService {

    private static final String OP_PLATFORM_CREATE = "PLATFORM_CREATE";
    private static final String OP_PLATFORM_LENGTH = "PLATFORM_LENGTH";

    private final PlatformRepository platformRepo;
    private final PlanRepository planRepo;
    private final IdempotencyRepository idemRepo;
    private final ObjectMapper objectMapper;
    private final Clock businessClock;
    private final TransactionTemplate tx;

    public PlatformService(PlatformRepository platformRepo, PlanRepository planRepo,
                           IdempotencyRepository idemRepo, ObjectMapper objectMapper,
                           Clock businessClock, PlatformTransactionManager txManager) {
        this.platformRepo = platformRepo;
        this.planRepo = planRepo;
        this.idemRepo = idemRepo;
        this.objectMapper = objectMapper;
        this.businessClock = businessClock;
        this.tx = new TransactionTemplate(txManager);
    }

    /**
     * 创建站台；站台代码全局唯一，重复创建返回 409。
     */
    public PlatformResponse createPlatform(PlatformCreateRequest req) {
        String code = req.code().trim();
        String hash = sha256(OP_PLATFORM_CREATE + '\n' + req.operator() + '\n' + code
                + '\n' + req.effectiveLength());
        Optional<PlatformResponse> replay = replayIfPresent(OP_PLATFORM_CREATE, req.requestKey(),
                hash, PlatformResponse.class);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> {
                if (platformRepo.findByCode(code).isPresent()) {
                    throw conflict("PLATFORM_EXISTS", "站台已存在: " + code);
                }
                long now = System.currentTimeMillis();
                platformRepo.insertPlatform(code, req.effectiveLength(), now);
                PlatformResponse response = new PlatformResponse(code, req.effectiveLength());
                idemRepo.insert(OP_PLATFORM_CREATE, req.requestKey(), hash, toJson(response), now);
                return response;
            });
        } catch (DuplicateKeyException e) {
            return replayIfPresent(OP_PLATFORM_CREATE, req.requestKey(), hash, PlatformResponse.class)
                    .orElseThrow(() -> conflict("PLATFORM_EXISTS", "站台已存在: " + code));
        }
    }

    /**
     * 调整站台有效长度。下调时在同一事务内回查未来已发布计划：编组超过新长度者
     * 标记 PLATFORM_RISK 并固化原长度快照（已有未解除风险的计划保留首次快照、
     * 仅刷新下调后长度），计划不自动取消；历史发布记录不重写。
     */
    public PlatformLengthResponse adjustLength(String code, PlatformLengthRequest req) {
        String hash = sha256(OP_PLATFORM_LENGTH + '\n' + req.operator() + '\n' + code
                + '\n' + req.effectiveLength());
        Optional<PlatformLengthResponse> replay = replayIfPresent(OP_PLATFORM_LENGTH,
                req.requestKey(), hash, PlatformLengthResponse.class);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> {
                planRepo.acquirePublishLock();
                Platform platform = platformRepo.findByCodeForUpdate(code)
                        .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                                "PLATFORM_NOT_FOUND", "站台不存在: " + code));
                int previousLength = platform.effectiveLength();
                int newLength = req.effectiveLength();
                long now = System.currentTimeMillis();
                platformRepo.updateLength(code, newLength, now);
                List<String> affected = new ArrayList<>();
                if (newLength < previousLength) {
                    LocalDate today = LocalDate.now(businessClock);
                    List<DayPlan> plans = platformRepo.findOverLengthPublishedPlans(code, today,
                            newLength);
                    for (DayPlan plan : plans) {
                        Optional<PlatformRisk> open = platformRepo.findOpenRisk(plan.id(), code);
                        if (open.isPresent()) {
                            platformRepo.refreshOpenRisk(open.get().id(), newLength, now);
                        } else {
                            platformRepo.insertRisk(plan.id(), code, previousLength, newLength,
                                    plan.consistLength(), now);
                            affected.add(plan.scheduleKey());
                        }
                    }
                }
                PlatformLengthResponse response = new PlatformLengthResponse(code, previousLength,
                        newLength, List.copyOf(affected));
                idemRepo.insert(OP_PLATFORM_LENGTH, req.requestKey(), hash, toJson(response), now);
                return response;
            });
        } catch (DuplicateKeyException e) {
            return replayIfPresent(OP_PLATFORM_LENGTH, req.requestKey(), hash,
                    PlatformLengthResponse.class)
                    .orElseThrow(() -> conflict("PLATFORM_EXISTS", "站台长度调整冲突: " + code));
        }
    }

    /**
     * 查询指定运营日某站台上已发布计划的占用窗口；站台不存在返回 404。
     */
    public List<PlatformOccupancyView> getOccupancy(LocalDate opDate, String code) {
        if (platformRepo.findByCode(code).isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "PLATFORM_NOT_FOUND", "站台不存在: " + code);
        }
        return platformRepo.findPlatformOccupancy(opDate, code).stream()
                .map(s -> new PlatformOccupancyView(s.scheduleKey(), s.platformCode(),
                        s.consistLength(), s.startUtc(), s.endUtc()))
                .toList();
    }

    // ---------- 内部实现 ----------

    /**
     * 幂等重放（泛型）：存在记录且参数一致返回首次结果；参数不一致抛 409。
     */
    private <T> Optional<T> replayIfPresent(String opType, String requestKey, String hash,
                                            Class<T> type) {
        return idemRepo.find(opType, requestKey).map(record -> {
            if (!record.requestHash().equals(hash)) {
                throw conflict("IDEMPOTENT_KEY_REUSED",
                        "requestKey 已用于其他参数: " + requestKey);
            }
            return fromJson(record.responseJson(), type);
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

    private <T> T fromJson(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("幂等响应反序列化失败", e);
        }
    }

    private ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }
}
