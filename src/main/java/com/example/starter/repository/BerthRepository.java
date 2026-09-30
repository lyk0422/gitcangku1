package com.example.starter.repository;

import java.util.List;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.example.starter.po.BerthPo;

/**
 * 泊位仓储。
 */
@Repository
public class BerthRepository {

    private static final RowMapper<BerthPo> MAPPER = (rs, n) -> new BerthPo(
            rs.getLong("id"),
            rs.getString("code"),
            rs.getBigDecimal("static_depth"),
            rs.getTimestamp("created_at").toLocalDateTime());

    private final JdbcTemplate jdbcTemplate;

    public BerthRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public BerthPo findByCode(String code) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT id, code, static_depth, created_at FROM berth WHERE code = ?",
                    MAPPER, code);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    public List<BerthPo> findAll() {
        return jdbcTemplate.query(
                "SELECT id, code, static_depth, created_at FROM berth ORDER BY code", MAPPER);
    }
}
