package com.jarvis.relay.protocol;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Who is on the other end of the socket. Values match the Kotlin protocol. */
public enum DeviceRole {
    @JsonProperty("phone") PHONE,
    @JsonProperty("agent") AGENT
}
