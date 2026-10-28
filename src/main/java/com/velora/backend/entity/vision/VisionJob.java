package com.velora.backend.entity.vision;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import com.velora.backend.entity.user.User;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "vision_jobs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VisionJob {

    @Version
    private Long version;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "style_prompt", nullable = false, length = 300)
    private String stylePrompt;

    @Column(name = "input_image_url", nullable = false, length = 1000)
    private String inputImageUrl;

    @Column(name = "result_image_url", length = 1000)
    private String resultImageUrl;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private VisionJobStatus status;

    @Column(length = 300)
    private String error;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(columnDefinition = "TEXT")
    private String preferences;

    @Column(name = "built_prompt", columnDefinition = "TEXT")
    private String builtPrompt;

    @JdbcTypeCode(SqlTypes.NUMERIC)
    @Column(name = "image_strength", precision = 3, scale = 2)
    private Double imageStrength;

    @Column(name = "request_fingerprint", length = 64)
    private String requestFingerprint;

    @Column(name = "completed_at")
    private Instant completedAt;
}
