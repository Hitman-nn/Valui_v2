package com.valui.parser.bookmaker.betboom.ws;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class WsPoolConfig {

    @Bean
    public WsClientBorrowingPool wsClientBorrowingPool(WsPoolProperties props) {
        return new WsClientBorrowingPool(props);
    }
}
