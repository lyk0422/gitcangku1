package com.example.starter.session;

import com.example.starter.observation.ApiException;
import com.example.starter.observation.ObservationResponse;
import com.example.starter.observation.ObservationService;
import com.example.starter.observation.RequestLogEntry;
import com.example.starter.observation.RequestLogRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 设备会话序列确认水位服务。
 *
 * <p>上传批次在单事务内处理：先占位写入幂等去重记录，再对 device_session 行加锁
 * （SELECT ... FOR UPDATE）完成收件判定、观测业务执行与水位推进，最后回填去重记录响应并整体提交；
 * 任何业务失败（含观测版本冲突）都回滚整批，不留收件记录、不推进水位、去重不占键。
 *
 * <p>水位规则：从 seq=1 起连续收到的最大序号为水位，初值 0，洞不能跳过；
 * 同 seq 同内容重放返回既有收件记录，不重复改变观测；同 seq 异内容 409。
 * 幂等指纹为结构化 JSON 的 SHA-256，身份含操作、操作者、资源及全部参数，避免分隔符碰撞。
 */
@Service
public class DeviceSessionService {

    private final DeviceSessionRepository sessionRepository;
    private final SessionMessageRepository messageRepository;
    private final RequestLogRepository requestLogRepository;
    private final ObservationService observationService;
    private final ObjectMapper objectMapper;

    public DeviceSessionService(DeviceSessionRepository sessionRepository,
                                SessionMessageRepository messageRepository,
                                RequestLogRepository requestLogRepository,
                                ObservationService observationService,
                                ObjectMapper objectMapper) {
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
        this.requestLogRepository = requestLogRepository;
        this.observationService = observationService;
        this.objectMapper = objectMapper;
    }

    /**
     * 上传消息批次：1 至 20 条有序消息，全部观测业务成功才整批保存并推进水位。
     */
    @Transactional
    public UploadBatchResponse uploadBatch(UploadBatchRequest request) {
        validateNoDuplicateSeq(request);
        String fingerprint = fingerprint("UPLOAD_BATCH", batchIdentity(request));
        UploadBatchResponse replayed = checkReplay(request.requestId(), fingerprint, UploadBatchResponse.class);
        if (replayed != null) {
            return replayed;
        }
        UploadBatchResponse concurrent = insertPlaceholder(
                request.requestId(), fingerprint, "UPLOAD_BATCH", UploadBatchResponse.class);
        if (concurrent != null) {
            return concurrent;
        }

        DeviceSession session = lockOrCreateSession(request.deviceId(), request.sessionId());
        if (session.closed()) {
            throw ApiException.conflict("session already closed: "
                    + request.deviceId() + "/" + request.sessionId(), null);
        }

        List<MessageReceipt> receipts = new ArrayList<>();
        for (BatchMessage message : request.messages()) {
            receipts.add(processMessage(request.deviceId(), request.sessionId(), message));
        }

        int watermark = advanceWatermark(session);
        List<Integer> above = messageRepository.findSeqsAbove(request.deviceId(), request.sessionId(), watermark);
        UploadBatchResponse response = new UploadBatchResponse(
                request.deviceId(), request.sessionId(), watermark, above, receipts);
        return complete(request.requestId(), response);
    }

    /**
     * 查询会话状态：返回水位及水位以上已收到序号（升序）；会话不存在返回 404。
     */
    @Transactional(readOnly = true)
    public SessionStateResponse getSession(String deviceId, String sessionId) {
        DeviceSession session = sessionRepository.find(deviceId, sessionId)
                .orElseThrow(() -> ApiException.notFound("session not found: " + deviceId + "/" + sessionId));
        return stateOf(session);
    }

    /**
     * 设备显式关闭会话：仅当水位等于声明末序号时成功；关闭后新消息 409。
     */
    @Transactional
    public SessionStateResponse closeSession(String deviceId, String sessionId, CloseSessionRequest request) {
        String fingerprint = fingerprint("CLOSE_SESSION", closeIdentity(deviceId, sessionId, request));
        SessionStateResponse replayed = checkReplay(request.requestId(), fingerprint, SessionStateResponse.class);
        if (replayed != null) {
            return replayed;
        }
        SessionStateResponse concurrent = insertPlaceholder(
                request.requestId(), fingerprint, "CLOSE_SESSION", SessionStateResponse.class);
        if (concurrent != null) {
            return concurrent;
        }

        DeviceSession session = sessionRepository.findForUpdate(deviceId, sessionId)
                .orElseThrow(() -> ApiException.notFound("session not found: " + deviceId + "/" + sessionId));
        if (session.closed()) {
            if (Objects.equals(session.declaredLastSeq(), request.lastSeq())) {
                // 同声明末序号的重复关闭：按当前状态幂等返回
                return complete(request.requestId(), stateOf(session));
            }
            throw ApiException.conflict("session already closed with different last seq: "
                    + deviceId + "/" + sessionId, null);
        }
        if (session.watermark() != request.lastSeq()) {
            throw ApiException.conflict("watermark " + session.watermark()
                    + " does not match declared last seq " + request.lastSeq(), null);
        }
        sessionRepository.markClosed(deviceId, sessionId, request.lastSeq());
        return complete(request.requestId(), new SessionStateResponse(deviceId, sessionId,
                session.watermark(), true,
                messageRepository.findSeqsAbove(deviceId, sessionId, session.watermark())));
    }
    /**
     * 处理单条消息：同 seq 同内容返回既有收件记录（不重复改变观测），异内容 409；
     * 新消息执行观测命令并写入收件记录。
     */
    private MessageReceipt processMessage(String deviceId, String sessionId, BatchMessage message) {
        String contentFingerprint = fingerprint("MESSAGE", message.command());
        Optional<SessionMessage> existing = messageRepository.find(deviceId, sessionId, message.seq());
        if (existing.isPresent()) {
            SessionMessage stored = existing.get();
            if (!stored.contentFingerprint().equals(contentFingerprint)) {
                throw ApiException.conflict("seq " + message.seq()
                        + " already received with different content", null);
            }
            return new MessageReceipt(message.seq(), true, stored.responseStatus(),
                    readJson(stored.responseBody(), ObservationResponse.class));
        }
        ObservationService.WriteOutcome outcome = switch (message.command()) {
            case CreateObservationCommand create -> observationService.create(create.request());
            case MergeObservationCommand merge -> observationService.merge(merge.observationId(), merge.request());
        };
        messageRepository.insert(new SessionMessage(deviceId, sessionId, message.seq(), contentFingerprint,
                observationRequestId(message.command()), outcome.status(), writeJson(outcome.body())));
        return new MessageReceipt(message.seq(), false, outcome.status(), outcome.body());
    }

    /**
     * 从当前水位起连续推进：洞不能跳过；水位变化时更新会话行。
     */
    private int advanceWatermark(DeviceSession session) {
        int watermark = session.watermark();
        while (messageRepository.exists(session.deviceId(), session.sessionId(), watermark + 1)) {
            watermark++;
        }
        if (watermark != session.watermark()) {
            sessionRepository.updateWatermark(session.deviceId(), session.sessionId(), watermark);
        }
        return watermark;
    }

    /**
     * 加锁读取会话；不存在时创建（水位 0、未关闭），并发首建由主键串行化后读对方已提交的行。
     */
    private DeviceSession lockOrCreateSession(String deviceId, String sessionId) {
        Optional<DeviceSession> existing = sessionRepository.findForUpdate(deviceId, sessionId);
        if (existing.isPresent()) {
            return existing.get();
        }
        try {
            sessionRepository.insert(deviceId, sessionId);
        } catch (DuplicateKeyException e) {
            // 并发首建同一会话：主键冲突后读取先到者已提交的行
        }
        return sessionRepository.findForUpdate(deviceId, sessionId)
                .orElseThrow(() -> new IllegalStateException(
                        "session not found after insert: " + deviceId + "/" + sessionId));
    }

    /**
     * 批内 seq 重复直接 400。
     */
    private void validateNoDuplicateSeq(UploadBatchRequest request) {
        Set<Integer> seen = new HashSet<>();
        for (BatchMessage message : request.messages()) {
            if (!seen.add(message.seq())) {
                throw ApiException.badRequest("duplicate seq in batch: " + message.seq());
            }
        }
    }

    private SessionStateResponse stateOf(DeviceSession session) {
        return new SessionStateResponse(session.deviceId(), session.sessionId(), session.watermark(),
                session.closed(),
                messageRepository.findSeqsAbove(session.deviceId(), session.sessionId(), session.watermark()));
    }

    private String observationRequestId(ObservationCommand command) {
        return switch (command) {
            case CreateObservationCommand create -> create.request().requestId();
            case MergeObservationCommand merge -> merge.request().requestId();
        };
    }

    /**
     * 批次幂等身份：操作、操作者、资源（设备/会话）及全部消息参数。
     */
    private Map<String, Object> batchIdentity(UploadBatchRequest request) {
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("operator", request.operator());
        identity.put("deviceId", request.deviceId());
        identity.put("sessionId", request.sessionId());
        identity.put("messages", request.messages());
        return identity;
    }

    /**
     * 关闭会话幂等身份：资源（设备/会话）与声明末序号。
     */
    private Map<String, Object> closeIdentity(String deviceId, String sessionId, CloseSessionRequest request) {
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("deviceId", deviceId);
        identity.put("sessionId", sessionId);
        identity.put("lastSeq", request.lastSeq());
        return identity;
    }

    /**
     * 幂等检查：同键同参返回原成功结果；同键异参返回 409；无记录返回 null 继续执行。
     */
    private <T> T checkReplay(String requestId, String fingerprint, Class<T> type) {
        Optional<RequestLogEntry> existing = requestLogRepository.find(requestId);
        if (existing.isEmpty()) {
            return null;
        }
        RequestLogEntry entry = existing.get();
        if (!entry.fingerprint().equals(fingerprint)) {
            throw ApiException.conflict("requestId reused with different parameters: " + requestId, null);
        }
        return readJson(entry.responseBody(), type);
    }

    /**
     * 占位写入去重记录；并发同键时主键冲突，读取对方已提交结果：同参重放、异参 409。
     * 业务失败时占位随事务回滚，不占键。
     */
    private <T> T insertPlaceholder(String requestId, String fingerprint, String operation, Class<T> type) {
        try {
            requestLogRepository.insertPlaceholder(requestId, fingerprint, operation);
            return null;
        } catch (DuplicateKeyException e) {
            RequestLogEntry entry = requestLogRepository.find(requestId)
                    .orElseThrow(() -> ApiException.conflict("requestId conflict: " + requestId, null));
            if (!entry.fingerprint().equals(fingerprint)) {
                throw ApiException.conflict("requestId reused with different parameters: " + requestId, null);
            }
            return readJson(entry.responseBody(), type);
        }
    }

    /**
     * 业务成功后回填去重记录响应（HTTP 200），与业务变更同事务提交。
     */
    private <T> T complete(String requestId, T response) {
        requestLogRepository.complete(requestId, 200, writeJson(response));
        return response;
    }

    /**
     * 结构化指纹：操作名与身份对象序列化为 JSON 后取 SHA-256，避免分隔符碰撞。
     */
    private String fingerprint(String operation, Object identity) {
        Map<String, Object> structured = new LinkedHashMap<>();
        structured.put("operation", operation);
        structured.put("identity", identity);
        try {
            String json = objectMapper.writeValueAsString(structured);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(json.getBytes(StandardCharsets.UTF_8)));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize fingerprint identity", e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize response", e);
        }
    }

    private <T> T readJson(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to deserialize stored response", e);
        }
    }
}