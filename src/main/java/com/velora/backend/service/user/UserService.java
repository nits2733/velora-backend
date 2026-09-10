package com.velora.backend.service.user;

import com.velora.backend.dto.user.UpdateProfileRequest;
import com.velora.backend.dto.user.UserProfileResponse;
import com.velora.backend.entity.professional.ProfessionalProfile;
import com.velora.backend.entity.user.Role;
import com.velora.backend.entity.user.User;
import com.velora.backend.exception.ResourceNotFoundException;
import com.velora.backend.mapper.user.UserMapper;
import com.velora.backend.repository.professional.ProfessionalProfileRepository;
import com.velora.backend.repository.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final ProfessionalProfileRepository professionalProfileRepository;
    private final UserMapper userMapper;

    @Transactional(readOnly = true)
    public UserProfileResponse getProfile(Long userId) {
        User user = findUser(userId);
        ProfessionalProfile professionalProfile = user.getRole() == Role.PROFESSIONAL
                ? professionalProfileRepository.findByUserId(userId).orElse(null)
                : null;
        return userMapper.toProfileResponse(user, professionalProfile);
    }

    @Transactional
    public UserProfileResponse updateProfile(Long userId, UpdateProfileRequest request) {
        User user = findUser(userId);

        if (request.fullName() != null && !request.fullName().isBlank()) {
            user.setFullName(request.fullName().trim());
        }
        if (request.phone() != null) {
            user.setPhone(request.phone());
        }
        if (request.avatarUrl() != null) {
            user.setAvatarUrl(request.avatarUrl());
        }
        userRepository.save(user);

        ProfessionalProfile professionalProfile = null;
        if (user.getRole() == Role.PROFESSIONAL) {
            professionalProfile = professionalProfileRepository.findByUserId(userId)
                    .orElseGet(() -> ProfessionalProfile.builder().user(user).build());

            if (request.bio() != null) {
                professionalProfile.setBio(request.bio());
            }
            if (request.yearsExperience() != null) {
                professionalProfile.setYearsExperience(request.yearsExperience());
            }
            if (request.specialization() != null) {
                professionalProfile.setSpecialization(request.specialization());
            }
            if (request.city() != null) {
                professionalProfile.setCity(request.city());
            }
            if (request.availabilityStatus() != null) {
                professionalProfile.setAvailabilityStatus(request.availabilityStatus());
            }
            professionalProfile = professionalProfileRepository.save(professionalProfile);
        }

        return userMapper.toProfileResponse(user, professionalProfile);
    }

    private User findUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));
    }
}
