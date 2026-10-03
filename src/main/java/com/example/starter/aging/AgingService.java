package com.example.starter.aging;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 熟成批次业务：入库、每日翻面、测温、作废替代、质检放行与查询。
 * 放行规则：自入库 UTC 自然日起连续 targetDays 天，每天一条翻面且每天至少一条
 * 状态为 VALID 且温度在 [8.0, 12.0] 摄氏度的记录。
 */
@Service
public class AgingService {

    /** 放行有效温度下限（含），摄氏度。 */
    public static final BigDecimal TEMP_MIN = new BigDecimal("8.0");
    /** 放行有效温度上限（含），摄氏度。 */
    public static final BigDecimal TEMP_MAX = new BigDecimal("12.0");
    /** 熟成目标天数。 */
    public static final int TARGET_DAYS = 7;

    private final AgingRepository repository;
    private final Clock clock;

    public AgingService(AgingRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional
    public AgingBatch createBatch(String requestId, String batchCode, Instant storedAt) {
        var existing = repository.findBatchByRequestId(requestId);
        if (existing.isPresent()) {
            AgingBatch found = existing.get();
            if (found.batchCode().equals(batchCode) && found.storedAt().equals(storedAt)) {
                return found;
            }
            throw conflict("requestId 已使用且参数不一致");
        }
        Instant stored = storedAt != null ? storedAt : Instant.now(clock);
        try {
            long id = repository.insertBatch(batchCode, requestId, stored, TARGET_DAYS, Instant.now(clock));
            return repository.findBatchById(id).orElseThrow();
        } catch (DuplicateKeyException e) {
            throw conflict("批次编码或 requestId 已存在");
        }
    }

    @Transactional
    public TurnRecord confirmTurn(long batchId, String requestId, LocalDate turnDate, String operator) {
        AgingBatch batch = requireBatch(batchId);
        var existing = repository.findTurnByRequestId(requestId);
        if (existing.isPresent()) {
            TurnRecord found = existing.get();
            if (found.batchId() == batchId && found.turnDate().equals(turnDate)
                    && found.operatorName().equals(operator)) {
                return found;
            }
            throw conflict("requestId 已使用且参数不一致");
        }
        requireWithinWindow(batch, turnDate);
        try {
            long id = repository.insertTurn(batchId, turnDate, Instant.now(clock), operator, requestId);
            return repository.findTurnByRequestId(requestId).orElseThrow();
        } catch (DuplicateKeyException e) {
            throw conflict("该批次当日已确认翻面: " + turnDate);
        }
    }

    @Transactional
    public TemperatureRecord recordTemperature(long batchId, String requestId, LocalDate date,
            BigDecimal celsius) {
        AgingBatch batch = requireBatch(batchId);
        var existing = repository.findTemperatureByRequestId(requestId);
        if (existing.isPresent()) {
            TemperatureRecord found = existing.get();
            if (found.batchId() == batchId && found.recordedDate().equals(date)
                    && found.celsius().compareTo(celsius) == 0) {
                return found;
            }
            throw conflict("requestId 已使用且参数不一致");
        }
        requireWithinWindow(batch, date);
        try {
            long id = repository.insertTemperature(batchId, date, celsius, Instant.now(clock), requestId);
            return repository.findTemperatureById(id).orElseThrow();
        } catch (DuplicateKeyException e) {
            throw conflict("requestId 已存在");
        }
    }

    private AgingBatch requireBatch(long batchId) {
        return repository.findBatchById(batchId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "批次不存在: " + batchId));
    }

    private void requireWithinWindow(AgingBatch batch, LocalDate date) {
        LocalDate start = storedDate(batch);
        LocalDate end = start.plusDays(batch.targetDays() - 1L);
        if (date.isBefore(start) || date.isAfter(end)) {
            throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "日期不在熟成窗口内: " + date + ", 窗口 " + start + " 至 " + end);
        }
    }

    private static LocalDate storedDate(AgingBatch batch) {
        return batch.storedAt().atOffset(ZoneOffset.UTC).toLocalDate();
    }

    private static BusinessException conflict(String message) {
        return new BusinessException(HttpStatus.CONFLICT, message);
    }
    /**
     * 作废错误测温并追加替代记录。幂等：同 requestId 同参重放返回原结果。
     */
    @Transactional
    public VoidResult voidTemperature(long batchId, long temperatureId, String requestId,
            String reason, BigDecimal replacementCelsius) {
        requireBatch(batchId);
        TemperatureRecord target = repository.findTemperatureById(temperatureId)
                .filter(t -> t.batchId() == batchId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND,
                        "温度记录不存在: " + temperatureId));
        if (TemperatureRecord.STATUS_VOID.equals(target.status())) {
            if (requestId.equals(target.voidRequestId())) {
                TemperatureRecord replacement = repository
                        .findTemperatureByRequestId(replacementRequestId(requestId)).orElseThrow();
                return new VoidResult(target, replacement);
            }
            throw conflict("该记录已作废");
        }
        repository.findTemperatureByVoidRequestId(requestId).ifPresent(t -> {
            throw conflict("requestId 已使用");
        });
        repository.markTemperatureVoided(temperatureId, reason, Instant.now(clock), requestId);
        long replacementId = repository.insertTemperature(batchId, target.recordedDate(),
                replacementCelsius, Instant.now(clock), replacementRequestId(requestId));
        return new VoidResult(repository.findTemperatureById(temperatureId).orElseThrow(),
                repository.findTemperatureById(replacementId).orElseThrow());
    }

    private static String replacementRequestId(String voidRequestId) {
        return voidRequestId + "/replacement";
    }

    /**
     * 质检放行：审核提交时有效的七日记录，成功则固化快照；失败不产生放行记录、不占 requestId。
     */
    @Transactional
    public ReleaseRecord release(long batchId, String requestId) {
        AgingBatch batch = requireBatch(batchId);
        var replayed = repository.findReleaseByRequestId(requestId);
        if (replayed.isPresent()) {
            ReleaseRecord found = replayed.get();
            if (found.batchId() == batchId) {
                return found;
            }
            throw conflict("requestId 已使用且参数不一致");
        }
        repository.findReleaseByBatchId(batchId).ifPresent(r -> {
            throw conflict("批次已放行，放行单ID: " + r.id());
        });

        LocalDate start = storedDate(batch);
        LocalDate end = start.plusDays(batch.targetDays() - 1L);
        List<TurnRecord> turns = repository.findTurns(batchId, start, end);
        List<TemperatureRecord> temps = repository.findTemperatures(batchId, start, end);

        List<String> missingTurnDates = new ArrayList<>();
        List<String> missingTemperatureDates = new ArrayList<>();
        List<Map<String, Object>> snapshot = new ArrayList<>();
        for (int i = 0; i < batch.targetDays(); i++) {
            LocalDate day = start.plusDays(i);
            TurnRecord turn = turns.stream().filter(t -> t.turnDate().equals(day)).findFirst().orElse(null);
            if (turn == null) {
                missingTurnDates.add(day.toString());
            }
            List<TemperatureRecord> valid = temps.stream()
                    .filter(t -> t.recordedDate().equals(day))
                    .filter(t -> TemperatureRecord.STATUS_VALID.equals(t.status()))
                    .filter(t -> t.celsius().compareTo(TEMP_MIN) >= 0 && t.celsius().compareTo(TEMP_MAX) <= 0)
                    .toList();
            if (valid.isEmpty()) {
                missingTemperatureDates.add(day.toString());
            }
            if (turn != null && !valid.isEmpty()) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("date", day.toString());
                entry.put("turnRecordId", turn.id());
                entry.put("temperatureRecordIds", valid.stream().map(TemperatureRecord::id).toList());
                snapshot.add(entry);
            }
        }
        if (!missingTurnDates.isEmpty() || !missingTemperatureDates.isEmpty()) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("missingTurnDates", missingTurnDates);
            details.put("missingTemperatureDates", missingTemperatureDates);
            throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "熟成记录不完整，不能放行", details);
        }
        String snapshotJson = toJson(snapshot);
        try {
            long id = repository.insertRelease(batchId, requestId, Instant.now(clock), snapshotJson);
            repository.markBatchReleased(batchId);
            return repository.findReleaseByBatchId(batchId).orElseThrow();
        } catch (DuplicateKeyException e) {
            return repository.findReleaseByBatchId(batchId)
                    .map(r -> {
                        if (r.requestId().equals(requestId)) {
                            return r;
                        }
                        throw conflict("批次已放行，放行单ID: " + r.id());
                    })
                    .orElseThrow(() -> conflict("requestId 已存在"));
        }
    }

    private static String toJson(List<Map<String, Object>> snapshot) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < snapshot.size(); i++) {
            Map<String, Object> e = snapshot.get(i);
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"date\":\"").append(e.get("date")).append("\",\"turnRecordId\":")
                    .append(e.get("turnRecordId")).append(",\"temperatureRecordIds\":")
                    .append(e.get("temperatureRecordIds")).append('}');
        }
        return sb.append(']').toString();
    }

    public record VoidResult(TemperatureRecord voided, TemperatureRecord replacement) {
    }
    /**
     * 查询批次视图：七日日期轴、当日翻面与有效温度、放行引用及快照。
     */
    @Transactional(readOnly = true)
    public Map<String, Object> batchView(long batchId) {
        AgingBatch batch = requireBatch(batchId);
        LocalDate start = storedDate(batch);
        LocalDate end = start.plusDays(batch.targetDays() - 1L);
        List<TurnRecord> turns = repository.findTurns(batchId, start, end);
        List<TemperatureRecord> temps = repository.findTemperatures(batchId, start, end);

        List<Map<String, Object>> days = new ArrayList<>();
        for (int i = 0; i < batch.targetDays(); i++) {
            LocalDate day = start.plusDays(i);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("date", day.toString());
            entry.put("turnRecordId", turns.stream().filter(t -> t.turnDate().equals(day))
                    .findFirst().map(TurnRecord::id).orElse(null));
            List<Map<String, Object>> validTemps = temps.stream()
                    .filter(t -> t.recordedDate().equals(day))
                    .filter(t -> TemperatureRecord.STATUS_VALID.equals(t.status()))
                    .map(t -> {
                        Map<String, Object> v = new LinkedHashMap<String, Object>();
                        v.put("id", t.id());
                        v.put("celsius", t.celsius());
                        v.put("inRange", t.celsius().compareTo(TEMP_MIN) >= 0
                                && t.celsius().compareTo(TEMP_MAX) <= 0);
                        return v;
                    })
                    .toList();
            entry.put("validTemperatures", validTemps);
            days.add(entry);
        }

        Map<String, Object> view = new LinkedHashMap<>();
        view.put("batchId", batch.id());
        view.put("batchCode", batch.batchCode());
        view.put("status", batch.status());
        view.put("storedAt", batch.storedAt().toString());
        view.put("targetDays", batch.targetDays());
        view.put("days", days);
        repository.findReleaseByBatchId(batchId).ifPresentOrElse(r -> {
            Map<String, Object> release = new LinkedHashMap<String, Object>();
            release.put("releaseId", r.id());
            release.put("releasedAt", r.releasedAt().toString());
            release.put("snapshot", r.snapshotJson());
            view.put("release", release);
        }, () -> view.put("release", null));
        return view;
    }
}