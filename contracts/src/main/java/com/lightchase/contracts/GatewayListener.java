package com.lightchase.contracts;
public interface GatewayListener {
void onConnection(ConnectionStatus status);
void onRoom(RoomState state);
void onView(PlayerView view);
void onResult(MatchResult result);
void onError(GatewayError error);
}
