package com.quantgen.common.db;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Postgres transaction-scoped advisory locks, for serializing work that has no single row to lock. */
@Component
public class AdvisoryLocks {

    private final JdbcTemplate jdbc;

    public AdvisoryLocks(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Blocks until the lock for {@code key} is held; released automatically at commit or rollback. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockForTransaction(String key) {
        jdbc.queryForObject("SELECT count(*) FROM (SELECT pg_advisory_xact_lock(hashtextextended(?, 0))) l",
                Long.class, key);
    }

    /** Tries to take the lock without waiting; returns false if another transaction holds it. */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean tryLockForTransaction(String key) {
        Boolean locked = jdbc.queryForObject("SELECT pg_try_advisory_xact_lock(hashtextextended(?, 0))",
                Boolean.class, key);
        return Boolean.TRUE.equals(locked);
    }
}
