package com.velora.backend.controller.professional;

import com.velora.backend.controller.common.WebLayerTest;

import com.velora.backend.dto.professional.ProfessionalSummaryResponse;
import com.velora.backend.dto.review.PublicReviewResponse;
import com.velora.backend.entity.professional.AvailabilityStatus;
import com.velora.backend.entity.user.Role;
import com.velora.backend.exception.ResourceNotFoundException;
import com.velora.backend.service.professional.ProfessionalService;
import com.velora.backend.service.review.ReviewService;
import com.velora.backend.util.PageResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static com.velora.backend.controller.common.WebLayerSupport.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The directory, profile and review endpoints are admin-only under the admin-controlled
 * assignment model - customers never browse or select a professional directly - so what
 * matters here is that anonymous/customer/professional callers are all rejected and only
 * an admin token gets through.
 */
@WebMvcTest(ProfessionalController.class)
@WebLayerTest
class ProfessionalControllerWebTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ProfessionalService professionalService;

    @MockBean
    private ReviewService reviewService;

    @Test
    void theDirectoryIsClosedToAnonymousCallers() throws Exception {
        mockMvc.perform(get("/api/professionals"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(professionalService);
    }

    @Test
    void theDirectoryIsClosedToCustomersAndProfessionals() throws Exception {
        mockMvc.perform(get("/api/professionals").with(as(1L, Role.CUSTOMER)))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/professionals").with(as(2L, Role.PROFESSIONAL)))
                .andExpect(status().isForbidden());

        verifyNoInteractions(professionalService);
    }

    @Test
    void theDirectoryIsReachableWithAnAdminTokenAtTheBareCollectionPath() throws Exception {
        when(professionalService.search(any(), any(), any(), any(), any(), any(), any(Pageable.class)))
                .thenReturn(new PageResponse<>(List.of(new ProfessionalSummaryResponse(
                        7L, "Asha Rao", "Modular Kitchen", "Pune", 10,
                        AvailabilityStatus.AVAILABLE, new BigDecimal("4.50"), 12)), 0, 20, 1, 1, true));

        mockMvc.perform(get("/api/professionals").with(as(3L, Role.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(7))
                .andExpect(jsonPath("$.content[0].fullName").value("Asha Rao"));
    }

    @Test
    void everyFilterIsBoundAndPassedThroughAsGiven() throws Exception {
        when(professionalService.search(any(), any(), any(), any(), any(), any(), any(Pageable.class)))
                .thenReturn(new PageResponse<>(List.of(), 0, 20, 0, 0, true));

        mockMvc.perform(get("/api/professionals")
                        .with(as(3L, Role.ADMIN))
                        .param("search", "kitchen")
                        .param("specialization", "Modular")
                        .param("city", "Pune")
                        .param("availability", "AVAILABLE")
                        .param("minExperience", "5")
                        .param("minRating", "4.0"))
                .andExpect(status().isOk());

        verify(professionalService).search(eq("kitchen"), eq("Modular"), eq("Pune"),
                eq(AvailabilityStatus.AVAILABLE), eq(5), eq(new BigDecimal("4.0")), any(Pageable.class));
    }

    @Test
    void theDirectoryDefaultsToHighestRatedFirst() throws Exception {
        when(professionalService.search(any(), any(), any(), any(), any(), any(), any(Pageable.class)))
                .thenReturn(new PageResponse<>(List.of(), 0, 20, 0, 0, true));

        mockMvc.perform(get("/api/professionals").with(as(3L, Role.ADMIN))).andExpect(status().isOk());

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(professionalService).search(any(), any(), any(), any(), any(), any(), captor.capture());
        assertThat(captor.getValue().getSort()).isEqualTo(Sort.by(Sort.Direction.DESC, "averageRating"));
    }

    @Test
    void anUnparseableAvailabilityIsABadRequestNotAServerError() throws Exception {
        mockMvc.perform(get("/api/professionals").with(as(3L, Role.ADMIN)).param("availability", "MAYBE"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid value for 'availability'"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("availability"))
                .andExpect(jsonPath("$.fieldErrors[0].message").value("Must be one of: AVAILABLE, UNAVAILABLE"));
    }

    @Test
    void anUnparseablePathVariableIsABadRequestNotAServerError() throws Exception {
        mockMvc.perform(get("/api/professionals/not-a-number/reviews").with(as(3L, Role.ADMIN)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("id"));
    }

    @Test
    void reviewsAreClosedToAnonymousCallers() throws Exception {
        mockMvc.perform(get("/api/professionals/7/reviews"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(reviewService);
    }

    @Test
    void reviewsAreReachableWithAnAdminTokenAndOrderedNewestFirst() throws Exception {
        when(reviewService.getProfessionalReviews(eq(7L), any(Pageable.class)))
                .thenReturn(new PageResponse<>(List.of(new PublicReviewResponse(
                        600L, 5, "Excellent", "Cust", Instant.now())), 0, 20, 1, 1, true));

        mockMvc.perform(get("/api/professionals/7/reviews").with(as(3L, Role.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].rating").value(5))
                .andExpect(jsonPath("$.content[0].customerName").value("Cust"))
                // the booking id is the whole reason this is a separate DTO
                .andExpect(jsonPath("$.content[0].bookingId").doesNotExist());

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(reviewService).getProfessionalReviews(eq(7L), captor.capture());
        assertThat(captor.getValue().getSort()).isEqualTo(Sort.by(Sort.Direction.DESC, "createdAt"));
    }

    @Test
    void reviewsForANonProfessionalAreANotFound() throws Exception {
        when(reviewService.getProfessionalReviews(eq(1L), any(Pageable.class)))
                .thenThrow(new ResourceNotFoundException("Professional not found: 1"));

        mockMvc.perform(get("/api/professionals/1/reviews").with(as(3L, Role.ADMIN)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value("Professional not found: 1"));
    }
}
