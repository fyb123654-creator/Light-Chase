package com.lightchase.game;

import com.lightchase.contracts.MaterialPoint;

/** One of the three materials. It is always in exactly one status. */
final class MaterialState {
    enum Status {
        GROUND,
        CARRIED,
        DELIVERED
    }

    final String id;
    final String deliveryId;
    Status status = Status.GROUND;
    double x;
    double y;
    boolean everPicked;

    MaterialState(MaterialPoint point) {
        this.id = point.id;
        this.deliveryId = point.deliveryId;
        this.x = point.x;
        this.y = point.y;
    }
}
