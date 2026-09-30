package uk.gov.hmcts.reform.services.listassist.ingest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.function.Consumer;
import javax.sql.DataSource;

/**
 * Session-level PostgreSQL advisory lock held on a dedicated connection for the whole run, following the pattern in
 * the CCD decentralised runtime. The lock is released when the connection closes, so a crashed pod never leaves it
 * held.
 */
@Component
public class PostgresAdvisoryLock {

    private static final Logger log = LoggerFactory.getLogger(PostgresAdvisoryLock.class);
    private static final int VALIDITY_TIMEOUT_SECONDS = 5;

    private final DataSource dataSource;

    public PostgresAdvisoryLock(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Runs {@code work} if the lock is free and returns true; returns false without running it otherwise.
     */
    public boolean runIfAcquired(String name, Consumer<HeldLock> work) {
        try (Connection connection = dataSource.getConnection()) {
            if (!call(connection, "select pg_try_advisory_lock(hashtext('listassist'), hashtext(?))", name)) {
                return false;
            }
            try {
                work.accept(() -> {
                    try {
                        if (!connection.isValid(VALIDITY_TIMEOUT_SECONDS)) {
                            throw new LockLostException(name);
                        }
                    } catch (SQLException e) {
                        throw new LockLostException(name);
                    }
                });
            } finally {
                unlock(connection, name);
            }
            return true;
        } catch (SQLException e) {
            throw new IllegalStateException("Could not use advisory lock " + name, e);
        }
    }

    /**
     * If the unlock does not confirm, the pooled connection is aborted rather than returned to the pool still holding
     * the lock; the server releases a session lock when its connection ends.
     */
    private static void unlock(Connection connection, String name) {
        boolean released = false;
        try {
            released = call(connection, "select pg_advisory_unlock(hashtext('listassist'), hashtext(?))", name);
        } catch (SQLException e) {
            log.warn("Could not release advisory lock {}", name);
        }
        if (!released) {
            try {
                connection.abort(Runnable::run);
            } catch (SQLException e) {
                log.warn("Could not abort advisory lock connection {}", name);
            }
        }
    }

    private static boolean call(Connection connection, String sql, String name) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, name);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && result.getBoolean(1);
            }
        }
    }

    /**
     * Lets long-running work confirm the lock connection is still alive before each unit of work.
     */
    @FunctionalInterface
    public interface HeldLock {
        void checkHeld();
    }

    public static class LockLostException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        LockLostException(String name) {
            super("Advisory lock connection lost: " + name);
        }
    }
}
