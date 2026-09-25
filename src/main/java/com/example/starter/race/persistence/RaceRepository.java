package com.example.starter.race.persistence;

import com.example.starter.race.domain.EntryStatus;
import com.example.starter.race.domain.PenaltyType;
import com.example.starter.race.domain.RaceStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/**
 * 赛事封榜领域的 JDBC 仓储；所有写方法均假定运行在业务事务内，
 * 版本推进与封榜全部使用条件 UPDATE 保证并发下无丢失更新。
 */
@Repository
public class RaceRepository {

    private static final RaceRowMapper RACE_ROW_MAPPER = new RaceRowMapper();
    private static final RunnerRowMapper RUNNER_ROW_MAPPER = new RunnerRowMapper();
    private static final PenaltyRowMapper PENALTY_ROW_MAPPER = new PenaltyRowMapper();
    private static final SnapshotEntryRowMapper SNAPSHOT_ENTRY_ROW_MAPPER =
            new SnapshotEntryRowMapper();
    private static final IdempotencyRowMapper IDEMPOTENCY_ROW_MAPPER = new IdempotencyRowMapper();
    private static final CheckpointRowMapper CHECKPOINT_ROW_MAPPER = new CheckpointRowMapper();
    private static final CheckpointTimingRowMapper CHECKPOINT_TIMING_ROW_MAPPER =
            new CheckpointTimingRowMapper();
    private static final SnapshotCheckpointRowMapper SNAPSHOT_CHECKPOINT_ROW_MAPPER =
            new SnapshotCheckpointRowMapper();
    private static final InspectionConfigRowMapper INSPECTION_CONFIG_ROW_MAPPER =
            new InspectionConfigRowMapper();
    private static final EquipmentInspectionRowMapper EQUIPMENT_INSPECTION_ROW_MAPPER =
            new EquipmentInspectionRowMapper();
    private static final EquipmentBindingRowMapper EQUIPMENT_BINDING_ROW_MAPPER =
            new EquipmentBindingRowMapper();
    private static final RunnerLifecycleRowMapper RUNNER_LIFECYCLE_ROW_MAPPER =
            new RunnerLifecycleRowMapper();
    private static final RaceStartRowMapper RACE_START_ROW_MAPPER = new RaceStartRowMapper();

    private final JdbcTemplate jdbcTemplate;

    public RaceRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 按ID查询赛事。 */
    public Optional<RaceRow> findRace(String raceId) {
        return jdbcTemplate
                .query("SELECT race_id, version, status, created_at FROM race WHERE race_id = ?",
                        RACE_ROW_MAPPER, raceId)
                .stream()
                .findFirst();
    }

    /**
     * 行锁方式查询赛事：获取 race 行写锁并持有到事务提交/回滚。
     * 写操作据此与其它写者在读取任何依赖数据之前先串行化，避免 READ_COMMITTED 下
     * “先读到旧版本、后读到他人已提交明细”造成的误判（如分段重复误报422）。
     */
    public Optional<RaceRow> findRaceForUpdate(String raceId) {
        return jdbcTemplate
                .query("SELECT race_id, version, status, created_at FROM race WHERE race_id = ? FOR UPDATE",
                        RACE_ROW_MAPPER, raceId)
                .stream()
                .findFirst();
    }

    /** 查询赛事下全部选手，按参赛号字典序排列。 */
    public List<RunnerRow> findRunners(String raceId) {
        return jdbcTemplate.query(
                "SELECT id, race_id, bib, finish_time_ms, created_at, updated_at "
                        + "FROM runner WHERE race_id = ? ORDER BY bib",
                RUNNER_ROW_MAPPER, raceId);
    }

    /** 按赛事与参赛号查询选手。 */
    public Optional<RunnerRow> findRunner(String raceId, String bib) {
        return jdbcTemplate
                .query("SELECT id, race_id, bib, finish_time_ms, created_at, updated_at "
                                + "FROM runner WHERE race_id = ? AND bib = ?",
                        RUNNER_ROW_MAPPER, raceId, bib)
                .stream()
                .findFirst();
    }

    /** 查询赛事下全部处罚（含已撤销），按新增时间与处罚ID排列。 */
    public List<PenaltyRow> findPenalties(String raceId) {
        return jdbcTemplate.query(
                "SELECT penalty_id, race_id, bib, type, amount_ms, revoked, created_at, revoked_at "
                        + "FROM penalty WHERE race_id = ? ORDER BY created_at, penalty_id",
                PENALTY_ROW_MAPPER, raceId);
    }

    /** 按全局处罚ID查询处罚。 */
    public Optional<PenaltyRow> findPenalty(String penaltyId) {
        return jdbcTemplate
                .query("SELECT penalty_id, race_id, bib, type, amount_ms, revoked, created_at, revoked_at "
                                + "FROM penalty WHERE penalty_id = ?",
                        PENALTY_ROW_MAPPER, penaltyId)
                .stream()
                .findFirst();
    }

    /** 查询赛事的全部检查点，按顺序 position 升序排列；未配置时为空列表。 */
    public List<CheckpointRow> findCheckpoints(String raceId) {
        return jdbcTemplate.query(
                "SELECT race_id, checkpoint_code, position, created_at "
                        + "FROM checkpoint WHERE race_id = ? ORDER BY position",
                CHECKPOINT_ROW_MAPPER, raceId);
    }

    /** 按赛事与代码查询检查点。 */
    public Optional<CheckpointRow> findCheckpoint(String raceId, String checkpointCode) {
        return jdbcTemplate
                .query("SELECT race_id, checkpoint_code, position, created_at "
                                + "FROM checkpoint WHERE race_id = ? AND checkpoint_code = ?",
                        CHECKPOINT_ROW_MAPPER, raceId, checkpointCode)
                .stream()
                .findFirst();
    }

    /** 统计赛事下的分段记录总数（用于“尚无任何分段记录时才可配置检查点”）。 */
    public int countTimings(String raceId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM checkpoint_timing WHERE race_id = ?",
                Integer.class, raceId);
        return count == null ? 0 : count;
    }

    /** 查询某选手的全部分段记录，按 position 升序排列。 */
    public List<CheckpointTimingRow> findTimingsForRunner(String raceId, String bib) {
        return jdbcTemplate.query(
                "SELECT timing_id, race_id, bib, checkpoint_code, position, elapsed_millis, created_at "
                        + "FROM checkpoint_timing WHERE race_id = ? AND bib = ? ORDER BY position",
                CHECKPOINT_TIMING_ROW_MAPPER, raceId, bib);
    }

    /** 查询赛事下全部分段记录（用于实时成绩与封榜计算）。 */
    public List<CheckpointTimingRow> findAllTimings(String raceId) {
        return jdbcTemplate.query(
                "SELECT timing_id, race_id, bib, checkpoint_code, position, elapsed_millis, created_at "
                        + "FROM checkpoint_timing WHERE race_id = ? ORDER BY bib, position",
                CHECKPOINT_TIMING_ROW_MAPPER, raceId);
    }

    /** 按全局分段ID查询记录（用于 timingId 幂等重放）。 */
    public Optional<CheckpointTimingRow> findTiming(String timingId) {
        return jdbcTemplate
                .query("SELECT timing_id, race_id, bib, checkpoint_code, position, elapsed_millis, created_at "
                                + "FROM checkpoint_timing WHERE timing_id = ?",
                        CHECKPOINT_TIMING_ROW_MAPPER, timingId)
                .stream()
                .findFirst();
    }

    /** 新增选手分段通过记录（全局唯一 timingId 与选手+检查点唯一键由数据库约束保证）。 */
    public void insertTiming(CheckpointTimingRow row) {
        jdbcTemplate.update(
                "INSERT INTO checkpoint_timing "
                        + "(timing_id, race_id, bib, checkpoint_code, position, elapsed_millis, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                row.timingId(), row.raceId(), row.bib(), row.checkpointCode(),
                row.position(), row.elapsedMillis(), row.createdAt());
    }

    /** 一次性写入赛事检查点配置（配置后不可修改）。 */
    public void insertCheckpoints(List<CheckpointRow> rows) {
        jdbcTemplate.batchUpdate(
                "INSERT INTO checkpoint (race_id, checkpoint_code, position, created_at) "
                        + "VALUES (?, ?, ?, ?)",
                rows,
                rows.size(),
                (ps, row) -> {
                    ps.setString(1, row.raceId());
                    ps.setString(2, row.checkpointCode());
                    ps.setInt(3, row.position());
                    ps.setLong(4, row.createdAt());
                });
    }

    /** 查询封榜快照（含全部条目与分段明细）；未封榜返回 empty。 */
    public Optional<SnapshotRow> findSnapshot(String raceId) {
        List<SnapshotRow> headers = jdbcTemplate.query(
                "SELECT race_id, version, sealed_at FROM result_snapshot WHERE race_id = ?",
                (rs, rowNum) -> new SnapshotRow(
                        rs.getString("race_id"),
                        rs.getInt("version"),
                        rs.getLong("sealed_at"),
                        List.of()),
                raceId);
        if (headers.isEmpty()) {
            return Optional.empty();
        }
        SnapshotRow header = headers.getFirst();
        List<SnapshotEntryRow> entryRows = jdbcTemplate.query(
                "SELECT race_id, bib, rank_no, status, finish_time_ms, penalty_ms, total_time_ms, "
                        + "display_order, checkpoint_count, covered_checkpoint_count "
                        + "FROM result_snapshot_entry WHERE race_id = ? ORDER BY display_order",
                SNAPSHOT_ENTRY_ROW_MAPPER, raceId);
        List<SnapshotCheckpointRow> checkpoints = jdbcTemplate.query(
                "SELECT race_id, bib, checkpoint_code, position, elapsed_millis, timing_id "
                        + "FROM result_snapshot_checkpoint WHERE race_id = ? "
                        + "ORDER BY bib, position",
                SNAPSHOT_CHECKPOINT_ROW_MAPPER, raceId);
        // 缺失检查点由明细表中 elapsed_millis 为 NULL 的行派生，保持展示顺序稳定。
        java.util.Map<String, List<String>> missingByBib = new java.util.LinkedHashMap<>();
        for (SnapshotCheckpointRow detail : checkpoints) {
            if (detail.elapsedMillis() == null) {
                missingByBib.computeIfAbsent(detail.bib(), key -> new java.util.ArrayList<>())
                        .add(detail.checkpointCode());
            }
        }
        List<SnapshotEntryRow> entries = entryRows.stream()
                .map(entry -> new SnapshotEntryRow(
                        entry.raceId(), entry.bib(), entry.rank(), entry.status(),
                        entry.finishTimeMs(), entry.penaltyMs(), entry.totalTimeMs(),
                        entry.displayOrder(), entry.checkpointCount(),
                        entry.coveredCheckpointCount(),
                        missingByBib.getOrDefault(entry.bib(), List.of())))
                .toList();
        return Optional.of(new SnapshotRow(header.raceId(), header.version(), header.sealedAt(),
                entries, checkpoints));
    }

    /** 新建赛事，初始版本1、状态OPEN。 */
    public void insertRace(String raceId, long now) {
        jdbcTemplate.update(
                "INSERT INTO race (race_id, version, status, created_at) VALUES (?, 1, 'OPEN', ?)",
                raceId, now);
    }

    /** 登记选手；finishTimeMs 为 null 表示计时缺失。 */
    public void insertRunner(String raceId, String bib, Long finishTimeMs, long now) {
        jdbcTemplate.update(
                "INSERT INTO runner (race_id, bib, finish_time_ms, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?)",
                raceId, bib, finishTimeMs, now, now);
    }

    /** 修订选手原始完赛耗时；返回受影响行数（0 表示选手不存在）。 */
    public int updateRunnerTiming(String raceId, String bib, Long finishTimeMs, long now) {
        return jdbcTemplate.update(
                "UPDATE runner SET finish_time_ms = ?, updated_at = ? WHERE race_id = ? AND bib = ?",
                finishTimeMs, now, raceId, bib);
    }

    /** 新增处罚（不可覆盖历史）。 */
    public void insertPenalty(
            String penaltyId,
            String raceId,
            String bib,
            PenaltyType type,
            Long amountMs,
            long now) {
        jdbcTemplate.update(
                "INSERT INTO penalty "
                        + "(penalty_id, race_id, bib, type, amount_ms, revoked, created_at, revoked_at) "
                        + "VALUES (?, ?, ?, ?, ?, FALSE, ?, NULL)",
                penaltyId, raceId, bib, type.name(), amountMs, now);
    }

    /** 撤销处罚，仅对当前未撤销的处罚生效；返回受影响行数。 */
    public int markPenaltyRevoked(String penaltyId, long now) {
        return jdbcTemplate.update(
                "UPDATE penalty SET revoked = TRUE, revoked_at = ? WHERE penalty_id = ? AND revoked = FALSE",
                now, penaltyId);
    }

    /**
     * 条件推进版本：仅当赛事仍为 OPEN 且版本等于 expectedVersion 时加一。
     *
     * @return 受影响行数；0 表示赛事不存在、已封榜或版本已被其他写操作推进
     */
    public int bumpVersionIfOpen(String raceId, int expectedVersion) {
        return jdbcTemplate.update(
                "UPDATE race SET version = version + 1 "
                        + "WHERE race_id = ? AND version = ? AND status = 'OPEN'",
                raceId, expectedVersion);
    }

    /**
     * 条件封榜：仅当赛事仍为 OPEN 且版本等于 expectedVersion 时转为 SEALED 并推进版本。
     *
     * @return 受影响行数；0 表示不存在、已封榜或版本不匹配
     */
    public int sealIfOpenAtVersion(String raceId, int expectedVersion, int newVersion) {
        return jdbcTemplate.update(
                "UPDATE race SET version = ?, status = 'SEALED' "
                        + "WHERE race_id = ? AND version = ? AND status = 'OPEN'",
                newVersion, raceId, expectedVersion);
    }

    /** 原子写入封榜快照头表、全部条目以及每名选手的分段明细。 */
    public void insertSnapshot(SnapshotRow snapshot) {
        jdbcTemplate.update(
                "INSERT INTO result_snapshot (race_id, version, sealed_at) VALUES (?, ?, ?)",
                snapshot.raceId(), snapshot.version(), snapshot.sealedAt());
        jdbcTemplate.batchUpdate(
                "INSERT INTO result_snapshot_entry "
                        + "(race_id, bib, rank_no, status, finish_time_ms, penalty_ms, total_time_ms, "
                        + "display_order, checkpoint_count, covered_checkpoint_count) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                snapshot.entries(),
                snapshot.entries().size(),
                (ps, entry) -> {
                    ps.setString(1, entry.raceId());
                    ps.setString(2, entry.bib());
                    ps.setObject(3, entry.rank());
                    ps.setString(4, entry.status().name());
                    ps.setObject(5, entry.finishTimeMs());
                    ps.setLong(6, entry.penaltyMs());
                    ps.setObject(7, entry.totalTimeMs());
                    ps.setInt(8, entry.displayOrder());
                    ps.setInt(9, entry.checkpointCount());
                    ps.setInt(10, entry.coveredCheckpointCount());
                });
        jdbcTemplate.batchUpdate(
                "INSERT INTO result_snapshot_checkpoint "
                        + "(race_id, bib, checkpoint_code, position, elapsed_millis, timing_id) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                snapshot.checkpoints(),
                snapshot.checkpoints().size(),
                (ps, detail) -> {
                    ps.setString(1, detail.raceId());
                    ps.setString(2, detail.bib());
                    ps.setString(3, detail.checkpointCode());
                    ps.setInt(4, detail.position());
                    ps.setObject(5, detail.elapsedMillis());
                    ps.setString(6, detail.timingId());
                });
    }

    /** 按请求ID查询幂等记录。 */
    public Optional<IdempotencyRow> findIdempotency(String requestId) {
        return jdbcTemplate
                .query("SELECT request_id, operation, request_digest, response_status, response_body, created_at "
                                + "FROM idempotency_record WHERE request_id = ?",
                        IDEMPOTENCY_ROW_MAPPER, requestId)
                .stream()
                .findFirst();
    }

    /**
     * 插入幂等占位行（response_status=0 表示进行中），同事务失败回滚即不占键。
     * 并发同键时由唯一约束拒绝，调用方再通过 {@link #findIdempotencyForUpdate} 等待先行者结束。
     */
    public void insertIdempotencyPlaceholder(
            String requestId, String operation, String requestDigest, long now) {
        jdbcTemplate.update(
                "INSERT INTO idempotency_record "
                        + "(request_id, operation, request_digest, response_status, response_body, created_at) "
                        + "VALUES (?, ?, ?, 0, '', ?)",
                requestId, operation, requestDigest, now);
    }

    /**
     * 行锁方式查询幂等记录：若先行者事务未结束则阻塞至其提交或回滚；
     * 回滚后占位行随事务消失，返回 empty，调用方可重新占位执行。
     */
    public Optional<IdempotencyRow> findIdempotencyForUpdate(String requestId) {
        return jdbcTemplate
                .query("SELECT request_id, operation, request_digest, response_status, response_body, created_at "
                                + "FROM idempotency_record WHERE request_id = ? FOR UPDATE",
                        IDEMPOTENCY_ROW_MAPPER, requestId)
                .stream()
                .findFirst();
    }

    /** 业务成功后把占位行补写为最终响应，与业务变更同事务原子提交。 */
    public void completeIdempotency(String requestId, int responseStatus, String responseBody) {
        jdbcTemplate.update(
                "UPDATE idempotency_record SET response_status = ?, response_body = ? "
                        + "WHERE request_id = ?",
                responseStatus, responseBody, requestId);
    }

    /** 查询赛事检录配置；未配置（非强制检录）返回 empty。 */
    public Optional<InspectionConfigRow> findInspectionConfig(String raceId) {
        return jdbcTemplate
                .query("SELECT race_id, inspection_required, valid_minutes, created_at "
                                + "FROM race_inspection_config WHERE race_id = ?",
                        INSPECTION_CONFIG_ROW_MAPPER, raceId)
                .stream()
                .findFirst();
    }

    /** 写入赛事检录配置（建赛时一次性写入，之后不可修改）。 */
    public void insertInspectionConfig(InspectionConfigRow row) {
        jdbcTemplate.update(
                "INSERT INTO race_inspection_config (race_id, inspection_required, valid_minutes, created_at) "
                        + "VALUES (?, ?, ?, ?)",
                row.raceId(), row.inspectionRequired(), row.validMinutes(), row.createdAt());
    }

    /** 按检录键查询检录记录（用于 inspectionKey 幂等重放）。 */
    public Optional<EquipmentInspectionRow> findInspection(String inspectionId) {
        return jdbcTemplate
                .query("SELECT inspection_id, race_id, bib, equipment_serial, result, valid_minutes, "
                                + "inspected_at, valid_until, created_at "
                                + "FROM equipment_inspection WHERE inspection_id = ?",
                        EQUIPMENT_INSPECTION_ROW_MAPPER, inspectionId)
                .stream()
                .findFirst();
    }

    /** 查询某选手的全部检录历史，按提交序号升序（只追加、不可变）。 */
    public List<EquipmentInspectionRow> findInspectionsForRunner(String raceId, String bib) {
        return jdbcTemplate.query(
                "SELECT inspection_id, race_id, bib, equipment_serial, result, valid_minutes, "
                        + "inspected_at, valid_until, created_at "
                        + "FROM equipment_inspection WHERE race_id = ? AND bib = ? "
                        + "ORDER BY seq, inspected_at, inspection_id",
                EQUIPMENT_INSPECTION_ROW_MAPPER, raceId, bib);
    }

    /** 查询某选手最近一条检录（当前有效检录）；无检录返回 empty。 */
    public Optional<EquipmentInspectionRow> findLatestInspection(String raceId, String bib) {
        return jdbcTemplate
                .query("SELECT inspection_id, race_id, bib, equipment_serial, result, valid_minutes, "
                                + "inspected_at, valid_until, created_at "
                                + "FROM equipment_inspection WHERE race_id = ? AND bib = ? "
                                + "ORDER BY seq DESC LIMIT 1",
                        EQUIPMENT_INSPECTION_ROW_MAPPER, raceId, bib)
                .stream()
                .findFirst();
    }

    /** 追加一条不可变检录历史记录。 */
    public void insertInspection(EquipmentInspectionRow row) {
        jdbcTemplate.update(
                "INSERT INTO equipment_inspection "
                        + "(inspection_id, race_id, bib, equipment_serial, result, valid_minutes, "
                        + "inspected_at, valid_until, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                row.inspectionId(), row.raceId(), row.bib(), row.equipmentSerial(),
                row.result().name(), row.validMinutes(), row.inspectedAt(),
                row.validUntil(), row.createdAt());
    }

    /** 查询赛事下某器材序列号的当前绑定；无绑定返回 empty。 */
    public Optional<EquipmentBindingRow> findBinding(String raceId, String equipmentSerial) {
        return jdbcTemplate
                .query("SELECT race_id, equipment_serial, bib, inspection_id, bound_at "
                                + "FROM equipment_binding WHERE race_id = ? AND equipment_serial = ?",
                        EQUIPMENT_BINDING_ROW_MAPPER, raceId, equipmentSerial)
                .stream()
                .findFirst();
    }

    /** 查询赛事全部器材当前绑定，按器材序列号字典序。 */
    public List<EquipmentBindingRow> findBindings(String raceId) {
        return jdbcTemplate.query(
                "SELECT race_id, equipment_serial, bib, inspection_id, bound_at "
                        + "FROM equipment_binding WHERE race_id = ? ORDER BY equipment_serial",
                EQUIPMENT_BINDING_ROW_MAPPER, raceId);
    }

    /** 查询某选手当前绑定的全部器材序列号。 */
    public List<EquipmentBindingRow> findBindingsForRunner(String raceId, String bib) {
        return jdbcTemplate.query(
                "SELECT race_id, equipment_serial, bib, inspection_id, bound_at "
                        + "FROM equipment_binding WHERE race_id = ? AND bib = ? ORDER BY equipment_serial",
                EQUIPMENT_BINDING_ROW_MAPPER, raceId, bib);
    }

    /** 新增器材绑定；唯一键冲突由调用方捕获并转为409。 */
    public void insertBinding(EquipmentBindingRow row) {
        jdbcTemplate.update(
                "INSERT INTO equipment_binding (race_id, equipment_serial, bib, inspection_id, bound_at) "
                        + "VALUES (?, ?, ?, ?, ?)",
                row.raceId(), row.equipmentSerial(), row.bib(), row.inspectionId(), row.boundAt());
    }

    /** 释放某选手在赛事下的全部器材绑定（退赛/取消资格/完赛时调用）；返回释放条数。 */
    public int deleteBindingsForRunner(String raceId, String bib) {
        return jdbcTemplate.update(
                "DELETE FROM equipment_binding WHERE race_id = ? AND bib = ?", raceId, bib);
    }

    /** 查询选手生命周期行；缺失行按 REGISTERED 未起跑处理。 */
    public Optional<RunnerLifecycleRow> findLifecycle(String raceId, String bib) {
        return jdbcTemplate
                .query("SELECT race_id, bib, status, started_at, created_at, updated_at "
                                + "FROM runner_lifecycle WHERE race_id = ? AND bib = ?",
                        RUNNER_LIFECYCLE_ROW_MAPPER, raceId, bib)
                .stream()
                .findFirst();
    }

    /** 新建生命周期行（首次起跑或退赛时按需创建）。 */
    public void insertLifecycle(RunnerLifecycleRow row) {
        jdbcTemplate.update(
                "INSERT INTO runner_lifecycle (race_id, bib, status, started_at, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                row.raceId(), row.bib(), row.status().name(), row.startedAt(),
                row.createdAt(), row.updatedAt());
    }

    /** 更新生命周期状态；返回受影响行数。 */
    public int updateLifecycleStatus(
            String raceId, String bib, com.example.starter.race.domain.RunnerLifecycleStatus status,
            Long startedAt, long now) {
        return jdbcTemplate.update(
                "UPDATE runner_lifecycle SET status = ?, started_at = ?, updated_at = ? "
                        + "WHERE race_id = ? AND bib = ?",
                status.name(), startedAt, now, raceId, bib);
    }

    /** 按起跑键查询起跑记录（用于 startId 幂等重放）。 */
    public Optional<RaceStartRow> findStart(String startId) {
        return jdbcTemplate
                .query("SELECT start_id, race_id, bib, started_at FROM race_start WHERE start_id = ?",
                        RACE_START_ROW_MAPPER, startId)
                .stream()
                .findFirst();
    }

    /** 查询某选手的起跑记录；未起跑返回 empty。 */
    public Optional<RaceStartRow> findStartForRunner(String raceId, String bib) {
        return jdbcTemplate
                .query("SELECT start_id, race_id, bib, started_at "
                                + "FROM race_start WHERE race_id = ? AND bib = ?",
                        RACE_START_ROW_MAPPER, raceId, bib)
                .stream()
                .findFirst();
    }

    /** 写入起跑记录；同一选手同一赛事的唯一键冲突由调用方捕获。 */
    public void insertStart(RaceStartRow row) {
        jdbcTemplate.update(
                "INSERT INTO race_start (start_id, race_id, bib, started_at) VALUES (?, ?, ?, ?)",
                row.startId(), row.raceId(), row.bib(), row.startedAt());
    }

    /** 测试辅助：清空全部业务数据，按外键依赖顺序删除。 */
    public void deleteAllForTesting() {
        jdbcTemplate.update("DELETE FROM result_snapshot_checkpoint");
        jdbcTemplate.update("DELETE FROM result_snapshot_entry");
        jdbcTemplate.update("DELETE FROM result_snapshot");
        jdbcTemplate.update("DELETE FROM idempotency_record");
        jdbcTemplate.update("DELETE FROM race_start");
        jdbcTemplate.update("DELETE FROM runner_lifecycle");
        jdbcTemplate.update("DELETE FROM equipment_binding");
        jdbcTemplate.update("DELETE FROM equipment_inspection");
        jdbcTemplate.update("DELETE FROM race_inspection_config");
        jdbcTemplate.update("DELETE FROM checkpoint_timing");
        jdbcTemplate.update("DELETE FROM checkpoint");
        jdbcTemplate.update("DELETE FROM penalty");
        jdbcTemplate.update("DELETE FROM runner");
        jdbcTemplate.update("DELETE FROM race");
    }

    private static final class RaceRowMapper implements RowMapper<RaceRow> {
        @Override
        public RaceRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new RaceRow(
                    rs.getString("race_id"),
                    rs.getInt("version"),
                    RaceStatus.valueOf(rs.getString("status")),
                    rs.getLong("created_at"));
        }
    }

    private static final class RunnerRowMapper implements RowMapper<RunnerRow> {
        @Override
        public RunnerRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            Long finishTimeMs = (Long) rs.getObject("finish_time_ms");
            return new RunnerRow(
                    rs.getLong("id"),
                    rs.getString("race_id"),
                    rs.getString("bib"),
                    finishTimeMs,
                    rs.getLong("created_at"),
                    rs.getLong("updated_at"));
        }
    }

    private static final class PenaltyRowMapper implements RowMapper<PenaltyRow> {
        @Override
        public PenaltyRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new PenaltyRow(
                    rs.getString("penalty_id"),
                    rs.getString("race_id"),
                    rs.getString("bib"),
                    PenaltyType.valueOf(rs.getString("type")),
                    (Long) rs.getObject("amount_ms"),
                    rs.getBoolean("revoked"),
                    rs.getLong("created_at"),
                    (Long) rs.getObject("revoked_at"));
        }
    }

    private static final class SnapshotEntryRowMapper implements RowMapper<SnapshotEntryRow> {
        @Override
        public SnapshotEntryRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new SnapshotEntryRow(
                    rs.getString("race_id"),
                    rs.getString("bib"),
                    (Integer) rs.getObject("rank_no"),
                    EntryStatus.valueOf(rs.getString("status")),
                    (Long) rs.getObject("finish_time_ms"),
                    rs.getLong("penalty_ms"),
                    (Long) rs.getObject("total_time_ms"),
                    rs.getInt("display_order"),
                    rs.getInt("checkpoint_count"),
                    rs.getInt("covered_checkpoint_count"),
                    List.of());
        }
    }

    private static final class CheckpointRowMapper implements RowMapper<CheckpointRow> {
        @Override
        public CheckpointRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new CheckpointRow(
                    rs.getString("race_id"),
                    rs.getString("checkpoint_code"),
                    rs.getInt("position"),
                    rs.getLong("created_at"));
        }
    }

    private static final class CheckpointTimingRowMapper implements RowMapper<CheckpointTimingRow> {
        @Override
        public CheckpointTimingRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new CheckpointTimingRow(
                    rs.getString("timing_id"),
                    rs.getString("race_id"),
                    rs.getString("bib"),
                    rs.getString("checkpoint_code"),
                    rs.getInt("position"),
                    rs.getLong("elapsed_millis"),
                    rs.getLong("created_at"));
        }
    }

    private static final class SnapshotCheckpointRowMapper
            implements RowMapper<SnapshotCheckpointRow> {
        @Override
        public SnapshotCheckpointRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new SnapshotCheckpointRow(
                    rs.getString("race_id"),
                    rs.getString("bib"),
                    rs.getString("checkpoint_code"),
                    rs.getInt("position"),
                    (Long) rs.getObject("elapsed_millis"),
                    rs.getString("timing_id"));
        }
    }

    private static final class IdempotencyRowMapper implements RowMapper<IdempotencyRow> {
        @Override
        public IdempotencyRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new IdempotencyRow(
                    rs.getString("request_id"),
                    rs.getString("operation"),
                    rs.getString("request_digest"),
                    rs.getInt("response_status"),
                    rs.getString("response_body"),
                    rs.getLong("created_at"));
        }
    }

    private static final class InspectionConfigRowMapper implements RowMapper<InspectionConfigRow> {
        @Override
        public InspectionConfigRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new InspectionConfigRow(
                    rs.getString("race_id"),
                    rs.getBoolean("inspection_required"),
                    rs.getInt("valid_minutes"),
                    rs.getLong("created_at"));
        }
    }

    private static final class EquipmentInspectionRowMapper
            implements RowMapper<EquipmentInspectionRow> {
        @Override
        public EquipmentInspectionRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new EquipmentInspectionRow(
                    rs.getString("inspection_id"),
                    rs.getString("race_id"),
                    rs.getString("bib"),
                    rs.getString("equipment_serial"),
                    com.example.starter.race.domain.InspectionResult.valueOf(rs.getString("result")),
                    rs.getInt("valid_minutes"),
                    rs.getLong("inspected_at"),
                    (Long) rs.getObject("valid_until"),
                    rs.getLong("created_at"));
        }
    }

    private static final class EquipmentBindingRowMapper implements RowMapper<EquipmentBindingRow> {
        @Override
        public EquipmentBindingRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new EquipmentBindingRow(
                    rs.getString("race_id"),
                    rs.getString("equipment_serial"),
                    rs.getString("bib"),
                    rs.getString("inspection_id"),
                    rs.getLong("bound_at"));
        }
    }

    private static final class RunnerLifecycleRowMapper implements RowMapper<RunnerLifecycleRow> {
        @Override
        public RunnerLifecycleRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new RunnerLifecycleRow(
                    rs.getString("race_id"),
                    rs.getString("bib"),
                    com.example.starter.race.domain.RunnerLifecycleStatus
                            .valueOf(rs.getString("status")),
                    (Long) rs.getObject("started_at"),
                    rs.getLong("created_at"),
                    rs.getLong("updated_at"));
        }
    }

    private static final class RaceStartRowMapper implements RowMapper<RaceStartRow> {
        @Override
        public RaceStartRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new RaceStartRow(
                    rs.getString("start_id"),
                    rs.getString("race_id"),
                    rs.getString("bib"),
                    rs.getLong("started_at"));
        }
    }
}
