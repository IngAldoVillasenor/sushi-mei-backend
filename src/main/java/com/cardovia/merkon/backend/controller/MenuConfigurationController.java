package com.cardovia.merkon.backend.controller;

import com.cardovia.merkon.backend.catalog.CatalogConfigurationService;
import com.cardovia.merkon.backend.catalog.CatalogTagResponse;
import com.cardovia.merkon.backend.catalog.CreateCatalogTagRequest;
import com.cardovia.merkon.backend.catalog.CreateMenuSelectionRuleRequest;
import com.cardovia.merkon.backend.catalog.MenuSelectionRuleResponse;
import com.cardovia.merkon.backend.catalog.UpdateCatalogTagRequest;
import com.cardovia.merkon.backend.catalog.UpdateMenuSelectionRuleRequest;
import com.cardovia.merkon.backend.security.TrustedBusinessContext;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
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
    private final TrustedBusinessContext trustedBusinessContext;

    public MenuConfigurationController(CatalogConfigurationService catalogConfigurationService,
                                       TrustedBusinessContext trustedBusinessContext) {
        this.catalogConfigurationService = Objects.requireNonNull(catalogConfigurationService,
                "catalogConfigurationService must not be null");
        this.trustedBusinessContext = Objects.requireNonNull(trustedBusinessContext, "trustedBusinessContext must not be null");
    }

    @GetMapping("/tags")
    public List<CatalogTagResponse> listTags(@AuthenticationPrincipal Jwt jwt, @RequestParam(defaultValue = "false") boolean includeInactive) {
        return catalogConfigurationService.listTags(businessId(jwt), includeInactive);
    }

    @PostMapping("/tags")
    public ResponseEntity<CatalogTagResponse> createTag(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreateCatalogTagRequest request) {
        CatalogTagResponse created = catalogConfigurationService.createTag(businessId(jwt), request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}")
                .buildAndExpand(created.id()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    @PutMapping("/tags/{id}")
    public CatalogTagResponse updateTag(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id, @Valid @RequestBody UpdateCatalogTagRequest request) {
        return catalogConfigurationService.updateTag(businessId(jwt), id, request);
    }

    @DeleteMapping("/tags/{id}")
    public ResponseEntity<Void> archiveTag(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        catalogConfigurationService.archiveTag(businessId(jwt), id);
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<MenuCatalogApiError> handleUnreadableRequest(HttpMessageNotReadableException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new MenuCatalogApiError("INVALID_MENU_CONFIGURATION", "Solicitud de configuración inválida."));
    }

    @PostMapping("/selection-groups/{groupId}/rules")
    public ResponseEntity<MenuSelectionRuleResponse> createRule(@AuthenticationPrincipal Jwt jwt, @PathVariable Long groupId,
                                                                 @Valid @RequestBody CreateMenuSelectionRuleRequest request) {
        MenuSelectionRuleResponse created = catalogConfigurationService.createRule(businessId(jwt), groupId, request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest().path("/{ruleId}")
                .buildAndExpand(created.id()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    @PutMapping("/selection-groups/{groupId}/rules/{ruleId}")
    public MenuSelectionRuleResponse updateRule(@AuthenticationPrincipal Jwt jwt, @PathVariable Long groupId,
                                                @PathVariable Long ruleId,
                                                @Valid @RequestBody UpdateMenuSelectionRuleRequest request) {
        return catalogConfigurationService.updateRule(businessId(jwt), groupId, ruleId, request);
    }

    @DeleteMapping("/selection-groups/{groupId}/rules/{ruleId}")
    public ResponseEntity<Void> archiveRule(@AuthenticationPrincipal Jwt jwt, @PathVariable Long groupId, @PathVariable Long ruleId) {
        catalogConfigurationService.archiveRule(businessId(jwt), groupId, ruleId);
        return ResponseEntity.noContent().build();
    }

    private Long businessId(Jwt jwt) {
        return trustedBusinessContext.requireBusinessId(jwt);
    }
}
