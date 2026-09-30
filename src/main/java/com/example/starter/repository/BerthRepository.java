package com.example.starter.repository;

import com.example.starter.domain.Berth;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

/**
 * 泊位表数据访问。
 */
@Repository
public class BerthRepository {

    private static final BerthRowMapper ROW_MAPPER = new BerthRowMapper();

    private final JdbcTemplate jdbcTemplate;

    /**
     * @param jdbcTemplate JDBC 模板
     */
    public BerthRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 按编码查询泊位。
     *
     * @param code 泊位编码
     * @return 泊位
     */
    public Optional<Berth> findByCode(String code) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(
                    "SELECT id, code, name, static_depth_m FROM berth WHERE code = ?",
                    ROW_MAPPER, code));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    /**
     * 按主键查询泊位。
     *
     * @param id 泊位主键
     * @return 泊位
     */
    public Optional<Berth> findById(Long id) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(
                    "SELECT id, code, name, static_depth_m FROM berth WHERE id = ?",
                    ROW_MAPPER, id));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    /**
     * 泊位行映射器。
     */
    static class BerthRowMapper implements RowMapper<Berth> {

        @Override
        public Berth mapRow(ResultSet rs, int rowNum) throws SQLException {
            Long id = rs.getLong("id");
            String code = rs.getString("code");
            String name = rs.getString("name");
            BigDecimal staticDepth = rs.getBigDecimal("static_depth_m");
            return new Berth(id, code, name, staticDepth);
        }
    }
}
