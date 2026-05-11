package com.valui.admin.profile;

import com.valui.admin.profile.dto.UserProfileDto;
import com.valui.common.entity.UserEntity;
import org.springframework.hateoas.EntityModel;
import org.springframework.hateoas.server.RepresentationModelAssembler;
import org.springframework.stereotype.Component;

import static org.springframework.hateoas.server.mvc.WebMvcLinkBuilder.*;

@Component
public class UserProfileAssembler
        implements RepresentationModelAssembler<UserEntity, EntityModel<UserProfileDto>> {

    @Override
    public EntityModel<UserProfileDto> toModel(UserEntity entity) {
        UserProfileDto dto = new UserProfileDto(entity);
        return EntityModel.of(dto,
                linkTo(methodOn(UserProfileController.class).getProfile(null))
                        .withSelfRel(),
                linkTo(methodOn(UserProfileController.class).getTokens(null))
                        .withRel("tokens")
        );
    }
}
