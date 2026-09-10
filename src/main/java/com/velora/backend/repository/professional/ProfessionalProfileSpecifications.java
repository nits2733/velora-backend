package com.velora.backend.repository.professional;

import com.velora.backend.entity.professional.AvailabilityStatus;
import com.velora.backend.entity.professional.ProfessionalProfile;
import com.velora.backend.entity.user.Role;
import jakarta.persistence.criteria.Join;
import org.springframework.data.jpa.domain.Specification;

/**
 * Filters for the public professional directory. Same shape as
 * {@link PortfolioItemSpecifications}: every filter returns {@code null} when its
 * parameter wasn't supplied, so an absent query parameter contributes no predicate at
 * all rather than a match-everything one.
 */
public final class ProfessionalProfileSpecifications {

    private ProfessionalProfileSpecifications() {
    }

    /**
     * Always applied. A profile row should only ever belong to a PROFESSIONAL account,
     * but the directory is public, so the role is asserted here rather than assumed -
     * the same guard {@code ProfessionalService.getPublicProfile} applies to single
     * lookups.
     */
    public static Specification<ProfessionalProfile> isProfessional() {
        return (root, query, cb) -> cb.equal(root.get("user").get("role"), Role.PROFESSIONAL);
    }

    public static Specification<ProfessionalProfile> hasAvailability(AvailabilityStatus status) {
        return (root, query, cb) -> status == null
                ? null
                : cb.equal(root.get("availabilityStatus"), status);
    }

    public static Specification<ProfessionalProfile> hasCity(String city) {
        return (root, query, cb) -> (city == null || city.isBlank())
                ? null
                : cb.equal(cb.lower(root.get("city")), city.trim().toLowerCase());
    }

    public static Specification<ProfessionalProfile> hasSpecialization(String specialization) {
        return (root, query, cb) -> (specialization == null || specialization.isBlank())
                ? null
                : cb.like(cb.lower(root.get("specialization")),
                        "%" + specialization.trim().toLowerCase() + "%");
    }

    public static Specification<ProfessionalProfile> hasMinimumExperience(Integer years) {
        return (root, query, cb) -> years == null
                ? null
                : cb.greaterThanOrEqualTo(root.get("yearsExperience"), years);
    }

    /**
     * Unrated professionals are excluded once a minimum rating is asked for - a null
     * average means "no reviews yet", which cannot be claimed to clear any bar.
     */
    public static Specification<ProfessionalProfile> hasMinimumRating(java.math.BigDecimal minRating) {
        return (root, query, cb) -> minRating == null
                ? null
                : cb.greaterThanOrEqualTo(root.get("averageRating"), minRating);
    }

    /** Free-text match against the professional's name, specialization and bio. */
    public static Specification<ProfessionalProfile> matchesSearch(String search) {
        return (root, query, cb) -> {
            if (search == null || search.isBlank()) {
                return null;
            }
            Join<Object, Object> user = root.join("user");
            String pattern = "%" + search.trim().toLowerCase() + "%";
            return cb.or(
                    cb.like(cb.lower(user.get("fullName")), pattern),
                    cb.like(cb.lower(root.get("specialization")), pattern),
                    cb.like(cb.lower(root.get("bio")), pattern)
            );
        };
    }
}
