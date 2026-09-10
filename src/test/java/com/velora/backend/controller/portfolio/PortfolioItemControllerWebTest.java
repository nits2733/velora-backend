package com.velora.backend.controller.portfolio;

import com.velora.backend.controller.common.WebLayerTest;

import com.velora.backend.dto.portfolio.PortfolioItemResponse;
import com.velora.backend.dto.portfolio.SavePortfolioItemRequest;
import com.velora.backend.entity.user.Role;
import com.velora.backend.exception.InvalidStateTransitionException;
import com.velora.backend.exception.UnauthorizedActionException;
import com.velora.backend.service.portfolio.PortfolioItemService;
import com.velora.backend.util.PageResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static com.velora.backend.controller.common.WebLayerSupport.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Portfolio is the one resource with both public reads and role-restricted writes, so
 * this is where the read/write access split gets pinned - plus the query-parameter
 * clamping that only exists in the controller.
 */
@WebMvcTest(PortfolioItemController.class)
@WebLayerTest
class PortfolioItemControllerWebTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private PortfolioItemService portfolioItemService;

    @Test
    void browsingTheCatalogNeedsNoToken() throws Exception {
        when(portfolioItemService.search(any(), any(), any(), any(Pageable.class)))
                .thenReturn(new PageResponse<>(List.of(), 0, 20, 0, 0, true));

        mockMvc.perform(get("/api/portfolio"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());
    }

    @Test
    void anAbsurdPageSizeIsClampedBeforeItReachesTheDatabase() throws Exception {
        when(portfolioItemService.search(any(), any(), any(), any(Pageable.class)))
                .thenReturn(new PageResponse<>(List.of(), 0, 50, 0, 0, true));

        mockMvc.perform(get("/api/portfolio").param("size", "5000").param("page", "-3"))
                .andExpect(status().isOk());

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(portfolioItemService).search(any(), any(), any(), captor.capture());
        assertThat(captor.getValue().getPageSize()).isEqualTo(50);
        assertThat(captor.getValue().getPageNumber()).isZero();
    }

    @Test
    void anUnknownSortFieldFallsBackToTheDefaultInsteadOfErroring() throws Exception {
        when(portfolioItemService.search(any(), any(), any(), any(Pageable.class)))
                .thenReturn(new PageResponse<>(List.of(), 0, 20, 0, 0, true));

        mockMvc.perform(get("/api/portfolio").param("sortBy", "'; DROP TABLE portfolio_items--"))
                .andExpect(status().isOk());

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(portfolioItemService).search(any(), any(), any(), captor.capture());
        assertThat(captor.getValue().getSort()).isEqualTo(Sort.by(Sort.Direction.DESC, "createdAt"));
    }

    @Test
    void uploadingAnonymouslyIsUnauthorized() throws Exception {
        mockMvc.perform(post("/api/portfolio")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"A Repaint","categoryId":8}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));

        verifyNoInteractions(portfolioItemService);
    }

    @Test
    void aCustomerCannotUploadAPortfolioItem() throws Exception {
        mockMvc.perform(post("/api/portfolio")
                        .with(as(1L, Role.CUSTOMER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"A Repaint","categoryId":8}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));

        verifyNoInteractions(portfolioItemService);
    }

    @Test
    void aProfessionalUploadsUnderTheirOwnIdTakenFromTheToken() throws Exception {
        when(portfolioItemService.create(eq(9L), any(SavePortfolioItemRequest.class)))
                .thenReturn(new PortfolioItemResponse(70L, "A Repaint", null, null, 9L, "Pro",
                        null, null, null, Instant.now()));

        mockMvc.perform(post("/api/portfolio")
                        .with(as(9L, Role.PROFESSIONAL))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"A Repaint","categoryId":8}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(70));

        verify(portfolioItemService).create(eq(9L), any(SavePortfolioItemRequest.class));
    }

    @Test
    void deletingSomeoneElsesItemIsForbidden() throws Exception {
        org.mockito.Mockito.doThrow(new UnauthorizedActionException("You can only modify your own portfolio items"))
                .when(portfolioItemService).delete(9L, 70L);

        mockMvc.perform(delete("/api/portfolio/70").with(as(9L, Role.PROFESSIONAL)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("You can only modify your own portfolio items"));
    }

    @Test
    void deletingAReferencedItemIsAConflict() throws Exception {
        org.mockito.Mockito.doThrow(new InvalidStateTransitionException(
                        "This portfolio item is referenced by an existing booking and cannot be deleted"))
                .when(portfolioItemService).delete(9L, 71L);

        mockMvc.perform(delete("/api/portfolio/71").with(as(9L, Role.PROFESSIONAL)))
                .andExpect(status().isConflict());
    }

    @Test
    void aSuccessfulDeleteReturnsNoContent() throws Exception {
        mockMvc.perform(delete("/api/portfolio/72").with(as(9L, Role.PROFESSIONAL)))
                .andExpect(status().isNoContent());

        verify(portfolioItemService).delete(9L, 72L);
    }
}
