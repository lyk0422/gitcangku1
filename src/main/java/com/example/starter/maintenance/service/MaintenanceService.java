package com.example.starter.maintenance.service;

import com.example.starter.maintenance.model.Equipment;
import com.example.starter.maintenance.model.MaintenanceRecord;
import com.example.starter.maintenance.model.Reading;
import com.example.starter.maintenance.model.ReadingRevision;
import com.example.starter.maintenance.repository.EquipmentRepository;
import com.example.starter.maintenance.repository.IdempotencyRepository;
import com.example.starter.maintenance.repository.MaintenanceRepository;
import com.example.starter.maintenance.repository.ReadingRepository;
import com.example.starter.maintenance.web.ApiException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * 设备工时保养判定核心业务：登记、读数、修订、保养、状态与历史。
 * 所有写操作在同一事务内完成幂等占键与业务变更，按设备行锁串行化。
 */
@Service
public class MaintenanceService {

    /**
     * 写操作统一响应：状态码与已序列化响应体，便于幂等重放。
     */
    public record ServiceResponse(int status, String body) {
    }

    public record EquipmentView(String equipmentId, int maintenanceIntervalMinutes, int version) {
    }

    public record ReadingView(String readingId, Instant sampledAt, long accumulatedMinutes, int currentRevision) {
    }

    public record RevisionView(int revisionNo, long accumulatedMinutes, Instant revisedAt) {
    }

    public record MaintenanceView(long maintenanceId, String anchorReadingId, int anchorRevisionNo,
                                  Instant anchorSampledAt, long anchorAccumulatedMinutes, Instant completedAt) {
    }

    public record StatusView(String equipmentId, int maintenanceIntervalMinutes, long runningMinutes,
                             String status, Instant latestReadingAt, Long latestAccumulatedMinutes,
                             Instant lastMaintenanceAnchorAt, Long lastMaintenanceAnchorMinutes) {
    }

    public record ReadingHistoryView(String readingId, Instant sampledAt, long accumulatedMinutes,
                                     int currentRevision, boolean maintenanceAnchor,
                                     List<RevisionView> revisions) {
    }

    public record HistoryView(String equipmentId, int maintenanceIntervalMinutes, int version,
                              List<ReadingHistoryView> readings, List<MaintenanceView> maintenances) {
    }

    private final EquipmentRepository equipmentRepository;
    private final ReadingRepository readingRepository;
    private final MaintenanceRepository maintenanceRepository;
    private final IdempotencyRepository idempotencyRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public MaintenanceService(EquipmentRepository equipmentRepository,
                              ReadingRepository readingRepository,
                              MaintenanceRepository maintenanceRepository,
                              IdempotencyRepository idempotencyRepository,
                              ObjectMapper objectMapper,
                              Clock clock) {
        this.equipmentRepository = equipmentRepository;
        this.readingRepository = readingRepository;
        this.maintenanceRepository = maintenanceRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 登记设备：保养周期为正整数且不可改，初始版本1。
     */
    @Transactional
    public ServiceResponse registerEquipment(String requestId, String equipmentId, int intervalMinutes) {
        String payloadHash = hash("REGISTER_EQUIPMENT", equipmentId, String.valueOf(intervalMinutes));
        Optional<ServiceResponse> replay = findReplay(requestId, "REGISTER_EQUIPMENT", payloadHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        if (equipmentRepository.findById(equipmentId).isPresent()) {
            throw ApiException.conflict("equipment already exists: " + equipmentId);
        }
        equipmentRepository.insert(equipmentId, intervalMinutes, now());
        EquipmentView view = new EquipmentView(equipmentId, intervalMinutes, 1);
        return success(requestId, "REGISTER_EQUIPMENT", payloadHash, HttpStatus.CREATED, view);
    }

    /**
     * 新增工时读数（允许补录历史）：按采样时刻排序后累计分钟须单调不减，同设备同一时刻仅一条。
     */
    @Transactional
    public ServiceResponse addReading(String requestId, String equipmentId, int expectedVersion,
                                      String readingId, Instant sampledAt, long accumulatedMinutes) {
        String payloadHash = hash("ADD_READING", equipmentId, String.valueOf(expectedVersion), readingId,
                sampledAt.toString(), String.valueOf(accumulatedMinutes));
        Optional<ServiceResponse> replay = findReplay(requestId, "ADD_READING", payloadHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        Equipment equipment = lockEquipment(equipmentId);
        // 拿到设备行锁后复查幂等键，关闭并发同键重放的窗口。
        replay = findReplay(requestId, "ADD_READING", payloadHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        checkVersion(equipment, expectedVersion);
        if (readingRepository.findById(equipmentId, readingId).isPresent()) {
            throw ApiException.conflict("reading already exists: " + readingId);
        }
        long sampledAtMs = sampledAt.toEpochMilli();
        if (readingRepository.existsBySampledAt(equipmentId, sampledAtMs)) {
            throw ApiException.unprocessable("another reading exists at the same sampledAt");
        }
        checkMonotonic(equipmentId, sampledAtMs, accumulatedMinutes);
        readingRepository.insert(equipmentId, readingId, sampledAtMs, accumulatedMinutes, now());
        readingRepository.insertRevision(equipmentId, readingId, 1, accumulatedMinutes, now());
        equipmentRepository.incrementVersion(equipmentId);
        ReadingView view = new ReadingView(readingId, sampledAt, accumulatedMinutes, 1);
        return success(requestId, "ADD_READING", payloadHash, HttpStatus.CREATED, view);
    }

    /**
     * 修订读数：只改变累计分钟，保留历史；作为任意历史保养锚点的读数不可修订。
     */
    @Transactional
    public ServiceResponse reviseReading(String requestId, String equipmentId, String readingId,
                                         int expectedVersion, long accumulatedMinutes) {
        String payloadHash = hash("REVISE_READING", equipmentId, readingId,
                String.valueOf(expectedVersion), String.valueOf(accumulatedMinutes));
        Optional<ServiceResponse> replay = findReplay(requestId, "REVISE_READING", payloadHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        Equipment equipment = lockEquipment(equipmentId);
        replay = findReplay(requestId, "REVISE_READING", payloadHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        checkVersion(equipment, expectedVersion);
        Reading reading = readingRepository.findById(equipmentId, readingId)
                .orElseThrow(() -> ApiException.notFound("reading not found: " + readingId));
        if (maintenanceRepository.isAnchorOfAnyMaintenance(equipmentId, readingId)) {
            throw ApiException.conflict("reading is an anchor of a completed maintenance and cannot be revised");
        }
        checkMonotonic(equipmentId, reading.sampledAt().toEpochMilli(), accumulatedMinutes);
        readingRepository.updateAccumulatedMinutes(equipmentId, readingId, accumulatedMinutes);
        int newRevision = reading.currentRevision() + 1;
        readingRepository.insertRevision(equipmentId, readingId, newRevision, accumulatedMinutes, now());
        equipmentRepository.incrementVersion(equipmentId);
        ReadingView view = new ReadingView(readingId, reading.sampledAt(), accumulatedMinutes, newRevision);
        return success(requestId, "REVISE_READING", payloadHash, HttpStatus.OK, view);
    }

    /**
     * 完成保养：锚点为现存读数及其当前修订号；锚点时间必须晚于上次保养锚点。
     */
    @Transactional
    public ServiceResponse completeMaintenance(String requestId, String equipmentId, int expectedVersion,
                                               String anchorReadingId, int anchorRevisionNo) {
        String payloadHash = hash("COMPLETE_MAINTENANCE", equipmentId, String.valueOf(expectedVersion),
                anchorReadingId, String.valueOf(anchorRevisionNo));
        Optional<ServiceResponse> replay = findReplay(requestId, "COMPLETE_MAINTENANCE", payloadHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        Equipment equipment = lockEquipment(equipmentId);
        replay = findReplay(requestId, "COMPLETE_MAINTENANCE", payloadHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        checkVersion(equipment, expectedVersion);
        Reading anchor = readingRepository.findById(equipmentId, anchorReadingId)
                .orElseThrow(() -> ApiException.notFound("anchor reading not found: " + anchorReadingId));
        if (anchor.currentRevision() != anchorRevisionNo) {
            throw ApiException.conflict("anchor revision mismatch: current is " + anchor.currentRevision());
        }
        Optional<MaintenanceRecord> latest = maintenanceRepository.findLatest(equipmentId);
        if (latest.isPresent()
                && !anchor.sampledAt().isAfter(latest.get().anchorSampledAt())) {
            throw ApiException.unprocessable("anchor sampledAt must be later than the last maintenance anchor");
        }
        long now = now();
        maintenanceRepository.insert(equipmentId, anchorReadingId, anchorRevisionNo,
                anchor.sampledAt().toEpochMilli(), anchor.accumulatedMinutes(), now);
        equipmentRepository.incrementVersion(equipmentId);
        MaintenanceRecord saved = maintenanceRepository.findLatest(equipmentId).orElseThrow();
        MaintenanceView view = toMaintenanceView(saved);
        return success(requestId, "COMPLETE_MAINTENANCE", payloadHash, HttpStatus.CREATED, view);
    }

    /**
     * 状态查询：最新读数减最近保养锚点工时为本轮运行分钟，达到周期即 DUE。
     */
    @Transactional(readOnly = true)
    public StatusView getStatus(String equipmentId) {
        Equipment equipment = equipmentRepository.findById(equipmentId)
                .orElseThrow(() -> ApiException.notFound("equipment not found: " + equipmentId));
        Optional<Reading> latestReading = readingRepository.findLatest(equipmentId);
        Optional<MaintenanceRecord> latestMaintenance = maintenanceRepository.findLatest(equipmentId);
        long anchorMinutes = latestMaintenance.map(MaintenanceRecord::anchorAccumulatedMinutes).orElse(0L);
        long latestMinutes = latestReading.map(Reading::accumulatedMinutes).orElse(0L);
        long runningMinutes = latestMinutes - anchorMinutes;
        String status = runningMinutes >= equipment.maintenanceIntervalMinutes() ? "DUE" : "OK";
        return new StatusView(equipmentId, equipment.maintenanceIntervalMinutes(), runningMinutes, status,
                latestReading.map(Reading::sampledAt).orElse(null),
                latestReading.map(Reading::accumulatedMinutes).orElse(null),
                latestMaintenance.map(MaintenanceRecord::anchorSampledAt).orElse(null),
                latestMaintenance.map(MaintenanceRecord::anchorAccumulatedMinutes).orElse(null));
    }

    /**
     * 历史查询：设备信息、全部读数及修订历史、全部保养记录。
     */
    @Transactional(readOnly = true)
    public HistoryView getHistory(String equipmentId) {
        Equipment equipment = equipmentRepository.findById(equipmentId)
                .orElseThrow(() -> ApiException.notFound("equipment not found: " + equipmentId));
        List<ReadingHistoryView> readings = readingRepository.findAllByEquipment(equipmentId).stream()
                .map(r -> {
                    List<RevisionView> revisions = readingRepository.findRevisions(equipmentId, r.readingId())
                            .stream().map(this::toRevisionView).toList();
                    boolean anchor = maintenanceRepository.isAnchorOfAnyMaintenance(equipmentId, r.readingId());
                    return new ReadingHistoryView(r.readingId(), r.sampledAt(), r.accumulatedMinutes(),
                            r.currentRevision(), anchor, revisions);
                })
                .toList();
        List<MaintenanceView> maintenances = maintenanceRepository.findAllByEquipment(equipmentId).stream()
                .map(this::toMaintenanceView).toList();
        return new HistoryView(equipmentId, equipment.maintenanceIntervalMinutes(), equipment.version(),
                readings, maintenances);
    }

    private Equipment lockEquipment(String equipmentId) {
        return equipmentRepository.findByIdForUpdate(equipmentId)
                .orElseThrow(() -> ApiException.notFound("equipment not found: " + equipmentId));
    }

    private void checkVersion(Equipment equipment, int expectedVersion) {
        if (equipment.version() != expectedVersion) {
            throw ApiException.conflict("equipment version mismatch: expected " + expectedVersion
                    + " but current is " + equipment.version());
        }
    }

    /**
     * 单调性校验：新值必须同时不早于前邻、不晚于后邻的累计分钟。
     */
    private void checkMonotonic(String equipmentId, long sampledAtMs, long accumulatedMinutes) {
        Optional<Reading> previous = readingRepository.findPrevious(equipmentId, sampledAtMs);
        if (previous.isPresent() && accumulatedMinutes < previous.get().accumulatedMinutes()) {
            throw ApiException.unprocessable("accumulatedMinutes is less than the previous reading");
        }
        Optional<Reading> next = readingRepository.findNext(equipmentId, sampledAtMs);
        if (next.isPresent() && accumulatedMinutes > next.get().accumulatedMinutes()) {
            throw ApiException.unprocessable("accumulatedMinutes is greater than the next reading");
        }
    }

    private Optional<ServiceResponse> findReplay(String requestId, String action, String payloadHash) {
        Optional<IdempotencyRepository.Entry> entry = idempotencyRepository.find(requestId);
        if (entry.isEmpty()) {
            return Optional.empty();
        }
        IdempotencyRepository.Entry found = entry.get();
        if (!found.action().equals(action) || !found.payloadHash().equals(payloadHash)) {
            throw ApiException.conflict("requestId already used with different parameters");
        }
        return Optional.of(new ServiceResponse(found.responseStatus(), found.responseBody()));
    }

    private ServiceResponse success(String requestId, String action, String payloadHash,
                                    HttpStatus status, Object view) {
        String body = toJson(view);
        idempotencyRepository.insert(requestId, action, payloadHash, status.value(), body, now());
        return new ServiceResponse(status.value(), body);
    }

    private String toJson(Object view) {
        try {
            return objectMapper.writeValueAsString(view);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize response", e);
        }
    }

    private RevisionView toRevisionView(ReadingRevision revision) {
        return new RevisionView(revision.revisionNo(), revision.accumulatedMinutes(), revision.revisedAt());
    }

    private MaintenanceView toMaintenanceView(MaintenanceRecord record) {
        return new MaintenanceView(record.maintenanceId(), record.anchorReadingId(), record.anchorRevisionNo(),
                record.anchorSampledAt(), record.anchorAccumulatedMinutes(), record.completedAt());
    }

    private long now() {
        return clock.millis();
    }

    private static String hash(String... parts) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        byte[] hashed = digest.digest(String.join("|", parts).getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(hashed);
    }
}
