package com.valui.admin.monitoring;

import com.valui.admin.monitoring.dto.ControllerApiDto;
import com.valui.monitor.dto.ControllerDto;
import org.springframework.hateoas.server.RepresentationModelAssembler;
import org.springframework.stereotype.Component;

import java.util.UUID;

import static org.springframework.hateoas.server.mvc.WebMvcLinkBuilder.*;

@Component
public class ControllerAssembler
        implements RepresentationModelAssembler<ControllerDto, ControllerApiDto> {

    @Override
    public ControllerApiDto toModel(ControllerDto dto) {
        UUID id = dto.id();
        ControllerApiDto model = new ControllerApiDto(dto);

        model.add(linkTo(methodOn(MonitoringController.class)
                .getController(id, null)).withSelfRel());
        model.add(linkTo(methodOn(MonitoringController.class)
                .listControllers(null, null, null)).withRel("controllers"));
        model.add(linkTo(methodOn(MonitoringController.class)
                .updateController(id, null, null)).withRel("update"));
        model.add(linkTo(methodOn(MonitoringController.class)
                .deleteController(id, null)).withRel("delete"));

        if (dto.isMuted()) {
            model.add(linkTo(methodOn(MonitoringController.class)
                    .unmuteController(id, null)).withRel("unmute"));
        } else {
            model.add(linkTo(methodOn(MonitoringController.class)
                    .muteController(id, null)).withRel("mute"));
        }

        return model;
    }
}
