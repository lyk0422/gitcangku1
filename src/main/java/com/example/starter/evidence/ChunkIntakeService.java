package com.example.starter.evidence;

import com.example.starter.error.ApiException;
import com.example.starter.evidence.dto.ChunkAbandonRequest;
import com.example.starter.evidence.dto.ChunkCompleteRequest;
import com.example.starter.evidence.dto.ChunkCompletionView;
import com.example.starter.evidence.dto.ChunkIntakeCreateRequest;
import com.example.starter.evidence.dto.ChunkIntakeView;
import com.example.starter.evidence.dto.ChunkUploadRequest;
import com.example.starter.evidence.dto.ChunkUploadView;
import com.example.starter.evidence.dto.ChunkView;
import com.example.starter.evidence.dto.EvidenceView;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * 数字证物分块接收服务。补块、完成、放弃均先锁定接收行（SELECT ... FOR UPDATE）串行校验：
 * 块可乱序到达；完全相同范围同内容返回已有块，其他重叠一律 409；
 * 显式完成必须恰好覆盖 [0,totalLength) 且拼接字节 SHA-256 相符，否则 422 且不产生 SEALED 证物。
 * 业务落库与幂等日志在同一事务提交，失败回滚不占用 requestId。块载荷不写日志。
 */
@Service
public class ChunkIntakeService {

    static final String OP_CREATE = "CHUNK_INTAKE_CREATE";
    static final String OP_UPLOAD = "CHUNK_UPLOAD";
    static final String OP_COMPLETE = "CHUNK_COMPLETE";
    static final String OP_ABANDON = "CHUNK_ABANDON";

    static final int MAX_TOTAL_LENGTH = 65536;
    static final int MAX_CHUNK_LENGTH = 4096;

    private final ChunkIntakeRepository intakeRepository;
    private final EvidenceChunkRepository chunkRepository;
    private final EvidenceRepository evidenceRepository;
    private final CommandLogRepository commandLogRepository;
    private final ObjectMapper objectMapper;

    public ChunkIntakeService(ChunkIntakeRepository intakeRepository,
                              EvidenceChunkRepository chunkRepository,
                              EvidenceRepository evidenceRepository,
                              CommandLogRepository commandLogRepository,
                              ObjectMapper objectMapper) {
        this.intakeRepository = intakeRepository;
        this.chunkRepository = chunkRepository;
        this.evidenceRepository = evidenceRepository;
        this.commandLogRepository = commandLogRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * 创建待接收记录：固定总长度与摘要；键不得与既有接收记录或证物冲突。
     */
    @Transactional
    public StoredResponse create(String actorId, ChunkIntakeCreateRequest request, String hash) {
        Optional<StoredResponse> replay = checkReplay(request.requestId(), hash);
        if (replay.isPresent()) {
            return replay.get();
        }
        if (intakeRepository.findByKey(request.intakeKey()).isPresent()) {
            throw ApiException.conflict("分块接收键已存在: " + request.intakeKey());
        }
        if (evidenceRepository.findByKey(request.intakeKey()).isPresent()) {
            throw ApiException.conflict("证物键已存在: " + request.intakeKey());
        }
        LocalDateTime now = LocalDateTime.now();
        intakeRepository.insert(request.intakeKey(), request.caseKey(), request.category(),
                request.sealNo(), actorId, request.totalLength(), request.sha256(), now);
        ChunkIntake intake = intakeRepository.findByKey(request.intakeKey()).orElseThrow();
        return ok(OP_CREATE, request.requestId(), actorId, hash, 201, toView(intake, List.of()));
    }

    /**
     * 补块：仅入库人；乱序允许；同范围同内容返回已有块，其他重叠（含同范围不同内容）409；完成后新块 409。
     */
    @Transactional
    public StoredResponse uploadChunk(String actorId, String intakeKey,
                                      ChunkUploadRequest request, String hash) {
        ChunkIntake intake = lock(intakeKey);
        // 权限先于重放：非入库人即使携带他人已成功的 requestId 也只能得到 409。
        requireOwner(intake, actorId);
        Optional<StoredResponse> replay = checkReplay(request.requestId(), hash);
        if (replay.isPresent()) {
            return replay.get();
        }
        requireReceiving(intake);
        if (request.expectedVersion() != null && request.expectedVersion() != intake.version()) {
            throw ApiException.conflict("期望版本与当前接收版本不一致: " + intake.version());
        }
        byte[] payload = ChunkEncoding.decode(request.dataBase64());
        if (payload.length < 1 || payload.length > MAX_CHUNK_LENGTH) {
            throw ApiException.badRequest("解码后块长度须为 1.." + MAX_CHUNK_LENGTH + " 字节");
        }
        long end = (long) request.offset() + payload.length;
        if (end > intake.totalLength()) {
            throw ApiException.conflict("块范围越过总长度: [0," + intake.totalLength() + ")");
        }
        List<EvidenceChunk> chunks = chunkRepository.findByIntakeKey(intakeKey);
        Optional<EvidenceChunk> sameRange = chunks.stream()
                .filter(c -> c.offset() == request.offset() && c.length() == payload.length)
                .findFirst();
        if (sameRange.isPresent()) {
            EvidenceChunk existing = sameRange.get();
            if (!java.util.Arrays.equals(existing.payload(), payload)) {
                throw ApiException.conflict("相同范围块内容不同，块写入后不可修改");
            }
            // 完全相同范围同内容（即使换了 requestId）：返回已有块，不新增、不递增版本。
            return ok(OP_UPLOAD, request.requestId(), actorId, hash,
                    new ChunkUploadView(existing.offset(), existing.length(), intake.version()));
        }
        if (chunks.stream().anyMatch(c -> request.offset() < c.offset() + c.length()
                && c.offset() < end)) {
            throw ApiException.conflict("新块与既有块范围重叠");
        }
        LocalDateTime now = LocalDateTime.now();
        chunkRepository.insert(intakeKey, request.offset(), payload.length, payload, now);
        intakeRepository.incrementVersion(intakeKey, intake.version() + 1, now);
        return ok(OP_UPLOAD, request.requestId(), actorId, hash,
                new ChunkUploadView(request.offset(), payload.length, intake.version() + 1));
    }

    /**
     * 显式完成：仅入库人；块必须恰好覆盖 [0,totalLength)（无缺块、无越界）且整体 SHA-256 相等。
     * 任一不满足返回 422，不更新接收状态、不创建 SEALED 证物、不写保管链首项。
     * 成功在同一事务内标记接收 SEALED、创建原 SEALED 证物并记录幂等日志。
     */
    @Transactional
    public StoredResponse complete(String actorId, String intakeKey,
                                   ChunkCompleteRequest request, String hash) {
        ChunkIntake intake = lock(intakeKey);
        requireOwner(intake, actorId);
        Optional<StoredResponse> replay = checkReplay(request.requestId(), hash);
        if (replay.isPresent()) {
            return replay.get();
        }
        requireReceiving(intake);
        if (request.expectedVersion() != null && request.expectedVersion() != intake.version()) {
            throw ApiException.conflict("期望版本与当前接收版本不一致: " + intake.version());
        }
        List<EvidenceChunk> chunks = chunkRepository.findByIntakeKey(intakeKey);
        byte[] assembled = assembleIfFullyCovered(intake, chunks);
        String actual = sha256Hex(assembled);
        if (!actual.equalsIgnoreCase(intake.sha256())) {
            throw ApiException.unprocessable("完整摘要不匹配，可放弃该接收后另建新记录");
        }
        LocalDateTime now = LocalDateTime.now();
        evidenceRepository.insert(intake.intakeKey(), intake.caseKey(), intake.category(),
                intake.sealNo(), intake.custodianId(), now);
        intakeRepository.markSealed(intakeKey, now);
        Evidence evidence = evidenceRepository.findByKey(intakeKey).orElseThrow();
        List<ChunkView> chunkViews = chunks.stream()
                .map(c -> new ChunkView(c.offset(), c.length())).toList();
        return ok(OP_COMPLETE, request.requestId(), actorId, hash, 200,
                new ChunkCompletionView(toView(evidence), chunkViews));
    }

    /**
     * 放弃接收：仅入库人且处于 RECEIVING；已完成（SEALED）不可放弃。放弃后只能另换新键新建接收。
     */
    @Transactional
    public StoredResponse abandon(String actorId, String intakeKey,
                                  ChunkAbandonRequest request, String hash) {
        ChunkIntake intake = lock(intakeKey);
        requireOwner(intake, actorId);
        Optional<StoredResponse> replay = checkReplay(request.requestId(), hash);
        if (replay.isPresent()) {
            return replay.get();
        }
        if (intake.status() == ChunkIntakeStatus.ABANDONED) {
            throw ApiException.conflict("接收已放弃: " + intakeKey);
        }
        if (intake.status() == ChunkIntakeStatus.SEALED) {
            throw ApiException.conflict("接收已完成封存，不可放弃: " + intakeKey);
        }
        LocalDateTime now = LocalDateTime.now();
        intakeRepository.markAbandoned(intakeKey, now);
        ChunkIntake updated = intakeRepository.findByKey(intakeKey).orElseThrow();
        return ok(OP_ABANDON, request.requestId(), actorId, hash, 200,
                toView(updated, chunkRepository.findByIntakeKey(intakeKey)));
    }

    /**
     * 查询接收状态（含已覆盖字节数与版本），不暴露块载荷。
     */
    @Transactional(readOnly = true)
    public ChunkIntakeView status(String intakeKey) {
        ChunkIntake intake = intakeRepository.findByKey(intakeKey)
                .orElseThrow(() -> ApiException.notFound("分块接收不存在: " + intakeKey));
        return toView(intake, chunkRepository.findByIntakeKey(intakeKey));
    }

    /**
     * 读取完整字节：只有当前保管人可获取。借用人不是保管人、待交接接收人尚未成为保管人，
     * 按原归属规则一律拒绝；待接收/已放弃记录尚无证物行，返回 404。
     */
    @Transactional(readOnly = true)
    public byte[] readBytes(String actorId, String evidenceKey) {
        Evidence evidence = evidenceRepository.findByKey(evidenceKey)
                .orElseThrow(() -> ApiException.notFound("证物不存在: " + evidenceKey));
        if (!evidence.custodianId().equals(actorId)) {
            throw ApiException.conflict("仅当前保管人可读取完整字节: " + actorId);
        }
        ChunkIntake intake = intakeRepository.findByKey(evidenceKey)
                .orElseThrow(() -> ApiException.notFound("证物不存在分块字节记录: " + evidenceKey));
        if (intake.status() != ChunkIntakeStatus.SEALED) {
            throw ApiException.notFound("分块接收尚未完成封存: " + evidenceKey);
        }
        List<EvidenceChunk> chunks = chunkRepository.findByIntakeKey(evidenceKey);
        return assembleIfFullyCovered(intake, chunks);
    }

    private ChunkIntake lock(String intakeKey) {
        return intakeRepository.findByKeyForUpdate(intakeKey)
                .orElseThrow(() -> ApiException.notFound("分块接收不存在: " + intakeKey));
    }

    private void requireOwner(ChunkIntake intake, String actorId) {
        if (!intake.custodianId().equals(actorId)) {
            throw ApiException.conflict("仅入库人可操作该接收: " + actorId);
        }
    }

    private void requireReceiving(ChunkIntake intake) {
        if (intake.status() == ChunkIntakeStatus.SEALED) {
            throw ApiException.conflict("接收已完成封存，块清单只读: " + intake.intakeKey());
        }
        if (intake.status() == ChunkIntakeStatus.ABANDONED) {
            throw ApiException.conflict("接收已放弃，不可继续操作: " + intake.intakeKey());
        }
    }

    /**
     * 校验块恰好覆盖 [0,totalLength)：块均在界内且按偏移拼接无缝无叠；否则 422。
     */
    private byte[] assembleIfFullyCovered(ChunkIntake intake, List<EvidenceChunk> chunks) {
        if (chunks.isEmpty() || chunks.get(0).offset() != 0) {
            throw ApiException.unprocessable("块未覆盖完整范围 [0," + intake.totalLength() + ")");
        }
        byte[] assembled = new byte[intake.totalLength()];
        int cursor = 0;
        for (EvidenceChunk chunk : chunks) {
            if (chunk.offset() != cursor) {
                throw ApiException.unprocessable(
                        "块未覆盖完整范围 [0," + intake.totalLength() + ")");
            }
            System.arraycopy(chunk.payload(), 0, assembled, cursor, chunk.length());
            cursor += chunk.length();
        }
        if (cursor != intake.totalLength()) {
            throw ApiException.unprocessable("块未覆盖完整范围 [0," + intake.totalLength() + ")");
        }
        return assembled;
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private Optional<StoredResponse> checkReplay(String requestId, String requestHash) {
        return commandLogRepository.findByKey(requestId).map(log -> {
            if (!log.requestHash().equals(requestHash)) {
                throw ApiException.conflict("requestId 已被不同参数使用: " + requestId);
            }
            return new StoredResponse(log.responseStatus(), log.responseBody());
        });
    }

    private StoredResponse ok(String operation, String requestId, String actorId,
                              String hash, Object body) {
        return ok(operation, requestId, actorId, hash, 200, body);
    }

    private StoredResponse ok(String operation, String requestId, String actorId,
                              String hash, int status, Object body) {
        String json = toJson(body);
        commandLogRepository.insert(requestId, actorId, operation, hash, status, json,
                LocalDateTime.now());
        return new StoredResponse(status, json);
    }

    private String toJson(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new IllegalStateException("响应序列化失败", e);
        }
    }

    private ChunkIntakeView toView(ChunkIntake intake, List<EvidenceChunk> chunks) {
        int receivedLength = coveredLength(chunks);
        return new ChunkIntakeView(intake.intakeKey(), intake.caseKey(), intake.category(),
                intake.sealNo(), intake.custodianId(), intake.totalLength(), intake.sha256(),
                intake.status(), intake.version(), receivedLength,
                intake.createdAt(), intake.updatedAt());
    }

    private EvidenceView toView(Evidence evidence) {
        return new EvidenceView(evidence.evidenceKey(), evidence.caseKey(), evidence.category(),
                evidence.sealNo(), evidence.custodianId(), evidence.status(),
                evidence.createdAt(), evidence.updatedAt());
    }

    /**
     * 已覆盖字节数：按区间并集计数（补块阶段不允许交叠，正常即长度之和），供状态视图展示。
     */
    private int coveredLength(List<EvidenceChunk> chunks) {
        boolean[] covered = new boolean[MAX_TOTAL_LENGTH];
        for (EvidenceChunk chunk : chunks) {
            for (int i = chunk.offset(); i < chunk.offset() + chunk.length() && i < MAX_TOTAL_LENGTH; i++) {
                covered[i] = true;
            }
        }
        int count = 0;
        for (boolean flag : covered) {
            if (flag) {
                count++;
            }
        }
        return count;
    }
}
