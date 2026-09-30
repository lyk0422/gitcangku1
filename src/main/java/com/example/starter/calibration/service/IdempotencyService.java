package com.example.starter.calibration.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.function.Supplier;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.calibration.api.ApiException;
import com.example.starter.calibration.repo.IdempotencyRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 请求幂等服务。业务方法在“同一个事务”内先登记 requestId 再执行：
 * <ul>
 *   <li>首次成功：提交后保存首次状态码与响应体；</li>
 *   <li>同参重放：返回首次状态码与响应体（反序列化为 JSON 树），不再次执行业务；</li>
 *   <li>异参重放（含方法/路径/角色不同）：409 IDEMPOTENCY_PARAM_CONFLICT；</li>
 *   <li>业务失败：事务回滚连带删除登记行，不占用 requestId，可原样重试。</li>
 * </ul>
 * 并发同键请求在唯一索引上等待首个事务结束：其提交则变为重放，其回滚则获得执行权。
 */
@Service
public class IdempotencyService {

    private final IdempotencyRepository requests;
    private final CalibrationClock clock;
    private final ObjectMapper objectMapper;

    public IdempotencyService(IdempotencyRepository requests, CalibrationClock clock, ObjectMapper objectMapper) {
        this.requests = requests;
        this.clock = clock;
        this.objectMapper = objectMapper;
    }

    /** 一次幂等调用的请求指纹；requestId 为 null 时表示不启用幂等。 */
    public record Context(String requestId, String method, String path, String actor, String requestHash) {
    }

    /** 业务执行结果：HTTP 状态码与响应体对象（首次结果将被序列化封存）。 */
    public record Outcome(int statusCode, Object body) {
    }

    /** 控制器返回值：重放时 replayed=true，body 为首次响应体反序列化出的 JSON 树。 */
    public record Response(int statusCode, Object body, boolean replayed) {
    }

    /**
     * 计算请求参数指纹：对请求体对象做规范化 JSON（Map 键排序）后 SHA-256。
     * 空白与键序差异不影响“同参”判定。
     */
    public String fingerprint(Object requestBody) {
        try {
            Object canonical = objectMapper.copy()
                    .configure(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
                    .convertValue(requestBody, JsonNode.class);
            byte[] bytes = objectMapper.writeValueAsBytes(canonical);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        } catch (Exception e) {
            throw ApiException.badRequest("请求体无法序列化为幂等指纹: " + e.getMessage());
        }
    }

    /** 无请求体（路径/查询参数已在 path 中）的指纹。 */
    public String fingerprintEmpty() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest("{}".getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /**
     * 在幂等语义下执行业务。ctx 为 null（无 X-Request-Id）时直接执行、不登记。
     */
    @Transactional
    public Response execute(Context ctx, Supplier<Outcome> action) {
        if (ctx == null) {
            Outcome outcome = action.get();
            return new Response(outcome.statusCode(), outcome.body(), false);
        }
        try {
            requests.insertPlaceholder(ctx.requestId(), ctx.method(), ctx.path(), ctx.actor(),
                    ctx.requestHash(), clock.now());
        } catch (DuplicateKeyException duplicate) {
            // 唯一索引冲突即首个事务已提交（若其回锁后回滚，本 INSERT 会在锁释放后直接成功，不进此分支）。
            // FOR UPDATE 等待首个事务结束后读取其最终结果。
            IdempotencyRepository.IdempotentRequest first = requests.findForUpdate(ctx.requestId())
                    .orElseThrow(() -> ApiException.conflict("IDEMPOTENCY_RACE", "幂等键并发冲突，请重试"));
            return replayOrConflict(ctx, first);
        }
        Outcome outcome = action.get();
        String json;
        try {
            json = objectMapper.writeValueAsString(outcome.body());
        } catch (Exception e) {
            throw new IllegalStateException("首次响应无法序列化", e);
        }
        requests.complete(ctx.requestId(), outcome.statusCode(), json);
        return new Response(outcome.statusCode(), outcome.body(), false);
    }

    private Response replayOrConflict(Context ctx, IdempotencyRepository.IdempotentRequest first) {
        if (!ctx.requestHash().equals(first.requestHash())
                || !ctx.method().equals(first.method())
                || !ctx.path().equals(first.path())
                || !java.util.Objects.equals(ctx.actor(), first.actor())) {
            throw ApiException.conflict("IDEMPOTENCY_PARAM_CONFLICT",
                    "requestId 已用于不同参数的请求: " + ctx.requestId());
        }
        if (first.statusCode() == null) {
            // 理论上唯一索引等待已保证首个事务结束；防御性处理残余登记行。
            throw ApiException.conflict("IDEMPOTENCY_IN_PROGRESS", "相同 requestId 的首次请求尚未完成");
        }
        try {
            JsonNode replayBody = objectMapper.readTree(first.responseBody());
            return new Response(first.statusCode(), replayBody, true);
        } catch (Exception e) {
            throw new IllegalStateException("封存响应无法还原", e);
        }
    }
}
