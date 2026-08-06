package ak.dev.irc.app.knowledge.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "topics")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Topic {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "name_en", nullable = false, length = 150)
    private String nameEn;

    @Column(name = "name_ar", nullable = false, length = 150)
    private String nameAr;

    @Column(name = "name_ckb", nullable = false, length = 150)
    private String nameCkb;

    /**
     * Soft-retire marker (knowledge-vocabulary.md §5): a retired row leaves
     * the pickers but stays resolvable by id, so existing profile references
     * never orphan. Hard delete is deliberately not offered.
     */
    @Column(name = "archived_at")
    private java.time.LocalDateTime archivedAt;
}
