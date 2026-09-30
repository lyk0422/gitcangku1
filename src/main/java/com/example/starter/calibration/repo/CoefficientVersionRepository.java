package com.example.starter.calibration.repo;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import com.example.starter.calibration.model.CoefficientVersion;

/**
 * 环境补偿系数版本持久化。版本只增不改；生效区间为 UTC 左闭右开。
 * 放行事务通过 coefficient_lock 哨兵行锁与版本创建串行化，读到的生效版本集合
 * 必然是某次提交后的完整快照，一条放行结果只引用一个完整版本。
 */
@Repository
public class CoefficientVersionRepository {

    /** 哨兵锁行的固定主键。 */
    static final String SENTINEL = "COEFFICIENT";

    private static final RowMapper<CoefficientVersion> MAPPER = (rs, rowNum) -> new CoefficientVersion(
            rs.getLong("id"),
            rs.getString("version_tag"),
            rs.getBigDecimal("reference_temperature"),
            rs.getBigDecimal("reference_humidity"),
            rs.getBigDecimal("temperature_coefficient"),
            rs.getBigDecimal("humidity_coefficient"),
            JdbcTimes.fromDb(rs.getObject("effective_from", LocalDateTime.class)),
            JdbcTimes.fromDb(rs.getObject("effective_to", LocalDateTime.class)),
            JdbcTimes.fromDb(rs.getObject("created_at", LocalDateTime.class)));

    private final JdbcTemplate jdbc;

    public CoefficientVersionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 获取系数版本串行锁（须在事务内调用）：不存在哨兵行则插入，随后对该行 FOR UPDATE。
     */
    public void lockCoefficientTable() {
        try {
            jdbc.update("INSERT INTO coefficient_lock (lock_id, created_at) VALUES (?, ?)",
                    SENTINEL, JdbcTimes.toDb(Instant.now()));
        } catch (DuplicateKeyException ignored) {
            // 哨兵行已存在，直接进入行锁等待
        }
        jdbc.queryForObject("SELECT lock_id FROM coefficient_lock WHERE lock_id = ? FOR UPDATE",
                String.class, SENTINEL);
    }

    /**
     * 插入系数版本并返回生成的 ID；version_tag 冲突时抛出 DuplicateKeyException。
     */
    public long insert(CoefficientVersion version) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO coefficient_version "
                            + "(version_tag, reference_temperature, reference_humidity, "
                            + "temperature_coefficient, humidity_coefficient, "
                            + "effective_from, effective_to, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, version.versionTag());
            ps.setBigDecimal(2, version.referenceTemperature());
            ps.setBigDecimal(3, version.referenceHumidity());
            ps.setBigDecimal(4, version.temperatureCoefficient());
            ps.setBigDecimal(5, version.humidityCoefficient());
            ps.setObject(6, JdbcTimes.toDb(version.effectiveFrom()));
            ps.setObject(7, JdbcTimes.toDb(version.effectiveTo()));
            ps.setObject(8, JdbcTimes.toDb(version.createdAt()));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /**
     * 按 ID 查询（不加锁）。
     */
    public Optional<CoefficientVersion> findById(long id) {
        return jdbc.query("SELECT * FROM coefficient_version WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    /**
     * 按标签查询（不加锁）。
     */
    public Optional<CoefficientVersion> findByTag(String tag) {
        return jdbc.query("SELECT * FROM coefficient_version WHERE version_tag = ?", MAPPER, tag)
                .stream().findFirst();
    }

    /**
     * 查询某时刻全部命中的生效版本：effective_from &lt;= at &lt; effective_to（左闭右开）。
     * 调用方须持有 coefficient_lock 行锁，以保证与版本创建串行化。
     */
    public List<CoefficientVersion> findEffectiveAt(Instant at) {
        return jdbc.query(
                "SELECT * FROM coefficient_version WHERE effective_from <= ? AND effective_to > ? ORDER BY id",
                MAPPER, JdbcTimes.toDb(at), JdbcTimes.toDb(at));
    }
}
