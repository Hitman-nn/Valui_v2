package com.valui.admin.users;

import com.valui.admin.users.dto.AdminUserSummaryDto;
import com.valui.common.entity.UserEntity;
import org.springframework.hateoas.server.RepresentationModelAssembler;
import org.springframework.stereotype.Component;

import static org.springframework.hateoas.server.mvc.WebMvcLinkBuilder.*;

@Component
public class AdminUserAssembler
        implements RepresentationModelAssembler<UserEntity, AdminUserSummaryDto> {

    @Override
    public AdminUserSummaryDto toModel(UserEntity entity) {
        AdminUserSummaryDto dto = new AdminUserSummaryDto(entity);

        dto.add(linkTo(methodOn(AdminUserController.class)
                .getUser(entity.getId(), null)).withSelfRel());
        dto.add(linkTo(methodOn(AdminUserController.class)
                .listUsers(null, null)).withRel("users"));

        if (entity.getStatus() != null) {
            switch (entity.getStatus()) {
                case ACTIVE, PENDING -> dto.add(linkTo(methodOn(AdminUserController.class)
                        .banUser(entity.getId(), null)).withRel("ban"));
                case BANNED -> dto.add(linkTo(methodOn(AdminUserController.class)
                        .unbanUser(entity.getId(), null)).withRel("unban"));
            }
        }

        dto.add(linkTo(methodOn(AdminUserController.class)
                .changeRole(entity.getId(), null, null)).withRel("changeRole"));
        dto.add(linkTo(methodOn(AdminMonitoringController.class)
                .listAllControllers(null, null)).withRel("controllers"));

        return dto;
    }
}
