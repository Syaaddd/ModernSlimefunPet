package id.alwination.slimefunpet.database;

import java.util.List;
import java.util.StringJoiner;

/** Which SQL dialect the pool talks to. */
public enum DatabaseType {
    SQLITE,
    MYSQL;

    /** INSERT that overwrites the row with the same key, in this dialect's syntax. */
    public String upsert(String table, String keyColumn, List<String> columns) {
        StringJoiner names = new StringJoiner(", ");
        StringJoiner marks = new StringJoiner(", ");
        StringJoiner assignments = new StringJoiner(", ");
        for (String column : columns) {
            names.add(column);
            marks.add("?");
            if (column.equals(keyColumn)) {
                continue;
            }
            if (this == SQLITE) {
                assignments.add(column + " = excluded." + column);
            } else {
                assignments.add(column + " = VALUES(" + column + ")");
            }
        }
        String insert = "INSERT INTO " + table + " (" + names + ") VALUES (" + marks + ")";
        if (this == SQLITE) {
            return insert + " ON CONFLICT(" + keyColumn + ") DO UPDATE SET " + assignments;
        }
        return insert + " ON DUPLICATE KEY UPDATE " + assignments;
    }
}
