package com.portfolio.deployops.repository;

import static org.assertj.core.api.Assertions.*;
import com.portfolio.deployops.domain.DeployStatus;
import java.time.LocalDateTime;
import java.util.*;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

class RequestOwnerVisibilitySqlTest {
    @Test void actualCountAndPagePredicatesRespectOwnerEnvironmentRevocationAndPagination() throws Exception {
        var ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:request_owner_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        try (var connection = ds.getConnection(); var sql = connection.createStatement()) {
            sql.execute("CREATE TABLE deploy_request(id BIGINT, project_id BIGINT, requested_by_id BIGINT, environment VARCHAR, status VARCHAR, created_at TIMESTAMP)");
            sql.execute("CREATE TABLE project_user_permissions(user_id BIGINT, project_id BIGINT, permission VARCHAR, environment VARCHAR, active BOOLEAN)");
            sql.execute("""
                INSERT INTO deploy_request VALUES
                (1,1,7,'TEST','TEST_REQUESTED','2026-10-20 10:00:00'),
                (2,1,7,'PROD','APPROVAL_PENDING','2026-10-20 10:00:00'),
                (3,1,8,'TEST','TEST_REQUESTED','2026-10-20 10:00:00'),
                (4,2,7,'TEST','TEST_REQUESTED','2026-10-20 10:00:00'),
                (5,3,7,'TEST','COMPLETED','2026-10-20 10:00:00'),
                (6,4,8,'PROD','COMPLETED','2026-10-20 10:00:00'),
                (7,3,7,'PROD','APPROVAL_PENDING','2026-10-20 10:00:00'),
                (8,1,7,'TEST','COMPLETED','2026-09-01 10:00:00')
                """);
            sql.execute("""
                INSERT INTO project_user_permissions VALUES
                (7,1,'DEPLOY_REQUEST','TEST',TRUE),
                (7,2,'DEPLOY_REQUEST','TEST',FALSE),
                (7,3,'DEPLOY_REQUEST','ALL',TRUE),
                (8,1,'DEPLOY_REQUEST','ALL',TRUE)
                """);
        }
        var configuration = new Configuration(new Environment("test", new JdbcTransactionFactory(), ds));
        configuration.addMapper(DeployRequestRepository.class);
        var from = LocalDateTime.of(2026, 10, 20, 0, 0);
        var to = from.plusDays(1);
        try (var session = new SqlSessionFactoryBuilder().build(configuration).openSession()) {
            var repo = session.getMapper(DeployRequestRepository.class);
            assertThat(repo.countVisibleOrOwned(null, null, List.of(), 7L, from, to)).isEqualTo(3);
            assertThat(repo.countVisibleOrOwned(null, null, List.of(4L), 7L, from, to)).isEqualTo(4);
            assertThat(repo.countVisibleOrOwned(null, 1L, List.of(), 7L, from, to)).isEqualTo(1);
            assertThat(repo.countVisibleOrOwned(DeployStatus.COMPLETED, null, List.of(), 7L, from, to)).isEqualTo(1);
            assertThat(repo.countVisibleOrOwned(null, null, List.of(), 99L, from, to)).isZero();
            assertThat(pageIds(configuration, session, from, to, 0)).containsExactly(7L, 6L);
            assertThat(pageIds(configuration, session, from, to, 2)).containsExactly(5L, 1L);
            try (var sql = session.getConnection().createStatement()) {
                sql.execute("UPDATE project_user_permissions SET active=FALSE WHERE user_id=7 AND project_id=1");
            }
            session.clearCache();
            assertThat(repo.countVisibleOrOwned(null, 1L, List.of(), 7L, from, to)).isZero();
        }
    }

    private List<Long> pageIds(Configuration configuration, SqlSession session,
                               LocalDateTime from, LocalDateTime to, long offset) throws Exception {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("status", null); parameters.put("projectId", null);
        parameters.put("viewProjectIds", List.of(4L)); parameters.put("userId", 7L);
        parameters.put("from", from); parameters.put("to", to);
        parameters.put("limit", 2); parameters.put("offset", offset);
        var bound = configuration.getMappedStatement(DeployRequestRepository.class.getName()
            + ".findVisibleOrOwnedPage").getBoundSql(parameters);
        // Exercise the actual page predicate/order/limit/bindings. The existing large
        // entity projection is replaced by IDs, so unrelated project/user tables are unnecessary.
        String sql = "SELECT r.id FROM deploy_request r " + bound.getSql().substring(
            bound.getSql().indexOf("WHERE r.created_at"));
        var ids = new ArrayList<Long>();
        try (var statement = session.getConnection().prepareStatement(sql)) {
            for (int i = 0; i < bound.getParameterMappings().size(); i++) {
                String name = bound.getParameterMappings().get(i).getProperty();
                Object value = bound.hasAdditionalParameter(name)
                    ? bound.getAdditionalParameter(name) : parameters.get(name);
                statement.setObject(i + 1, value);
            }
            try (var rows = statement.executeQuery()) {
                while (rows.next()) ids.add(rows.getLong(1));
            }
        }
        return ids;
    }
}
