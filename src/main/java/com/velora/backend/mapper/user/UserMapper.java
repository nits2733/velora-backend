package com.velora.backend.mapper.user;

import com.velora.backend.dto.user.ProfessionalProfileResponse;
import com.velora.backend.dto.user.UserProfileResponse;
import com.velora.backend.entity.professional.ProfessionalProfile;
import com.velora.backend.entity.user.User;
import org.springframework.stereotype.Component;

@Component
public class UserMapper {

    public UserProfileResponse toProfileResponse(User user, ProfessionalProfile professionalProfile) {
        ProfessionalProfileResponse professionalResponse = professionalProfile == null ? null
                : new ProfessionalProfileResponse(
                        professionalProfile.getBio(),
                        professionalProfile.getYearsExperience(),
                        professionalProfile.getSpecialization(),
                        professionalProfile.getCity(),
                        professionalProfile.getAvailabilityStatus(),
                        professionalProfile.getAverageRating(),
                        professionalProfile.getRatingCount()
                );

        return new UserProfileResponse(
                user.getId(),
                user.getEmail(),
                user.getFullName(),
                user.getPhone(),
                user.getAvatarUrl(),
                user.getRole(),
                user.getCreatedAt(),
                professionalResponse
        );
    }
}
