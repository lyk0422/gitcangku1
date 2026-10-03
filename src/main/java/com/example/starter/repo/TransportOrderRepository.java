package com.example.starter.repo;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 运输单持久化。 */
@Repository
public class TransportOrderRepository {

    private final JdbcClient jdbc;

    public TransportOrderRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public boolean existsByOrderNo(String orderNo) {
        return jdbc.sql("SELECT COUNT(*) FROM transport_order WHERE order_no = :o")
                .param("o", orderNo).query(Long.class).single() > 0;
    }
}