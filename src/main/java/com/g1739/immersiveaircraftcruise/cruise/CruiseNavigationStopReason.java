package com.g1739.immersiveaircraftcruise.cruise;

public enum CruiseNavigationStopReason {
    NORMAL("message.immersive_aircraft_cruise.disabled"),
    FINISHED("message.immersive_aircraft_cruise.disabled_finished"),
    COLLISION("message.immersive_aircraft_cruise.disabled_collision");

    private final String messageKey;

    CruiseNavigationStopReason(String messageKey) {
        this.messageKey = messageKey;
    }

    public String messageKey() {
        return messageKey;
    }
}
