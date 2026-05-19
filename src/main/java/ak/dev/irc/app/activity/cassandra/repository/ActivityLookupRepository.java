package ak.dev.irc.app.activity.cassandra.repository;

import ak.dev.irc.app.activity.cassandra.entity.ActivityLookupEntity;
import org.springframework.data.cassandra.repository.CassandraRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface ActivityLookupRepository
        extends CassandraRepository<ActivityLookupEntity, UUID> {}
