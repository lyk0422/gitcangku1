package com.example.starter.maintenance.service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.maintenance.api.ApiException;
import com.example.starter.maintenance.api.dto.AddReadingRequest;
import com.example.starter.maintenance.api.dto.CalendarResponse;
import com.example.starter.maintenance.api.dto.CompleteMaintenanceRequest;
import com.example.starter.maintenance.api.dto.EnableCalendarRequest;
import com.example.starter.maintenance.api.dto.EquipmentResponse;
import com.example.starter.maintenance.api.dto.MaintenanceResponse;
import com.example.starter.maintenance.api.dto.ReadingResponse;
import com.example.starter.maintenance.api.dto.RegisterEquipmentRequest;
import com.example.starter.maintenance.api.dto.ReviseReadingRequest;
import com.example.starter.maintenance.api.dto.RevisionView;
import com.example.starter.maintenance.api.dto.StatusResponse;
import com.example.starter.maintenance.domain.Equipment;
import com.example.starter.maintenance.domain.EquipmentCalendar;
import com.example.starter.maintenance.domain.MaintenanceRecord;
import com.example.starter.maintenance.domain.Reading;
import com.example.starter.maintenance.store.EquipmentRepository;

/**
 * 设备工时保养事务业务服务。写操作流程：设备行锁 → 幂等判定 → 版本校验 → 业务规则 → 变更并版本加一。
 * 去重记录与业务变更同事务提交；任一规则失败抛异常整体回滚。
 */
@Service
public class EquipmentTxService {

    private final EquipmentRepository repository;
    private final IdempotencyService idempotency;
    private final Clock clock;

    public EquipmentTxService(EquipmentRepository repository, IdempotencyService idempotency, Clock clock) {
        this.repository = repository;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    // ---------- 登记设备 ----------

    @Transactional
    public EquipmentResponse register(RegisterEquipmentRequest req) {
        return idempotency.execute(req.requestId(), "REGISTER_EQUIPMENT", registerFingerprint(req),
                EquipmentResponse.class, () -> {
                    if (repository.findEquipment(req.equipmentId()).isPresent()) {
                        throw ApiException.conflict("EQUIPMENT_EXISTS", "设备已存在：" + req.equipmentId());
                    }
                    repository.insertEquipment(req.equipmentId(), req.maintenancePeriodMinutes(), clock.instant());
                    return new EquipmentResponse(req.equipmentId(), req.maintenancePeriodMinutes(), 1L);
                });
    }

    // ---------- 启用日历档案 ----------

    /**
     * 启用设备日历档案：每台设备仅可启用一次，投用时刻与周期启用后不可修改。
     * 与其他写操作共享设备 expectedVersion，成功版本加一。
     */
    @Transactional
    public CalendarResponse enableCalendar(String equipmentId, EnableCalendarRequest req) {
        Equipment equipment = lockEquipment(equipmentId);
        return idempotency.execute(req.requestId(), "ENABLE_CALENDAR",
                enableCalendarFingerprint(equipmentId, req),
                CalendarResponse.class, () -> {
                    checkVersion(equipment, req.expectedVersion());
                    if (repository.findCalendar(equipmentId).isPresent()) {
                        throw ApiException.conflict("CALENDAR_ALREADY_ENABLED",
                                "设备日历档案已启用，不可修改：" + equipmentId);
                    }
                    repository.insertCalendar(new EquipmentCalendar(equipmentId, req.commissionedAt(),
                            req.periodDays(), req.operator(), req.requestId(), clock.instant()));
                    repository.incrementVersion(equipmentId);
                    return new CalendarResponse(equipmentId, req.commissionedAt(), req.periodDays(),
                            req.operator(), equipment.version() + 1);
                });
    }

    // ---------- 新增读数 ----------

    @Transactional
    public ReadingResponse addReading(String equipmentId, AddReadingRequest req) {
        Equipment equipment = lockEquipment(equipmentId);
        return idempotency.execute(req.requestId(), "ADD_READING",
                addReadingFingerprint(equipmentId, req),
                ReadingResponse.class, () -> {
                    checkVersion(equipment, req.expectedVersion());
                    if (repository.findReading(equipmentId, req.readingId()).isPresent()) {
                        throw ApiException.conflict("READING_EXISTS", "读数已存在：" + req.readingId());
                    }
                    if (repository.findReadingAt(equipmentId, req.sampledAt()).isPresent()) {
                        throw ApiException.unprocessable("READING_TIME_DUPLICATE",
                                "同一设备同一采样时刻仅允许一条读数");
                    }
                    checkMonotonic(equipmentId, req.sampledAt(), req.cumulativeMinutes());
                    Instant now = clock.instant();
                    repository.insertReading(
                            new Reading(equipmentId, req.readingId(), req.sampledAt(),
                                    req.cumulativeMinutes(), 1),
                            now);
                    repository.insertRevision(equipmentId, req.readingId(), 1,
                            req.cumulativeMinutes(), req.requestId(), now);
                    repository.incrementVersion(equipmentId);
                    return new ReadingResponse(equipmentId, req.readingId(), req.sampledAt(),
                            req.cumulativeMinutes(), 1, false, equipment.version() + 1);
                });
    }

    // ---------- 修订读数 ----------

    @Transactional
    public ReadingResponse reviseReading(String equipmentId, String readingId, ReviseReadingRequest req) {
        Equipment equipment = lockEquipment(equipmentId);
        return idempotency.execute(req.requestId(), "REVISE_READING",
                reviseReadingFingerprint(equipmentId, readingId, req),
                ReadingResponse.class, () -> {
                    checkVersion(equipment, req.expectedVersion());
                    Reading reading = repository.findReading(equipmentId, readingId)
                            .orElseThrow(() -> ApiException.notFound("READING_NOT_FOUND",
                                    "读数不存在：" + readingId));
                    if (repository.existsMaintenanceAnchoringReading(equipmentId, readingId)) {
                        throw ApiException.conflict("READING_ANCHORED",
                                "读数已作为历史保养锚点，不可修订：" + readingId);
                    }
                    checkMonotonic(equipmentId, reading.sampledAt(), req.cumulativeMinutes());
                    int newRevisionNo = reading.revisionNo() + 1;
                    Instant now = clock.instant();
                    repository.updateReadingValue(equipmentId, readingId, req.cumulativeMinutes(),
                            newRevisionNo, now);
                    repository.insertRevision(equipmentId, readingId, newRevisionNo,
                            req.cumulativeMinutes(), req.requestId(), now);
                    repository.incrementVersion(equipmentId);
                    return new ReadingResponse(equipmentId, readingId, reading.sampledAt(),
                            req.cumulativeMinutes(), newRevisionNo, false, equipment.version() + 1);
                });
    }

    // ---------- 完成保养 ----------

    @Transactional
    public MaintenanceResponse completeMaintenance(String equipmentId, CompleteMaintenanceRequest req) {
        Equipment equipment = lockEquipment(equipmentId);
        return idempotency.execute(req.requestId(), "COMPLETE_MAINTENANCE",
                maintenanceFingerprint(equipmentId, req),
                MaintenanceResponse.class, () -> {
                    checkVersion(equipment, req.expectedVersion());
                    Reading anchor = repository.findReading(equipmentId, req.readingId())
                            .orElseThrow(() -> ApiException.notFound("READING_NOT_FOUND",
                                    "锚点读数不存在：" + req.readingId()));
                    if (anchor.revisionNo() != req.anchorRevisionNo()) {
                        throw ApiException.conflict("ANCHOR_REVISION_CONFLICT",
                                "锚点修订号与读数当前修订号不一致：期望 " + req.anchorRevisionNo()
                                        + "，当前 " + anchor.revisionNo());
                    }
                    Optional<MaintenanceRecord> last = repository.findLastMaintenance(equipmentId);
                    if (last.isPresent() && !anchor.sampledAt().isAfter(last.get().anchorSampledAt())) {
                        throw ApiException.unprocessable("ANCHOR_TIME_NOT_LATER",
                                "保养锚点时间必须晚于上次保养锚点时间");
                    }
                    Instant now = clock.instant();
                    // 时间门禁：锚点不得晚于服务端当前时间；已启用日历的设备还不得早于投用时刻
                    if (anchor.sampledAt().isAfter(now)) {
                        throw ApiException.unprocessable("ANCHOR_TIME_IN_FUTURE",
                                "保养锚点时间不能晚于服务端当前时间");
                    }
                    Optional<EquipmentCalendar> calendar = repository.findCalendar(equipmentId);
                    if (calendar.isPresent()
                            && anchor.sampledAt().isBefore(calendar.get().commissionedAt())) {
                        throw ApiException.unprocessable("ANCHOR_BEFORE_COMMISSIONING",
                                "保养锚点时间不能早于设备投用时刻");
                    }
                    // 冻结完成时的两个到期原因与本次保养后的新日历截止
                    boolean hoursDue = currentRunMinutes(equipmentId, last)
                            >= equipment.maintenancePeriodMinutes();
                    boolean calendarDue = calendar
                            .map(cal -> !now.isBefore(calendarDeadline(cal, last)))
                            .orElse(false);
                    Instant newDeadline = calendar
                            .map(cal -> anchor.sampledAt().plus(cal.periodDays(), ChronoUnit.DAYS))
                            .orElse(null);
                    long maintenanceId = repository.insertMaintenance(equipmentId, req.readingId(),
                            req.anchorRevisionNo(), anchor.sampledAt(), anchor.cumulativeMinutes(),
                            hoursDue, calendarDue, newDeadline, req.requestId(), now);
                    repository.incrementVersion(equipmentId);
                    return new MaintenanceResponse(maintenanceId, equipmentId, req.readingId(),
                            req.anchorRevisionNo(), anchor.sampledAt(), anchor.cumulativeMinutes(),
                            now, equipment.version() + 1, hoursDue, calendarDue, newDeadline);
                });
    }

    // ---------- 查询 ----------

    @Transactional(readOnly = true)
    public StatusResponse getStatus(String equipmentId) {
        Equipment equipment = repository.findEquipment(equipmentId)
                .orElseThrow(() -> equipmentNotFound(equipmentId));
        Optional<Reading> latest = repository.findLatestReading(equipmentId);
        Optional<MaintenanceRecord> last = repository.findLastMaintenance(equipmentId);
        long latestCumulative = latest.map(Reading::cumulativeMinutes).orElse(0L);
        long anchorCumulative = last.map(MaintenanceRecord::anchorCumulativeMinutes).orElse(0L);
        long runMinutes = latestCumulative - anchorCumulative;
        boolean hoursDue = runMinutes >= equipment.maintenancePeriodMinutes();
        Optional<EquipmentCalendar> calendar = repository.findCalendar(equipmentId);
        Instant deadline = calendar.map(cal -> calendarDeadline(cal, last)).orElse(null);
        boolean calendarDue = deadline != null && !clock.instant().isBefore(deadline);
        String status = hoursDue || calendarDue ? "DUE" : "OK";
        return new StatusResponse(equipmentId, equipment.version(), equipment.maintenancePeriodMinutes(),
                latest.map(Reading::sampledAt).orElse(null), latestCumulative,
                last.map(MaintenanceRecord::anchorSampledAt).orElse(null), anchorCumulative,
                runMinutes, status, hoursDue,
                calendar.isPresent(),
                calendar.map(EquipmentCalendar::periodDays).orElse(null),
                calendar.map(EquipmentCalendar::commissionedAt).orElse(null),
                deadline, calendarDue);
    }

    @Transactional(readOnly = true)
    public List<ReadingResponse> listReadings(String equipmentId) {
        Equipment equipment = repository.findEquipment(equipmentId)
                .orElseThrow(() -> equipmentNotFound(equipmentId));
        return repository.listReadings(equipmentId).stream()
                .map(reading -> new ReadingResponse(equipmentId, reading.readingId(), reading.sampledAt(),
                        reading.cumulativeMinutes(), reading.revisionNo(),
                        repository.existsMaintenanceAnchoringReading(equipmentId, reading.readingId()),
                        equipment.version()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<RevisionView> listRevisions(String equipmentId, String readingId) {
        repository.findEquipment(equipmentId).orElseThrow(() -> equipmentNotFound(equipmentId));
        repository.findReading(equipmentId, readingId)
                .orElseThrow(() -> ApiException.notFound("READING_NOT_FOUND", "读数不存在：" + readingId));
        return repository.listRevisions(equipmentId, readingId).stream()
                .map(row -> new RevisionView(row.revisionNo(), row.cumulativeMinutes(),
                        row.requestId(), row.createdAt()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<MaintenanceResponse> listMaintenances(String equipmentId) {
        repository.findEquipment(equipmentId).orElseThrow(() -> equipmentNotFound(equipmentId));
        return repository.listMaintenances(equipmentId).stream()
                .map(record -> new MaintenanceResponse(record.maintenanceId(), equipmentId,
                        record.readingId(), record.anchorRevisionNo(), record.anchorSampledAt(),
                        record.anchorCumulativeMinutes(), record.completedAt(), 0L,
                        record.hoursDue(), record.calendarDue(), record.newCalendarDeadline()))
                .toList();
    }

    // ---------- 内部规则 ----------

    private Equipment lockEquipment(String equipmentId) {
        return repository.findEquipmentForUpdate(equipmentId)
                .orElseThrow(() -> equipmentNotFound(equipmentId));
    }

    private ApiException equipmentNotFound(String equipmentId) {
        return ApiException.notFound("EQUIPMENT_NOT_FOUND", "设备不存在：" + equipmentId);
    }

    private void checkVersion(Equipment equipment, long expectedVersion) {
        if (equipment.version() != expectedVersion) {
            throw ApiException.conflict("VERSION_CONFLICT",
                    "设备版本冲突：期望 " + expectedVersion + "，当前 " + equipment.version());
        }
    }

    /** 单调性校验：新值须同时不早于前相邻读数、不晚于后相邻读数（按采样时刻排序）。 */
    private void checkMonotonic(String equipmentId, Instant sampledAt, long cumulativeMinutes) {
        Optional<Reading> prev = repository.findPrevReading(equipmentId, sampledAt);
        if (prev.isPresent() && cumulativeMinutes < prev.get().cumulativeMinutes()) {
            throw ApiException.unprocessable("READING_ORDER_VIOLATION",
                    "累计工时小于前一条读数（" + prev.get().cumulativeMinutes() + "），违反单调不减约束");
        }
        Optional<Reading> next = repository.findNextReading(equipmentId, sampledAt);
        if (next.isPresent() && cumulativeMinutes > next.get().cumulativeMinutes()) {
            throw ApiException.unprocessable("READING_ORDER_VIOLATION",
                    "累计工时大于后一条读数（" + next.get().cumulativeMinutes() + "），违反单调不减约束");
        }
    }

    /** 当前本轮运行分钟：最新读数累计工时 - 最近保养锚点工时（无保养从 0 计）。 */
    private long currentRunMinutes(String equipmentId, Optional<MaintenanceRecord> last) {
        long latestCumulative = repository.findLatestReading(equipmentId)
                .map(Reading::cumulativeMinutes).orElse(0L);
        long anchorCumulative = last.map(MaintenanceRecord::anchorCumulativeMinutes).orElse(0L);
        return latestCumulative - anchorCumulative;
    }

    /** 当前日历截止：最近保养锚点时刻 + 日历周期；无保养时从投用时刻起算。 */
    private Instant calendarDeadline(EquipmentCalendar calendar, Optional<MaintenanceRecord> last) {
        Instant base = last.map(MaintenanceRecord::anchorSampledAt).orElse(calendar.commissionedAt());
        return base.plus(calendar.periodDays(), ChronoUnit.DAYS);
    }

    // ---------- 请求指纹（外观层重放补偿与事务层共用，保证同键异参判定一致） ----------

    static String registerFingerprint(RegisterEquipmentRequest req) {
        return req.equipmentId() + "|" + req.maintenancePeriodMinutes();
    }

    static String enableCalendarFingerprint(String equipmentId, EnableCalendarRequest req) {
        return equipmentId + "|" + req.commissionedAt() + "|" + req.periodDays()
                + "|" + req.operator() + "|" + req.expectedVersion();
    }

    static String addReadingFingerprint(String equipmentId, AddReadingRequest req) {
        return equipmentId + "|" + req.readingId() + "|" + req.sampledAt()
                + "|" + req.cumulativeMinutes() + "|" + req.expectedVersion();
    }

    static String reviseReadingFingerprint(String equipmentId, String readingId,
                                           ReviseReadingRequest req) {
        return equipmentId + "|" + readingId + "|" + req.cumulativeMinutes()
                + "|" + req.expectedVersion();
    }

    static String maintenanceFingerprint(String equipmentId, CompleteMaintenanceRequest req) {
        return equipmentId + "|" + req.readingId() + "|" + req.anchorRevisionNo()
                + "|" + req.expectedVersion();
    }
}
