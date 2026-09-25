package com.example.starter.service;

import com.example.starter.api.ApiException;
import com.example.starter.api.dto.MutationResponse;
import com.example.starter.repo.DedupPo;
import com.example.starter.repo.ReviewRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Supplier;

/**
 * 写操作幂等执行器：与 AirspaceReviewService 内置逻辑同一语义，
 * 供容量账本与转配等新增写操作复用。
 *
 * <p>同键同参返回首次成功结果（标记 replayed=true）；同键异参/异种操作抛 409；
 * 业务异常回滚、不占用 requestId；去重记录与业务变更同一事务原子提交。</p>
 *
 * <p>同键并发时，落败事务可能先撞上唯一约束（DuplicateKeyException），
 * 也可能在持锁后读到赢家已提交的状态而抛业务冲突（409）；两种情况都在
 * 回滚后用新事务查询去重表：赢家同键同参已提交则重放原结果，否则按原错误抛出。</p>
 */
@Component
public class IdempotencyExecutor {

    private final ReviewRepository reviewRepo;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final TransactionTemplate txTemplate;

    public IdempotencyExecutor(ReviewRepository reviewRepo, ObjectMapper objectMapper,
                               Clock clock, PlatformTransactionManager transactionManager) {
        this.reviewRepo = reviewRepo;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.txTemplate = new TransactionTemplate(transactionManager);
    }

    /** 在事务内执行幂等写操作（数组字段保持顺序语义）。 */
    public MutationResponse execute(String requestId, String kind, String paramHash,
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

    /**
     * 并发落败后的裁决：去重表中存在同键记录则按重放/异参冲突处理；
     * 不存在（说明该 requestId 尚未成功、这是一次真实业务冲突）则抛出原错误，
     * 不占用 requestId。
     */
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
        // 业务异常会触发事务回滚，去重键不会被占用
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
            // 业务结果原样重放，仅传输标记告知客户端本次为重放
            return new MutationResponse(original.requestId(), true, original.data());
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("无法解析幂等重放结果: " + po.requestId(), ex);
        }
    }

    /**
     * 计算请求参数的规范化哈希（SHA-256）：对象字段按键名字典序递归排序，
     * 列表保持顺序。
     */
    public String canonicalHash(Object request) {
        return canonicalHash(request, null);
    }

    /**
     * 计算请求参数的规范化哈希（SHA-256）。
     *
     * @param unorderedArrayField 非空时，该名字的对象字段若为数组，
     *                            数组元素按各自规范化 JSON 字典序排序后再哈希，
     *                            使数组元素换序被视为同参（用于转配项列表）。
     */
    public String canonicalHash(Object request, String unorderedArrayField) {
        try {
            JsonNode sorted = canonicalize(objectMapper.valueToTree(request), unorderedArrayField);
            String canonical = objectMapper.writeValueAsString(sorted);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (JsonProcessingException | NoSuchAlgorithmException ex) {
            throw new IllegalStateException("无法计算请求哈希", ex);
        }
    }

    private JsonNode canonicalize(JsonNode node, String unorderedArrayField) {
        if (node.isObject()) {
            ObjectNode sorted = JsonNodeFactory.instance.objectNode();
            List<String> names = new ArrayList<>();
            node.fieldNames().forEachRemaining(names::add);
            names.sort(String::compareTo);
            for (String name : names) {
                JsonNode child = canonicalize(node.get(name), unorderedArrayField);
                if (name.equals(unorderedArrayField) && child.isArray()) {
                    child = sortArray(child);
                }
                sorted.set(name, child);
            }
            return sorted;
        }
        if (node.isArray()) {
            ArrayNode array = JsonNodeFactory.instance.arrayNode();
            node.forEach(child -> array.add(canonicalize(child, unorderedArrayField)));
            return array;
        }
        return node;
    }

    private JsonNode sortArray(JsonNode array) {
        List<JsonNode> elements = new ArrayList<>();
        array.forEach(elements::add);
        elements.sort(Comparator.comparing(JsonNode::toString));
        ArrayNode sorted = JsonNodeFactory.instance.arrayNode();
        elements.forEach(sorted::add);
        return sorted;
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
}
