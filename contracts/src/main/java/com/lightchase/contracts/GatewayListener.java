package com.lightchase.contracts;
public interface GatewayListener {
void onSession(SessionInfo session); // null clears the previous identity
void onControl(ControlStatus status);
void onConnection(ConnectionStatus status);
void onRoom(RoomState state);
void onView(PlayerView view);
void onResult(MatchResult result);
void onError(GatewayError error);
}
