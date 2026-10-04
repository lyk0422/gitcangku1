package com.example.starter.observation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 设备会话序列确认水位服务：仅管理合成会话，不接真实终端。
 *
 * <p>批次上传（1 至 20 条有序消息）在单事务内完成：先占位写入幂等去重记录，
 * 再按（设备、会话）排序逐会话加行锁处理消息，最后回填去重记录响应并整体提交；
 * 任何观测业务失败都会回滚整批，不留下收件记录、不推进水位、去重记录不占键。
 *
 * <p>水位规则：从 seq=1 起连续收到的最大序号为水位，初值 0，洞不能跳过；
 * 同序号同内容返回既有收件记录且不重复改变观测，异内容 409；会话最多 100 条；
 * 会话由设备显式关闭，仅当水位等于声明末序号时成功，关闭后新消息 409。
 *
 * <p>幂等身份为操作、操作者、资源及全部参数和期望版本的结构化 JSON 指纹（SHA-256），
 * 不使用分隔符拼接，避免分隔符碰撞。
 */
@Service
public class DeviceSessionService {

    /**
     * 单个会话允许的最大收件消息条数。
     */
    public static final int MAX_MESSAGES_PER_SESSION = 100;

    private static final String OP_BATCH_UPLOAD = "BATCH_UPLOAD";
    private static final String OP_SESSION_CLOSE = "SESSION_CLOSE";

    private final DeviceSessionRepository sessionRepository;
    private final SessionMessageRepository messageRepository;
    private final SessionRequestLogRepository requestLogRepository;
    private final ObservationService observationService;
    private final ObjectMapper objectMapper;

    public DeviceSessionService(DeviceSessionRepository sessionRepository,
                                SessionMessageRepository messageRepository,
                                SessionRequestLogRepository requestLogRepository,
                                ObservationService observationService,
                                ObjectMapper objectMapper) {
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
        this.requestLogRepository = requestLogRepository;
        this.observationService = observationService;
        this.objectMapper = objectMapper;
    }

    /**
     * 上传批次：全部观测业务成功才整批保存；同键同参重放首次完整响应（含首次水位），异参 409。
     */
    @Transactional
    public BatchOutcome uploadBatch(BatchUploadRequest request) {
        String fingerprint = structuredFingerprint(batchIdentity(request));
        String replayed = replayBody(request.requestId(), fingerprint);
        if (replayed != null) {
            return new BatchOutcome(200, readBatchResponse(replayed));
        }
        String concurrent = placeholderOrReplay(request.requestId(), fingerprint, OP_BATCH_UPLOAD);
        if (concurrent != null) {
            return new BatchOutcome(200, readBatchResponse(concurrent));
        }

        validateMessages(request.messages());

        // 按（设备、会话）分组并保持批内顺序；会话键排序后逐个加锁，避免交叉加锁顺序不一致
        Map<SessionKey, List<IndexedMessage>> bySession = new LinkedHashMap<>();
        for (int i = 0; i < request.messages().size(); i++) {
            SessionMessageRequest message = request.messages().get(i);
            SessionKey key = new SessionKey(message.deviceId(), message.sessionId());
            bySession.computeIfAbsent(key, k -> new ArrayList<>()).add(new IndexedMessage(i, message));
        }
        List<SessionKey> orderedKeys = bySession.keySet().stream()
                .sorted(Comparator.comparing(SessionKey::deviceId).thenComparing(SessionKey::sessionId))
                .toList();

        MessageReceipt[] receipts = new MessageReceipt[request.messages().size()];
        List<SessionStateResponse> sessionStates = new ArrayList<>();
        for (SessionKey key : orderedKeys) {
            sessionStates.add(processSession(key, bySession.get(key), request.requestId(), receipts));
        }

        BatchUploadResponse response = new BatchUploadResponse(request.requestId(),
                List.of(receipts), sessionStates);
        complete(request.requestId(), 200, writeJson(response));
        return new BatchOutcome(200, response);
    }

    /**
     * 查询会话状态：返回水位及水位以上已收到序号（升序）；会话不存在返回 404。
     */
    @Transactional(readOnly = true)
    public SessionStateResponse getSession(String deviceId, String sessionId) {
        DeviceSession session = sessionRepository.find(deviceId, sessionId)
                .orElseThrow(() -> ApiException.notFound("session not found: " + deviceId + "/" + sessionId));
        return toStateResponse(session);
    }

    /**
     * 设备显式关闭会话：仅当水位等于声明末序号时成功；关闭后新消息 409，已成功旧键仍重放首次响应。
     */
    @Transactional
    public CloseOutcome closeSession(String deviceId, String sessionId, CloseSessionRequest request) {
        String fingerprint = structuredFingerprint(closeIdentity(deviceId, sessionId, request));
        String replayed = replayBody(request.requestId(), fingerprint);
        if (replayed != null) {
            return new CloseOutcome(200, readStateResponse(replayed));
        }
        String concurrent = placeholderOrReplay(request.requestId(), fingerprint, OP_SESSION_CLOSE);
        if (concurrent != null) {
            return new CloseOutcome(200, readStateResponse(concurrent));
        }

        DeviceSession session = sessionRepository.findForUpdate(deviceId, sessionId)
                .orElseThrow(() -> ApiException.notFound("session not found: " + deviceId + "/" + sessionId));
        if (session.closed()) {
            throw ApiException.conflict("session already closed: " + deviceId + "/" + sessionId, null);
        }
        if (session.watermark() != request.finalSeq()) {
            throw ApiException.conflict("watermark " + session.watermark()
                    + " does not match declared finalSeq " + request.finalSeq(), session.watermark());
        }
        sessionRepository.markClosed(deviceId, sessionId, request.finalSeq());
        SessionStateResponse body = toStateResponse(new DeviceSession(deviceId, sessionId,
                session.watermark(), session.messageCount(), true, request.finalSeq()));
        complete(request.requestId(), 200, writeJson(body));
        return new CloseOutcome(200, body);
    }
    /**
     * 处理单会话内的本批消息：行锁串行化并发批次；先分类重放与待执行消息，再执行观测业务并推进水位。
     */
    private SessionStateResponse processSession(SessionKey key, List<IndexedMessage> messages,
                                                String batchRequestId, MessageReceipt[] receipts) {
        DeviceSession session = lockOrCreateSession(key.deviceId(), key.sessionId());

        // 第一遍：同序号同内容命中既有收件记录直接重放，异内容 409；新消息在会话已关闭时 409
        Map<Integer, String> contentFingerprints = new HashMap<>();
        List<IndexedMessage> toApply = new ArrayList<>();
        for (IndexedMessage indexed : messages) {
            SessionMessageRequest message = indexed.message();
            String contentFingerprint = structuredFingerprint(messageContent(message));
            contentFingerprints.put(indexed.index(), contentFingerprint);
            Optional<SessionMessageRecord> existing = messageRepository.find(
                    key.deviceId(), key.sessionId(), message.seq());
            if (existing.isPresent()) {
                SessionMessageRecord record = existing.get();
                if (!record.contentFingerprint().equals(contentFingerprint)) {
                    throw ApiException.conflict("seq " + message.seq()
                            + " already received with different content", null);
                }
                receipts[indexed.index()] = new MessageReceipt(key.deviceId(), key.sessionId(),
                        message.seq(), true, readObservationResponse(record.responseBody()));
            } else {
                if (session.closed()) {
                    throw ApiException.conflict(
                            "session already closed: " + key.deviceId() + "/" + key.sessionId(), null);
                }
                toApply.add(indexed);
            }
        }

        int totalCount = messageRepository.count(key.deviceId(), key.sessionId()) + toApply.size();
        if (totalCount > MAX_MESSAGES_PER_SESSION) {
            throw ApiException.badRequest("session message limit exceeded: max "
                    + MAX_MESSAGES_PER_SESSION + " messages per session");
        }

        // 第二遍：执行观测业务并写入收件记录；任何失败都回滚整批，不留收件记录、不推进水位
        for (IndexedMessage indexed : toApply) {
            SessionMessageRequest message = indexed.message();
            ObservationResponse observation = switch (message.operation()) {
                case CREATE -> observationService.applyCreate(message.observationId(),
                        message.location(), message.reading(), message.note());
                case MERGE -> observationService.applyMerge(message.observationId(), message.baseVersion(),
                        message.location(), message.reading(), message.note());
            };
            int status = message.operation() == SessionOperation.CREATE ? 201 : 200;
            messageRepository.insert(new SessionMessageRecord(key.deviceId(), key.sessionId(), message.seq(),
                    contentFingerprints.get(indexed.index()), batchRequestId, status, writeJson(observation)));
            receipts[indexed.index()] = new MessageReceipt(key.deviceId(), key.sessionId(),
                    message.seq(), false, observation);
        }

        // 水位重算：从 seq=1 起连续收到的最大序号，洞不能跳过；与收件记录同事务推进
        List<Integer> seqs = messageRepository.listSeqs(key.deviceId(), key.sessionId());
        int watermark = 0;
        for (int seq : seqs) {
            if (seq != watermark + 1) {
                break;
            }
            watermark = seq;
        }
        sessionRepository.updateProgress(key.deviceId(), key.sessionId(), watermark, seqs.size());
        return new SessionStateResponse(key.deviceId(), key.sessionId(), watermark,
                pendingAbove(seqs, watermark), seqs.size(), session.closed(), session.finalSeq());
    }

    /**
     * 加锁读取会话，不存在则创建；并发新建由主键串行化，重读已提交行并加锁，不泄露唯一键异常。
     */
    private DeviceSession lockOrCreateSession(String deviceId, String sessionId) {
        Optional<DeviceSession> existing = sessionRepository.findForUpdate(deviceId, sessionId);
        if (existing.isPresent()) {
            return existing.get();
        }
        try {
            sessionRepository.insert(deviceId, sessionId);
        } catch (DuplicateKeyException e) {
            // 并发新建同一会话：等待对方事务提交后重读加锁
        }
        return sessionRepository.findForUpdate(deviceId, sessionId)
                .orElseThrow(() -> new IllegalStateException(
                        "session missing after insert: " + deviceId + "/" + sessionId));
    }

    /**
     * 校验批内消息：MERGE 必须携带 baseVersion，CREATE 不得携带；批内（设备、会话、序号）不得重复。
     */
    private void validateMessages(List<SessionMessageRequest> messages) {
        Set<List<String>> seen = new HashSet<>();
        for (SessionMessageRequest message : messages) {
            if (message.operation() == SessionOperation.MERGE && message.baseVersion() == null) {
                throw ApiException.badRequest("baseVersion is required for MERGE message at seq " + message.seq());
            }
            if (message.operation() == SessionOperation.CREATE && message.baseVersion() != null) {
                throw ApiException.badRequest("baseVersion must be omitted for CREATE message at seq " + message.seq());
            }
            List<String> key = List.of(message.deviceId(), message.sessionId(), String.valueOf(message.seq()));
            if (!seen.add(key)) {
                throw ApiException.badRequest("duplicate seq in batch: " + message.deviceId() + "/"
                        + message.sessionId() + "#" + message.seq());
            }
        }
    }
    /**
     * 批次请求身份：操作、操作者及全部消息参数的结构化表示（有序 JSON），不使用分隔符拼接。
     */
    private Map<String, Object> batchIdentity(BatchUploadRequest request) {
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("operation", OP_BATCH_UPLOAD);
        identity.put("operator", request.operator());
        List<Map<String, Object>> messages = new ArrayList<>();
        for (SessionMessageRequest message : request.messages()) {
            Map<String, Object> item = new LinkedHashMap<>(messageContent(message));
            item.put("deviceId", message.deviceId());
            item.put("sessionId", message.sessionId());
            item.put("seq", message.seq());
            messages.add(item);
        }
        identity.put("messages", messages);
        return identity;
    }

    /**
     * 关闭请求身份：操作、操作者、资源（设备、会话）及期望末序号的结构化表示。
     */
    private Map<String, Object> closeIdentity(String deviceId, String sessionId, CloseSessionRequest request) {
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("operation", OP_SESSION_CLOSE);
        identity.put("operator", request.operator());
        identity.put("deviceId", deviceId);
        identity.put("sessionId", sessionId);
        identity.put("finalSeq", request.finalSeq());
        return identity;
    }

    /**
     * 单条消息内容身份：观测操作及全部参数（含期望基线版本）的结构化表示，用于同序号异内容判定。
     */
    private Map<String, Object> messageContent(SessionMessageRequest message) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("operation", message.operation() == null ? null : message.operation().name());
        content.put("observationId", message.observationId());
        content.put("baseVersion", message.baseVersion());
        content.put("location", message.location());
        content.put("reading", message.reading());
        content.put("note", message.note());
        return content;
    }

    /**
     * 结构化指纹：将身份对象序列化为有序 JSON 后计算 SHA-256，避免分隔符碰撞。
     */
    private String structuredFingerprint(Object identity) {
        String json = writeJson(identity);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(json.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /**
     * 幂等检查：同键同参返回已存响应体；同键异参 409；无记录返回 null 继续执行。
     */
    private String replayBody(String requestId, String fingerprint) {
        Optional<RequestLogEntry> existing = requestLogRepository.find(requestId);
        if (existing.isEmpty()) {
            return null;
        }
        RequestLogEntry entry = existing.get();
        if (!entry.fingerprint().equals(fingerprint)) {
            throw ApiException.conflict("requestId reused with different parameters: " + requestId, null);
        }
        return entry.responseBody();
    }

    /**
     * 占位写入去重记录；并发同键时主键冲突，等待对方事务结束后读取已提交结果：
     * 同参返回已存响应体，异参抛 409；正常占位返回 null。业务失败时占位随事务回滚，不占键。
     */
    private String placeholderOrReplay(String requestId, String fingerprint, String operation) {
        try {
            requestLogRepository.insertPlaceholder(requestId, fingerprint, operation);
            return null;
        } catch (DuplicateKeyException e) {
            RequestLogEntry entry = requestLogRepository.find(requestId)
                    .orElseThrow(() -> ApiException.conflict("requestId conflict: " + requestId, null));
            if (!entry.fingerprint().equals(fingerprint)) {
                throw ApiException.conflict("requestId reused with different parameters: " + requestId, null);
            }
            return entry.responseBody();
        }
    }

    /**
     * 业务成功后回填去重记录响应，与业务变更同事务提交。
     */
    private void complete(String requestId, int responseStatus, String responseBody) {
        requestLogRepository.complete(requestId, responseStatus, responseBody);
    }

    private SessionStateResponse toStateResponse(DeviceSession session) {
        List<Integer> seqs = messageRepository.listSeqs(session.deviceId(), session.sessionId());
        return new SessionStateResponse(session.deviceId(), session.sessionId(), session.watermark(),
                pendingAbove(seqs, session.watermark()), session.messageCount(),
                session.closed(), session.finalSeq());
    }

    /**
     * 水位以上已收到的序号，按升序排列（输入已按序号升序）。
     */
    private List<Integer> pendingAbove(List<Integer> seqs, int watermark) {
        return seqs.stream().filter(seq -> seq > watermark).toList();
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize json", e);
        }
    }

    private BatchUploadResponse readBatchResponse(String json) {
        try {
            return objectMapper.readValue(json, BatchUploadResponse.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to deserialize stored batch response", e);
        }
    }

    private SessionStateResponse readStateResponse(String json) {
        try {
            return objectMapper.readValue(json, SessionStateResponse.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to deserialize stored session state", e);
        }
    }

    private ObservationResponse readObservationResponse(String json) {
        try {
            return objectMapper.readValue(json, ObservationResponse.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to deserialize stored observation response", e);
        }
    }

    /**
     * 会话键：设备标识 + 会话标识。
     */
    private record SessionKey(String deviceId, String sessionId) {
    }

    /**
     * 带批内序号位置的消息，用于按请求顺序组装回执。
     */
    private record IndexedMessage(int index, SessionMessageRequest message) {
    }

    /**
     * 批次上传结果：HTTP 状态码与响应体。
     */
    public record BatchOutcome(int status, BatchUploadResponse body) {
    }

    /**
     * 关闭会话结果：HTTP 状态码与响应体。
     */
    public record CloseOutcome(int status, SessionStateResponse body) {
    }
}