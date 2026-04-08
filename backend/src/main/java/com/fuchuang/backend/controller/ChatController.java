package com.fuchuang.backend.controller;

import com.fuchuang.backend.dto.ChatRequestDto;
import com.fuchuang.backend.dto.ChatResponseDto;
import com.fuchuang.backend.service.ChatService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/chat")
public class ChatController {
    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @PostMapping
    public ChatResponseDto chat(@Valid @RequestBody ChatRequestDto request) {
        return chatService.chat(request);
    }
}
