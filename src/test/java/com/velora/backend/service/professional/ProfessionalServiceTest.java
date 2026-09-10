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
import com.velora.backend.repository.user.UserRepository;
import com.velora.backend.util.PageResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProfessionalServiceTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private ProfessionalProfileRepository professionalProfileRepository;

    private final ProfessionalMapper professionalMapper = new ProfessionalMapper();

    private ProfessionalService professionalService;

    private User professional;
    private ProfessionalProfile profile;

    @BeforeEach
    void setUp() {
        professionalService = new ProfessionalService(
                userRepository, professionalProfileRepository, professionalMapper);

        professional = User.builder().id(7L).fullName("Asha Rao").role(Role.PROFESSIONAL).build();
        profile = ProfessionalProfile.builder()
                .id(70L)
                .user(professional)
                .bio("Ten years of kitchen work")
                .specialization("Modular Kitchen")
                .city("Pune")
                .yearsExperience(10)
                .availabilityStatus(AvailabilityStatus.AVAILABLE)
                .averageRating(new BigDecimal("4.50"))
                .ratingCount(12)
                .build();
    }

    @Test
    void searchReturnsSummaryCardsKeyedByUserId() {
        Pageable pageable = PageRequest.of(0, 20);
        when(professionalProfileRepository.findAll(any(Specification.class), eq(pageable)))
                .thenReturn(new PageImpl<>(List.of(profile), pageable, 1));

        PageResponse<ProfessionalSummaryResponse> page = professionalService.search(
                null, null, null, null, null, null, pageable);

        assertThat(page.totalElements()).isEqualTo(1);
        assertThat(page.content()).singleElement().satisfies(card -> {
            assertThat(card.id()).isEqualTo(7L);
            assertThat(card.fullName()).isEqualTo("Asha Rao");
            assertThat(card.specialization()).isEqualTo("Modular Kitchen");
            assertThat(card.city()).isEqualTo("Pune");
            assertThat(card.yearsExperience()).isEqualTo(10);
            assertThat(card.availabilityStatus()).isEqualTo(AvailabilityStatus.AVAILABLE);
            assertThat(card.averageRating()).isEqualByComparingTo("4.50");
            assertThat(card.ratingCount()).isEqualTo(12);
        });
    }

    @Test
    void searchWithNoMatchesReturnsAnEmptyPage() {
        Pageable pageable = PageRequest.of(0, 20);
        when(professionalProfileRepository.findAll(any(Specification.class), eq(pageable)))
                .thenReturn(Page.empty(pageable));

        PageResponse<ProfessionalSummaryResponse> page = professionalService.search(
                "nobody", "Plumbing", "Nowhere", AvailabilityStatus.AVAILABLE, 30,
                new BigDecimal("5.00"), pageable);

        assertThat(page.content()).isEmpty();
        assertThat(page.totalElements()).isZero();
    }

    @Test
    void getPublicProfileReturnsTheFullProfileIncludingBio() {
        when(userRepository.findById(7L)).thenReturn(Optional.of(professional));
        when(professionalProfileRepository.findByUserId(7L)).thenReturn(Optional.of(profile));

        ProfessionalPublicProfileResponse response = professionalService.getPublicProfile(7L);

        assertThat(response.id()).isEqualTo(7L);
        assertThat(response.bio()).isEqualTo("Ten years of kitchen work");
        assertThat(response.ratingCount()).isEqualTo(12);
    }

    @Test
    void getPublicProfileRejectsAUserWhoIsNotAProfessional() {
        User customer = User.builder().id(8L).fullName("Cust").role(Role.CUSTOMER).build();
        when(userRepository.findById(8L)).thenReturn(Optional.of(customer));

        assertThatThrownBy(() -> professionalService.getPublicProfile(8L))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
