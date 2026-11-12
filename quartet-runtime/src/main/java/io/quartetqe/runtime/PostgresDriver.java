package io.quartetqe.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quartetqe.core.Json;
import io.quartetqe.core.Tool;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.List;

/** PostgreSQL queries. Connections are read-only unless the target sets allowWrite. */
final class PostgresDriver implements Driver {
    private static final int MAX_ROWS = 100;
    private final JsonNode config;
    private Connection connection;

    PostgresDriver(JsonNode config) {
        this.config = config;
    }

    @Override
    public List<Tool> tools() {
        return List.of(Tool.of("pg_query", "Run one parameterised SQL statement. Use ? placeholders and params. "
                + "Returns rows, capped at " + MAX_ROWS + ".", """
                {"type":"object","required":["sql"],"properties":{
                  "sql":{"type":"string"},"params":{"type":"array"}}}""", this::query));
    }

    private static final java.util.Set<String> READ_ONLY_STARTS = java.util.Set.of("SELECT", "WITH", "SHOW", "EXPLAIN", "VALUES", "TABLE");

    private boolean writable() {
        return config.path("allowWrite").asBoolean(false);
    }

    private synchronized Connection connection() throws Exception {
        if (connection == null) {
            connection = DriverManager.getConnection(config.path("url").asText(),
                    config.path("user").asText(null), config.path("password").asText(null));
            if (!writable()) {
                // setReadOnly alone is not enforced on an auto-committing connection (it let a CREATE and an INSERT through).
                // A read-only transaction is, and every query ends with a rollback.
                connection.setAutoCommit(false);
                connection.setReadOnly(true);
            }
        }
        return connection;
    }

    /** Only reading statements, on a read-only target. The database itself refuses the rest; this says why in plain words. */
    static void requireReadOnlyStatement(String sql) {
        String stripped = sql.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)--.*$", " ").replaceFirst("^[\\s(]+", "");
        String first = stripped.isEmpty() ? "" : stripped.split("[\\s(;]", 2)[0].toUpperCase();
        if (!READ_ONLY_STARTS.contains(first)) {
            throw new IllegalArgumentException("This database target is read-only, so only SELECT, WITH, SHOW, EXPLAIN, VALUES and TABLE statements are allowed. "
                    + "To allow changes the target needs allowWrite: true, and it should be a test database.");
        }
    }

    private JsonNode query(JsonNode args) throws Exception {
        if (writable()) return run(args);
        requireReadOnlyStatement(args.path("sql").asText());
        try {
            return run(args);
        } finally {
            connection().rollback();
        }
    }

    private JsonNode run(JsonNode args) throws Exception {
        try (PreparedStatement statement = connection().prepareStatement(args.path("sql").asText())) {
            int index = 1;
            for (JsonNode param : args.path("params")) {
                if (param.isIntegralNumber()) statement.setLong(index++, param.asLong());
                else if (param.isNumber()) statement.setBigDecimal(index++, param.decimalValue());
                else if (param.isBoolean()) statement.setBoolean(index++, param.asBoolean());
                else statement.setString(index++, param.asText());
            }
            ObjectNode result = Json.obj();
            if (!statement.execute()) {
                result.put("updated", statement.getUpdateCount());
                return Driver.check(result, "updated:" + statement.getUpdateCount());
            }
            ArrayNode rows = result.putArray("rows");
            try (ResultSet resultSet = statement.getResultSet()) {
                ResultSetMetaData meta = resultSet.getMetaData();
                while (resultSet.next() && rows.size() < MAX_ROWS) {
                    ObjectNode row = rows.addObject();
                    for (int i = 1; i <= meta.getColumnCount(); i++) {
                        row.set(meta.getColumnLabel(i), Json.MAPPER.valueToTree(jsonSafe(resultSet.getObject(i))));
                    }
                }
            }
            result.put("rowCount", rows.size());
            return Driver.check(result, rows.isEmpty() ? "rows:empty" : "rows:some");
        }
    }

    private static Object jsonSafe(Object value) {
        if (value == null || value instanceof Number || value instanceof Boolean || value instanceof String) return value;
        return value.toString();
    }

    @Override
    public void close() throws Exception {
        if (connection != null) connection.close();
    }
}
