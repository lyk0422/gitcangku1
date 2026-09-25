package com.example.starter.evidence;

import com.example.starter.error.ApiException;
import com.example.starter.evidence.dto.BatchIntakeItem;
import com.example.starter.evidence.dto.BatchIntakeRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 幂等编排（非事务）：计算请求指纹、处理并发下 command_key 唯一约束冲突。
 * 同键同参重放返回首次结果；同键改参返回 409；并发重复插入时回滚后重放先提交事务的结果。
 */
@Component
public class IdempotencyAdvisor {

    private final CommandLogRepository commandLogRepository;
    private final ObjectMapper objectMapper;

    public IdempotencyAdvisor(CommandLogRepository commandLogRepository, ObjectMapper objectMapper) {
        this.commandLogRepository = commandLogRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * 计算请求指纹：操作类型 + 操作人 + 证物键 + 请求体规范化 JSON 的 SHA-256。
     */
    public String hash(String operation, String actorId, String evidenceKey, Object request) {
        try {
            String canonical = String.join("\n",
                    operation,
                    actorId == null ? "" : actorId,
                    evidenceKey == null ? "" : evidenceKey,
                    objectMapper.writeValueAsString(request));
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        } catch (Exception e) {
            throw new IllegalStateException("请求序列化失败", e);
        }
    }

    /**
     * 批量入库请求指纹：清单项与实测重量按下标配对后按 evidenceKey 排序归一，
     * 清单换序视为同参；重量统一去除尾零，避免 1.0 与 1.00 被识别为异参。
     */
    public String hashBatch(String operation, String actorId, BatchIntakeRequest request) {
        List<Map<String, String>> pairs = new ArrayList<>();
        for (int i = 0; i < request.items().size(); i++) {
            BatchIntakeItem item = request.items().get(i);
            BigDecimal measured = request.measuredWeights() != null
                    && i < request.measuredWeights().size()
                    ? request.measuredWeights().get(i) : null;
            Map<String, String> pair = new LinkedHashMap<>();
            pair.put("evidenceKey", item.evidenceKey() == null ? "" : item.evidenceKey());
            pair.put("description", item.description() == null ? "" : item.description());
            pair.put("declaredWeight", canonicalWeight(item.declaredWeight()));
            pair.put("measuredWeight", canonicalWeight(measured));
            pairs.add(pair);
        }
        pairs.sort(Comparator.comparing(pair -> pair.get("evidenceKey")));
        return hash(operation, actorId, request.requestId(), pairs);
    }

    private String canonicalWeight(BigDecimal weight) {
        return weight == null ? "" : weight.stripTrailingZeros().toPlainString();
    }

    /**
     * 在事务边界外执行命令；捕获并发导致的唯一约束冲突并重放/报 409。
     */
    public StoredResponse guard(String commandKey, String requestHash,
                                Supplier<StoredResponse> transactionalCall) {
        return guard(commandKey, requestHash, transactionalCall, false);
    }

    /**
     * 在事务边界外执行命令；捕获并发导致的唯一约束冲突：
     * command_key 冲突时等待先提交者落盘后重放同参快照或对异参报 409（先提交者回滚则自身重试一次）；
     * 其他业务键（批量入库的 evidence_key）冲突时，批量入库按题意返回 400，其余操作返回 409。
     */
    public StoredResponse guard(String commandKey, String requestHash,
                                Supplier<StoredResponse> transactionalCall,
                                boolean evidenceConflictBadRequest) {
        try {
            return transactionalCall.get();
        } catch (DuplicateKeyException e) {
            if (isCommandLogConflict(e)) {
                // command_key 冲突：对端仍可能在提交中，等待其响应快照落盘；对端回滚则自身重试一次。
                StoredResponse replay = waitForCommitted(commandKey, requestHash);
                if (replay != null) {
                    return replay;
                }
                try {
                    return transactionalCall.get();
                } catch (DuplicateKeyException retry) {
                    StoredResponse retryReplay = waitForCommitted(commandKey, requestHash);
                    if (retryReplay != null) {
                        return retryReplay;
                    }
                }
                throw ApiException.conflict("幂等键冲突: " + commandKey);
            }
            // 业务键（如 evidence_key）冲突：能观察到冲突说明对端事务已提交，其幂等记录应可直接重放。
            Optional<CommandLogRepository.CommandLog> existing = commandLogRepository.findByKey(commandKey);
            if (existing.isPresent()) {
                CommandLogRepository.CommandLog log = existing.get();
                if (log.requestHash().equals(requestHash)) {
                    return new StoredResponse(log.responseStatus(), log.responseBody());
                }
                throw ApiException.conflict("幂等键已被不同参数使用: " + commandKey);
            }
            if (evidenceConflictBadRequest) {
                throw ApiException.badRequest("清单中存在已被其他事务创建的证物，整批已回滚");
            }
            throw ApiException.conflict("资源键冲突，目标已存在");
        }
    }

    private boolean isCommandLogConflict(DuplicateKeyException e) {
        Throwable cause = e.getMostSpecificCause();
        String message = cause == null ? null : cause.getMessage();
        return message != null && message.toLowerCase().contains("command_log");
    }

    /**
     * 等待并发先占键事务提交其响应快照；返回 null 表示对端已回滚或等待超时后仍未见提交。
     */
    private StoredResponse waitForCommitted(String commandKey, String requestHash) {
        for (int attempt = 0; attempt < 40; attempt++) {
            Optional<CommandLogRepository.CommandLog> existing = commandLogRepository.findByKey(commandKey);
            // response_status=0 为事务内占位行，其他事务不可见；可见时也视为尚未提交完成，继续等待。
            if (existing.isPresent() && existing.get().responseStatus() != 0) {
                CommandLogRepository.CommandLog log = existing.get();
                if (!log.requestHash().equals(requestHash)) {
                    throw ApiException.conflict("幂等键已被不同参数使用: " + commandKey);
                }
                return new StoredResponse(log.responseStatus(), log.responseBody());
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw ApiException.conflict("等待并发请求结果时被中断: " + commandKey);
            }
        }
        return null;
    }
}
