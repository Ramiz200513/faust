package com.jarvis.relay.protocol;

import com.fasterxml.jackson.annotation.JsonProperty;

public enum CommandStatus {
    @JsonProperty("ok") OK,
    @JsonProperty("error") ERROR,
    @JsonProperty("denied") DENIED,
    @JsonProperty("timeout") TIMEOUT
}
