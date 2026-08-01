package ak.dev.irc.app.post.cassandra.repository;

import ak.dev.irc.app.post.cassandra.entity.SoundCounterEntity;
import org.springframework.data.cassandra.repository.CassandraRepository;
import org.springframework.data.cassandra.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SoundCounterRepository extends CassandraRepository<SoundCounterEntity, UUID> {

    @Query("SELECT * FROM sound_counters WHERE sound_id = :soundId")
    Optional<SoundCounterEntity> findBySoundId(@Param("soundId") UUID soundId);

    /** Bulk counter read — one IN query per reindex batch instead of one point read per sound. */
    @Query("SELECT * FROM sound_counters WHERE sound_id IN :soundIds")
    List<SoundCounterEntity> findAllBySoundIdIn(@Param("soundIds") java.util.Collection<UUID> soundIds);
}
