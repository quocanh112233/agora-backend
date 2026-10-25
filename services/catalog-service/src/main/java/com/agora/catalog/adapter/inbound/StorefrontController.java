package com.agora.catalog.adapter.inbound;

import com.agora.catalog.application.*;
import com.agora.catalog.domain.CatalogFailure;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/products")
public class StorefrontController {
    private final StorefrontService service;
    public StorefrontController(StorefrontService service){this.service=service;}
    @GetMapping public StorefrontService.Page browse(HttpServletRequest request){return service.browse(StorefrontQuery.parse(request.getParameterMap()));}
    @GetMapping("/{id}") public StorefrontService.Product detail(@PathVariable String id,HttpServletRequest request){
        if(!request.getParameterMap().isEmpty())throw new CatalogFailure(422,"VALIDATION_ERROR","Public product filters are invalid.");
        return service.detail(StorefrontQuery.uuid(id));
    }
}
