package com.jarvis.agent.protocol;

import com.fasterxml.jackson.annotation.JsonProperty;

public enum ImageFormat {
    @JsonProperty("jpeg") JPEG,
    @JsonProperty("png") PNG
}
