package com.portfolio.deployops.repository;

import static org.assertj.core.api.Assertions.assertThat;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.UUID;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ImageProvenanceMapperTest {
    UnpooledDataSource dataSource;
    Configuration configuration;
    String sha = "0123456789abcdef0123456789abcdef01234567";
    @BeforeEach void setup() throws Exception {
        dataSource = new UnpooledDataSource("org.h2.Driver", "jdbc:h2:mem:" + UUID.randomUUID()
            + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        try (Connection connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE project_image_history(project_id BIGINT, image_repository VARCHAR(255),"
                + " image_tag VARCHAR(255), image_digest VARCHAR(255), gitlab_pipeline_id BIGINT, commit_sha VARCHAR(64))");
        }
        configuration = new Configuration(new Environment("test", new JdbcTransactionFactory(), dataSource));
        configuration.addMapper(ProjectImageHistoryRepository.class);
    }
    void add(long project, String repo, String tag, String digest, Long pipeline, String commit) throws Exception {
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(
            "INSERT INTO project_image_history VALUES(?,?,?,?,?,?)")) {
            statement.setLong(1, project); statement.setString(2, repo); statement.setString(3, tag);
            statement.setString(4, digest); statement.setObject(5, pipeline); statement.setString(6, commit);
            statement.executeUpdate();
        }
    }
    @Test void actualSqlDeduplicatesSameScmPairAndMapsCommitNormalization() throws Exception {
        add(3, "demo-app", "release-4", "sha256:fixed", 4L, sha);
        add(3, "demo-app", "release-4", "sha256:fixed", 4L, " " + sha.toUpperCase() + " ");
        try (var session = new SqlSessionFactoryBuilder().build(configuration).openSession()) {
            var found = session.getMapper(ProjectImageHistoryRepository.class).findExactImageProvenance(3L, "demo-app", "release-4", "sha256:fixed");
            assertThat(found).hasSize(1);
            assertThat(found.getFirst().getGitlabPipelineId()).isEqualTo(4L);
            assertThat(found.getFirst().getCommitSha()).isEqualTo(sha);
        }
    }
    @Test void actualSqlExcludesEveryWrongIdentityAndMissingScm() throws Exception {
        add(99, "demo-app", "release-4", "sha256:fixed", 1L, sha);
        add(3, "other", "release-4", "sha256:fixed", 1L, sha);
        add(3, "demo-app", "other", "sha256:fixed", 1L, sha);
        add(3, "demo-app", "release-4", "sha256:other", 1L, sha);
        add(3, "demo-app", "release-4", "sha256:fixed", null, sha);
        add(3, "demo-app", "release-4", "sha256:fixed", 0L, sha);
        add(3, "demo-app", "release-4", "sha256:fixed", 4L, null);
        add(3, "demo-app", "release-4", "sha256:fixed", 4L, " ");
        try (var session = new SqlSessionFactoryBuilder().build(configuration).openSession()) {
            assertThat(session.getMapper(ProjectImageHistoryRepository.class).findExactImageProvenance(3L, "demo-app", "release-4", "sha256:fixed")).isEmpty();
        }
    }
    @Test void actualSqlReturnsTwoDifferentPairsForAmbiguityWithoutUnboundedRows() throws Exception {
        add(3, "demo-app", "release-4", "sha256:fixed", 4L, sha);
        add(3, "demo-app", "release-4", "sha256:fixed", 5L, sha);
        add(3, "demo-app", "release-4", "sha256:fixed", 6L, sha);
        try (var session = new SqlSessionFactoryBuilder().build(configuration).openSession()) {
            var found = session.getMapper(ProjectImageHistoryRepository.class).findExactImageProvenance(3L, "demo-app", "release-4", "sha256:fixed");
            assertThat(found).hasSize(2).extracting(row -> row.getGitlabPipelineId()).containsExactly(4L, 5L);
        }
    }
}
