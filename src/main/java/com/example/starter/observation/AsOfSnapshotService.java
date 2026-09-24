package com.example.starter.observation;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * 按时刻一致视图与冻结快照业务服务。
 *
 * <p>按时刻查询为只读：以 observation_version_commit 中登记的版本事务提交时刻为唯一时间权威，
 * 取目标 UTC 时刻（含）之前最后一个已提交版本；该时刻尚未创建返回 ABSENT（不视为 404）；
 * 墓碑按删除提交时刻生效，删除期间不会取到此后恢复的版本。
 *
 * <p>冻结快照在单事务内完成：先锁定全局版本提交互斥行（SELECT ... FOR UPDATE，
 * 与所有版本写事务竞争同一把锁），再锁定集合内 observationId 的当前行，形成与并发写入的
 * 提交顺序裁决点——快照事务提交前已完成的版本全部可见、之后提交的版本被阻塞，
 * 杜绝同一快照内新旧版本混读；随后读取逐条一致状态与切刻处全局最新版本序号，
 * 并原子写入不可变慢照头与逐条内容。
 * 集合内任一 ID 在任何时刻都不存在则整次 404，不保存任何部分快照。
 */
@Service
public class AsOfSnapshotService {

    private static final String SEPARATOR = "\u0001";

    private final ObservationRepository observationRepository;
    private final ResolutionRepository resolutionRepository;
    private final SnapshotRepository snapshotRepository;
    private final Clock clock;

    public AsOfSnapshotService(ObservationRepository observationRepository,
                               ResolutionRepository resolutionRepository,
                               SnapshotRepository snapshotRepository,
                               Clock clock) {
        this.observationRepository = observationRepository;
        this.resolutionRepository = resolutionRepository;
        this.snapshotRepository = snapshotRepository;
        this.clock = clock;
    }

    /**
     * 按时刻只读查询：返回各 observationId（升序、去重）在目标 UTC 时刻的最后版本、状态与最近解决记录标识。
     * 目标时刻晚于服务端当前时刻返回 400；不推进观测版本、不写入解决记录。
     */
    @Transactional(readOnly = true)
    public List<AsOfEntry> queryAsOf(Instant asOfUtc, List<String> rawObservationIds) {
        validateNotFuture(asOfUtc);
        List<String> observationIds = normalizeIds(rawObservationIds);
        return readAsOfEntries(asOfUtc, observationIds);
    }

    /**
     * 创建冻结快照：单事务内读取一致状态并保存不可变快照。
     * requestId 同键同参（snapshotKey、目标时刻、ID 集合一致；集合换序视为同参）重放首次快照，异参 409；
     * snapshotKey 全局唯一，跨 requestId 复用返回 409；业务失败随事务回滚，不占 requestId 与 snapshotKey。
     */
    @Transactional
    public SnapshotRecord createSnapshot(SnapshotCreateRequest request) {
        Instant targetTime = request.targetTimeUtc();
        validateNotFuture(targetTime);
        List<String> observationIds = normalizeIds(request.observationIds());
        String idFingerprint = fingerprint(observationIds);

        // 快速幂等判定：成功快照按 requestId / snapshotKey 唯一。最终并发裁决仍由数据库唯一约束兜底。
        Optional<SnapshotRepository.SnapshotHeader> byRequest =
                snapshotRepository.findHeaderByRequestId(request.requestId());
        if (byRequest.isPresent()) {
            return replayOrConflict(byRequest.get(), request, targetTime, idFingerprint);
        }
        Optional<SnapshotRepository.SnapshotHeader> byKey =
                snapshotRepository.findHeaderByKey(request.snapshotKey());
        if (byKey.isPresent()) {
            return replayOrConflict(byKey.get(), request, targetTime, idFingerprint);
        }

        // 先取全局版本提交互斥锁，再对集合内全部当前行加锁（与版本写事务使用相同的加锁顺序）：
        // 持锁期间没有任何版本写事务能够提交，得到按事务提交顺序的一致切刻。
        // 任一 ID 在任何时刻都不存在（当前行从未建立）则整次 404，不写入部分快照。
        observationRepository.lockGlobalWriteMutex();
        for (String observationId : observationIds) {
            observationRepository.findCurrentForUpdate(observationId)
                    .orElseThrow(() -> ApiException.notFound(
                            "observation not found at any time: " + observationId));
        }

        List<AsOfEntry> entries = readAsOfEntries(targetTime, observationIds);
        long globalLatestVersion = observationRepository.countCommittedVersions(targetTime);
        SnapshotRecord record = buildRecord(request, targetTime, idFingerprint, globalLatestVersion, entries);

        try {
            snapshotRepository.insert(record);
        } catch (DuplicateKeyException e) {
            // 并发下 requestId 或 snapshotKey 已被其他事务占用：重读后同参重放，异参 409。
            SnapshotRepository.SnapshotHeader winner = snapshotRepository
                    .findHeaderByRequestId(request.requestId())
                    .or(() -> snapshotRepository.findHeaderByKey(request.snapshotKey()))
                    .orElseThrow(() -> ApiException.conflict(
                            "snapshot request conflict: " + request.requestId(), null));
            return replayOrConflict(winner, request, targetTime, idFingerprint);
        }
        return record;
    }

    /**
     * 按 snapshotKey 读取不可变快照；不存在返回 404。重复读取内容稳定。
     */
    @Transactional(readOnly = true)
    public SnapshotRecord getSnapshot(String snapshotKey) {
        return snapshotRepository.findByKey(snapshotKey)
                .orElseThrow(() -> ApiException.notFound("snapshot not found: " + snapshotKey));
    }

    /**
     * 读取目标时刻各记录的一致条目：逐条取最后提交版本与最近解决记录；该时刻尚未创建记为 ABSENT。
     * 调用方须已持有快照事务内的当前行锁（快照场景）或处于只读历史查询（按时刻查询场景）。
     */
    private List<AsOfEntry> readAsOfEntries(Instant asOfUtc, List<String> observationIds) {
        List<AsOfEntry> entries = new ArrayList<>(observationIds.size());
        for (String observationId : observationIds) {
            Optional<ObservationSnapshot> version = observationRepository.findVersionAsOf(observationId, asOfUtc);
            if (version.isEmpty()) {
                entries.add(AsOfEntry.absent(observationId));
                continue;
            }
            String lastResolutionId = resolutionRepository.findLatestAsOf(observationId, asOfUtc)
                    .map(ResolutionRecord::resolutionId)
                    .orElse(null);
            entries.add(AsOfEntry.of(version.get(), lastResolutionId));
        }
        return List.copyOf(entries);
    }

    /**
     * 依据一致读取结果组装不可变慢照记录（逐条内容按 observationId 升序编号）。
     */
    private SnapshotRecord buildRecord(SnapshotCreateRequest request, Instant targetTime, String idFingerprint,
                                       long globalLatestVersion, List<AsOfEntry> entries) {
        List<SnapshotItemRecord> items = new ArrayList<>(entries.size());
        for (int i = 0; i < entries.size(); i++) {
            AsOfEntry entry = entries.get(i);
            items.add(new SnapshotItemRecord(request.snapshotKey(), i, entry.observationId(), entry.state(),
                    entry.version(), entry.location(), entry.reading(), entry.note(), entry.lastResolutionId()));
        }
        return new SnapshotRecord(request.snapshotKey(), request.requestId(), targetTime,
                globalLatestVersion, idFingerprint, List.copyOf(items));
    }

    /**
     * 同参重放判定：snapshotKey、requestId、目标时刻与归一化 ID 集合全部一致时返回已落库的完整快照；
     * 任一参数不同（含跨 requestId 复用 snapshotKey）抛 409。
     */
    private SnapshotRecord replayOrConflict(SnapshotRepository.SnapshotHeader header,
                                            SnapshotCreateRequest request,
                                            Instant targetTime, String idFingerprint) {
        boolean sameParams = header.snapshotKey().equals(request.snapshotKey())
                && header.requestId().equals(request.requestId())
                && header.targetTimeUtc().equals(targetTime)
                && header.idFingerprint().equals(idFingerprint);
        if (!sameParams) {
            throw ApiException.conflict(
                    "snapshot requestId or snapshotKey reused with different parameters: "
                            + request.requestId() + " / " + request.snapshotKey(), null);
        }
        return snapshotRepository.findByKey(header.snapshotKey())
                .orElseThrow(() -> new IllegalStateException(
                        "committed snapshot missing on replay: " + header.snapshotKey()));
    }

    /**
     * 目标时刻不得晚于服务端当前时刻（按注入 Clock 判定）。
     */
    private void validateNotFuture(Instant asOfUtc) {
        if (asOfUtc.isAfter(Instant.now(clock))) {
            throw ApiException.badRequest("as-of time must not be later than server current time: " + asOfUtc);
        }
    }

    /**
     * 归一化 observationId 集合：去重后按字典序升序；集合换序视为同参，重复 ID 不产生重复条目。
     */
    private List<String> normalizeIds(List<String> rawObservationIds) {
        return rawObservationIds.stream().distinct().sorted().toList();
    }

    /**
     * 归一化 ID 集合指纹：升序去重序列拼接后 SHA-256，用于同键异参判定。
     */
    private String fingerprint(List<String> observationIds) {
        String raw = String.join(SEPARATOR, observationIds);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
