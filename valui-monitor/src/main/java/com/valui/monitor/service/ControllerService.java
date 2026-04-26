package com.valui.monitor.service;

import com.valui.monitor.dto.ControllerDto;
import com.valui.monitor.dto.CreateControllerRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.UUID;

public interface ControllerService {

    ControllerDto addController(CreateControllerRequest req, Long telegramId);

    void removeController(UUID controllerId, Long telegramId);

    ControllerDto getController(UUID controllerId);

    List<ControllerDto> getUserControllers(Long telegramId);

    Page<ControllerDto> getUserControllers(Long telegramId, Pageable pageable);

    void muteController(UUID controllerId, Long telegramId);

    void unmuteController(UUID controllerId, Long telegramId);

    ControllerDto updateFilterRule(UUID controllerId, Long telegramId, String rule);

    /** Admin / system use: deactivates the controller without ownership check. */
    void deactivateController(UUID controllerId);
}
