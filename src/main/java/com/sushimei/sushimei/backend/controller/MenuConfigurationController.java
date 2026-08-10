package com.sushimei.sushimei.backend.controller;

import com.sushimei.sushimei.backend.catalog.CatalogConfigurationService;
import com.sushimei.sushimei.backend.catalog.CatalogTagResponse;
import com.sushimei.sushimei.backend.catalog.CreateCatalogTagRequest;
import com.sushimei.sushimei.backend.catalog.CreateMenuSelectionRuleRequest;
import com.sushimei.sushimei.backend.catalog.MenuSelectionRuleResponse;
import com.sushimei.sushimei.backend.catalog.UpdateCatalogTagRequest;
import com.sushimei.sushimei.backend.catalog.UpdateMenuSelectionRuleRequest;
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
@RequestMapping("/api/v1/menu")
public class MenuConfigurationController {

    private final CatalogConfigurationService catalogConfigurationService;

    public MenuConfigurationController(CatalogConfigurationService catalogConfigurationService) {
        this.catalogConfigurationService = Objects.requireNonNull(catalogConfigurationService,
                "catalogConfigurationService must not be null");
    }

    @GetMapping("/tags")
    public List<CatalogTagResponse> listTags(@RequestParam(defaultValue = "false") boolean includeInactive) {
        return catalogConfigurationService.listTags(includeInactive);
    }

    @PostMapping("/tags")
    public ResponseEntity<CatalogTagResponse> createTag(@Valid @RequestBody CreateCatalogTagRequest request) {
        CatalogTagResponse created = catalogConfigurationService.createTag(request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}")
                .buildAndExpand(created.id()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    @PutMapping("/tags/{id}")
    public CatalogTagResponse updateTag(@PathVariable Long id, @Valid @RequestBody UpdateCatalogTagRequest request) {
        return catalogConfigurationService.updateTag(id, request);
    }

    @DeleteMapping("/tags/{id}")
    public ResponseEntity<Void> archiveTag(@PathVariable Long id) {
        catalogConfigurationService.archiveTag(id);
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<MenuCatalogApiError> handleUnreadableRequest(HttpMessageNotReadableException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new MenuCatalogApiError("INVALID_MENU_CONFIGURATION", "Solicitud de configuración inválida."));
    }

    @PostMapping("/selection-groups/{groupId}/rules")
    public ResponseEntity<MenuSelectionRuleResponse> createRule(@PathVariable Long groupId,
                                                                 @Valid @RequestBody CreateMenuSelectionRuleRequest request) {
        MenuSelectionRuleResponse created = catalogConfigurationService.createRule(groupId, request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest().path("/{ruleId}")
                .buildAndExpand(created.id()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    @PutMapping("/selection-groups/{groupId}/rules/{ruleId}")
    public MenuSelectionRuleResponse updateRule(@PathVariable Long groupId,
                                                @PathVariable Long ruleId,
                                                @Valid @RequestBody UpdateMenuSelectionRuleRequest request) {
        return catalogConfigurationService.updateRule(groupId, ruleId, request);
    }

    @DeleteMapping("/selection-groups/{groupId}/rules/{ruleId}")
    public ResponseEntity<Void> archiveRule(@PathVariable Long groupId, @PathVariable Long ruleId) {
        catalogConfigurationService.archiveRule(groupId, ruleId);
        return ResponseEntity.noContent().build();
    }
}
