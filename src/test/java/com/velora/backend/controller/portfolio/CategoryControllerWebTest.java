package com.velora.backend.controller.portfolio;

import com.velora.backend.controller.common.WebLayerTest;
import com.velora.backend.dto.portfolio.CategoryResponse;
import com.velora.backend.dto.portfolio.CreateCategoryRequest;
import com.velora.backend.dto.portfolio.UpdateCategoryRequest;
import com.velora.backend.entity.portfolio.ServiceGroup;
import com.velora.backend.entity.user.Role;
import com.velora.backend.exception.DuplicateResourceException;
import com.velora.backend.exception.InvalidStateTransitionException;
import com.velora.backend.service.portfolio.CategoryService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static com.velora.backend.controller.common.WebLayerSupport.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CategoryController.class)
@WebLayerTest
class CategoryControllerWebTest {

    private static final String VALID_BODY =
            "{\"name\":\"Painting\",\"description\":\"Walls\",\"serviceGroup\":\"INDIVIDUAL_SERVICE\"}";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private CategoryService categoryService;

    private CategoryResponse stub(Long id, String name, Long parentId, List<CategoryResponse> subs) {
        return new CategoryResponse(id, name, null, ServiceGroup.HOME_PROJECT, null, parentId, subs);
    }

    @Test
    void listIsPublicAndReturnsNestedSubcategories() throws Exception {
        CategoryResponse child = stub(3L, "Modular", 1L, List.of());
        when(categoryService.getAllCategories()).thenReturn(List.of(stub(1L, "Kitchen", null, List.of(child))));

        mockMvc.perform(get("/api/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Kitchen"))
                .andExpect(jsonPath("$[0].parentId").doesNotExist())
                .andExpect(jsonPath("$[0].subcategories[0].name").value("Modular"))
                .andExpect(jsonPath("$[0].subcategories[0].parentId").value(1))
                .andExpect(jsonPath("$[0].subcategories[0].subcategories").isEmpty());
    }

    @Test
    void createIsRejectedForAnonymousAndNonAdmin() throws Exception {
        mockMvc.perform(post("/api/categories").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/categories").with(as(1L, Role.CUSTOMER))
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/categories").with(as(2L, Role.PROFESSIONAL))
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isForbidden());

        verifyNoInteractions(categoryService);
    }

    @Test
    void createAsAdminReturns201AndPassesRequestToService() throws Exception {
        when(categoryService.createCategory(any(CreateCategoryRequest.class)))
                .thenReturn(stub(10L, "Painting", null, List.of()));

        mockMvc.perform(post("/api/categories").with(as(99L, Role.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(10));

        ArgumentCaptor<CreateCategoryRequest> captor = ArgumentCaptor.forClass(CreateCategoryRequest.class);
        verify(categoryService).createCategory(captor.capture());
        assertThat(captor.getValue().name()).isEqualTo("Painting");
        assertThat(captor.getValue().serviceGroup()).isEqualTo(ServiceGroup.INDIVIDUAL_SERVICE);
        assertThat(captor.getValue().parentId()).isNull();
    }

    @Test
    void createSubcategoryWithoutServiceGroupPassesValidation() throws Exception {
        when(categoryService.createCategory(any(CreateCategoryRequest.class)))
                .thenReturn(stub(11L, "Island", 1L, List.of()));

        mockMvc.perform(post("/api/categories").with(as(99L, Role.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Island\",\"parentId\":1}"))
                .andExpect(status().isCreated());
    }

    @Test
    void createRejectsBlankNameOversizedFieldsAndEmptyBody() throws Exception {
        mockMvc.perform(post("/api/categories").with(as(99L, Role.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\" \",\"serviceGroup\":\"HOME_PROJECT\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/categories").with(as(99L, Role.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + "a".repeat(101) + "\",\"serviceGroup\":\"HOME_PROJECT\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/categories").with(as(99L, Role.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"X\",\"description\":\"" + "d".repeat(501)
                                + "\",\"serviceGroup\":\"HOME_PROJECT\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/categories").with(as(99L, Role.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(categoryService);
    }

    @Test
    void createDuplicateNameSurfacesAs409() throws Exception {
        when(categoryService.createCategory(any(CreateCategoryRequest.class)))
                .thenThrow(new DuplicateResourceException("Category already exists"));

        mockMvc.perform(post("/api/categories").with(as(99L, Role.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isConflict());
    }

    @Test
    void createDepthViolationSurfacesAs400() throws Exception {
        when(categoryService.createCategory(any(CreateCategoryRequest.class)))
                .thenThrow(new IllegalArgumentException("Max depth is 2"));

        mockMvc.perform(post("/api/categories").with(as(99L, Role.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updateIsRejectedForAnonymousAndNonAdmin() throws Exception {
        mockMvc.perform(put("/api/categories/1").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(put("/api/categories/1").with(as(1L, Role.CUSTOMER))
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isForbidden());

        verifyNoInteractions(categoryService);
    }

    @Test
    void updateAsAdminReturns200() throws Exception {
        when(categoryService.updateCategory(eq(1L), any(UpdateCategoryRequest.class)))
                .thenReturn(stub(1L, "Painting", null, List.of()));

        mockMvc.perform(put("/api/categories/1").with(as(99L, Role.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Painting"));

        ArgumentCaptor<UpdateCategoryRequest> captor = ArgumentCaptor.forClass(UpdateCategoryRequest.class);
        verify(categoryService).updateCategory(eq(1L), captor.capture());
        assertThat(captor.getValue().name()).isEqualTo("Painting");
    }

    @Test
    void updateRejectsInvalidBodyWith400() throws Exception {
        mockMvc.perform(put("/api/categories/1").with(as(99L, Role.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"serviceGroup\":\"HOME_PROJECT\"}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(categoryService);
    }

    @Test
    void deleteIsRejectedForAnonymousAndNonAdmin() throws Exception {
        mockMvc.perform(delete("/api/categories/1"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/categories/1").with(as(1L, Role.CUSTOMER)))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/categories/1").with(as(2L, Role.PROFESSIONAL)))
                .andExpect(status().isForbidden());

        verifyNoInteractions(categoryService);
    }

    @Test
    void deleteAsAdminReturns204() throws Exception {
        mockMvc.perform(delete("/api/categories/7").with(as(99L, Role.ADMIN)))
                .andExpect(status().isNoContent());

        verify(categoryService).deleteCategory(7L);
    }

    @Test
    void deleteInUseCategorySurfacesAs409() throws Exception {
        doThrow(new InvalidStateTransitionException("Category in use"))
                .when(categoryService).deleteCategory(7L);

        mockMvc.perform(delete("/api/categories/7").with(as(99L, Role.ADMIN)))
                .andExpect(status().isConflict());
    }
}
