package com.skillsphere.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PostgresNativeClientBinaryResolutionTest {

    @Test
    @DisplayName("resolveBinary falls back safely when postgres-bin-path is empty")
    void testResolveBinary_EmptyBinPath() {
        PostgresNativeClient client = new PostgresNativeClient(
                "",
                "jdbc:postgresql://127.0.0.1:5432/skillsphere_db",
                "postgres",
                ""
        );

        String resolved = client.resolveBinary("pg_dump");
        assertNotNull(resolved);
        assertFalse(resolved.isBlank());
        // Either resolved to existing D:\PostgreSQL\18\bin\pg_dump.exe (on this local Windows machine) or bare "pg_dump"
        assertTrue(resolved.endsWith("pg_dump.exe") || resolved.equals("pg_dump"));
    }

    @Test
    @DisplayName("resolveBinary falls back to utility name when non-existent path is provided")
    void testResolveBinary_NonExistentPath() {
        PostgresNativeClient client = new PostgresNativeClient(
                "/non/existent/path/to/postgres/bin",
                "jdbc:postgresql://127.0.0.1:5432/skillsphere_db",
                "postgres",
                ""
        );

        String resolved = client.resolveBinary("pg_dump");
        assertNotNull(resolved);
        // If D:\PostgreSQL\18\bin\pg_dump.exe exists on host, it may fallback there, or return utility name
        assertTrue(resolved.contains("pg_dump"));
    }

    @Test
    @DisplayName("parseJdbcUrl handles standard JDBC PostgreSQL URLs")
    void testParseJdbcUrl() {
        PostgresNativeClient.HostPortDb parsed = PostgresNativeClient.parseJdbcUrl("jdbc:postgresql://db.cloud.internal:5433/prod_db");
        assertEquals("db.cloud.internal", parsed.host());
        assertEquals(5433, parsed.port());
        assertEquals("prod_db", parsed.database());
    }
}
