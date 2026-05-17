package ak.dev.irc.app.knowledge.repository;

import ak.dev.irc.app.knowledge.entity.Madhhab;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface MadhhabRepository extends JpaRepository<Madhhab, Integer> {}
