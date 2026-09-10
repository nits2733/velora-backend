package com.velora.backend.mapper.professional;

import com.velora.backend.dto.professional.ProfessionalPublicProfileResponse;
import com.velora.backend.dto.professional.ProfessionalSummaryResponse;
import com.velora.backend.entity.professional.ProfessionalProfile;
import com.velora.backend.entity.user.User;
import org.springframework.stereotype.Component;

@Component
public class ProfessionalMapper {

    /**
     * Directory card. The id is the <em>user</em> id, not the profile row id, so it can
     * be fed straight back into {@code GET /api/professionals/{id}} and every other
     * endpoint that identifies a professional.
     */
    public ProfessionalSummaryResponse toSummaryResponse(ProfessionalProfile profile) {
        return new ProfessionalSummaryResponse(
                profile.getUser().getId(),
                profile.getUser().getFullName(),
                profile.getSpecialization(),
                profile.getCity(),
                profile.getYearsExperience(),
                profile.getAvailabilityStatus(),
                profile.getAverageRating(),
                profile.getRatingCount()
        );
    }

    public ProfessionalPublicProfileResponse toPublicProfileResponse(User user, ProfessionalProfile profile) {
        return new ProfessionalPublicProfileResponse(
                user.getId(),
                user.getFullName(),
                profile.getBio(),
                profile.getYearsExperience(),
                profile.getSpecialization(),
                profile.getCity(),
                profile.getAvailabilityStatus(),
                profile.getAverageRating(),
                profile.getRatingCount()
        );
    }
}
