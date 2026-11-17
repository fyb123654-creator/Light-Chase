package com.lightchase.contracts.v2;
import com.lightchase.contracts.*;
public interface GatewayListener { void onSession(SessionInfo session); void onControl(ControlStatus status); void onConnection(ConnectionStatus status); void onRoom(RoomState state); void onView(PlayerView view); void onResult(MatchResult result); void onError(GatewayError error); void onAction(ActionStatus status); }
