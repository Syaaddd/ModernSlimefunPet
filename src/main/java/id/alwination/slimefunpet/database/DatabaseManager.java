package id.alwination.slimefunpet.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Owns the HikariCP pool and the small SQL helpers every store uses (update, query, transaction,
 * upsert), so the stores read as steps and not as JDBC boilerplate.
 * <p>
 * Every method blocks on the database: call them from an async task, never from the main thread.
 */
public final class DatabaseManager {

    /** Reads one row of a query result. */
    @FunctionalInterface
    public interface RowReader<T> {
        T read(ResultSet row) throws SQLException;
    }

    /** SQL that must run on one connection, inside one transaction. */
    @FunctionalInterface
    public interface TransactionWork {
        void run(Connection connection) throws SQLException;
    }

    private final DatabaseType type;
    private final HikariDataSource dataSource;

    private DatabaseManager(DatabaseType type, HikariConfig config) {
        this.type = type;
        this.dataSource = new HikariDataSource(config);
    }

    public static DatabaseManager sqlite(File file) {
        HikariConfig config = new HikariConfig();
        config.setPoolName("ModernSlimefunPet-sqlite");
        config.setJdbcUrl("jdbc:sqlite:" + file.getAbsolutePath());
        // One writer at a time: a bigger pool only produces SQLITE_BUSY.
        config.setMaximumPoolSize(1);
        config.addDataSourceProperty("journal_mode", "WAL");
        config.addDataSourceProperty("busy_timeout", "5000");
        return new DatabaseManager(DatabaseType.SQLITE, config);
    }

    public static DatabaseManager mysql(String host, int port, String database, String username,
                                        String password, boolean useSsl, int poolSize) {
        HikariConfig config = new HikariConfig();
        config.setPoolName("ModernSlimefunPet-mysql");
        config.setJdbcUrl("jdbc:mysql://" + host + ":" + port + "/" + database
                + "?useSSL=" + useSsl + "&characterEncoding=utf8&serverTimezone=UTC");
        config.setUsername(username);
        config.setPassword(password);
        config.setMaximumPoolSize(poolSize);
        return new DatabaseManager(DatabaseType.MYSQL, config);
    }

    public DatabaseType getType() {
        return this.type;
    }

    public void update(String sql, Object... values) throws SQLException {
        try (Connection connection = this.dataSource.getConnection()) {
            execute(connection, sql, values);
        }
    }

    public <T> List<T> query(String sql, RowReader<T> reader, Object... values) throws SQLException {
        try (Connection connection = this.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, values);
            try (ResultSet rows = statement.executeQuery()) {
                List<T> found = new ArrayList<>();
                while (rows.next()) {
                    found.add(reader.read(rows));
                }
                return found;
            }
        }
    }

    /** Runs the work in one transaction: all of it is written, or none of it. */
    public void inTransaction(TransactionWork work) throws SQLException {
        try (Connection connection = this.dataSource.getConnection()) {
            connection.setAutoCommit(false);
            boolean committed = false;
            try {
                work.run(connection);
                connection.commit();
                committed = true;
            } finally {
                if (!committed) {
                    connection.rollback();
                }
            }
        }
    }

    /** Update on a connection that is already open (used inside {@link #inTransaction}). */
    public static void execute(Connection connection, String sql, Object... values) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, values);
            statement.executeUpdate();
        }
    }

    /** INSERT that overwrites the row with the same key, in this database's own syntax. */
    public String upsert(String table, String keyColumn, List<String> columns) {
        return this.type.upsert(table, keyColumn, columns);
    }

    public void close() {
        this.dataSource.close();
    }

    private static void bind(PreparedStatement statement, Object[] values) throws SQLException {
        for (int index = 0; index < values.length; index++) {
            statement.setObject(index + 1, values[index]);
        }
    }
}
