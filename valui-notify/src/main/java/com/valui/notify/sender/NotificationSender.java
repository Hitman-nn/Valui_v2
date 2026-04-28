package com.valui.notify.sender;

public interface NotificationSender {
    void send(Long recipientId, String messageText) throws Exception;
}
