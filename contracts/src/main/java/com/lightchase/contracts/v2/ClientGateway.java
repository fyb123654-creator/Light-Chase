package com.lightchase.contracts.v2;
import com.lightchase.contracts.ConnectionOptions;
import com.lightchase.contracts.Role;
public interface ClientGateway { void connect(ConnectionOptions options,GatewayListener listener); void selectRole(Role role); void setReady(boolean ready); void submitInput(InputFrame frame); void useItem(int slot,String expectedItemId); void returnToLobby(); void leaveRoom(); void background(); void close(); }
