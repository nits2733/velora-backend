package com.velora.backend.service.portfolio;

import com.velora.backend.dto.portfolio.CategoryResponse;
import com.velora.backend.dto.portfolio.CreateCategoryRequest;
import com.velora.backend.dto.portfolio.UpdateCategoryRequest;
import com.velora.backend.entity.portfolio.Category;
import com.velora.backend.entity.portfolio.ServiceGroup;
import com.velora.backend.exception.DuplicateResourceException;
import com.velora.backend.exception.InvalidStateTransitionException;
import com.velora.backend.exception.ResourceNotFoundException;
import com.velora.backend.mapper.portfolio.PortfolioItemMapper;
import com.velora.backend.repository.booking.BookingRepository;
import com.velora.backend.repository.portfolio.CategoryRepository;
import com.velora.backend.repository.portfolio.PortfolioItemRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.annotation.CacheEvict;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CategoryServiceTest {

    @Mock
    private CategoryRepository categoryRepository;
    @Mock
    private BookingRepository bookingRepository;
    @Mock
    private PortfolioItemRepository portfolioItemRepository;

    private final PortfolioItemMapper portfolioItemMapper = new PortfolioItemMapper();

    private CategoryService categoryService;

    private Category kitchen;   // top-level, HOME_PROJECT
    private Category plumbing;  // top-level, INDIVIDUAL_SERVICE
    private Category modular;   // child of kitchen

    @BeforeEach
    void setUp() {
        categoryService = new CategoryService(categoryRepository, bookingRepository,
                portfolioItemRepository, portfolioItemMapper);

        kitchen = Category.builder().id(1L).name("Kitchen").serviceGroup(ServiceGroup.HOME_PROJECT).build();
        plumbing = Category.builder().id(2L).name("Plumbing").serviceGroup(ServiceGroup.INDIVIDUAL_SERVICE).build();
        modular = Category.builder().id(3L).name("Modular").serviceGroup(ServiceGroup.HOME_PROJECT).parent(kitchen).build();
        kitchen.setSubcategories(List.of(modular));
    }

    private void echoSave() {
        when(categoryRepository.save(any(Category.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    // ---- read ----

    @Test
    void getAllCategoriesReturnsTopLevelOnlyWithNestedSubcategories() {
        when(categoryRepository.findByParentIsNull()).thenReturn(List.of(kitchen, plumbing));

        List<CategoryResponse> result = categoryService.getAllCategories();

        assertThat(result).hasSize(2);
        CategoryResponse kitchenDto = result.get(0);
        assertThat(kitchenDto.parentId()).isNull();
        assertThat(kitchenDto.subcategories()).hasSize(1);
        assertThat(kitchenDto.subcategories().get(0).name()).isEqualTo("Modular");
        assertThat(kitchenDto.subcategories().get(0).parentId()).isEqualTo(1L);
        assertThat(kitchenDto.subcategories().get(0).subcategories()).isEmpty();
        assertThat(result.get(1).subcategories()).isEmpty();
        verify(categoryRepository, never()).findAll();
    }

    @Test
    void cacheEvictedOnEveryCategoryMutation() {
        for (String name : List.of("createCategory", "updateCategory", "deleteCategory")) {
            Method m = Arrays.stream(CategoryService.class.getDeclaredMethods())
                    .filter(x -> x.getName().equals(name)).findFirst().orElseThrow();
            CacheEvict evict = m.getAnnotation(CacheEvict.class);
            assertThat(evict).as(name).isNotNull();
            assertThat(evict.value()).contains("categories");
            assertThat(evict.allEntries()).as(name).isTrue();
        }
    }

    // ---- create ----

    @Test
    void createTopLevelCategorySucceeds() {
        echoSave();
        CategoryResponse r = categoryService.createCategory(
                new CreateCategoryRequest("Painting", "Walls", ServiceGroup.INDIVIDUAL_SERVICE, null, null));

        assertThat(r.name()).isEqualTo("Painting");
        assertThat(r.serviceGroup()).isEqualTo(ServiceGroup.INDIVIDUAL_SERVICE);
        assertThat(r.parentId()).isNull();
        assertThat(r.subcategories()).isEmpty();
    }

    @Test
    void createTopLevelRejectsMissingServiceGroup() {
        assertThatThrownBy(() -> categoryService.createCategory(
                new CreateCategoryRequest("Painting", null, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
        verify(categoryRepository, never()).save(any());
    }

    @Test
    void createTopLevelRejectsDuplicateNameIgnoringCase() {
        when(categoryRepository.existsByParentIsNullAndNameIgnoreCase("kitchen")).thenReturn(true);

        assertThatThrownBy(() -> categoryService.createCategory(
                new CreateCategoryRequest("kitchen", null, ServiceGroup.HOME_PROJECT, null, null)))
                .isInstanceOf(DuplicateResourceException.class);
        verify(categoryRepository, never()).save(any());
    }

    @Test
    void createChildInheritsParentServiceGroupIgnoringRequestedOne() {
        when(categoryRepository.findById(1L)).thenReturn(Optional.of(kitchen));
        echoSave();

        CategoryResponse r = categoryService.createCategory(
                new CreateCategoryRequest("Island", null, ServiceGroup.INDIVIDUAL_SERVICE, null, 1L));

        assertThat(r.serviceGroup()).isEqualTo(ServiceGroup.HOME_PROJECT);
        assertThat(r.parentId()).isEqualTo(1L);
    }

    @Test
    void createChildDoesNotRequireServiceGroup() {
        when(categoryRepository.findById(1L)).thenReturn(Optional.of(kitchen));
        echoSave();

        CategoryResponse r = categoryService.createCategory(
                new CreateCategoryRequest("Island", null, null, null, 1L));

        assertThat(r.serviceGroup()).isEqualTo(ServiceGroup.HOME_PROJECT);
    }

    @Test
    void createChildRejectsMissingParent() {
        when(categoryRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> categoryService.createCategory(
                new CreateCategoryRequest("Island", null, null, null, 99L)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void createChildRejectsParentThatIsItselfAChild() {
        when(categoryRepository.findById(3L)).thenReturn(Optional.of(modular));

        assertThatThrownBy(() -> categoryService.createCategory(
                new CreateCategoryRequest("TooDeep", null, null, null, 3L)))
                .isInstanceOf(IllegalArgumentException.class);
        verify(categoryRepository, never()).save(any());
    }

    @Test
    void createChildRejectsDuplicateSiblingName() {
        when(categoryRepository.findById(1L)).thenReturn(Optional.of(kitchen));
        when(categoryRepository.existsByParentIdAndNameIgnoreCase(1L, "modular")).thenReturn(true);

        assertThatThrownBy(() -> categoryService.createCategory(
                new CreateCategoryRequest("modular", null, null, null, 1L)))
                .isInstanceOf(DuplicateResourceException.class);
    }

    @Test
    void createChildAllowsSameNameUnderDifferentParent() {
        when(categoryRepository.findById(2L)).thenReturn(Optional.of(plumbing));
        when(categoryRepository.existsByParentIdAndNameIgnoreCase(2L, "Modular")).thenReturn(false);
        echoSave();

        CategoryResponse r = categoryService.createCategory(
                new CreateCategoryRequest("Modular", null, null, null, 2L));

        assertThat(r.serviceGroup()).isEqualTo(ServiceGroup.INDIVIDUAL_SERVICE);
    }

    // ---- update ----

    @Test
    void updateRejectsMissingCategory() {
        when(categoryRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> categoryService.updateCategory(99L,
                new UpdateCategoryRequest("X", null, ServiceGroup.HOME_PROJECT, null, null)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void updateTopLevelChangesFields() {
        when(categoryRepository.findById(2L)).thenReturn(Optional.of(plumbing));
        echoSave();

        CategoryResponse r = categoryService.updateCategory(2L,
                new UpdateCategoryRequest("Plumbing and Pipes", "Pipes", ServiceGroup.INDIVIDUAL_SERVICE,
                        "http://img.test/p.png", null));

        assertThat(r.name()).isEqualTo("Plumbing and Pipes");
        assertThat(r.description()).isEqualTo("Pipes");
        assertThat(r.imageUrl()).isEqualTo("http://img.test/p.png");
    }

    @Test
    void updateKeepingSameNameIsNotADuplicateOfItself() {
        when(categoryRepository.findById(2L)).thenReturn(Optional.of(plumbing));
        // the self-excluding variant must be used, so the row itself never counts as a duplicate
        when(categoryRepository.existsByParentIsNullAndNameIgnoreCaseAndIdNot("Plumbing", 2L)).thenReturn(false);
        echoSave();

        CategoryResponse r = categoryService.updateCategory(2L,
                new UpdateCategoryRequest("Plumbing", "new desc", ServiceGroup.INDIVIDUAL_SERVICE, null, null));

        assertThat(r.description()).isEqualTo("new desc");
    }

    @Test
    void updateRejectsRenameToExistingTopLevelName() {
        when(categoryRepository.findById(2L)).thenReturn(Optional.of(plumbing));
        when(categoryRepository.existsByParentIsNullAndNameIgnoreCaseAndIdNot("Kitchen", 2L)).thenReturn(true);

        assertThatThrownBy(() -> categoryService.updateCategory(2L,
                new UpdateCategoryRequest("Kitchen", null, ServiceGroup.INDIVIDUAL_SERVICE, null, null)))
                .isInstanceOf(DuplicateResourceException.class);
    }

    @Test
    void updateRejectsRenameToExistingSiblingName() {
        when(categoryRepository.findById(3L)).thenReturn(Optional.of(modular));
        when(categoryRepository.findById(1L)).thenReturn(Optional.of(kitchen));
        when(categoryRepository.existsByParentIdAndNameIgnoreCaseAndIdNot(1L, "Island", 3L)).thenReturn(true);

        assertThatThrownBy(() -> categoryService.updateCategory(3L,
                new UpdateCategoryRequest("Island", null, null, null, 1L)))
                .isInstanceOf(DuplicateResourceException.class);
    }

    @Test
    void updateRejectsCategoryBeingItsOwnParent() {
        when(categoryRepository.findById(1L)).thenReturn(Optional.of(kitchen));

        assertThatThrownBy(() -> categoryService.updateCategory(1L,
                new UpdateCategoryRequest("Kitchen", null, null, null, 1L)))
                .isInstanceOf(IllegalArgumentException.class);
        verify(categoryRepository, never()).save(any());
    }

    @Test
    void updateRejectsMakingCategoryWithChildrenIntoAChild() {
        when(categoryRepository.findById(1L)).thenReturn(Optional.of(kitchen));
        when(categoryRepository.findById(2L)).thenReturn(Optional.of(plumbing));
        when(categoryRepository.existsByParentId(1L)).thenReturn(true);

        assertThatThrownBy(() -> categoryService.updateCategory(1L,
                new UpdateCategoryRequest("Kitchen", null, null, null, 2L)))
                .isInstanceOf(IllegalArgumentException.class);
        verify(categoryRepository, never()).save(any());
    }

    @Test
    void updateRejectsReparentingUnderAChild() {
        Category other = Category.builder().id(4L).name("Other").serviceGroup(ServiceGroup.HOME_PROJECT).build();
        when(categoryRepository.findById(4L)).thenReturn(Optional.of(other));
        when(categoryRepository.findById(3L)).thenReturn(Optional.of(modular));

        assertThatThrownBy(() -> categoryService.updateCategory(4L,
                new UpdateCategoryRequest("Other", null, null, null, 3L)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void updateRejectsMissingParent() {
        when(categoryRepository.findById(2L)).thenReturn(Optional.of(plumbing));
        when(categoryRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> categoryService.updateCategory(2L,
                new UpdateCategoryRequest("Plumbing", null, null, null, 99L)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void updateMovingLeafUnderNewParentForcesParentServiceGroup() {
        Category leaf = Category.builder().id(5L).name("Fixtures").serviceGroup(ServiceGroup.HOME_PROJECT).build();
        when(categoryRepository.findById(5L)).thenReturn(Optional.of(leaf));
        when(categoryRepository.findById(2L)).thenReturn(Optional.of(plumbing));
        echoSave();

        CategoryResponse r = categoryService.updateCategory(5L,
                new UpdateCategoryRequest("Fixtures", null, ServiceGroup.HOME_PROJECT, null, 2L));

        assertThat(r.parentId()).isEqualTo(2L);
        assertThat(r.serviceGroup()).isEqualTo(ServiceGroup.INDIVIDUAL_SERVICE);
    }

    @Test
    void updateTopLevelRejectsMissingServiceGroup() {
        when(categoryRepository.findById(2L)).thenReturn(Optional.of(plumbing));

        assertThatThrownBy(() -> categoryService.updateCategory(2L,
                new UpdateCategoryRequest("Plumbing", null, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- delete ----

    @Test
    void deleteRejectsMissingCategory() {
        when(categoryRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> categoryService.deleteCategory(99L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void deleteRejectsCategoryWithChildren() {
        when(categoryRepository.findById(1L)).thenReturn(Optional.of(kitchen));
        when(categoryRepository.existsByParentId(1L)).thenReturn(true);

        assertThatThrownBy(() -> categoryService.deleteCategory(1L))
                .isInstanceOf(InvalidStateTransitionException.class);
        verify(categoryRepository, never()).delete(any(Category.class));
    }

    @Test
    void deleteRejectsCategoryReferencedByBooking() {
        when(categoryRepository.findById(2L)).thenReturn(Optional.of(plumbing));
        when(bookingRepository.existsByCategoryId(2L)).thenReturn(true);

        assertThatThrownBy(() -> categoryService.deleteCategory(2L))
                .isInstanceOf(InvalidStateTransitionException.class);
        verify(categoryRepository, never()).delete(any(Category.class));
    }

    @Test
    void deleteRejectsCategoryReferencedByPortfolioItem() {
        when(categoryRepository.findById(2L)).thenReturn(Optional.of(plumbing));
        when(portfolioItemRepository.existsByCategoryId(2L)).thenReturn(true);

        assertThatThrownBy(() -> categoryService.deleteCategory(2L))
                .isInstanceOf(InvalidStateTransitionException.class);
        verify(categoryRepository, never()).delete(any(Category.class));
    }

    @Test
    void deleteUnusedLeafSucceeds() {
        when(categoryRepository.findById(3L)).thenReturn(Optional.of(modular));

        categoryService.deleteCategory(3L);

        verify(categoryRepository).delete(modular);
    }
}
