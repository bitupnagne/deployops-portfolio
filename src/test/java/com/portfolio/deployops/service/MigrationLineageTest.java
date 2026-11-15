package com.portfolio.deployops.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 공개 예제에서 동결한 V1과 순차 증분 마이그레이션의 계보를 검증한다. */
class MigrationLineageTest {

    private static final Path MIGRATIONS = Path.of("src", "main", "resources", "db", "migration");
    private static final String FROZEN_V1_SHA256 =
        "36438bef5576183d72fdf0d08cd6e3c4e8167c1c906143b729f44682c2e95462";

    /** 주석을 뺀 실행 SQL. 주석에 적힌 컬럼 이름이 오탐을 만들지 않게 한다. */
    private static String executableSql(Path file) throws Exception {
        return Files.readString(file).lines()
            .filter(line -> !line.stripLeading().startsWith("--"))
            .reduce("", (a, b) -> a + "\n" + b);
    }

    private static List<Path> versionedMigrationsAfterBaseline() throws Exception {
        try (var files = Files.list(MIGRATIONS)) {
            return files
                .filter(p -> p.getFileName().toString().matches("V\\d+__.*\\.sql"))
                .filter(p -> !p.getFileName().toString().startsWith("V1__"))
                .sorted()
                .toList();
        }
    }

    @Test
    @DisplayName("V1 에도 있는 컬럼을 추가하는 마이그레이션은 IF NOT EXISTS 를 쓴다")
    void migrationsThatMirrorBaselineColumnsMustBeIdempotent() throws Exception {
        String baseline = executableSql(MIGRATIONS.resolve("V1__baseline.sql"));
        List<String> offenders = new ArrayList<>();
        for (Path migration : versionedMigrationsAfterBaseline()) {
            unguardedBaselineColumns(baseline,executableSql(migration)).forEach(column ->
                offenders.add("%s: ADD COLUMN %s (같은 V1 테이블에도 있는데 IF NOT EXISTS 가 없다)"
                    .formatted(migration.getFileName(),column)));
        }

        assertThat(offenders)
            .as("신규 설치에서 ERROR 1060 으로 Flyway 가 멈춘다")
            .isEmpty();
    }

    private static List<String> unguardedBaselineColumns(String baseline,String migration) {
        Pattern tables=Pattern.compile("CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?`?([A-Za-z0-9_]+)`?\\s*\\((.*?);",
            Pattern.CASE_INSENSITIVE|Pattern.DOTALL);
        var definitions=new java.util.HashMap<String,String>();
        Matcher created=tables.matcher(baseline);
        while(created.find()) definitions.put(created.group(1).toLowerCase(java.util.Locale.ROOT),created.group(2));
        Pattern alter=Pattern.compile("ALTER\\s+TABLE\\s+`?([A-Za-z0-9_]+)`?(.*?);",
            Pattern.CASE_INSENSITIVE|Pattern.DOTALL);
        Pattern column=Pattern.compile("ADD\\s+COLUMN\\s+(IF\\s+NOT\\s+EXISTS\\s+)?`?([A-Za-z0-9_]+)`?",Pattern.CASE_INSENSITIVE);
        List<String> result=new ArrayList<>();
        Matcher changed=alter.matcher(migration);
        while(changed.find()) {
            String definition=definitions.getOrDefault(changed.group(1).toLowerCase(java.util.Locale.ROOT),"");
            Matcher added=column.matcher(changed.group(2));
            while(added.find()) {
                if(added.group(1)==null && Pattern.compile("(?m)^\\s*`"+Pattern.quote(added.group(2))+"`\\s+",
                    Pattern.CASE_INSENSITIVE).matcher(definition).find())
                    result.add(changed.group(1)+"."+added.group(2));
            }
        }
        return result;
    }

    @Test
    void baselineColumnGuardIsScopedToItsOwningTable() {
        String baseline="CREATE TABLE `project` (\n `approval_policy_id` BIGINT\n);\n"
            +"CREATE TABLE `server_group` (\n `id` BIGINT\n);";
        assertThat(unguardedBaselineColumns(baseline,"ALTER TABLE server_group ADD COLUMN approval_policy_id BIGINT;")).isEmpty();
        assertThat(unguardedBaselineColumns(baseline,"ALTER TABLE project ADD COLUMN approval_policy_id BIGINT;"))
            .containsExactly("project.approval_policy_id");
        assertThat(unguardedBaselineColumns(baseline,"ALTER TABLE project ADD COLUMN IF NOT EXISTS approval_policy_id BIGINT;")).isEmpty();
    }

    @Test
    @DisplayName("V1 에도 있는 인덱스를 만드는 마이그레이션은 IF NOT EXISTS 를 쓴다")
    void migrationsThatMirrorBaselineIndexesMustBeIdempotent() throws Exception {
        String baseline = executableSql(MIGRATIONS.resolve("V1__baseline.sql"));
        Pattern createIndex = Pattern.compile(
            "CREATE\\s+(?:UNIQUE\\s+)?INDEX\\s+(IF\\s+NOT\\s+EXISTS\\s+)?`?([A-Za-z0-9_]+)`?",
            Pattern.CASE_INSENSITIVE);

        List<String> offenders = new ArrayList<>();
        for (Path migration : versionedMigrationsAfterBaseline()) {
            Matcher matcher = createIndex.matcher(executableSql(migration));
            while (matcher.find()) {
                if (matcher.group(1) == null && baseline.contains(matcher.group(2))) {
                    offenders.add("%s: CREATE INDEX %s".formatted(
                        migration.getFileName(), matcher.group(2)));
                }
            }
        }

        assertThat(offenders).isEmpty();
    }

    @Test
    @DisplayName("승인 기록 사용자 중복 방지 제약은 V11에서 증분 적용한다")
    void approvalRecordUniquenessExistsInIncrementalMigration() throws Exception {
        String baseline = executableSql(MIGRATIONS.resolve("V1__baseline.sql"));
        String incremental = executableSql(MIGRATIONS.resolve("V11__approval_policy_integrity.sql"));

        assertThat(baseline).doesNotContain("uk_approval_record_request_approver");
        assertThat(incremental)
            .contains("first_record.id < duplicate_record.id")
            .contains("CREATE UNIQUE INDEX IF NOT EXISTS `uk_approval_record_request_approver`")
            .contains("(`deploy_request_id`, `approver_id`)");
    }

    @Test
    @DisplayName("자동화 결정 출처와 다중 인스턴스 실행 잠금은 V13에서 증분 적용한다")
    void deploymentAutomationSchemaExistsInIncrementalMigration() throws Exception {
        String baseline = executableSql(MIGRATIONS.resolve("V1__baseline.sql"));
        String incremental = executableSql(MIGRATIONS.resolve("V13__deployment_automation.sql"));

        assertThat(baseline)
            .doesNotContain("`approval_decision_source`")
            .doesNotContain("`execution_trigger`")
            .doesNotContain("deployment_execution_guard");
        assertThat(incremental)
            .contains("ADD COLUMN IF NOT EXISTS `approval_decision_source`")
            .contains("ADD COLUMN IF NOT EXISTS `execution_trigger`")
            .contains("CREATE TABLE IF NOT EXISTS `deployment_execution_guard`")
            .contains("CHECK (`id` = 1)")
            .contains("INSERT IGNORE INTO `deployment_execution_guard` (`id`) VALUES (1)");
    }

    @Test
    @DisplayName("검증 provenance와 자동 스캔 차단 필드는 V14에서만 증분 적용한다")
    void deploymentProvenanceExistsOnlyInV14() throws Exception {
        String baseline = executableSql(MIGRATIONS.resolve("V1__baseline.sql"));
        String incremental = executableSql(
            MIGRATIONS.resolve("V14__deployment_provenance_and_project_guard.sql"));

        assertThat(baseline)
            .doesNotContain("`release_verification_status`")
            .doesNotContain("`release_verification_evidence_hash`")
            .doesNotContain("`auto_dispatch_blocked_at`");
        assertThat(incremental)
            .contains("ADD COLUMN IF NOT EXISTS `release_verification_status`")
            .contains("ADD COLUMN IF NOT EXISTS `release_verification_job_id`")
            .contains("ADD COLUMN IF NOT EXISTS `release_verification_evidence_hash`")
            .contains("ADD COLUMN IF NOT EXISTS `auto_dispatch_blocked_at`")
            .contains("CREATE INDEX IF NOT EXISTS `idx_deploy_request_auto_dispatch`");
    }

    @Test
    @DisplayName("HTTP 없는 PROC용 컨테이너 실행 상태는 V21에서 두 설정 테이블에 증분 적용한다")
    void containerRunningHealthModeExistsInV21() throws Exception {
        String baseline = executableSql(MIGRATIONS.resolve("V1__baseline.sql"));
        String incremental = executableSql(MIGRATIONS.resolve("V21__container_running_health_mode.sql"));

        assertThat(baseline).doesNotContain("CONTAINER_RUNNING");
        assertThat(incremental)
            .contains("ALTER TABLE `project`")
            .contains("ALTER TABLE `deploy_component`")
            .contains("'CONTAINER_RUNNING'");
    }

    @Test
    @DisplayName("공개 빌드는 필수 마이그레이션 전체를 classpath 리소스로 포함한다")
    void sourceArtifactKeepsCurrentMigrationLineage() throws Exception {
        assertThat(getClass().getResource("/db/migration/V22__merge_request_reviewer_gitlab_identity.sql")).isNotNull();
        assertThat(getClass().getResource("/db/migration/V26__audit_target_description_capacity.sql")).isNotNull();
        for (Path migration : versionedMigrationsAfterBaseline()) {
            String name = migration.getFileName().toString();
            int version = Integer.parseInt(name.substring(1, name.indexOf("__")));
            if (version == 4 || version >= 11) {
                assertThat(getClass().getResource("/db/migration/" + name)).as("Missing classpath migration %s", name).isNotNull();
            }
        }
        String incremental = executableSql(MIGRATIONS.resolve("V22__merge_request_reviewer_gitlab_identity.sql"));
        assertThat(incremental).contains("ALTER TABLE `merge_request_evidence`",
            "ADD COLUMN IF NOT EXISTS `reviewer_gitlab_user_id` BIGINT DEFAULT NULL");
        String auditCapacity = executableSql(MIGRATIONS.resolve("V26__audit_target_description_capacity.sql"));
        assertThat(auditCapacity).contains("ALTER TABLE `audit_log`", "MODIFY COLUMN `target_desc` TEXT");
    }

    @Test
    @DisplayName("공개 예제 V1은 byte 단위로 동결한다")
    void baselineIsFrozen() throws Exception {
        byte[] baseline = Files.readAllBytes(MIGRATIONS.resolve("V1__baseline.sql"));
        String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(baseline));

        assertThat(actual).isEqualTo(FROZEN_V1_SHA256);
    }

    @Test
    @DisplayName("Flyway repair는 기본 비활성이고 명시적 확인 문구 없이는 거부한다")
    void flywayRepairIsFailClosed() throws Exception {
        String config = Files.readString(Path.of(
            "src", "main", "java", "com", "portfolio", "deployops", "config", "FlywayConfig.java"));

        assertThat(config).contains("FlywayMigrationStrategy");
        assertThat(config).contains("${deploy.flyway.repair-on-migrate:false}");
        assertThat(config).contains("I_ACKNOWLEDGE_FLYWAY_CHECKSUM_REWRITE");
        assertThat(config).contains("flyway.repair()");
    }
}
