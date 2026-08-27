package com.sushimei.sushimei.backend.controller;

import com.sushimei.sushimei.backend.catalog.CatalogConfigurationService;
import com.sushimei.sushimei.backend.catalog.CreateMenuItemRequest;
import com.sushimei.sushimei.backend.catalog.CreateMenuSelectionGroupRequest;
import com.sushimei.sushimei.backend.catalog.MenuCatalogService;
import com.sushimei.sushimei.backend.catalog.MenuItemConfigurationDefinitionResponse;
import com.sushimei.sushimei.backend.catalog.MenuItemConfigurationResponse;
import com.sushimei.sushimei.backend.catalog.MenuItemQuoteRequest;
import com.sushimei.sushimei.backend.catalog.MenuItemQuoteResponse;
import com.sushimei.sushimei.backend.catalog.MenuItemResponse;
import com.sushimei.sushimei.backend.catalog.DefaultComponentResponse;
import com.sushimei.sushimei.backend.catalog.MenuItemComponentService;
import com.sushimei.sushimei.backend.catalog.MenuSelectionGroupResponse;
import com.sushimei.sushimei.backend.catalog.ReplaceMenuItemTagsRequest;
import com.sushimei.sushimei.backend.catalog.UpdateMenuItemRequest;
import com.sushimei.sushimei.backend.catalog.UpdateMenuSelectionGroupRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;
import java.util.Objects;

@RestController
@RequestMapping("/api/v1/menu/items")
public class MenuCatalogController {

    private final MenuCatalogService menuCatalogService;
    private final CatalogConfigurationService catalogConfigurationService;
    private final MenuItemComponentService menuItemComponentService;

    public MenuCatalogController(MenuCatalogService menuCatalogService,
                                 CatalogConfigurationService catalogConfigurationService,
                                 MenuItemComponentService menuItemComponentService) {
        this.menuCatalogService = Objects.requireNonNull(menuCatalogService,
                "menuCatalogService must not be null");
        this.catalogConfigurationService = Objects.requireNonNull(catalogConfigurationService,
                "catalogConfigurationService must not be null");
        this.menuItemComponentService = Objects.requireNonNull(menuItemComponentService,
                "menuItemComponentService must not be null");
    }

    @GetMapping
    public List<MenuItemResponse> list(
            @RequestParam(defaultValue = "false") boolean includeInactive,
            @RequestParam(defaultValue = "false") boolean standaloneOnly) {
        return menuCatalogService.list(includeInactive, standaloneOnly);
    }

    @GetMapping("/{id}")
    public MenuItemResponse get(@PathVariable Long id) {
        return menuCatalogService.get(id);
    }

    @GetMapping("/{id}/configuration")
    public MenuItemConfigurationResponse configuration(@PathVariable Long id) {
        return catalogConfigurationService.operationalConfiguration(id);
    }

    @GetMapping("/{id}/components")
    public List<DefaultComponentResponse> components(@PathVariable Long id) {
        return menuItemComponentService.activeComponents(id);
    }

    @GetMapping("/{id}/configuration-definition")
    public MenuItemConfigurationDefinitionResponse configurationDefinition(@PathVariable Long id) {
        return catalogConfigurationService.configurationDefinition(id);
    }

    @PostMapping("/{id}/quote")
    public MenuItemQuoteResponse quote(@PathVariable Long id,
                                       @Valid @RequestBody MenuItemQuoteRequest request) {
        return catalogConfigurationService.quote(id, request);
    }

    @PostMapping
    public ResponseEntity<MenuItemResponse> create(@Valid @RequestBody CreateMenuItemRequest request) {
        MenuItemResponse created = menuCatalogService.create(request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(created.id())
                .toUri();
        return ResponseEntity.created(location).body(created);
    }

    @PutMapping("/{id}")
    public MenuItemResponse update(@PathVariable Long id,
                                   @Valid @RequestBody UpdateMenuItemRequest request) {
        return menuCatalogService.update(id, request);
    }

    @PutMapping("/{id}/tags")
    public MenuItemResponse replaceTags(@PathVariable Long id,
                                        @Valid @RequestBody ReplaceMenuItemTagsRequest request) {
        return catalogConfigurationService.replaceItemTags(id, request);
    }

    @PostMapping("/{itemId}/selection-groups")
    public ResponseEntity<MenuSelectionGroupResponse> createGroup(@PathVariable Long itemId,
                                                                    @Valid @RequestBody CreateMenuSelectionGroupRequest request) {
        MenuSelectionGroupResponse created = catalogConfigurationService.createGroup(itemId, request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest().path("/{groupId}")
                .buildAndExpand(created.id()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    @PutMapping("/{itemId}/selection-groups/{groupId}")
    public MenuSelectionGroupResponse updateGroup(@PathVariable Long itemId,
                                                   @PathVariable Long groupId,
                                                   @Valid @RequestBody UpdateMenuSelectionGroupRequest request) {
        return catalogConfigurationService.updateGroup(itemId, groupId, request);
    }

    @DeleteMapping("/{itemId}/selection-groups/{groupId}")
    public ResponseEntity<Void> archiveGroup(@PathVariable Long itemId, @PathVariable Long groupId) {
        catalogConfigurationService.archiveGroup(itemId, groupId);
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<MenuCatalogApiError> handleUnreadableRequest(HttpMessageNotReadableException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new MenuCatalogApiError("INVALID_MENU_ITEM", "Solicitud de catálogo inválida."));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> archive(@PathVariable Long id) {
        menuCatalogService.archive(id);
        return ResponseEntity.noContent().build();
    }
}
