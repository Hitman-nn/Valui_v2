package com.valui.common.entity;

import com.valui.common.entity.audit.AuditEntityListener;
import com.valui.common.entity.audit.HasCreatedAt;
import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(
    name = "detected_events",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_detected_events_controller_external",
        columnNames = {"controller_id", "event_external_id"}
    )
)
@EntityListeners(AuditEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DetectedEventEntity implements HasCreatedAt {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "controller_id", nullable = false)
    private ControllerEntity controller;

    @Column(name = "event_external_id", nullable = false, length = 255)
    private String eventExternalId;

    @Column(name = "title", nullable = false, columnDefinition = "text")
    private String title;

    @Column(name = "url", columnDefinition = "text")
    private String url;

    @Column(name = "extra_data", columnDefinition = "text")
    private String extraData;

    // Stored as "detected_at" in the DB; mapped via HasCreatedAt so AuditEntityListener
    // sets it on @PrePersist consistently with the rest of the audit infrastructure.
    @Column(name = "detected_at", nullable = false, updatable = false)
    private OffsetDateTime detectedAt;

    @Column(name = "expires_at")
    private OffsetDateTime expiresAt;

    // HasCreatedAt maps to the domain-meaningful "detectedAt" field.
    // Conditional set preserves any pre-assigned value (e.g. in migrations).
    @Override
    public OffsetDateTime getCreatedAt() { return detectedAt; }

    @Override
    public void setCreatedAt(OffsetDateTime value) {
        if (this.detectedAt == null) this.detectedAt = value;
    }
}
