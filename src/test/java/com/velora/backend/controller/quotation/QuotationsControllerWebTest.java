package com.velora.backend.controller.quotation;

import com.velora.backend.controller.common.WebLayerTest;

import com.velora.backend.entity.user.Role;
import com.velora.backend.service.quotation.QuotationService;
import com.velora.backend.util.PageResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static com.velora.backend.controller.common.WebLayerSupport.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(QuotationsController.class)
@WebLayerTest
class QuotationsControllerWebTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private QuotationService quotationService;

    @Test
    void anonymousCallersAreRejected() throws Exception {
        mockMvc.perform(get("/api/quotations"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(quotationService);
    }

    @Test
    void listsTheCallersOwnQuotationsNewestFirst() throws Exception {
        when(quotationService.getQuotationsForUser(any(), any(), any(Pageable.class)))
                .thenReturn(new PageResponse<>(List.of(), 0, 20, 0, 0, true));

        mockMvc.perform(get("/api/quotations").with(as(7L, Role.CUSTOMER)))
                .andExpect(status().isOk());

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(quotationService).getQuotationsForUser(eq(7L), eq(Role.CUSTOMER), captor.capture());
        assertThat(captor.getValue().getSort()).isEqualTo(Sort.by(Sort.Direction.DESC, "createdAt"));
    }

    @Test
    void passesTheCallerRoleThroughSoTheServiceCanBranchByCustomerOrProfessional() throws Exception {
        when(quotationService.getQuotationsForUser(any(), any(), any(Pageable.class)))
                .thenReturn(new PageResponse<>(List.of(), 0, 20, 0, 0, true));

        mockMvc.perform(get("/api/quotations").with(as(9L, Role.PROFESSIONAL)))
                .andExpect(status().isOk());

        verify(quotationService).getQuotationsForUser(eq(9L), eq(Role.PROFESSIONAL), any(Pageable.class));
    }

    @Test
    void pageSizeIsClampedToTheMaximum() throws Exception {
        when(quotationService.getQuotationsForUser(any(), any(), any(Pageable.class)))
                .thenReturn(new PageResponse<>(List.of(), 0, 50, 0, 0, true));

        mockMvc.perform(get("/api/quotations?size=500").with(as(7L, Role.CUSTOMER)))
                .andExpect(status().isOk());

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(quotationService).getQuotationsForUser(eq(7L), eq(Role.CUSTOMER), captor.capture());
        assertThat(captor.getValue().getPageSize()).isEqualTo(50);
    }
}
