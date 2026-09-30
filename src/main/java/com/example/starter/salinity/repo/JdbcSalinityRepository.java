package com.example.starter.salinity.repo;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import com.example.starter.salinity.po.ProfileErratum;
import com.example.starter.salinity.po.SalinitySample;
import com.example.starter.salinity.po.SealedProfilePoint;
import com.example.starter.salinity.po.Voyage;
import com.example.starter.salinity.po.VoyageStatus;

/**
 * 基于 {@link JdbcTemplate} 的持久化实现，面向 H2（MODE=MySQL）。
 * 所有 SQL 参数均使用绑定变量，时间一律以 UTC 写入 {@code TIMESTAMP(3)}。
 */
@Repository
public class JdbcSalinityRepository implements SalinityRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcSalinityRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static Instant instant(ResultSet rs, String column) throws java.sql.SQLException {
        // H2（MODE=MySQL）TIMESTAMP 不带时区；读写都按驱动默认时区，
        // 配合 Timestamp.from / toInstant 保证 Instant 往返一致（业务时刻以 UTC 语义保存）。
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }

    private static final RowMapper<Voyage> VOYAGE_MAPPER = (rs, rowNum) -> new Voyage(
            rs.getLong("id"),
            rs.getString("voyage_code"),
            VoyageStatus.valueOf(rs.getString("status")),
            instant(rs, "sealed_utc"),
            instant(rs, "created_utc")
    );

    private static final RowMapper<SalinitySample> SAMPLE_MAPPER = (rs, rowNum) -> new SalinitySample(
            rs.getLong("id"),
            rs.getString("voyage_code"),
            rs.getString("station_code"),
            rs.getInt("depth_m"),
            rs.getBigDecimal("salinity_psu"),
            instant(rs, "collected_utc"),
            rs.getString("sample_no"),
            rs.getString("request_id"),
            rs.getBoolean("adopted"),
            rs.getObject("superseded_by", Long.class),
            instant(rs, "created_utc")
    );

    private static final RowMapper<SealedProfilePoint> PROFILE_MAPPER = (rs, rowNum) -> new SealedProfilePoint(
            rs.getLong("id"),
            rs.getString("voyage_code"),
            rs.getString("station_code"),
            rs.getInt("depth_m"),
            rs.getLong("sample_id"),
            rs.getBigDecimal("salinity_psu"),
            instant(rs, "collected_utc"),
            instant(rs, "sealed_utc")
    );

    private static final RowMapper<ProfileErratum> ERRATUM_MAPPER = (rs, rowNum) -> new ProfileErratum(
            rs.getLong("id"),
            rs.getString("voyage_code"),
            rs.getString("note"),
            instant(rs, "ref_sealed_utc"),
            instant(rs, "created_utc")
    );

    @Override
    public void registerVoyageIfAbsent(String voyageCode, Instant now) {
        jdbcTemplate.update(
                "INSERT INTO voyage (voyage_code, status, sealed_utc, created_utc) "
                        + "SELECT ?, 'OPEN', NULL, ? WHERE NOT EXISTS (SELECT 1 FROM voyage WHERE voyage_code = ?)",
                voyageCode, Timestamp.from(now), voyageCode);
    }

    @Override
    public Optional<Voyage> findVoyage(String voyageCode) {
        List<Voyage> voyages = jdbcTemplate.query(
                "SELECT id, voyage_code, status, sealed_utc, created_utc FROM voyage WHERE voyage_code = ?",
                VOYAGE_MAPPER, voyageCode);
        return voyages.stream().findFirst();
    }

    @Override
    public SalinitySample insertSample(SalinitySample sample) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO salinity_sample (voyage_code, station_code, depth_m, salinity_psu, "
                            + "collected_utc, sample_no, request_id, adopted, superseded_by, created_utc) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, sample.voyageCode());
            ps.setString(2, sample.stationCode());
            ps.setInt(3, sample.depthM());
            ps.setBigDecimal(4, sample.salinityPsu());
            ps.setTimestamp(5, Timestamp.from(sample.collectedUtc()));
            ps.setString(6, sample.sampleNo());
            if (sample.requestId() == null) {
                ps.setNull(7, Types.VARCHAR);
            } else {
                ps.setString(7, sample.requestId());
            }
            ps.setBoolean(8, sample.adopted());
            if (sample.supersededBy() == null) {
                ps.setNull(9, Types.BIGINT);
            } else {
                ps.setLong(9, sample.supersededBy());
            }
            ps.setTimestamp(10, Timestamp.from(sample.createdUtc()));
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        return new SalinitySample(
                key == null ? null : key.longValue(),
                sample.voyageCode(),
                sample.stationCode(),
                sample.depthM(),
                sample.salinityPsu(),
                sample.collectedUtc(),
                sample.sampleNo(),
                sample.requestId(),
                sample.adopted(),
                sample.supersededBy(),
                sample.createdUtc());
    }

    @Override
    public Optional<SalinitySample> findSampleByNo(String sampleNo) {
        List<SalinitySample> samples = jdbcTemplate.query(
                "SELECT * FROM salinity_sample WHERE sample_no = ?", SAMPLE_MAPPER, sampleNo);
        return samples.stream().findFirst();
    }

    @Override
    public Optional<SalinitySample> findSampleByRequestId(String requestId) {
        if (requestId == null) {
            return Optional.empty();
        }
        List<SalinitySample> samples = jdbcTemplate.query(
                "SELECT * FROM salinity_sample WHERE request_id = ?", SAMPLE_MAPPER, requestId);
        return samples.stream().findFirst();
    }

    @Override
    public List<SalinitySample> findSamples(String voyageCode) {
        return jdbcTemplate.query(
                "SELECT * FROM salinity_sample WHERE voyage_code = ? ORDER BY id",
                SAMPLE_MAPPER, voyageCode);
    }

    @Override
    public Optional<Voyage> lockVoyage(String voyageCode) {
        List<Voyage> voyages = jdbcTemplate.query(
                "SELECT id, voyage_code, status, sealed_utc, created_utc FROM voyage "
                        + "WHERE voyage_code = ? FOR UPDATE",
                VOYAGE_MAPPER, voyageCode);
        return voyages.stream().findFirst();
    }

    @Override
    public void markSealed(String voyageCode, Instant sealedUtc) {
        jdbcTemplate.update(
                "UPDATE voyage SET status = 'SEALED', sealed_utc = ? WHERE voyage_code = ? AND status = 'OPEN'",
                Timestamp.from(sealedUtc), voyageCode);
    }

    @Override
    public List<SalinitySample> lockPosition(String voyageCode, String stationCode, int depthM) {
        return jdbcTemplate.query(
                "SELECT * FROM salinity_sample WHERE voyage_code = ? AND station_code = ? AND depth_m = ? "
                        + "ORDER BY id FOR UPDATE",
                SAMPLE_MAPPER, voyageCode, stationCode, depthM);
    }

    @Override
    public void demoteAdopted(String voyageCode, String stationCode, int depthM, Long replacementId) {
        jdbcTemplate.update(
                "UPDATE salinity_sample SET adopted = FALSE, superseded_by = ? "
                        + "WHERE voyage_code = ? AND station_code = ? AND depth_m = ? AND adopted = TRUE",
                replacementId, voyageCode, stationCode, depthM);
    }

    @Override
    public void markAdopted(Long targetId) {
        jdbcTemplate.update("UPDATE salinity_sample SET adopted = TRUE WHERE id = ?", targetId);
    }

    @Override
    public Optional<SalinitySample> findAdopted(String voyageCode, String stationCode, int depthM) {
        List<SalinitySample> samples = jdbcTemplate.query(
                "SELECT * FROM salinity_sample WHERE voyage_code = ? AND station_code = ? AND depth_m = ? "
                        + "AND adopted = TRUE",
                SAMPLE_MAPPER, voyageCode, stationCode, depthM);
        return samples.stream().findFirst();
    }

    @Override
    public List<SalinitySample> lockAndLoadAdoptedSnapshot(String voyageCode) {
        return jdbcTemplate.query(
                "SELECT * FROM salinity_sample WHERE voyage_code = ? AND adopted = TRUE "
                        + "ORDER BY station_code, depth_m FOR UPDATE",
                SAMPLE_MAPPER, voyageCode);
    }

    @Override
    public void insertProfilePoints(List<SealedProfilePoint> points) {
        jdbcTemplate.batchUpdate(
                "INSERT INTO sealed_profile_point "
                        + "(voyage_code, station_code, depth_m, sample_id, salinity_psu, collected_utc, sealed_utc) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                points,
                points.size(),
                (ps, point) -> {
                    ps.setString(1, point.voyageCode());
                    ps.setString(2, point.stationCode());
                    ps.setInt(3, point.depthM());
                    ps.setLong(4, point.sampleId());
                    ps.setBigDecimal(5, point.salinityPsu());
                    ps.setTimestamp(6, Timestamp.from(point.collectedUtc()));
                    ps.setTimestamp(7, Timestamp.from(point.sealedUtc()));
                });
    }

    @Override
    public List<SealedProfilePoint> findProfilePoints(String voyageCode) {
        return jdbcTemplate.query(
                "SELECT * FROM sealed_profile_point WHERE voyage_code = ? ORDER BY station_code, depth_m",
                PROFILE_MAPPER, voyageCode);
    }

    @Override
    public ProfileErratum insertErratum(ProfileErratum erratum) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO profile_erratum (voyage_code, note, ref_sealed_utc, created_utc) "
                            + "VALUES (?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, erratum.voyageCode());
            ps.setString(2, erratum.note());
            ps.setTimestamp(3, Timestamp.from(erratum.refSealedUtc()));
            ps.setTimestamp(4, Timestamp.from(erratum.createdUtc()));
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        return new ProfileErratum(
                key == null ? null : key.longValue(),
                erratum.voyageCode(),
                erratum.note(),
                erratum.refSealedUtc(),
                erratum.createdUtc());
    }

    @Override
    public List<ProfileErratum> findErrata(String voyageCode) {
        return jdbcTemplate.query(
                "SELECT * FROM profile_erratum WHERE voyage_code = ? ORDER BY id",
                ERRATUM_MAPPER, voyageCode);
    }
}
