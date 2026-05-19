package com.valui.bot.vk;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "valui.vk")
@Getter
@Setter
public class VkBotProperties {
    private boolean enabled = false;
    private String communityToken = "";
    private long groupId = 0;
}
