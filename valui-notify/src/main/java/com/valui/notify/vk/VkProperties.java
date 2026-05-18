package com.valui.notify.vk;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "valui.vk")
@Getter
@Setter
public class VkProperties {
    private boolean enabled = false;
    private String communityToken = "";
    private long groupId = 0;
}
