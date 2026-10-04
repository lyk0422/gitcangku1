package com.example.starter.observation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 设备会话序列确认水位服务。
 *
 * <p>批次上传在单事务内完成：先校验批内重复并做批次级幂等判定（占位写入回执），
 * 再按固定顺序锁定涉及会话行，逐消息分类为同内容重放或新消息，新消息复用现有观测创建/合并业务，
 * 全部成功后写入收件记录并在同事务内推进水位；任何失败整体回滚，不留收件记录、不推进水位、不占幂等键。
 *
 * <p>水位定义为从 seq=1 起连续收到的最大序号，初值 0，洞不能跳过；会话最多 100 条收件记录。
 * 会话由设备显式关闭，仅当水位等于声明末序号时成功；关闭后新消息 409，
 * 已成功旧键仍重放首次保存的原始响应（含当时水位）。
 *
 * <p>幂等指纹由操作、操作者、资源及全部参数（含期望版本）构造结构化 JSON 后取 SHA-256，
 * 不依赖分隔符拼接，避免分隔符碰撞。仅管理合成会话，不对接真实终端。
 */
@Service
public class DeviceSessionService {

    private static final int MAX_MESSAGES_PER_SESSION = 100;
    private static final String OP_BATCH_UPLOAD = "BATCH_UPLOAD";
    private static final String OP_SESSION_CLOSE = "SESSION_CLOSE";

    private final DeviceSessionRepository sessionRepository;
    private final SessionReceiptRepository receiptRepository;
    private final ObservationService observationService;
    private final ObjectMapper objectMapper;

    public DeviceSessionService(DeviceSessionRepository sessionRepository,
                                SessionReceiptRepository receiptRepository,
                                ObservationService observationService,
                                ObjectMapper objectMapper) {
        this.sessionRepository = sessionRepository;
        this.receiptRepository = receiptRepository;
        this.observationService = observationService;
        this.objectMapper = objectMapper;
    }

    /**
     * 上传观测批次：整批原子保存并推进涉及会话的水位。
     */
    @Transactional
    public BatchResponse uploadBatch(UploadBatchRequest request) {
        validateBatchShape(request);
        String fingerprint = batchFingerprint(request);
        BatchResponse replayed = checkReplay(request.requestId(), fingerprint, BatchResponse.class);
        if (replayed != null) {
            return replayed;
        }
        BatchResponse concurrent = insertPlaceholder(request.requestId(), fingerprint,
                OP_BATCH_UPLOAD, BatchResponse.class);
        if (concurrent != null) {
            return concurrent;
        }

        // 按固定顺序锁定涉及会话（不存在则创建），避免并发批次间加锁顺序不一致
        List<SessionKey> keys = request.messages().stream()
                .map(message -> new SessionKey(message.deviceId(), message.sessionId()))
                .distinct().sorted().toList();
        Map<SessionKey, DeviceSession> sessions = new LinkedHashMap<>();
        Map<SessionKey, Map<Integer, InboxMessage>> inboxBySession = new HashMap<>();
        for (SessionKey key : keys) {
            sessions.put(key, lockOrCreateSession(key));
            Map<Integer, InboxMessage> inbox = new HashMap<>();
            for (InboxMessage message : sessionRepository.findInbox(key.deviceId(), key.sessionId())) {
                inbox.put(message.seq(), message);
            }
            inboxBySession.put(key, inbox);
        }

        // 逐消息分类：命中既有收件记录（同内容重放、异内容 409）或新消息（关闭会话拒绝新消息）
        List<PlannedMessage> plan = new ArrayList<>();
        Map<SessionKey, Integer> newCounts = new HashMap<>();
        for (SessionMessage message : request.messages()) {
            SessionKey key = new SessionKey(message.deviceId(), message.sessionId());
            String contentFingerprint = messageFingerprint(message.observation());
            InboxMessage existing = inboxBySession.get(key).get(message.seq());
            if (existing != null) {
                if (!existing.contentFingerprint().equals(contentFingerprint)) {
                    throw ApiException.conflict("seq already received with different content: "
                            + key.deviceId() + "/" + key.sessionId() + "#" + message.seq(), null);
                }
                plan.add(PlannedMessage.replay(message, existing));
                continue;
            }
            if (sessions.get(key).closed()) {
                throw ApiException.conflict(
                        "session already closed: " + key.deviceId() + "/" + key.sessionId(), null);
            }
            plan.add(PlannedMessage.fresh(message, contentFingerprint));
            newCounts.merge(key, 1, Integer::sum);
        }
        for (Map.Entry<SessionKey, Integer> entry : newCounts.entrySet()) {
            DeviceSession session = sessions.get(entry.getKey());
            if (session.messageCount() + entry.getValue() > MAX_MESSAGES_PER_SESSION) {
                throw ApiException.conflict("session message limit exceeded: " + entry.getKey().deviceId()
                        + "/" + entry.getKey().sessionId() + " (max " + MAX_MESSAGES_PER_SESSION + ")", null);
            }
        }

        // 按请求顺序执行观测业务并写入收件记录；任何失败整体回滚
        List<MessageResult> results = new ArrayList<>();
        for (PlannedMessage planned : plan) {
            SessionMessage message = planned.message();
            if (planned.replayOf() != null) {
                InboxMessage inbox = planned.replayOf();
                results.add(new MessageResult(message.deviceId(), message.sessionId(), message.seq(),
                        inbox.messageStatus(), true, readJson(inbox.messageBody(), ObservationResponse.class)));
                continue;
            }
            ObservationService.WriteOutcome outcome = applyCommand(message.observation());
            sessionRepository.insertInbox(message.deviceId(), message.sessionId(), message.seq(),
                    planned.contentFingerprint(), request.requestId(),
                    outcome.status(), writeJson(outcome.body()));
            results.add(new MessageResult(message.deviceId(), message.sessionId(), message.seq(),
                    outcome.status(), false, outcome.body()));
        }

        // 同事务内推进各会话水位并累加收件计数
        List<SessionStateView> sessionViews = new ArrayList<>();
        for (Map.Entry<SessionKey, DeviceSession> entry : sessions.entrySet()) {
            SessionKey key = entry.getKey();
            DeviceSession session = entry.getValue();
            int messageCount = session.messageCount() + newCounts.getOrDefault(key, 0);
            int watermark = advanceWatermark(key, session.watermark());
            sessionRepository.advanceWatermark(key.deviceId(), key.sessionId(), watermark, messageCount);
            sessionViews.add(new SessionStateView(key.deviceId(), key.sessionId(), watermark,
                    sessionRepository.findPendingSeqs(key.deviceId(), key.sessionId(), watermark),
                    session.closed()));
        }

        BatchResponse response = new BatchResponse(request.requestId(), results, sessionViews);
        receiptRepository.complete(request.requestId(), HttpStatus.OK.value(), writeJson(response));
        return response;
    }
    /**
     * 设备显式关闭会话：仅当当前水位等于声明末序号时成功；重复关闭或水位不符均 409。
     */
    @Transactional
    public SessionStateView closeSession(CloseSessionRequest request) {
        String fingerprint = closeFingerprint(request);
        SessionStateView replayed = checkReplay(request.requestId(), fingerprint, SessionStateView.class);
        if (replayed != null) {
            return replayed;
        }
        SessionStateView concurrent = insertPlaceholder(request.requestId(), fingerprint,
                OP_SESSION_CLOSE, SessionStateView.class);
        if (concurrent != null) {
            return concurrent;
        }

        DeviceSession session = sessionRepository.findForUpdate(request.deviceId(), request.sessionId())
                .orElseThrow(() -> ApiException.notFound(
                        "session not found: " + request.deviceId() + "/" + request.sessionId()));
        if (session.closed()) {
            throw ApiException.conflict(
                    "session already closed: " + request.deviceId() + "/" + request.sessionId(), null);
        }
        if (session.watermark() != request.declaredLastSeq()) {
            throw ApiException.conflict("declaredLastSeq does not match watermark: declared "
                    + request.declaredLastSeq() + ", watermark " + session.watermark(), null);
        }
        sessionRepository.markClosed(request.deviceId(), request.sessionId(), request.declaredLastSeq());
        SessionStateView view = new SessionStateView(request.deviceId(), request.sessionId(),
                session.watermark(),
                sessionRepository.findPendingSeqs(request.deviceId(), request.sessionId(), session.watermark()),
                true);
        receiptRepository.complete(request.requestId(), HttpStatus.OK.value(), writeJson(view));
        return view;
    }

    /**
     * 查询会话水位及水位以上已收到序号（升序）；会话不存在返回 404。
     */
    @Transactional(readOnly = true)
    public SessionStateView getSession(String deviceId, String sessionId) {
        DeviceSession session = sessionRepository.find(deviceId, sessionId)
                .orElseThrow(() -> ApiException.notFound("session not found: " + deviceId + "/" + sessionId));
        return new SessionStateView(deviceId, sessionId, session.watermark(),
                sessionRepository.findPendingSeqs(deviceId, sessionId, session.watermark()), session.closed());
    }

    /**
     * 批次形状校验：同一设备会话内 seq 不得重复（400），观测请求标识不得重复，命令必填字段齐全。
     */
    private void validateBatchShape(UploadBatchRequest request) {
        Set<String> seenSeqs = new HashSet<>();
        Set<String> seenRequestIds = new HashSet<>();
        for (SessionMessage message : request.messages()) {
            String seqKey = message.deviceId() + "" + message.sessionId() + "" + message.seq();
            if (!seenSeqs.add(seqKey)) {
                throw ApiException.badRequest("duplicate seq in batch: " + message.deviceId() + "/"
                        + message.sessionId() + "#" + message.seq());
            }
            if (!seenRequestIds.add(message.observation().requestId())) {
                throw ApiException.badRequest(
                        "duplicate observation requestId in batch: " + message.observation().requestId());
            }
            validateCommand(message.observation());
        }
    }

    /**
     * 观测命令必填字段校验：location/reading/note 均必填，MERGE 另需 baseVersion。
     */
    private void validateCommand(ObservationCommand command) {
        if (command.location() == null || command.reading() == null || command.note() == null) {
            throw ApiException.badRequest(
                    "observation command requires location/reading/note: " + command.type());
        }
        if (command.type() == CommandType.MERGE && command.baseVersion() == null) {
            throw ApiException.badRequest("MERGE command requires baseVersion");
        }
    }

    /**
     * 将批次消息中的观测命令转换为现有创建/合并业务调用（加入当前事务）。
     */
    private ObservationService.WriteOutcome applyCommand(ObservationCommand command) {
        return switch (command.type()) {
            case CREATE -> observationService.create(new CreateObservationRequest(command.requestId(),
                    command.observationId(), command.location(), command.reading(), command.note()));
            case MERGE -> observationService.merge(command.observationId(), new MergeObservationRequest(
                    command.requestId(), command.baseVersion(), command.location(), command.reading(),
                    command.note()));
        };
    }

    /**
     * 锁定会话行；不存在则创建（并发首建由主键串行化，冲突后重读并加锁）。
     */
    private DeviceSession lockOrCreateSession(SessionKey key) {
        Optional<DeviceSession> existing = sessionRepository.findForUpdate(key.deviceId(), key.sessionId());
        if (existing.isPresent()) {
            return existing.get();
        }
        try {
            sessionRepository.insertSession(key.deviceId(), key.sessionId());
        } catch (DuplicateKeyException e) {
            return sessionRepository.findForUpdate(key.deviceId(), key.sessionId())
                    .orElseThrow(() -> new IllegalStateException("session missing after insert conflict"));
        }
        return new DeviceSession(key.deviceId(), key.sessionId(), 0, 0, false, null);
    }

    /**
     * 从当前水位起连续推进：水位以上已收到序号中连续段的最大值，洞不能跳过。
     */
    private int advanceWatermark(SessionKey key, int watermark) {
        int current = watermark;
        for (int seq : sessionRepository.findPendingSeqs(key.deviceId(), key.sessionId(), watermark)) {
            if (seq != current + 1) {
                break;
            }
            current = seq;
        }
        return current;
    }
    /**
     * 幂等检查：同键同参返回原成功结果；同键异参返回 409；无记录返回 null 继续执行。
     */
    private <T> T checkReplay(String requestId, String fingerprint, Class<T> type) {
        Optional<SessionReceipt> existing = receiptRepository.find(requestId);
        if (existing.isEmpty()) {
            return null;
        }
        SessionReceipt receipt = existing.get();
        if (!receipt.fingerprint().equals(fingerprint)) {
            throw ApiException.conflict("requestId reused with different parameters: " + requestId, null);
        }
        return readJson(receipt.responseBody(), type);
    }

    /**
     * 占位写入回执；并发同键时主键冲突，等待对方事务结束后读取已提交结果：
     * 同参返回重放结果，异参抛 409；正常占位返回 null。业务失败时占位随事务回滚，不占键。
     */
    private <T> T insertPlaceholder(String requestId, String fingerprint, String operation, Class<T> type) {
        try {
            receiptRepository.insertPlaceholder(requestId, fingerprint, operation);
            return null;
        } catch (DuplicateKeyException e) {
            SessionReceipt receipt = receiptRepository.find(requestId)
                    .orElseThrow(() -> ApiException.conflict("requestId conflict: " + requestId, null));
            if (!receipt.fingerprint().equals(fingerprint)) {
                throw ApiException.conflict("requestId reused with different parameters: " + requestId, null);
            }
            return readJson(receipt.responseBody(), type);
        }
    }

    /**
     * 批次指纹：操作、操作者与全部消息参数（含合并期望版本）构造结构化 JSON 后取 SHA-256。
     */
    private String batchFingerprint(UploadBatchRequest request) {
        Map<String, Object> tree = new LinkedHashMap<>();
        tree.put("operation", OP_BATCH_UPLOAD);
        tree.put("operator", request.operator());
        List<Map<String, Object>> messages = new ArrayList<>();
        for (SessionMessage message : request.messages()) {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("deviceId", message.deviceId());
            node.put("sessionId", message.sessionId());
            node.put("seq", message.seq());
            node.put("observation", commandNode(message.observation()));
            messages.add(node);
        }
        tree.put("messages", messages);
        return sha256(writeJson(tree));
    }

    /**
     * 关闭指纹：操作、操作者、设备会话资源及声明末序号构造结构化 JSON 后取 SHA-256。
     */
    private String closeFingerprint(CloseSessionRequest request) {
        Map<String, Object> tree = new LinkedHashMap<>();
        tree.put("operation", OP_SESSION_CLOSE);
        tree.put("operator", request.operator());
        tree.put("deviceId", request.deviceId());
        tree.put("sessionId", request.sessionId());
        tree.put("declaredLastSeq", request.declaredLastSeq());
        return sha256(writeJson(tree));
    }

    /**
     * 单条消息内容指纹：观测命令全部参数的结构化 JSON 取 SHA-256，用于同序号异内容判定。
     */
    private String messageFingerprint(ObservationCommand command) {
        return sha256(writeJson(commandNode(command)));
    }

    /**
     * 将观测命令转为结构化节点：JSON 转义天然避免分隔符碰撞，null 字段显式保留。
     */
    private Map<String, Object> commandNode(ObservationCommand command) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("type", command.type().name());
        node.put("requestId", command.requestId());
        node.put("observationId", command.observationId());
        node.put("location", command.location());
        node.put("reading", command.reading());
        node.put("note", command.note());
        node.put("baseVersion", command.baseVersion());
        return node;
    }

    private String sha256(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize", e);
        }
    }

    private <T> T readJson(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to deserialize stored response", e);
        }
    }

    /**
     * 设备会话键：按设备、会话字典序排序，保证并发批次加锁顺序一致。
     */
    private record SessionKey(String deviceId, String sessionId) implements Comparable<SessionKey> {
        @Override
        public int compareTo(SessionKey other) {
            int byDevice = deviceId.compareTo(other.deviceId);
            return byDevice != 0 ? byDevice : sessionId.compareTo(other.sessionId);
        }
    }

    /**
     * 消息处理计划：新消息（带内容指纹）或命中既有收件记录的重放。
     */
    private record PlannedMessage(SessionMessage message, String contentFingerprint, InboxMessage replayOf) {
        static PlannedMessage fresh(SessionMessage message, String contentFingerprint) {
            return new PlannedMessage(message, contentFingerprint, null);
        }

        static PlannedMessage replay(SessionMessage message, InboxMessage existing) {
            return new PlannedMessage(message, null, existing);
        }
    }
}