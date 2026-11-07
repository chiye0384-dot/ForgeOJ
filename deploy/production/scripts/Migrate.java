/* Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0 */
import java.nio.file.Files;
import java.nio.file.Path;
import org.flywaydb.core.Flyway;

/** Deployment-only runner; uses the API's exact resolved Flyway dependencies. */
public final class Migrate {
    public static void main(String[] args) {
        try {
            if (args.length != 0) throw new IllegalArgumentException();
            String password = Files.readString(Path.of("/run/secrets/migrator-password")).strip();
            if (!password.matches("[a-f0-9]{64}")) throw new IllegalArgumentException();
            Flyway.configure().dataSource(System.getenv("SPRING_DATASOURCE_URL"),
                    "forgeoj_migrator", password)
                .locations("classpath:db/migration").cleanDisabled(true)
                .validateMigrationNaming(true).load().migrate();
            System.out.println("Versioned migrations completed; development data excluded.");
        } catch (Exception failure) {
            // No connection parameters, SQL values or secret-bearing exception chain.
            System.err.println("Migration failed; runtime services must remain stopped.");
            System.exit(1);
        }
    }
}
