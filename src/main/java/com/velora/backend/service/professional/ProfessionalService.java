package com.velora.backend.service.professional;

import com.velora.backend.dto.professional.ProfessionalPublicProfileResponse;
import com.velora.backend.dto.professional.ProfessionalSummaryResponse;
import com.velora.backend.entity.professional.AvailabilityStatus;
import com.velora.backend.entity.professional.ProfessionalProfile;
import com.velora.backend.entity.user.Role;
import com.velora.backend.entity.user.User;
import com.velora.backend.exception.ResourceNotFoundException;
import com.velora.backend.mapper.professional.ProfessionalMapper;
import com.velora.backend.repository.professional.ProfessionalProfileRepository;
import com.velora.backend.repository.professional.ProfessionalProfileSpecifications;
import com.velora.backend.repository.user.UserRepository;
import com.velora.backend.util.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Service
@RequiredArgsConstructor
public class ProfessionalService {

    private final UserRepository userRepository;
    private final ProfessionalProfileRepository professionalProfileRepository;
    private final ProfessionalMapper professionalMapper;

    /**
     * Public directory. Every filter is optional and independent - supplying none returns
     * every professional, which is the plain "browse all" case.
     */
    @Transactional(readOnly = true)
    public PageResponse<ProfessionalSummaryResponse> search(String search, String specialization, String city,
                                                             AvailabilityStatus availability, Integer minExperience,
                                                             BigDecimal minRating, Pageable pageable) {
        Specification<ProfessionalProfile> spec = Specification
                .where(ProfessionalProfileSpecifications.isProfessional())
                .and(ProfessionalProfileSpecifications.matchesSearch(search))
                .and(ProfessionalProfileSpecifications.hasSpecialization(specialization))
                .and(ProfessionalProfileSpecifications.hasCity(city))
                .and(ProfessionalProfileSpecifications.hasAvailability(availability))
                .and(ProfessionalProfileSpecifications.hasMinimumExperience(minExperience))
                .and(ProfessionalProfileSpecifications.hasMinimumRating(minRating));

        Page<ProfessionalSummaryResponse> page = professionalProfileRepository.findAll(spec, pageable)
                .map(professionalMapper::toSummaryResponse);

        return PageResponse.from(page);
    }

    @Transactional(readOnly = true)
    public ProfessionalPublicProfileResponse getPublicProfile(Long professionalId) {
        User user = userRepository.findById(professionalId)
                .filter(u -> u.getRole() == Role.PROFESSIONAL)
                .orElseThrow(() -> new ResourceNotFoundException("Professional not found: " + professionalId));

        ProfessionalProfile profile = professionalProfileRepository.findByUserId(professionalId)
                .orElseThrow(() -> new ResourceNotFoundException("Professional profile not found: " + professionalId));

        return professionalMapper.toPublicProfileResponse(user, profile);
    }
}
