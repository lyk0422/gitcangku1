package com.example.starter.repo;

import java.sql.Timestamp;
import java.time.Instant;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 动物表访问；动物编号为合成数据，首次附着时自动登记。
 */
@Repository
public class AnimalRepository {

    private final JdbcClient jdbc;

    public AnimalRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public boolean exists(String animalCode) {
        return jdbc.sql("SELECT COUNT(*) FROM animal WHERE animal_code = :a")
                .param("a", animalCode)
                .query(Long.class)
                .single() > 0;
    }

    public void insertIfAbsent(String animalCode, Instant createdAt) {
        jdbc.sql("INSERT INTO animal (animal_code, created_at)"
                + " SELECT :a, :t WHERE NOT EXISTS (SELECT 1 FROM animal WHERE animal_code = :a)")
                .param("a", animalCode)
                .param("t", Timestamp.from(createdAt))
                .update();
    }
}