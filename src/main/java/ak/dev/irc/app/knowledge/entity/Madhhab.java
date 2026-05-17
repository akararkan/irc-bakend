package ak.dev.irc.app.knowledge.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "madhhabs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Madhhab {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "name_en", nullable = false, length = 100)
    private String nameEn;

    @Column(name = "name_ar", nullable = false, length = 100)
    private String nameAr;

    @Column(name = "name_ckb", nullable = false, length = 100)
    private String nameCkb;
}
