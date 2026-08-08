package com.sushimei.sushimei.backend.controller;

import com.sushimei.sushimei.backend.catalog.CreateMenuItemRequest;
import com.sushimei.sushimei.backend.catalog.MenuCatalogService;
import com.sushimei.sushimei.backend.catalog.MenuItemResponse;
import com.sushimei.sushimei.backend.catalog.UpdateMenuItemRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
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

    public MenuCatalogController(MenuCatalogService menuCatalogService) {
        this.menuCatalogService = Objects.requireNonNull(menuCatalogService,
                "menuCatalogService must not be null");
    }

    @GetMapping
    public List<MenuItemResponse> list(
            @RequestParam(defaultValue = "false") boolean includeInactive) {
        return menuCatalogService.list(includeInactive);
    }

    @GetMapping("/{id}")
    public MenuItemResponse get(@PathVariable Long id) {
        return menuCatalogService.get(id);
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
