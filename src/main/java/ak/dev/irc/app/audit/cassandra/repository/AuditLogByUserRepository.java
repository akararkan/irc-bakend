package ak.dev.irc.app.audit.cassandra.repository;

import ak.dev.irc.app.audit.cassandra.entity.AuditLogByUserEntity;
import org.springframework.data.cassandra.core.mapping.MapId;
import org.springframework.data.cassandra.repository.CassandraRepository;
import org.springframework.data.cassandra.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface AuditLogByUserRepository extends CassandraRepository<AuditLogByUserEntity, MapId> {

    @Query("SELECT * FROM audit_log_by_user WHERE user_id = :userId LIMIT :pageSize")
    List<AuditLogByUserEntity> firstPage(@Param("userId") UUID userId,
                                         @Param("pageSize") int pageSize);

    @Query("SELECT * FROM audit_log_by_user WHERE user_id = :userId " +
           "AND created_at < :cursor LIMIT :pageSize")
    List<AuditLogByUserEntity> nextPage(@Param("userId") UUID userId,
                                        @Param("cursor") Instant cursor,
                                        @Param("pageSize") int pageSize);
}
