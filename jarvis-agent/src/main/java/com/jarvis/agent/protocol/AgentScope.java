package com.jarvis.agent.protocol;

import com.fasterxml.jackson.annotation.JsonProperty;

public enum AgentScope {
    @JsonProperty("browser") BROWSER,
    @JsonProperty("screen") SCREEN
}
