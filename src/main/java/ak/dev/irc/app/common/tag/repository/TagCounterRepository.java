package ak.dev.irc.app.common.tag.repository;

import ak.dev.irc.app.common.tag.entity.TagCounterEntity;
import org.springframework.data.cassandra.core.mapping.MapId;
import org.springframework.data.cassandra.repository.CassandraRepository;
import org.springframework.data.cassandra.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TagCounterRepository extends CassandraRepository<TagCounterEntity, MapId> {

    /** All tags for one scope partition — read once per scope by the trending job. */
    @Query("SELECT * FROM tag_counters WHERE scope = :scope")
    List<TagCounterEntity> findByScope(@Param("scope") String scope);

    @Query("SELECT * FROM tag_counters WHERE scope = :scope AND tag = :tag")
    Optional<TagCounterEntity> findOne(@Param("scope") String scope, @Param("tag") String tag);

    /**
     * Prefix scan within a scope partition — used by the {@code /tags/search?prefix=}
     * autocomplete endpoint. Uses a clustering-key range over {@code tag} (which
     * is ASC-ordered), so no secondary index is needed. The caller computes the
     * inclusive lower bound and the exclusive upper bound (prefix + {@code "￿"}).
     */
    @Query("SELECT * FROM tag_counters WHERE scope = :scope AND tag >= :lo AND tag < :hi LIMIT :limit")
    List<TagCounterEntity> prefixScan(@Param("scope") String scope,
                                      @Param("lo")    String lo,
                                      @Param("hi")    String hi,
                                      @Param("limit") int    limit);
}
