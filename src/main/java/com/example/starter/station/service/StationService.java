package com.example.starter.station.service;

import com.example.starter.plan.repo.IdempotencyRepository;
import com.example.starter.plan.repo.PlanRepository;
import com.example.starter.station.model.StationConfig;
import com.example.starter.station.model.StationVersion;
import com.example.starter.station.repo.StationRepository;
import com.example.starter.station.web.dto.StationConfigRequest;
import com.example.starter.station.web.dto.StationConfigView;
import com.example.starter.station.web.dto.StationHistoryResponse;
import com.example.starter.plan.web.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 站台配置服务：首次创建与后续修订共用一个幂等接口。
 *
 * <p>修订在发布全局锁 + 站点行锁内提交，与发布/改签按事务提交顺序裁决；
 * 发布事务在同一把锁内读取当前规则并固化快照，因此规则修订只影响之后的发布，不改写历史。
 * 仅成功结果写入幂等记录，失败不占键。
 */
@Service
public class StationService {

    /** 操作者缺省标识，null/空白在入服务时统一归一为本值。 */
    public static final String DEFAULT_OPERATOR = "anonymous";

    private static final String OP_STATION_CONFIG = "STATION_CONFIG";

    private final StationRepository stationRepo;
    private final IdempotencyRepository idemRepo;
    private final PlanRepository planRepo;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate tx;

    public StationService(StationRepository stationRepo, IdempotencyRepository idemRepo,
                          PlanRepository planRepo, ObjectMapper objectMapper,
                          PlatformTransactionManager txManager) {
        this.stationRepo = stationRepo;
        this.idemRepo = idemRepo;
        this.planRepo = planRepo;
        this.objectMapper = objectMapper;
        this.tx = new TransactionTemplate(txManager);
    }

    /**
     * 创建或修订站点配置：不存在则创建版本 1，存在则版本加一并追加不可变版本。
     * 同 requestKey 同参重放返回首次完整响应；同键异参返回 409；失败不占键。
     */
    public StationConfigView upsertConfig(String stationId, StationConfigRequest req) {
        String operatorId = normalizeOperator(req.operatorId());
        String hash = hash(stationId, req, operatorId);
        Optional<StationConfigView> replay = replayIfPresent(req.requestKey(), hash);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> {
                // 与发布/改签共用全局锁：配置修订与发布快照按事务提交顺序裁决
                planRepo.acquirePublishLock();
                long now = System.currentTimeMillis();
                StationConfigView view;
                StationConfig current = stationRepo.findForUpdate(stationId).orElse(null);
                if (current == null) {
                    stationRepo.insert(new StationConfig(stationId, req.minDwellMinutes(),
                            req.platformCapacity(), 1, operatorId, now, now), now);
                    view = toView(new StationConfig(stationId, req.minDwellMinutes(),
                            req.platformCapacity(), 1, operatorId, now, now));
                } else {
                    stationRepo.update(current, req.minDwellMinutes(), req.platformCapacity(),
                            operatorId, now);
                    view = toView(new StationConfig(stationId, req.minDwellMinutes(),
                            req.platformCapacity(), current.version() + 1, operatorId,
                            current.createdAt(), now));
                }
                idemRepo.insert(OP_STATION_CONFIG, req.requestKey(), hash, toJson(view), now);
                return view;
            });
        } catch (DuplicateKeyException e) {
            return replayIfPresent(req.requestKey(), hash)
                    .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "STATION_CONFIG_CONFLICT",
                            "站台配置并发修订冲突: " + stationId));
        }
    }

    /**
     * 查询站点当前配置，不存在返回 404。
     */
    public StationConfigView getConfig(String stationId) {
        return stationRepo.find(stationId).map(this::toView)
                .orElseThrow(() -> stationNotFound(stationId));
    }

    /**
     * 查询站点不可变版本历史，按版本号升序；站点不存在返回 404。
     */
    public StationHistoryResponse getHistory(String stationId) {
        if (stationRepo.find(stationId).isEmpty()) {
            throw stationNotFound(stationId);
        }
        List<StationHistoryResponse.VersionItem> versions = stationRepo.findVersions(stationId).stream()
                .map(this::toVersionItem)
                .toList();
        return new StationHistoryResponse(stationId, versions);
    }

    private StationConfigView toView(StationConfig config) {
        return new StationConfigView(config.stationId(), config.minDwellMinutes(),
                config.platformCapacity(), config.version(), config.operatorId(),
                config.createdAt(), config.updatedAt());
    }

    private StationHistoryResponse.VersionItem toVersionItem(StationVersion v) {
        return new StationHistoryResponse.VersionItem(v.version(), v.minDwellMinutes(),
                v.platformCapacity(), v.operatorId(), v.createdAt());
    }

    private Optional<StationConfigView> replayIfPresent(String requestKey, String hash) {
        return idemRepo.find(OP_STATION_CONFIG, requestKey).map(record -> {
            if (!record.requestHash().equals(hash)) {
                throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENT_KEY_REUSED",
                        "requestKey 已用于其他参数: " + requestKey);
            }
            return fromJson(record.responseJson());
        });
    }

    private StationConfigView fromJson(String json) {
        try {
            return objectMapper.readValue(json, StationConfigView.class);
        } catch (Exception e) {
            throw new IllegalStateException("幂等响应反序列化失败", e);
        }
    }

    /**
     * 幂等指纹：操作者 + 操作类型 + 站点 + 全部影响结果的字段；标量字段以换行分隔。
     */
    private String hash(String stationId, StationConfigRequest req, String operatorId) {
        String text = OP_STATION_CONFIG + '\n' + operatorId + '\n' + stationId + '\n'
                + req.minDwellMinutes() + '\n' + req.platformCapacity();
        return sha256(text);
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

    private ApiException stationNotFound(String stationId) {
        return new ApiException(HttpStatus.NOT_FOUND, "STATION_NOT_FOUND",
                "站点不存在: " + stationId);
    }

    /**
     * 操作者归一：null 或空白一律视为 {@link #DEFAULT_OPERATOR}，其余去除首尾空白。
     */
    public static String normalizeOperator(String operatorId) {
        if (operatorId == null || operatorId.isBlank()) {
            return DEFAULT_OPERATOR;
        }
        return operatorId.trim();
    }
}
