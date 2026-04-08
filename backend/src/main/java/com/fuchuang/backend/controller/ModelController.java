package com.fuchuang.backend.controller;

import com.fuchuang.backend.dto.ModelConfigDto;
import com.fuchuang.backend.model.ModelConfig;
import com.fuchuang.backend.service.ModelProviderService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/models")
public class ModelController {
    private final ModelProviderService modelProviderService;

    public ModelController(ModelProviderService modelProviderService) {
        this.modelProviderService = modelProviderService;
    }

    @GetMapping("/config")
    public ModelConfigDto getConfig() {
        ModelConfig config = modelProviderService.getConfig();
        return new ModelConfigDto(config.mode(), config.apiBaseUrl(), config.apiKey(), config.modelName());
    }

    @PutMapping("/config")
    public ModelConfigDto updateConfig(@RequestBody ModelConfigDto dto) {
        ModelConfig config = modelProviderService.updateConfig(new ModelConfig(dto.mode(), dto.apiBaseUrl(), dto.apiKey(), dto.modelName()));
        return new ModelConfigDto(config.mode(), config.apiBaseUrl(), config.apiKey(), config.modelName());
    }
}
