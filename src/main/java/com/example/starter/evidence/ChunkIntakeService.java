package com.example.starter.evidence;

import com.example.starter.error.ApiException;
import com.example.starter.evidence.dto.ChunkAppendRequest;
import com.example.starter.evidence.dto.ChunkCommandRequest;
import com.example.starter.evidence.dto.ChunkIntakeCreateRequest;
import com.example.starter.evidence.dto.ChunkIntakeManifestView;
import com.example.starter.evidence.dto.ChunkIntakeView;
import com.example.starter.evidence.dto.EvidenceBytesView;
import com.example.starter.evidence.dto.EvidenceView;
import com.example.starter.evidence.dto.IntakeChunkView;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 分块入库核心服务：数字证物先接齐字节再进入封存链。
 * 补块、完成、放弃在单事务内先锁定待接收记录行（SELECT ... FOR UPDATE）串行校验；
 * 权限校验（仅入库人）先于幂等重放；块写入后不可改，完成后块清单只读。
 */
@Service
public class ChunkIntakeService {

    static final String OP_CHUNK_INTAKE_CREATE = "CHUNK_INTAKE_CREATE";
    static final String OP_CHUNK_APPEND = "CHUNK_APPEND";
    static final String OP_CHUNK_COMPLETE = "CHUNK_COMPLETE";
    static final String OP_CHUNK_ABANDON = "CHUNK_ABANDON";

    /**
     * 单块解码后最大字节数。
     */
    static final int MAX_CHUNK_BYTES = 4096;

    private final ChunkIntakeRepository intakeRepository;
    private final IntakeChunkRepository chunkRepository;
    private final EvidenceRepository evidenceRepository;
    private final CommandLogRepository commandLogRepository;
    private final ObjectMapper objectMapper;

    public ChunkIntakeService(ChunkIntakeRepository intakeRepository,
                              IntakeChunkRepository chunkRepository,
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
     * 创建待接收记录：固定总字节数与完整 SHA-256，入库人为操作人。
     */
    @Transactional
    public StoredResponse create(String actorId, ChunkIntakeCreateRequest request, String requestHash) {
        Optional<StoredResponse> replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        if (intakeRepository.findByKey(request.intakeKey()).isPresent()) {
            throw ApiException.conflict("分块入库记录已存在: " + request.intakeKey());
        }
        if (evidenceRepository.findByKey(request.evidenceKey()).isPresent()) {
            throw ApiException.conflict("证物已存在: " + request.evidenceKey());
        }
        if (intakeRepository.findReceivingByEvidenceKey(request.evidenceKey()).isPresent()) {
            throw ApiException.conflict("该证物键存在接收中的分块入库: " + request.evidenceKey());
        }
        LocalDateTime now = LocalDateTime.now();
        String sha256 = request.sha256().toLowerCase(Locale.ROOT);
        intakeRepository.insert(request.intakeKey(), request.evidenceKey(), request.caseKey(),
                request.category(), request.sealNo(), request.totalBytes(), sha256, actorId, now);
        ChunkIntake intake = intakeRepository.findByKey(request.intakeKey()).orElseThrow();
        return record(request.commandKey(), OP_CHUNK_INTAKE_CREATE, actorId, requestHash,
                201, toView(intake, 0));
    }

    /**
     * 追加块：仅入库人（权限先于幂等重放）；可乱序；完全相同范围同内容返回已有块，其余重叠 409。
     * 块身份比较基于解码后的字节，等价 Base64 编码不形成不同内容。
     */
    @Transactional
    public StoredResponse appendChunk(String actorId, String intakeKey,
                                      ChunkAppendRequest request, String requestHash) {
        ChunkIntake intake = lockIntake(intakeKey);
        requireCreator(intake, actorId);
        // 持锁后复查幂等日志，重放先提交事务的首次结果。
        Optional<StoredResponse> replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        requireReceiving(intake);
        requireVersion(intake, request.expectedVersion());
        byte[] data = decode(request.dataBase64());
        if (data.length < 1 || data.length > MAX_CHUNK_BYTES) {
            throw ApiException.badRequest("块解码后长度须在 1 至 " + MAX_CHUNK_BYTES + " 字节之间");
        }
        long endExclusive = (long) request.offset() + data.length;
        if (endExclusive > intake.totalBytes()) {
            throw ApiException.badRequest("块范围越过声明的总长度: " + intakeKey);
        }
        List<IntakeChunk> overlapping = chunkRepository.findOverlapping(
                intakeKey, request.offset(), (int) endExclusive);
        if (!overlapping.isEmpty()) {
            IntakeChunk identical = findIdentical(overlapping, request.offset(), data);
            if (identical != null) {
                return record(request.commandKey(), OP_CHUNK_APPEND, actorId, requestHash,
                        200, toView(identical));
            }
            throw ApiException.conflict("块范围与已有块重叠: " + intakeKey);
        }
        LocalDateTime now = LocalDateTime.now();
        chunkRepository.insert(intakeKey, request.offset(), data.length, data, now);
        intakeRepository.bumpVersion(intake.id());
        IntakeChunk saved = chunkRepository.findByIntakeKey(intakeKey).stream()
                .filter(chunk -> chunk.offsetBytes() == request.offset())
                .findFirst().orElseThrow();
        return record(request.commandKey(), OP_CHUNK_APPEND, actorId, requestHash,
                201, toView(saved));
    }

    /**
     * 完成接收：与补块、放弃串行校验；覆盖 [0,总长度) 且完整摘要相等才生成 SEALED 证物，
     * 缺块或摘要错返回 422，不生成证物或保管链首项；成功后块清单只读，新块 409。
     */
    @Transactional
    public StoredResponse complete(String actorId, String intakeKey,
                                   ChunkCommandRequest request, String requestHash) {
        ChunkIntake intake = lockIntake(intakeKey);
        requireCreator(intake, actorId);
        // 持锁后复查幂等日志，重放先提交事务的首次结果。
        Optional<StoredResponse> replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        requireReceiving(intake);
        requireVersion(intake, request.expectedVersion());
        byte[] assembled = assemble(intake, chunkRepository.findByIntakeKey(intakeKey));
        if (!sha256Hex(assembled).equals(intake.sha256())) {
            throw ApiException.unprocessable("完整摘要与声明不一致，可放弃该接收另建新记录: " + intakeKey);
        }
        if (evidenceRepository.findByKey(intake.evidenceKey()).isPresent()) {
            throw ApiException.conflict("证物已存在: " + intake.evidenceKey());
        }
        LocalDateTime now = LocalDateTime.now();
        evidenceRepository.insert(intake.evidenceKey(), intake.caseKey(), intake.category(),
                intake.sealNo(), actorId, now);
        intakeRepository.decide(intake.id(), ChunkIntakeStatus.COMPLETED, now);
        Evidence evidence = evidenceRepository.findByKey(intake.evidenceKey()).orElseThrow();
        return record(request.commandKey(), OP_CHUNK_COMPLETE, actorId, requestHash,
                201, toView(evidence));
    }

    /**
     * 放弃接收：仅入库人；放弃后证物键可由新记录复用，新块 409。
     */
    @Transactional
    public StoredResponse abandon(String actorId, String intakeKey,
                                  ChunkCommandRequest request, String requestHash) {
        ChunkIntake intake = lockIntake(intakeKey);
        requireCreator(intake, actorId);
        // 持锁后复查幂等日志，重放先提交事务的首次结果。
        Optional<StoredResponse> replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        requireReceiving(intake);
        requireVersion(intake, request.expectedVersion());
        intakeRepository.decide(intake.id(), ChunkIntakeStatus.ABANDONED, LocalDateTime.now());
        ChunkIntake updated = intakeRepository.findByKey(intakeKey).orElseThrow();
        return record(request.commandKey(), OP_CHUNK_ABANDON, actorId, requestHash,
                200, toView(updated, receivedBytes(intakeKey)));
    }

    /**
     * 查询只读块清单：待接收记录 + 已接收块（不含载荷）。
     */
    @Transactional(readOnly = true)
    public ChunkIntakeManifestView manifest(String intakeKey) {
        ChunkIntake intake = intakeRepository.findByKey(intakeKey)
                .orElseThrow(() -> ApiException.notFound("分块入库记录不存在: " + intakeKey));
        List<IntakeChunk> chunks = chunkRepository.findByIntakeKey(intakeKey);
        int received = chunks.stream().mapToInt(IntakeChunk::lengthBytes).sum();
        return new ChunkIntakeManifestView(toView(intake, received),
                chunks.stream().map(this::toView).toList());
    }

    /**
     * 读取完整字节：仅当前保管人；借出及待交接期间保管人不变，按原归属限制。
     */
    @Transactional(readOnly = true)
    public EvidenceBytesView readBytes(String actorId, String evidenceKey) {
        Evidence evidence = evidenceRepository.findByKey(evidenceKey)
                .orElseThrow(() -> ApiException.notFound("证物不存在: " + evidenceKey));
        if (!evidence.custodianId().equals(actorId)) {
            throw ApiException.conflict("仅当前保管人可读取完整字节: " + actorId);
        }
        ChunkIntake intake = intakeRepository.findCompletedByEvidenceKey(evidenceKey)
                .orElseThrow(() -> ApiException.notFound("证物无分块入库字节内容: " + evidenceKey));
        byte[] assembled = assemble(intake, chunkRepository.findByIntakeKey(intake.intakeKey()));
        return new EvidenceBytesView(evidenceKey, intake.totalBytes(), intake.sha256(),
                Base64.getEncoder().encodeToString(assembled));
    }

    private ChunkIntake lockIntake(String intakeKey) {
        return intakeRepository.findByKeyForUpdate(intakeKey)
                .orElseThrow(() -> ApiException.notFound("分块入库记录不存在: " + intakeKey));
    }

    private void requireCreator(ChunkIntake intake, String actorId) {
        if (!intake.creatorId().equals(actorId)) {
            throw ApiException.conflict("仅入库人可执行该操作: " + actorId);
        }
    }

    private void requireReceiving(ChunkIntake intake) {
        if (intake.status() != ChunkIntakeStatus.RECEIVING) {
            throw ApiException.conflict("接收已结束，禁止该操作: " + intake.intakeKey());
        }
    }

    private void requireVersion(ChunkIntake intake, int expectedVersion) {
        if (intake.version() != expectedVersion) {
            throw ApiException.conflict("期望版本与当前版本不一致: 期望 " + expectedVersion
                    + " 当前 " + intake.version());
        }
    }

    private Optional<StoredResponse> checkReplay(String commandKey, String requestHash) {
        return commandLogRepository.findByKey(commandKey).map(log -> {
            if (!log.requestHash().equals(requestHash)) {
                throw ApiException.conflict("幂等键已被不同参数使用: " + commandKey);
            }
            return new StoredResponse(log.responseStatus(), log.responseBody());
        });
    }

    private StoredResponse record(String commandKey, String operation, String actorId,
                                  String requestHash, int status, Object body) {
        String json = toJson(body);
        commandLogRepository.insert(commandKey, actorId, operation, requestHash, status, json,
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

    private String sha256Hex(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /**
     * 解码 Base64 块数据：容忍等价编码（缺省填充、含空白），其余非法输入返回 400。
     */
    private byte[] decode(String dataBase64) {
        try {
            return Base64.getMimeDecoder().decode(dataBase64.replaceAll("\\s", ""));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("块数据不是合法的 Base64 编码");
        }
    }

    /**
     * 拼接完整字节：块按偏移升序必须无缝覆盖 [0,总长度)，缺块返回 422。
     */
    private byte[] assemble(ChunkIntake intake, List<IntakeChunk> chunks) {
        byte[] assembled = new byte[intake.totalBytes()];
        int expected = 0;
        for (IntakeChunk chunk : chunks) {
            if (chunk.offsetBytes() != expected) {
                throw ApiException.unprocessable("接收缺块，未覆盖完整范围: " + intake.intakeKey());
            }
            System.arraycopy(chunk.data(), 0, assembled, expected, chunk.lengthBytes());
            expected += chunk.lengthBytes();
        }
        if (expected != intake.totalBytes()) {
            throw ApiException.unprocessable("接收缺块，未覆盖完整范围: " + intake.intakeKey());
        }
        return assembled;
    }

    /**
     * 在重叠块中查找完全相同范围且解码字节相同的块（等价 Base64 编码视为同一内容）。
     */
    private IntakeChunk findIdentical(List<IntakeChunk> overlapping, int offset, byte[] data) {
        return overlapping.stream()
                .filter(chunk -> chunk.offsetBytes() == offset
                        && chunk.lengthBytes() == data.length
                        && Arrays.equals(chunk.data(), data))
                .findFirst().orElse(null);
    }

    private int receivedBytes(String intakeKey) {
        return chunkRepository.findByIntakeKey(intakeKey).stream()
                .mapToInt(IntakeChunk::lengthBytes).sum();
    }

    private ChunkIntakeView toView(ChunkIntake intake, int receivedBytes) {
        return new ChunkIntakeView(intake.intakeKey(), intake.evidenceKey(), intake.creatorId(),
                intake.totalBytes(), intake.sha256(), intake.status().name(), intake.version(),
                receivedBytes, intake.createdAt(), intake.decidedAt());
    }

    private IntakeChunkView toView(IntakeChunk chunk) {
        return new IntakeChunkView(chunk.intakeKey(), chunk.offsetBytes(), chunk.lengthBytes(),
                sha256Hex(chunk.data()), chunk.createdAt());
    }

    private EvidenceView toView(Evidence evidence) {
        return new EvidenceView(evidence.evidenceKey(), evidence.caseKey(), evidence.category(),
                evidence.sealNo(), evidence.custodianId(), evidence.status(),
                evidence.createdAt(), evidence.updatedAt());
    }
}
