package com.jarvis.relay.protocol;

import com.fasterxml.jackson.annotation.JsonProperty;

public enum AgentScope {
    @JsonProperty("browser") BROWSER,
    @JsonProperty("screen") SCREEN
}
