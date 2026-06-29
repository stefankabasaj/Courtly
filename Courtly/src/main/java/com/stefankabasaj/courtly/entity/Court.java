package com.stefankabasaj.courtly.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(
    name = "courts",
    schema = "courtly",
    uniqueConstraints = @UniqueConstraint(name = "uq_courts_name", columnNames = "name")
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Court {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(nullable = false, unique = true, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Surface surface;

    // BigDecimal, not double. double can't represent money exactly
    // (0.1 + 0.2 != 0.3) and that's a non-starter for prices
    @Column(name = "price_per_hour", nullable = false, precision = 10, scale = 2)
    private BigDecimal pricePerHour;

    // soft delete again - a court with bookings can't just be deleted (FK),
    // so "removing" it really means flipping this to false
    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    public enum Surface {
        CLAY,
        GRASS,
        HARD,
        ARTIFICIAL
    }
}
