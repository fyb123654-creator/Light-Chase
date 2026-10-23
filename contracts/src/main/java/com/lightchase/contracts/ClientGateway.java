package com.lightchase.contracts;
public interface ClientGateway {
void connect(ConnectionOptions options, GatewayListener listener);
void selectRole(Role role);
void setReady(boolean ready);
void submitInput(InputFrame frame);
void returnToLobby();
void leaveRoom();
void background();
void close();
}
