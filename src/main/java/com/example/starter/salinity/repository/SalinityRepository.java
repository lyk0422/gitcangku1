package com.example.starter.salinity.repository;

import com.example.starter.salinity.model.AdoptionRecord;
import com.example.starter.salinity.model.Correction;
import com.example.starter.salinity.model.Sample;
import com.example.starter.salinity.model.SealedProfileEntry;
import com.example.starter.salinity.model.Voyage;
import com.example.starter.salinity.model.VoyageStatus;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 盐度采样仓储：基于 JdbcClient/JdbcTemplate 访问 H2（MODE=MySQL）。
 * 所有写操作均由服务层事务包裹；行级锁（FOR UPDATE）与唯一约束保证并发一致性。
 */
@Repository
public class SalinityRepository {

    private final JdbcClient jdbcClient;

    /**
     * @param jdbcClient Spring 自动配置的 JDBC 客户端
     */
    public SalinityRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /**
     * 从 KeyHolder 取自增主键 ID。
     * H2（MODE=MySQL）会把生成列（如 ADOPTED_SLOT）一并作为 generated key 返回，
     * 不能使用只允许单键的 {@link KeyHolder#getKey()}，需按列名显式取 ID。
     */
    private long keyOf(KeyHolder keyHolder) {
        Object id = keyHolder.getKeys().entrySet().stream()
                .filter(e -> "ID".equalsIgnoreCase(e.getKey()))
                .map(java.util.Map.Entry::getValue)
                .findFirst()
                .orElse(null);
        return ((Number) Objects.requireNonNull(id, "未取得自增主键 ID")).longValue();
    }

    private static final RowMapper<Sample> SAMPLE_MAPPER = (rs, n) -> new Sample(
            rs.getLong("id"),
            rs.getString("voyage_code"),
            rs.getString("station"),
            rs.getInt("depth_m"),
            rs.getBigDecimal("salinity_psu"),
            rs.getTimestamp("collected_at_utc").toInstant(),
            rs.getString("sample_no"),
            rs.getString("request_id"),
            rs.getBoolean("adopted"),
            rs.getInt("version_no"),
            rs.getTimestamp("created_at_utc").toInstant());

    private static final RowMapper<Voyage> VOYAGE_MAPPER = (rs, n) -> new Voyage(
            rs.getString("code"),
            VoyageStatus.valueOf(rs.getString("status")),
            Optional.ofNullable(rs.getTimestamp("sealed_at_utc")).map(Timestamp::toInstant).orElse(null),
            rs.getTimestamp("created_at_utc").toInstant());

    private static final RowMapper<SealedProfileEntry> SEALED_MAPPER = (rs, n) -> new SealedProfileEntry(
            rs.getLong("id"),
            rs.getString("voyage_code"),
            rs.getString("station"),
            rs.getInt("depth_m"),
            rs.getLong("sample_id"),
            rs.getString("sample_no"),
            rs.getBigDecimal("salinity_psu"),
            rs.getTimestamp("collected_at_utc").toInstant(),
            rs.getTimestamp("sealed_at_utc").toInstant());

    private static final RowMapper<AdoptionRecord> ADOPTION_MAPPER = (rs, n) -> new AdoptionRecord(
            rs.getLong("id"),
            rs.getString("request_id"),
            rs.getString("voyage_code"),
            rs.getString("station"),
            rs.getInt("depth_m"),
            rs.getLong("sample_id"),
            rs.getTimestamp("adopted_at_utc").toInstant());

    private static final RowMapper<Correction> CORRECTION_MAPPER = (rs, n) -> new Correction(
            rs.getLong("id"),
            rs.getString("voyage_code"),
            rs.getString("station"),
            rs.getInt("depth_m"),
            rs.getLong("sealed_profile_id"),
            rs.getBigDecimal("corrected_salinity_psu"),
            rs.getString("note"),
            rs.getString("request_id"),
            rs.getTimestamp("created_at_utc").toInstant());

    /**
     * 按请求幂等键查找样本。
     */
    public Optional<Sample> findSampleByRequestId(String requestId) {
        return jdbcClient.sql("SELECT * FROM samples WHERE request_id = :requestId")
                .param("requestId", requestId)
                .query(SAMPLE_MAPPER)
                .optional();
    }

    /**
     * 按采样编号查找样本。
     */
    public Optional<Sample> findSampleBySampleNo(String sampleNo) {
        return jdbcClient.sql("SELECT * FROM samples WHERE sample_no = :sampleNo")
                .param("sampleNo", sampleNo)
                .query(SAMPLE_MAPPER)
                .optional();
    }

    /**
     * 按主键查找样本。
     */
    public Optional<Sample> findSampleById(long id) {
        return jdbcClient.sql("SELECT * FROM samples WHERE id = :id")
                .param("id", id)
                .query(SAMPLE_MAPPER)
                .optional();
    }

    /**
     * 插入样本（含采纳标记与版本号），回填自增主键。
     */
    public Sample insertSample(Sample sample) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcClient.sql("""
                        INSERT INTO samples
                            (voyage_code, station, depth_m, salinity_psu, collected_at_utc,
                             sample_no, request_id, adopted, version_no, created_at_utc)
                        VALUES
                            (:voyageCode, :station, :depthM, :salinityPsu, :collectedAtUtc,
                             :sampleNo, :requestId, :adopted, :versionNo, :createdAtUtc)
                        """)
                .param("voyageCode", sample.voyageCode())
                .param("station", sample.station())
                .param("depthM", sample.depthM())
                .param("salinityPsu", sample.salinityPsu())
                .param("collectedAtUtc", Timestamp.from(sample.collectedAtUtc()))
                .param("sampleNo", sample.sampleNo())
                .param("requestId", sample.requestId())
                .param("adopted", sample.adopted())
                .param("versionNo", sample.versionNo())
                .param("createdAtUtc", Timestamp.from(sample.createdAtUtc()))
                .update(keyHolder);
        return sample.withId(keyOf(keyHolder));
    }

    /**
     * 计算指定位置的下一个重测版本号（当前最大版本号 + 1，无记录时为 1）。
     */
    public int nextVersionNo(String voyageCode, String station, Integer depthM) {
        Integer max = jdbcClient.sql("""
                        SELECT COALESCE(MAX(version_no), 0) FROM samples
                        WHERE voyage_code = :voyageCode AND station = :station AND depth_m = :depthM
                        """)
                .param("voyageCode", voyageCode)
                .param("station", station)
                .param("depthM", depthM)
                .query(Integer.class)
                .single();
        return max + 1;
    }

    /**
     * 查询航次下全部原始样本，按站位、深度、版本号升序。
     */
    public List<Sample> findSamplesByVoyage(String voyageCode) {
        return jdbcClient.sql("""
                        SELECT * FROM samples WHERE voyage_code = :voyageCode
                        ORDER BY station ASC, depth_m ASC, version_no ASC
                        """)
                .param("voyageCode", voyageCode)
                .query(SAMPLE_MAPPER)
                .list();
    }

    /**
     * 查询指定位置当前采纳版本（无采纳时为空）。
     */
    public Optional<Sample> findAdopted(String voyageCode, String station, Integer depthM) {
        return jdbcClient.sql("""
                        SELECT * FROM samples
                        WHERE voyage_code = :voyageCode AND station = :station
                          AND depth_m = :depthM AND adopted = TRUE
                        """)
                .param("voyageCode", voyageCode)
                .param("station", station)
                .param("depthM", depthM)
                .query(SAMPLE_MAPPER)
                .optional();
    }

    /**
     * 查询航次下全部采纳版本，按站位、深度升序。
     */
    public List<Sample> findAllAdopted(String voyageCode) {
        return jdbcClient.sql("""
                        SELECT * FROM samples WHERE voyage_code = :voyageCode AND adopted = TRUE
                        ORDER BY station ASC, depth_m ASC
                        """)
                .param("voyageCode", voyageCode)
                .query(SAMPLE_MAPPER)
                .list();
    }

    /**
     * 取消指定位置当前采纳版本（采纳 → 未采纳），返回受影响行数（0 或 1）。
     */
    public int clearAdoptionForPosition(String voyageCode, String station, Integer depthM) {
        return jdbcClient.sql("""
                        UPDATE samples SET adopted = FALSE
                        WHERE voyage_code = :voyageCode AND station = :station
                          AND depth_m = :depthM AND adopted = TRUE
                        """)
                .param("voyageCode", voyageCode)
                .param("station", station)
                .param("depthM", depthM)
                .update();
    }

    /**
     * 将指定样本置为采纳版本，返回受影响行数。
     */
    public int markAdopted(long sampleId) {
        return jdbcClient.sql("UPDATE samples SET adopted = TRUE WHERE id = :id")
                .param("id", sampleId)
                .update();
    }

    /**
     * 查询航次，不存在时为空。
     */
    public Optional<Voyage> findVoyage(String code) {
        return jdbcClient.sql("SELECT * FROM voyages WHERE code = :code")
                .param("code", code)
                .query(VOYAGE_MAPPER)
                .optional();
    }

    /**
     * 插入新航次。
     */
    public void insertVoyage(Voyage voyage) {
        jdbcClient.sql("""
                        INSERT INTO voyages (code, status, sealed_at_utc, created_at_utc)
                        VALUES (:code, :status, :sealedAtUtc, :createdAtUtc)
                        """)
                .param("code", voyage.code())
                .param("status", voyage.status().name())
                .param("sealedAtUtc",
                        voyage.sealedAtUtc() == null ? null : Timestamp.from(voyage.sealedAtUtc()))
                .param("createdAtUtc", Timestamp.from(voyage.createdAtUtc()))
                .update();
    }

    /**
     * 按请求幂等键查找质控采纳记录。
     */
    public Optional<AdoptionRecord> findAdoptionByRequestId(String requestId) {
        return jdbcClient.sql("SELECT * FROM adoptions WHERE request_id = :requestId")
                .param("requestId", requestId)
                .query(ADOPTION_MAPPER)
                .optional();
    }

    /**
     * 插入质控采纳记录。
     */
    public void insertAdoption(AdoptionRecord record) {
        jdbcClient.sql("""
                        INSERT INTO adoptions
                            (request_id, voyage_code, station, depth_m, sample_id, adopted_at_utc)
                        VALUES
                            (:requestId, :voyageCode, :station, :depthM, :sampleId, :adoptedAtUtc)
                        """)
                .param("requestId", record.requestId())
                .param("voyageCode", record.voyageCode())
                .param("station", record.station())
                .param("depthM", record.depthM())
                .param("sampleId", record.sampleId())
                .param("adoptedAtUtc", Timestamp.from(record.adoptedAtUtc()))
                .update();
    }

    /**
     * 锁定航次行（SELECT ... FOR UPDATE），返回锁定到的航次；不存在时为空。
     * 必须在事务内调用。
     */
    public Optional<Voyage> lockVoyage(String code) {
        return jdbcClient.sql("SELECT * FROM voyages WHERE code = :code FOR UPDATE")
                .param("code", code)
                .query(VOYAGE_MAPPER)
                .optional();
    }

    /**
     * 条件式封存：仅当航次处于 OPEN 时置为 SEALED，返回受影响行数（1=本次封存成功，0=已封存/不存在）。
     */
    public int markSealedIfOpen(String code, Instant sealedAtUtc) {
        return jdbcClient.sql("""
                        UPDATE voyages SET status = 'SEALED', sealed_at_utc = :sealedAtUtc
                        WHERE code = :code AND status = 'OPEN'
                        """)
                .param("code", code)
                .param("sealedAtUtc", Timestamp.from(sealedAtUtc))
                .update();
    }

    /**
     * 插入封存剖面条目。
     */
    public SealedProfileEntry insertSealedProfile(SealedProfileEntry entry) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        var updater = jdbcClient.sql("""
                        INSERT INTO sealed_profiles
                            (voyage_code, station, depth_m, sample_id, sample_no,
                             salinity_psu, collected_at_utc, sealed_at_utc)
                        VALUES
                            (:voyageCode, :station, :depthM, :sampleId, :sampleNo,
                             :salinityPsu, :collectedAtUtc, :sealedAtUtc)
                        """)
                .param("voyageCode", entry.voyageCode())
                .param("station", entry.station())
                .param("depthM", entry.depthM())
                .param("sampleId", entry.sampleId())
                .param("sampleNo", entry.sampleNo())
                .param("salinityPsu", entry.salinityPsu())
                .param("collectedAtUtc", Timestamp.from(entry.collectedAtUtc()))
                .param("sealedAtUtc", Timestamp.from(entry.sealedAtUtc()))
                .update(keyHolder);
        return new SealedProfileEntry(
                keyOf(keyHolder),
                entry.voyageCode(), entry.station(), entry.depthM(), entry.sampleId(),
                entry.sampleNo(), entry.salinityPsu(), entry.collectedAtUtc(), entry.sealedAtUtc());
    }

    /**
     * 查询航次冻结剖面，按站位、深度升序。
     */
    public List<SealedProfileEntry> findSealedByVoyage(String voyageCode) {
        return jdbcClient.sql("""
                        SELECT * FROM sealed_profiles WHERE voyage_code = :voyageCode
                        ORDER BY station ASC, depth_m ASC
                        """)
                .param("voyageCode", voyageCode)
                .query(SEALED_MAPPER)
                .list();
    }

    /**
     * 查询指定站位深度的冻结条目。
     */
    public Optional<SealedProfileEntry> findSealed(String voyageCode, String station, Integer depthM) {
        return jdbcClient.sql("""
                        SELECT * FROM sealed_profiles
                        WHERE voyage_code = :voyageCode AND station = :station AND depth_m = :depthM
                        """)
                .param("voyageCode", voyageCode)
                .param("station", station)
                .param("depthM", depthM)
                .query(SEALED_MAPPER)
                .optional();
    }

    /**
     * 追加勘误。
     */
    public Correction insertCorrection(Correction correction) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcClient.sql("""
                        INSERT INTO corrections
                            (voyage_code, station, depth_m, sealed_profile_id,
                             corrected_salinity_psu, note, request_id, created_at_utc)
                        VALUES
                            (:voyageCode, :station, :depthM, :sealedProfileId,
                             :correctedSalinityPsu, :note, :requestId, :createdAtUtc)
                        """)
                .param("voyageCode", correction.voyageCode())
                .param("station", correction.station())
                .param("depthM", correction.depthM())
                .param("sealedProfileId", correction.sealedProfileId())
                .param("correctedSalinityPsu", correction.correctedSalinityPsu())
                .param("note", correction.note())
                .param("requestId", correction.requestId())
                .param("createdAtUtc", Timestamp.from(correction.createdAtUtc()))
                .update(keyHolder);
        return new Correction(
                keyOf(keyHolder),
                correction.voyageCode(), correction.station(), correction.depthM(),
                correction.sealedProfileId(), correction.correctedSalinityPsu(),
                correction.note(), correction.requestId(), correction.createdAtUtc());
    }

    /**
     * 按请求幂等键查找勘误。
     */
    public Optional<Correction> findCorrectionByRequestId(String requestId) {
        return jdbcClient.sql("SELECT * FROM corrections WHERE request_id = :requestId")
                .param("requestId", requestId)
                .query(CORRECTION_MAPPER)
                .optional();
    }

    /**
     * 查询航次全部勘误，按主键（追加先后）升序。
     */
    public List<Correction> findCorrectionsByVoyage(String voyageCode) {
        return jdbcClient.sql("""
                        SELECT * FROM corrections WHERE voyage_code = :voyageCode
                        ORDER BY id ASC
                        """)
                .param("voyageCode", voyageCode)
                .query(CORRECTION_MAPPER)
                .list();
    }
}
