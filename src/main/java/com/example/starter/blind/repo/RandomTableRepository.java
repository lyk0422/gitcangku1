package com.example.starter.blind.repo;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Objects;

/**
 * 随机表版本与封存记录数据访问。
 * 版本与封存记录只增不改：封存不可撤销，扩容只新建后继版本。
 * 具体序列（席位-处理映射）仍只存于 seat 表，不通过本仓库外泄。
 */
@Repository
public class RandomTableRepository {

    /** 随机表版本行。capacity 为该版本覆盖的区组累计容量；createdAt 为 Unix 毫秒 UTC。 */
    public record VersionRow(
            long id,
            String experimentId,
            int blockNo,
            int versionNo,
            int capacity,
            String treatmentCodes,
            String tableDigest,
            Long predecessorId,
            long createdAt) {
    }

    /** 封存记录行：只保存表摘要、区组容量、处理代码集合与封存时刻，不保存 sealKey。 */
    public record SealRow(
            long id,
            String experimentId,
            int blockNo,
            long versionId,
            String tableDigest,
            int capacity,
            String treatmentCodes,
            String sealedActor,
            long sealedAt) {
    }

    private static final String VERSION_COLUMNS =
            "id, experiment_id, block_no, version_no, capacity, treatment_codes, "
                    + "table_digest, predecessor_id, created_at";

    private static final String SEAL_COLUMNS =
            "id, experiment_id, block_no, version_id, table_digest, capacity, "
                    + "treatment_codes, sealed_actor, sealed_at";

    private static final RowMapper<VersionRow> VERSION_MAPPER = (rs, n) -> new VersionRow(
            rs.getLong("id"),
            rs.getString("experiment_id"),
            rs.getInt("block_no"),
            rs.getInt("version_no"),
            rs.getInt("capacity"),
            rs.getString("treatment_codes"),
            rs.getString("table_digest"),
            (Long) rs.getObject("predecessor_id"),
            rs.getLong("created_at"));

    private static final RowMapper<SealRow> SEAL_MAPPER = (rs, n) -> new SealRow(
            rs.getLong("id"),
            rs.getString("experiment_id"),
            rs.getInt("block_no"),
            rs.getLong("version_id"),
            rs.getString("table_digest"),
            rs.getInt("capacity"),
            rs.getString("treatment_codes"),
            rs.getString("sealed_actor"),
            rs.getLong("sealed_at"));

    private final JdbcTemplate jdbc;

    public RandomTableRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入新版本并回填自增主键。
     *
     * @return 带主键的版本行
     */
    public VersionRow insertVersion(VersionRow row) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO random_table_version ("
                            + "experiment_id, block_no, version_no, capacity, treatment_codes, "
                            + "table_digest, predecessor_id, created_at"
                            + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, row.experimentId());
            ps.setInt(2, row.blockNo());
            ps.setInt(3, row.versionNo());
            ps.setInt(4, row.capacity());
            ps.setString(5, row.treatmentCodes());
            ps.setString(6, row.tableDigest());
            if (row.predecessorId() == null) {
                ps.setObject(7, null);
            } else {
                ps.setLong(7, row.predecessorId());
            }
            ps.setLong(8, row.createdAt());
            return ps;
        }, keyHolder);
        long id = Objects.requireNonNull(keyHolder.getKey(), "版本主键生成失败").longValue();
        return new VersionRow(id, row.experimentId(), row.blockNo(), row.versionNo(),
                row.capacity(), row.treatmentCodes(), row.tableDigest(),
                row.predecessorId(), row.createdAt());
    }

    public VersionRow findVersionById(long versionId) {
        List<VersionRow> rows = jdbc.query(
                "SELECT " + VERSION_COLUMNS + " FROM random_table_version WHERE id = ?",
                VERSION_MAPPER, versionId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 查询区组当前最新版本（版本号最大）。
     */
    public VersionRow findLatestVersion(String experimentId, int blockNo) {
        List<VersionRow> rows = jdbc.query(
                "SELECT " + VERSION_COLUMNS + " FROM random_table_version "
                        + "WHERE experiment_id = ? AND block_no = ? "
                        + "ORDER BY version_no DESC LIMIT 1",
                VERSION_MAPPER, experimentId, blockNo);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 区组版本历史，按版本号升序，只增不改。
     */
    public List<VersionRow> listVersions(String experimentId, int blockNo) {
        return jdbc.query(
                "SELECT " + VERSION_COLUMNS + " FROM random_table_version "
                        + "WHERE experiment_id = ? AND block_no = ? ORDER BY version_no",
                VERSION_MAPPER, experimentId, blockNo);
    }

    /**
     * 插入封存记录并回填自增主键；同一版本重复封存由 uq_rts_version 兜底。
     *
     * @return 带主键的封存记录行
     */
    public SealRow insertSeal(SealRow row) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO random_table_seal ("
                            + "experiment_id, block_no, version_id, table_digest, capacity, "
                            + "treatment_codes, sealed_actor, sealed_at"
                            + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, row.experimentId());
            ps.setInt(2, row.blockNo());
            ps.setLong(3, row.versionId());
            ps.setString(4, row.tableDigest());
            ps.setInt(5, row.capacity());
            ps.setString(6, row.treatmentCodes());
            ps.setString(7, row.sealedActor());
            ps.setLong(8, row.sealedAt());
            return ps;
        }, keyHolder);
        long id = Objects.requireNonNull(keyHolder.getKey(), "封存主键生成失败").longValue();
        return new SealRow(id, row.experimentId(), row.blockNo(), row.versionId(),
                row.tableDigest(), row.capacity(), row.treatmentCodes(),
                row.sealedActor(), row.sealedAt());
    }

    public SealRow findSealByVersion(long versionId) {
        List<SealRow> rows = jdbc.query(
                "SELECT " + SEAL_COLUMNS + " FROM random_table_seal WHERE version_id = ?",
                SEAL_MAPPER, versionId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 区组封存历史，按封存时刻与主键升序。
     */
    public List<SealRow> listSeals(String experimentId, int blockNo) {
        return jdbc.query(
                "SELECT " + SEAL_COLUMNS + " FROM random_table_seal "
                        + "WHERE experiment_id = ? AND block_no = ? ORDER BY sealed_at, id",
                SEAL_MAPPER, experimentId, blockNo);
    }
}
