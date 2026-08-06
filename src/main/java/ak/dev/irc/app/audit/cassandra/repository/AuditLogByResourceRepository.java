package ak.dev.irc.app.audit.cassandra.repository;

import ak.dev.irc.app.audit.cassandra.entity.AuditLogByResourceEntity;
import org.springframework.data.cassandra.core.mapping.MapId;
import org.springframework.data.cassandra.repository.CassandraRepository;
import org.springframework.data.cassandra.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface AuditLogByResourceRepository extends CassandraRepository<AuditLogByResourceEntity, MapId> {

    @Query("SELECT * FROM audit_log_by_resource WHERE resource_type = :type " +
           "AND resource_id = :id LIMIT :pageSize")
    List<AuditLogByResourceEntity> firstPage(@Param("type") String resourceType,
                                             @Param("id") UUID resourceId,
                                             @Param("pageSize") int pageSize);

    @Query("SELECT * FROM audit_log_by_resource WHERE resource_type = :type " +
           "AND resource_id = :id AND created_at < :cursor LIMIT :pageSize")
    List<AuditLogByResourceEntity> nextPage(@Param("type") String resourceType,
                                            @Param("id") UUID resourceId,
                                            @Param("cursor") java.time.Instant cursor,
                                            @Param("pageSize") int pageSize);
}
