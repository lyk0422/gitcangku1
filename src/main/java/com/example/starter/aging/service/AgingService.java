package com.example.starter.aging.service;

import com.example.starter.aging.domain.AgingBatch;
import com.example.starter.aging.domain.BatchStatus;
import com.example.starter.aging.domain.ReleaseRecord;
import com.example.starter.aging.domain.SnapshotItem;
import com.example.starter.aging.domain.TempStatus;
import com.example.starter.aging.domain.TemperatureRecord;
import com.example.starter.aging.domain.TurnRecord;
import com.example.starter.aging.repo.BatchRepository;
import com.example.starter.aging.repo.ReleaseRepository;
import com.example.starter.aging.repo.TemperatureRepository;
import com.example.starter.aging.repo.TurnRepository;
import com.example.starter.aging.web.ApiException;
import com.example.starter.aging.web.ApiViews.BatchView;
import com.example.starter.aging.web.ApiViews.BoardView;
import com.example.starter.aging.web.ApiViews.DayView;
import com.example.starter.aging.web.ApiViews.ReleaseRef;
import com.example.starter.aging.web.ApiViews.ReleaseView;
import com.example.starter.aging.web.ApiViews.SnapshotItemView;
import com.example.starter.aging.web.ApiViews.TempRef;
import com.example.starter.aging.web.ApiViews.TempView;
import com.example.starter.aging.web.ApiViews.TurnRef;
import com.example.starter.aging.web.ApiViews.TurnView;
import com.example.starter.aging.web.ApiViews.VoidResultView;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 熟成批次业务服务：翻面确认、测温追加与作废、质检放行、看板查询。
 * 放行与作废通过批次行锁串行化，按提交时有效的七日记录裁决。
 */
@Service
public class AgingService {

    /** 熟成目标天数与放行所需连续自然日数。 */
    public static final int TARGET_DAYS = 7;

    /** 放行有效温度下限（摄氏度，含边界）。 */
    public static final BigDecimal TEMP_MIN = new BigDecimal("8.0");

    /** 放行有效温度上限（摄氏度，含边界）。 */
    public static final BigDecimal TEMP_MAX = new BigDecimal("12.0");

    private final BatchRepository batchRepo;
    private final TurnRepository turnRepo;
    private final TemperatureRepository tempRepo;
    private final ReleaseRepository releaseRepo;

    public AgingService(BatchRepository batchRepo, TurnRepository turnRepo,
                        TemperatureRepository tempRepo, ReleaseRepository releaseRepo) {
        this.batchRepo = batchRepo;
        this.turnRepo = turnRepo;
        this.tempRepo = tempRepo;
        this.releaseRepo = releaseRepo;
    }

    /** 创建熟成批次，批次编码重复时返回 409。 */
    @Transactional
    public BatchView createBatch(String batchCode, Instant entryAt) {
        long id;
        try {
            id = batchRepo.insert(batchCode, entryAt, TARGET_DAYS);
        } catch (DuplicateKeyException e) {
            throw new ApiException(HttpStatus.CONFLICT, "批次编码已存在: " + batchCode);
        }
        return toBatchView(batchRepo.findById(id).orElseThrow());
    }

    /**
     * 确认某日翻面。同一批次同一 UTC 自然日最多一条；
     * 同 requestId 同参数重放返回原记录，异参数或同日异请求返回 409。
     */
    @Transactional
    public TurnView confirmTurn(long batchId, String requestId, LocalDate turnDate,
                                String operator, String note) {
        AgingBatch batch = requireBatch(batchId);
        checkAxisDate(batch, turnDate);
        var byRequest = turnRepo.findByBatchAndRequestId(batchId, requestId);
        if (byRequest.isPresent()) {
            TurnRecord existing = byRequest.get();
            if (sameTurn(existing, turnDate, operator, note)) {
                return toTurnView(existing);
            }
            throw new ApiException(HttpStatus.CONFLICT, "相同 requestId 的翻面参数不一致");
        }
        try {
            long id = turnRepo.insert(batchId, turnDate, requestId, operator, note);
            return toTurnView(turnRepo.findByBatchAndDate(batchId, turnDate).orElseThrow());
        } catch (DuplicateKeyException e) {
            TurnRecord existing = turnRepo.findByBatchAndDate(batchId, turnDate)
                    .orElseThrow(() -> e);
            if (existing.requestId().equals(requestId) && sameTurn(existing, turnDate, operator, note)) {
                return toTurnView(existing);
            }
            throw new ApiException(HttpStatus.CONFLICT,
                    "该自然日已确认翻面，参数不一致: " + turnDate);
        }
    }

    /** 追加一条有效测温记录，温度越界也允许记录但不计入放行资格。 */
    @Transactional
    public TempView addTemperature(long batchId, LocalDate tempDate, BigDecimal celsius,
                                   String recordedBy) {
        AgingBatch batch = requireBatch(batchId);
        checkAxisDate(batch, tempDate);
        long id = tempRepo.insert(batchId, tempDate, celsius, recordedBy);
        return toTempView(tempRepo.findById(id).orElseThrow());
    }
    /**
     * 作废测温记录（仅状态迁移，不物理删除），可同事务追加替代记录。
     * 通过批次行锁与放行串行化：作废先提交则放行按作废后的记录裁决，反之亦然。
     */
    @Transactional
    public VoidResultView voidTemperature(long tempId, String reason,
                                          LocalDate replacementDate, BigDecimal replacementCelsius,
                                          String replacementRecordedBy) {
        TemperatureRecord rec = tempRepo.findById(tempId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "测温记录不存在: " + tempId));
        AgingBatch batch = batchRepo.lockById(rec.batchId()).orElseThrow();
        if (rec.status() == TempStatus.VOID) {
            throw new ApiException(HttpStatus.CONFLICT, "测温记录已作废: " + tempId);
        }
        Long replacedById = null;
        TempView replacement = null;
        if (replacementDate != null) {
            checkAxisDate(batch, replacementDate);
            replacedById = tempRepo.insert(rec.batchId(), replacementDate, replacementCelsius,
                    replacementRecordedBy);
            replacement = toTempView(tempRepo.findById(replacedById).orElseThrow());
        }
        tempRepo.markVoid(tempId, reason, Instant.now(), replacedById);
        return new VoidResultView(tempId, replacement);
    }

    /**
     * 质检放行：连续 7 个 UTC 自然日均有翻面且每日至少一条 8 至 12 摄氏度的有效温度。
     * 满足时固化七天翻面与温度记录编号；不满足时返回明确日期且不占用 requestId。
     * 同 requestId 重放返回原放行记录，已放行批次异 requestId 返回 409。
     */
    @Transactional
    public ReleaseView release(long batchId, String requestId) {
        AgingBatch batch = batchRepo.lockById(batchId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "批次不存在: " + batchId));
        var existing = releaseRepo.findByBatchId(batchId);
        if (existing.isPresent()) {
            ReleaseRecord record = existing.get();
            if (record.requestId().equals(requestId)) {
                return toReleaseView(record);
            }
            throw new ApiException(HttpStatus.CONFLICT, "批次已放行，requestId 不一致");
        }
        releaseRepo.findByRequestId(requestId).ifPresent(r -> {
            throw new ApiException(HttpStatus.CONFLICT, "requestId 已被其他批次放行占用: " + requestId);
        });

        List<LocalDate> axis = axisDays(batch);
        Map<LocalDate, TurnRecord> turns = turnRepo
                .findByBatchAndDateBetween(batchId, axis.get(0), axis.get(TARGET_DAYS - 1))
                .stream().collect(Collectors.toMap(TurnRecord::turnDate, Function.identity()));
        Map<LocalDate, List<TemperatureRecord>> temps = tempRepo
                .findByBatchAndDateBetween(batchId, axis.get(0), axis.get(TARGET_DAYS - 1))
                .stream().filter(t -> t.status() == TempStatus.VALID)
                .collect(Collectors.groupingBy(TemperatureRecord::tempDate));

        List<LocalDate> missingTurnDates = new ArrayList<>();
        List<LocalDate> missingTempDates = new ArrayList<>();
        Map<LocalDate, TemperatureRecord> qualifying = new LinkedHashMap<>();
        for (LocalDate day : axis) {
            if (!turns.containsKey(day)) {
                missingTurnDates.add(day);
            }
            TemperatureRecord qualified = temps.getOrDefault(day, List.of()).stream()
                    .filter(AgingService::isQualifying).findFirst().orElse(null);
            if (qualified == null) {
                missingTempDates.add(day);
            } else {
                qualifying.put(day, qualified);
            }
        }
        if (!missingTurnDates.isEmpty() || !missingTempDates.isEmpty()) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("missingTurnDates", missingTurnDates);
            details.put("missingTemperatureDates", missingTempDates);
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "不满足放行条件", details);
        }

        long releaseId;
        try {
            releaseId = releaseRepo.insertRelease(batchId, requestId, Instant.now());
        } catch (DuplicateKeyException e) {
            ReleaseRecord record = releaseRepo.findByBatchId(batchId).orElseThrow(() -> e);
            if (record.requestId().equals(requestId)) {
                return toReleaseView(record);
            }
            throw new ApiException(HttpStatus.CONFLICT, "批次已放行，requestId 不一致");
        }
        for (LocalDate day : axis) {
            releaseRepo.insertSnapshotItem(releaseId, day, turns.get(day).id(),
                    qualifying.get(day).id());
        }
        batchRepo.updateStatus(batchId, BatchStatus.RELEASED);
        return toReleaseView(releaseRepo.findByBatchId(batchId).orElseThrow());
    }

    /** 看板查询：日期轴、当日翻面与有效温度、放行引用及快照。 */
    @Transactional(readOnly = true)
    public BoardView board(long batchId) {
        AgingBatch batch = requireBatch(batchId);
        List<LocalDate> axis = axisDays(batch);
        Map<LocalDate, TurnRecord> turns = turnRepo
                .findByBatchAndDateBetween(batchId, axis.get(0), axis.get(TARGET_DAYS - 1))
                .stream().collect(Collectors.toMap(TurnRecord::turnDate, Function.identity()));
        Map<LocalDate, List<TemperatureRecord>> temps = tempRepo
                .findByBatchAndDateBetween(batchId, axis.get(0), axis.get(TARGET_DAYS - 1))
                .stream().filter(t -> t.status() == TempStatus.VALID)
                .collect(Collectors.groupingBy(TemperatureRecord::tempDate));

        ReleaseRecord release = releaseRepo.findByBatchId(batchId).orElse(null);
        Map<LocalDate, SnapshotItem> snapshot = release == null ? Map.of()
                : releaseRepo.findSnapshotByReleaseId(release.id()).stream()
                        .collect(Collectors.toMap(SnapshotItem::dayDate, Function.identity()));

        List<DayView> days = new ArrayList<>();
        for (LocalDate day : axis) {
            TurnRecord turn = turns.get(day);
            List<TempRef> validTemps = temps.getOrDefault(day, List.of()).stream()
                    .map(t -> new TempRef(t.id(), t.celsius(), t.recordedBy(), t.createdAt()))
                    .toList();
            SnapshotItem item = snapshot.get(day);
            days.add(new DayView(day,
                    turn == null ? null : new TurnRef(turn.id(), turn.operatorName(), turn.createdAt()),
                    validTemps,
                    item == null ? null
                            : new SnapshotItemView(item.dayDate(), item.turnId(), item.temperatureId())));
        }
        return new BoardView(batch.id(), batch.batchCode(), batch.entryAt(), batch.targetDays(),
                batch.status().name(),
                release == null ? null
                        : new ReleaseRef(release.id(), release.requestId(), release.releasedAt()),
                days);
    }

    private AgingBatch requireBatch(long batchId) {
        return batchRepo.findById(batchId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "批次不存在: " + batchId));
    }

    private List<LocalDate> axisDays(AgingBatch batch) {
        LocalDate start = batch.entryAt().atOffset(ZoneOffset.UTC).toLocalDate();
        List<LocalDate> days = new ArrayList<>();
        for (int i = 0; i < batch.targetDays(); i++) {
            days.add(start.plusDays(i));
        }
        return days;
    }

    private void checkAxisDate(AgingBatch batch, LocalDate date) {
        List<LocalDate> axis = axisDays(batch);
        if (date.isBefore(axis.get(0)) || date.isAfter(axis.get(axis.size() - 1))) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "日期不在熟成周期内: " + date + "，周期 " + axis.get(0) + " 至 "
                            + axis.get(axis.size() - 1));
        }
    }

    private static boolean isQualifying(TemperatureRecord rec) {
        return rec.celsius().compareTo(TEMP_MIN) >= 0 && rec.celsius().compareTo(TEMP_MAX) <= 0;
    }

    private static boolean sameTurn(TurnRecord existing, LocalDate turnDate,
                                    String operator, String note) {
        return existing.turnDate().equals(turnDate)
                && existing.operatorName().equals(operator)
                && Objects.equals(existing.note(), note);
    }

    private ReleaseView toReleaseView(ReleaseRecord record) {
        List<SnapshotItemView> snapshot = releaseRepo.findSnapshotByReleaseId(record.id()).stream()
                .map(i -> new SnapshotItemView(i.dayDate(), i.turnId(), i.temperatureId()))
                .toList();
        return new ReleaseView(record.id(), record.batchId(), record.requestId(),
                record.releasedAt(), snapshot);
    }

    private static BatchView toBatchView(AgingBatch batch) {
        return new BatchView(batch.id(), batch.batchCode(), batch.entryAt(),
                batch.targetDays(), batch.status().name());
    }

    private static TurnView toTurnView(TurnRecord turn) {
        return new TurnView(turn.id(), turn.batchId(), turn.turnDate(), turn.requestId(),
                turn.operatorName(), turn.note(), turn.createdAt());
    }

    private static TempView toTempView(TemperatureRecord rec) {
        return new TempView(rec.id(), rec.batchId(), rec.tempDate(), rec.celsius(),
                rec.status().name(), rec.recordedBy(), rec.createdAt());
    }
}